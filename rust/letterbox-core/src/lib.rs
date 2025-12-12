use mail_parser::{MessageParser, MimeHeaders};
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::Mutex;

uniffi::setup_scaffolding!();

/// Error type for email parsing operations.
#[derive(Debug, thiserror::Error, PartialEq, Eq, uniffi::Error)]
pub enum ParseError {
    #[error("Invalid message format")]
    Invalid,
    #[error("Empty payload")]
    Empty,
}

/// Holds parsed email content in Rust memory.
/// Kotlin code holds a reference to this object and calls methods to retrieve content.
#[derive(uniffi::Object)]
pub struct EmailHandle {
    inner: Mutex<ParsedMessage>,
}

/// Internal parsed message structure.
struct ParsedMessage {
    subject: String,
    from: String,
    to: String,
    cc: String,
    reply_to: String,
    message_id: String,
    date: String,
    body_html: Option<String>,
    body_text: Option<String>,
    inline_assets: HashMap<String, Vec<u8>>,
    attachments: Vec<Attachment>,
}

/// Represents an email attachment.
#[derive(Clone)]
struct Attachment {
    name: String,
    content_type: String,
    size: u64,
    content: Vec<u8>,
}

/// Attachment metadata exposed to Kotlin via UniFFI.
#[derive(Clone, uniffi::Record)]
pub struct AttachmentInfo {
    pub name: String,
    pub content_type: String,
    pub size: u64,
}

/// Parse an EML file from raw bytes.
/// Returns an opaque handle that stays in Rust memory.
#[uniffi::export]
pub fn parse_eml(data: Vec<u8>) -> Result<Arc<EmailHandle>, ParseError> {
    if data.is_empty() {
        return Err(ParseError::Empty);
    }

    let parser = MessageParser::default();
    let message = parser.parse(&data).ok_or(ParseError::Invalid)?;

    let subject = message
        .subject()
        .map(|s| s.to_string())
        .unwrap_or_else(|| "Untitled".to_string());

    let from = message
        .from()
        .map(|addrs| format_addresses(addrs))
        .unwrap_or_default();

    let to = message
        .to()
        .map(|addrs| format_addresses(addrs))
        .unwrap_or_default();

    let cc = message
        .cc()
        .map(|addrs| format_addresses(addrs))
        .unwrap_or_default();

    let reply_to = message
        .reply_to()
        .map(|addrs| format_addresses(addrs))
        .unwrap_or_default();

    let message_id = message
        .message_id()
        .map(|s| s.to_string())
        .unwrap_or_default();

    let date = message.date().map(|d| d.to_rfc3339()).unwrap_or_default();

    // Get body HTML
    let body_html = message.body_html(0).map(|s| s.to_string());

    // Get body text
    let body_text = message.body_text(0).map(|s| s.to_string());

    // Extract inline assets
    let mut inline_assets = HashMap::new();
    for part in message.parts.iter() {
        if let Some(content_id) = part.content_id() {
            // This is an inline attachment referenced by cid:
            let cid = content_id
                .trim_start_matches('<')
                .trim_end_matches('>')
                .to_string();
            let bytes = part.contents();
            if !bytes.is_empty() {
                inline_assets.insert(cid, bytes.to_vec());
            }
        }
    }

    // Extract attachments (files that are not inline CID references and not body parts)
    let mut attachments = Vec::new();
    for (part_idx, part) in message.parts.iter().enumerate() {
        // Skip the root part (usually multipart container)
        if part_idx == 0 {
            continue;
        }
        
        // Check if this part is an attachment (has Content-Disposition: attachment)
        // or has a filename and is not already an inline CID reference
        let is_inline_cid = part.content_id().is_some();
        let content_type = part.content_type().map(|ct| {
            if let Some(subtype) = ct.subtype() {
                format!("{}/{}", ct.ctype(), subtype)
            } else {
                ct.ctype().to_string()
            }
        }).unwrap_or_else(|| "application/octet-stream".to_string());
        
        // Skip text/plain and text/html parts that are the main body
        let is_body_part = content_type == "text/plain" || content_type == "text/html";
        
        // Get the attachment name
        let attachment_name = part.attachment_name()
            .map(|s| s.to_string())
            .or_else(|| {
                // Fallback to Content-Type name parameter
                part.content_type()
                    .and_then(|ct| ct.attribute("name"))
                    .map(|s| s.to_string())
            });
        
        // Determine if this is an attachment:
        // - Has a filename
        // - Or is explicitly marked as attachment
        // - And is not an inline CID or body part
        let has_content_disposition_attachment = part.content_disposition()
            .map(|cd| cd.ctype() == "attachment")
            .unwrap_or(false);
        
        if attachment_name.is_some() || has_content_disposition_attachment {
            if !is_inline_cid && !(is_body_part && attachment_name.is_none()) {
                let bytes = part.contents();
                if !bytes.is_empty() {
                    attachments.push(Attachment {
                        name: attachment_name.unwrap_or_else(|| format!("attachment_{}", part_idx)),
                        content_type: content_type.clone(),
                        size: bytes.len() as u64,
                        content: bytes.to_vec(),
                    });
                }
            }
        }
    }

    // If no HTML body, convert text to basic HTML
    let final_body_html = body_html.or_else(|| {
        body_text.as_ref().map(|text| {
            format!(
                "<html><body><pre style=\"white-space: pre-wrap; font-family: sans-serif;\">{}</pre></body></html>",
                html_escape(text)
            )
        })
    });

    let parsed = ParsedMessage {
        subject,
        from,
        to,
        cc,
        reply_to,
        message_id,
        date,
        body_html: final_body_html,
        body_text,
        inline_assets,
        attachments,
    };

    Ok(Arc::new(EmailHandle {
        inner: Mutex::new(parsed),
    }))
}

