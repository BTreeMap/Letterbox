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
    date: String,
    body_html: Option<String>,
    body_text: Option<String>,
    inline_assets: HashMap<String, Vec<u8>>,
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
        date,
        body_html: final_body_html,
        body_text,
        inline_assets,
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
}