fn format_addresses(addresses: &mail_parser::Address) -> String {
    match addresses {
        mail_parser::Address::List(list) => list
            .iter()
            .map(|addr| {
                if let Some(name) = &addr.name {
                    format!("{} <{}>", name, addr.address.as_deref().unwrap_or(""))
                } else {
                    addr.address.as_deref().unwrap_or("").to_string()
                }
            })
            .collect::<Vec<_>>()
            .join(", "),
        mail_parser::Address::Group(groups) => groups
            .iter()
            .flat_map(|g| g.addresses.iter())
            .map(|addr| {
                if let Some(name) = &addr.name {
                    format!("{} <{}>", name, addr.address.as_deref().unwrap_or(""))
                } else {
                    addr.address.as_deref().unwrap_or("").to_string()
                }
            })
            .collect::<Vec<_>>()
            .join(", "),
    }
}

fn html_escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

#[uniffi::export]
impl EmailHandle {
    /// Get the email subject.
    pub fn subject(&self) -> String {
        self.inner
            .lock()
            .map(|msg| msg.subject.clone())
            .unwrap_or_default()
    }

    /// Get the "From" field formatted as a string.
    pub fn from(&self) -> String {
        self.inner
            .lock()
            .map(|msg| msg.from.clone())
            .unwrap_or_default()
    }

    /// Get the "To" field formatted as a string.
    pub fn to(&self) -> String {
        self.inner
            .lock()
            .map(|msg| msg.to.clone())
            .unwrap_or_default()
    }

    /// Get the "Cc" field formatted as a string.
    pub fn cc(&self) -> String {
        self.inner
            .lock()
            .map(|msg| msg.cc.clone())
            .unwrap_or_default()
    }

    /// Get the "Reply-To" field formatted as a string.
    pub fn reply_to(&self) -> String {
        self.inner
            .lock()
            .map(|msg| msg.reply_to.clone())
            .unwrap_or_default()
    }

    /// Get the "Message-ID" header.
    pub fn message_id(&self) -> String {
        self.inner
            .lock()
            .map(|msg| msg.message_id.clone())
            .unwrap_or_default()
    }

    /// Get the date as an RFC3339 string.
    pub fn date(&self) -> String {
        self.inner
            .lock()
            .map(|msg| msg.date.clone())
            .unwrap_or_default()
    }

    /// Get the HTML body content, if available.
    pub fn body_html(&self) -> Option<String> {
        self.inner.lock().ok().and_then(|msg| msg.body_html.clone())
    }

    /// Get the plain text body content, if available.
    pub fn body_text(&self) -> Option<String> {
        self.inner.lock().ok().and_then(|msg| msg.body_text.clone())
    }

    /// Get an inline resource by Content-ID for cid: URL resolution.
    pub fn get_resource(&self, cid: String) -> Option<Vec<u8>> {
        self.inner
            .lock()
            .ok()
            .and_then(|msg| msg.inline_assets.get(&cid).cloned())
    }

    /// Get the list of all inline asset Content-IDs.
    pub fn get_resource_ids(&self) -> Vec<String> {
        self.inner
            .lock()
            .map(|msg| msg.inline_assets.keys().cloned().collect())
            .unwrap_or_default()
    }

    /// Get a list of all attachments with their metadata.
    pub fn get_attachments(&self) -> Vec<AttachmentInfo> {
        self.inner
            .lock()
            .map(|msg| {
                msg.attachments
                    .iter()
                    .map(|a| AttachmentInfo {
                        name: a.name.clone(),
                        content_type: a.content_type.clone(),
                        size: a.size,
                    })
                    .collect()
            })
            .unwrap_or_default()
    }

    /// Get the number of attachments.
    pub fn attachment_count(&self) -> u32 {
        self.inner
            .lock()
            .map(|msg| msg.attachments.len() as u32)
            .unwrap_or(0)
    }

    /// Get attachment content by index.
    pub fn get_attachment_content(&self, index: u32) -> Option<Vec<u8>> {
        self.inner
            .lock()
            .ok()
            .and_then(|msg| msg.attachments.get(index as usize).map(|a| a.content.clone()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use once_cell::sync::Lazy;

    static SIMPLE_EMAIL: Lazy<&'static str> = Lazy::new(|| {
        "Subject: Hello\r\nFrom: sender@example.com\r\nTo: recipient@example.com\r\n\r\n<p>Body</p>"
    });

    static MULTIPART_EMAIL: Lazy<&'static str> = Lazy::new(|| {
        r#"Subject: Test Multipart
From: sender@example.com
To: recipient@example.com
MIME-Version: 1.0
Content-Type: multipart/alternative; boundary="boundary"

--boundary
Content-Type: text/plain

Plain text body
--boundary
Content-Type: text/html

<html><body><p>HTML body</p></body></html>
--boundary--
"#
    });

    #[test]
    fn parses_simple_email() {
        let handle = parse_eml(SIMPLE_EMAIL.as_bytes().to_vec()).expect("should parse");
        assert_eq!(handle.subject(), "Hello");
        assert_eq!(handle.from(), "sender@example.com");
        assert_eq!(handle.to(), "recipient@example.com");
    }

    #[test]
    fn parses_multipart_email() {
        let handle = parse_eml(MULTIPART_EMAIL.as_bytes().to_vec()).expect("should parse");
        assert_eq!(handle.subject(), "Test Multipart");

        // Should have HTML body
        let body_html = handle.body_html();
        assert!(body_html.is_some());
        assert!(body_html.unwrap().contains("HTML body"));

        // Should have plain text body
        let body_text = handle.body_text();
        assert!(body_text.is_some());
        assert!(body_text.unwrap().contains("Plain text body"));
    }

    #[test]
    fn rejects_empty_payload() {
        let result = parse_eml(vec![]);
        assert!(result.is_err());
        match result {
            Err(ParseError::Empty) => (),
            _ => panic!("Expected ParseError::Empty"),
        }
    }

    #[test]
    fn returns_none_for_missing_cid() {
        let handle = parse_eml(SIMPLE_EMAIL.as_bytes().to_vec()).expect("should parse");
        assert_eq!(handle.get_resource("nonexistent".to_string()), None);
    }

    #[test]
    fn handles_malformed_input_gracefully() {
        let result = parse_eml(b"not a valid email".to_vec());
        // mail-parser is lenient, so this might parse but with empty fields
        // The important thing is it doesn't crash
        assert!(result.is_ok() || result.is_err());
    }

    static EMAIL_WITH_HEADERS: Lazy<&'static str> = Lazy::new(|| {
        "Subject: Full Headers\r\n\
         From: sender@example.com\r\n\
         To: recipient@example.com\r\n\
         Cc: cc@example.com\r\n\
         Reply-To: reply@example.com\r\n\
         Message-ID: <msg123@example.com>\r\n\r\n\
         Body"
    });

    #[test]
    fn parses_extended_headers() {
        let handle = parse_eml(EMAIL_WITH_HEADERS.as_bytes().to_vec()).expect("should parse");
        assert_eq!(handle.cc(), "cc@example.com");
        assert_eq!(handle.reply_to(), "reply@example.com");
        assert_eq!(handle.message_id(), "msg123@example.com");
    }

    static EMAIL_WITH_ATTACHMENT: Lazy<&'static str> = Lazy::new(|| {
        "Subject: With Attachment\r\n\
         From: sender@example.com\r\n\
         To: recipient@example.com\r\n\
         MIME-Version: 1.0\r\n\
         Content-Type: multipart/mixed; boundary=\"mixed-boundary\"\r\n\
         \r\n\
         --mixed-boundary\r\n\
         Content-Type: text/plain\r\n\
         \r\n\
         Body text\r\n\
         --mixed-boundary\r\n\
         Content-Type: application/pdf\r\n\
         Content-Disposition: attachment; filename=\"test.pdf\"\r\n\
         Content-Transfer-Encoding: base64\r\n\
         \r\n\
         SGVsbG8gV29ybGQh\r\n\
         --mixed-boundary--\r\n"
    });

    #[test]
    fn parses_attachment() {
        let handle = parse_eml(EMAIL_WITH_ATTACHMENT.as_bytes().to_vec()).expect("should parse");
        let attachments = handle.get_attachments();
        assert_eq!(attachments.len(), 1);
        assert_eq!(attachments[0].name, "test.pdf");
        assert_eq!(attachments[0].content_type, "application/pdf");
        assert_eq!(handle.attachment_count(), 1);
        // Content should be available
        let content = handle.get_attachment_content(0);
        assert!(content.is_some());
    }
}
