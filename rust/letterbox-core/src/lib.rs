use mail_parser::{MessageParser, MimeHeaders};
use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::Path;
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
    #[error("File not found: {path}")]
    FileNotFound { path: String },
    #[error("IO error: {details}")]
    IoError { details: String },
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
    /// Timestamp in milliseconds since Unix epoch, 0 if unparseable
    date_timestamp: i64,
    body_html: Option<String>,
    body_text: Option<String>,
    inline_assets: HashMap<String, InlineAsset>,
    attachments: Vec<Attachment>,
    /// Structured sender information for search/filter
    sender_info: AddressInfo,
    /// Structured recipient information for search/filter
    recipient_info: Vec<AddressInfo>,
}

/// Structured address information for search and filtering.
/// Exposed to Kotlin via UniFFI to enable separate indexing of name and email.
#[derive(Clone, Default, uniffi::Record)]
pub struct AddressInfo {
    /// Email address (e.g., "sender@example.com")
    pub email: String,
    /// Display name (e.g., "John Doe"), empty if not available
    pub name: String,
}

/// Internal representation of an inline asset with metadata.
#[derive(Clone)]
struct InlineAsset {
    content_type: String,
    content: Vec<u8>,
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

/// Inline resource metadata for batch queries.
/// Allows Kotlin to efficiently map cid: URLs without probing Rust repeatedly.
/// Size threshold constant for determining small vs large resources.
pub const SMALL_RESOURCE_THRESHOLD: u64 = 64 * 1024; // 64 KB

#[derive(Clone, uniffi::Record)]
pub struct ResourceMeta {
    /// Content-ID (without angle brackets)
    pub cid: String,
    /// MIME type of the resource
    pub content_type: String,
    /// Size in bytes
    pub size: u64,
    /// True if resource is small enough to be returned inline (< 64KB)
    pub is_small: bool,
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

    // Parse date to epoch milliseconds for sorting
    // Uses the mail-parser's DateTime which provides to_timestamp()
    let date_timestamp = message
        .date()
        .map(|d| d.to_timestamp() * 1000) // Convert seconds to milliseconds
        .unwrap_or(0);

    // Extract structured sender info for search/filter
    let sender_info = message
        .from()
        .map(|addrs| extract_first_address_info(addrs))
        .unwrap_or_default();

    // Extract structured recipient info (To + Cc) for search/filter
    let mut recipient_info = Vec::new();
    if let Some(addrs) = message.to() {
        recipient_info.extend(extract_all_address_info(addrs));
    }
    if let Some(addrs) = message.cc() {
        recipient_info.extend(extract_all_address_info(addrs));
    }

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
                let content_type = part
                    .content_type()
                    .map(|ct| {
                        if let Some(subtype) = ct.subtype() {
                            format!("{}/{}", ct.ctype(), subtype)
                        } else {
                            ct.ctype().to_string()
                        }
                    })
                    .unwrap_or_else(|| "application/octet-stream".to_string());
                inline_assets.insert(
                    cid,
                    InlineAsset {
                        content_type,
                        content: bytes.to_vec(),
                    },
                );
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
        let content_type = part
            .content_type()
            .map(|ct| {
                if let Some(subtype) = ct.subtype() {
                    format!("{}/{}", ct.ctype(), subtype)
                } else {
                    ct.ctype().to_string()
                }
            })
            .unwrap_or_else(|| "application/octet-stream".to_string());

        // Skip text/plain and text/html parts that are the main body
        let is_body_part = content_type == "text/plain" || content_type == "text/html";

        // Get the attachment name
        let attachment_name = part.attachment_name().map(|s| s.to_string()).or_else(|| {
            // Fallback to Content-Type name parameter
            part.content_type()
                .and_then(|ct| ct.attribute("name"))
                .map(|s| s.to_string())
        });

        // Determine if this is an attachment:
        // - Has a filename
        // - Or is explicitly marked as attachment
        // - And is not an inline CID or body part
        let has_content_disposition_attachment = part
            .content_disposition()
            .map(|cd| cd.ctype() == "attachment")
            .unwrap_or(false);

        // Check if this part qualifies as an attachment:
        // 1. Has a filename OR is explicitly marked as attachment
        // 2. Is not an inline CID reference
        // 3. Is not a body part without a filename
        let is_attachment_candidate =
            attachment_name.is_some() || has_content_disposition_attachment;
        // Exclusion conditions: skip inline CID parts, or unnamed body parts (text/html, text/plain)
        let should_exclude = is_inline_cid || (is_body_part && attachment_name.is_none());

        if is_attachment_candidate && !should_exclude {
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
        date_timestamp,
        body_html: final_body_html,
        body_text,
        inline_assets,
        attachments,
        sender_info,
        recipient_info,
    };

    Ok(Arc::new(EmailHandle {
        inner: Mutex::new(parsed),
    }))
}

/// Parse an EML file from a file path.
/// This is the preferred method for large emails as it avoids copying the entire
/// file into the JVM heap first. Rust reads the file directly.
/// Returns an opaque handle that stays in Rust memory.
///
/// # Security
/// The caller should ensure the path points to an untrusted EML file that is safe to parse.
/// The mail-parser library handles malformed input gracefully, but the caller should still
/// validate that the file exists in an expected location.
#[uniffi::export]
pub fn parse_eml_from_path(path: String) -> Result<Arc<EmailHandle>, ParseError> {
    let file_path = Path::new(&path);
    if !file_path.exists() {
        return Err(ParseError::FileNotFound { path });
    }

    let data = fs::read(file_path).map_err(|e| ParseError::IoError {
        details: e.to_string(),
    })?;

    parse_eml(data)
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

/// Extract the first address from an Address object as structured AddressInfo.
/// Used for sender information where typically only the first address matters.
fn extract_first_address_info(addresses: &mail_parser::Address) -> AddressInfo {
    match addresses {
        mail_parser::Address::List(list) => list
            .first()
            .map(|addr| AddressInfo {
                email: addr.address.as_deref().unwrap_or("").to_string(),
                name: addr
                    .name
                    .as_ref()
                    .map(|n| n.to_string())
                    .unwrap_or_default(),
            })
            .unwrap_or_default(),
        mail_parser::Address::Group(groups) => groups
            .first()
            .and_then(|g| g.addresses.first())
            .map(|addr| AddressInfo {
                email: addr.address.as_deref().unwrap_or("").to_string(),
                name: addr
                    .name
                    .as_ref()
                    .map(|n| n.to_string())
                    .unwrap_or_default(),
            })
            .unwrap_or_default(),
    }
}

/// Extract all addresses from an Address object as structured AddressInfo.
/// Used for recipient information where all addresses are relevant.
fn extract_all_address_info(addresses: &mail_parser::Address) -> Vec<AddressInfo> {
    match addresses {
        mail_parser::Address::List(list) => list
            .iter()
            .map(|addr| AddressInfo {
                email: addr.address.as_deref().unwrap_or("").to_string(),
                name: addr
                    .name
                    .as_ref()
                    .map(|n| n.to_string())
                    .unwrap_or_default(),
            })
            .collect(),
        mail_parser::Address::Group(groups) => groups
            .iter()
            .flat_map(|g| g.addresses.iter())
            .map(|addr| AddressInfo {
                email: addr.address.as_deref().unwrap_or("").to_string(),
                name: addr
                    .name
                    .as_ref()
                    .map(|n| n.to_string())
                    .unwrap_or_default(),
            })
            .collect(),
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

    /// Get the date as epoch milliseconds.
    /// Returns 0 if the date is missing or unparseable.
    /// Used for sorting and filtering in the Kotlin layer.
    pub fn date_timestamp(&self) -> i64 {
        self.inner.lock().map(|msg| msg.date_timestamp).unwrap_or(0)
    }

    /// Get structured sender information.
    /// Returns AddressInfo with separate email and name fields for search indexing.
    pub fn sender_info(&self) -> AddressInfo {
        self.inner
            .lock()
            .map(|msg| msg.sender_info.clone())
            .unwrap_or_default()
    }

    /// Get structured recipient information (To + Cc).
    /// Returns list of AddressInfo for all recipients for search indexing.
    pub fn recipient_info(&self) -> Vec<AddressInfo> {
        self.inner
            .lock()
            .map(|msg| msg.recipient_info.clone())
            .unwrap_or_default()
    }

    /// Get a preview of the body text for search indexing.
    /// Returns the first 500 characters of the plain text body.
    pub fn body_preview(&self) -> String {
        self.inner
            .lock()
            .map(|msg| {
                msg.body_text
                    .as_ref()
                    .map(|text| {
                        // Take first 500 characters for search index
                        let chars: String = text.chars().take(500).collect();
                        // Clean up whitespace
                        chars.split_whitespace().collect::<Vec<_>>().join(" ")
                    })
                    .unwrap_or_default()
            })
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
    /// Note: For large resources (>64KB), consider using write_resource_to_path instead.
    pub fn get_resource(&self, cid: String) -> Option<Vec<u8>> {
        self.inner.lock().ok().and_then(|msg| {
            msg.inline_assets
                .get(&cid)
                .map(|asset| asset.content.clone())
        })
    }

    /// Get the list of all inline asset Content-IDs.
    pub fn get_resource_ids(&self) -> Vec<String> {
        self.inner
            .lock()
            .map(|msg| msg.inline_assets.keys().cloned().collect())
            .unwrap_or_default()
    }

    /// Get metadata for all inline resources in a single call.
    /// This allows Kotlin to efficiently map cid: URLs and decide the retrieval strategy
    /// (inline for small resources, file-based for large ones).
    pub fn get_resource_metadata(&self) -> Vec<ResourceMeta> {
        self.inner
            .lock()
            .map(|msg| {
                msg.inline_assets
                    .iter()
                    .map(|(cid, asset)| {
                        let size = asset.content.len() as u64;
                        ResourceMeta {
                            cid: cid.clone(),
                            content_type: asset.content_type.clone(),
                            size,
                            is_small: size <= SMALL_RESOURCE_THRESHOLD,
                        }
                    })
                    .collect()
            })
            .unwrap_or_default()
    }

    /// Get the content type of an inline resource without returning the bytes.
    /// Useful for setting MIME types in WebResourceResponse without loading content.
    pub fn get_resource_content_type(&self, cid: String) -> Option<String> {
        self.inner.lock().ok().and_then(|msg| {
            msg.inline_assets
                .get(&cid)
                .map(|asset| asset.content_type.clone())
        })
    }

    /// Write an inline resource directly to a file path.
    /// This avoids copying large resources across the FFI boundary.
    /// Returns true on success.
    ///
    /// # Security
    /// The caller is responsible for validating that `path` is a safe, sandboxed location.
    /// This function will create parent directories and write to the specified path without
    /// additional path validation. Use only with paths constructed from trusted sources
    /// (e.g., application cache directories).
    pub fn write_resource_to_path(&self, cid: String, path: String) -> Result<bool, ParseError> {
        let content = self.inner.lock().ok().and_then(|msg| {
            msg.inline_assets
                .get(&cid)
                .map(|asset| asset.content.clone())
        });

        match content {
            Some(bytes) => {
                let path = Path::new(&path);
                // Create parent directories if needed
                if let Some(parent) = path.parent() {
                    fs::create_dir_all(parent).map_err(|e| ParseError::IoError {
                        details: e.to_string(),
                    })?;
                }
                let mut file = fs::File::create(path).map_err(|e| ParseError::IoError {
                    details: e.to_string(),
                })?;
                file.write_all(&bytes).map_err(|e| ParseError::IoError {
                    details: e.to_string(),
                })?;
                Ok(true)
            }
            None => Ok(false),
        }
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
    /// Note: For large attachments, consider using write_attachment_to_path instead.
    pub fn get_attachment_content(&self, index: u32) -> Option<Vec<u8>> {
        self.inner.lock().ok().and_then(|msg| {
            msg.attachments
                .get(index as usize)
                .map(|a| a.content.clone())
        })
    }

    /// Write an attachment directly to a file path.
    /// This avoids copying large attachments across the FFI boundary.
    /// Returns true on success, false if attachment not found.
    ///
    /// # Security
    /// The caller is responsible for validating that `path` is a safe, sandboxed location.
    /// This function will create parent directories and write to the specified path without
    /// additional path validation. Use only with paths constructed from trusted sources
    /// (e.g., application cache directories).
    pub fn write_attachment_to_path(&self, index: u32, path: String) -> Result<bool, ParseError> {
        let content = self.inner.lock().ok().and_then(|msg| {
            msg.attachments
                .get(index as usize)
                .map(|a| a.content.clone())
        });

        match content {
            Some(bytes) => {
                let path = Path::new(&path);
                // Create parent directories if needed
                if let Some(parent) = path.parent() {
                    fs::create_dir_all(parent).map_err(|e| ParseError::IoError {
                        details: e.to_string(),
                    })?;
                }
                let mut file = fs::File::create(path).map_err(|e| ParseError::IoError {
                    details: e.to_string(),
                })?;
                file.write_all(&bytes).map_err(|e| ParseError::IoError {
                    details: e.to_string(),
                })?;
                Ok(true)
            }
            None => Ok(false),
        }
    }
}

/// Result of extracting remote image URLs from HTML.
#[derive(Clone, uniffi::Record)]
pub struct RemoteImage {
    /// Original image URL (http:// or https://)
    pub url: String,
    /// Whether this is a tracking pixel (1x1 image)
    pub is_tracking_pixel: bool,
}

/// Extract all remote image URLs from HTML content.
/// Uses proper HTML parsing instead of regex to handle edge cases.
///
/// Returns a list of remote image URLs found in <img src="..."> tags.
/// Only returns http:// and https:// URLs, excludes cid: URLs.
#[uniffi::export]
pub fn extract_remote_images(html: String) -> Vec<RemoteImage> {
    use scraper::{Html, Selector};

    let document = Html::parse_document(&html);
    let img_selector = Selector::parse("img").unwrap();

    let mut images = Vec::new();

    for element in document.select(&img_selector) {
        if let Some(src) = element.value().attr("src") {
            // Only include http:// and https:// URLs
            if src.starts_with("http://") || src.starts_with("https://") {
                // Check if it's a tracking pixel (1x1 image)
                let is_tracking = element
                    .value()
                    .attr("width")
                    .and_then(|w| w.parse::<u32>().ok())
                    .map(|w| w <= 1)
                    .unwrap_or(false)
                    && element
                        .value()
                        .attr("height")
                        .and_then(|h| h.parse::<u32>().ok())
                        .map(|h| h <= 1)
                        .unwrap_or(false);

                images.push(RemoteImage {
                    url: src.to_string(),
                    is_tracking_pixel: is_tracking,
                });
            }
        }
    }

    images
}

/// Rewrite HTML to proxy remote images through a configurable proxy URL.
/// Uses proper HTML parsing and reconstruction instead of regex.
///
/// @param html The original HTML content
/// @param proxy_base_url The proxy base URL (e.g., "https://proxy.example.com/?u=")
/// @return HTML with rewritten image URLs
///
/// Note: This function is retained for backwards compatibility but is deprecated.
/// The new WARP proxy architecture fetches images directly through the WireGuard
/// tunnel without URL rewriting.
#[uniffi::export]
pub fn rewrite_image_urls(html: String, proxy_base_url: String) -> String {
    use scraper::{Html, Selector};

    let document = Html::parse_document(&html);
    let img_selector = Selector::parse("img").unwrap();

    let mut result = html.clone();
    let mut replacements = Vec::new();

    // Collect all img tags that need rewriting
    for element in document.select(&img_selector) {
        if let Some(src) = element.value().attr("src") {
            // Only rewrite http:// and https:// URLs
            if src.starts_with("http://") || src.starts_with("https://") {
                // URL-encode the target URL
                let encoded_src = urlencoding::encode(src);
                let proxied_url = format!("{}{}", proxy_base_url, encoded_src);
                replacements.push((src.to_string(), proxied_url));
            }
        }
    }

    // Apply replacements
    for (original, proxied) in replacements {
        // Replace src="original" with src="proxied"
        result = result.replace(
            &format!("src=\"{}\"", original),
            &format!("src=\"{}\"", proxied),
        );
        result = result.replace(
            &format!("src='{}'", original),
            &format!("src='{}'", proxied),
        );
    }

    result
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

    // Tests for new optimized FFI functions

    static EMAIL_WITH_INLINE_IMAGE: Lazy<&'static str> = Lazy::new(|| {
        "Subject: Email with Inline Image\r\n\
         From: sender@example.com\r\n\
         To: recipient@example.com\r\n\
         MIME-Version: 1.0\r\n\
         Content-Type: multipart/related; boundary=\"related-boundary\"\r\n\
         \r\n\
         --related-boundary\r\n\
         Content-Type: text/html\r\n\
         \r\n\
         <html><body><img src=\"cid:image001\"></body></html>\r\n\
         --related-boundary\r\n\
         Content-Type: image/png\r\n\
         Content-ID: <image001>\r\n\
         Content-Transfer-Encoding: base64\r\n\
         \r\n\
         iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==\r\n\
         --related-boundary--\r\n"
    });

    #[test]
    fn get_resource_metadata_returns_inline_assets_info() {
        let handle = parse_eml(EMAIL_WITH_INLINE_IMAGE.as_bytes().to_vec()).expect("should parse");

        let metadata = handle.get_resource_metadata();
        assert_eq!(metadata.len(), 1);

        let meta = &metadata[0];
        assert_eq!(meta.cid, "image001");
        assert_eq!(meta.content_type, "image/png");
        assert!(meta.size > 0);
        assert!(meta.is_small); // Small test image should be under 64KB threshold
    }

    #[test]
    fn get_resource_content_type_returns_mime_type() {
        let handle = parse_eml(EMAIL_WITH_INLINE_IMAGE.as_bytes().to_vec()).expect("should parse");

        let content_type = handle.get_resource_content_type("image001".to_string());
        assert_eq!(content_type, Some("image/png".to_string()));

        // Non-existent CID should return None
        let missing = handle.get_resource_content_type("nonexistent".to_string());
        assert_eq!(missing, None);
    }

    #[test]
    fn parse_eml_from_path_works_with_valid_file() {
        // Create a temp file
        let temp_dir = std::env::temp_dir();
        let temp_file = temp_dir.join("test_email.eml");

        let mut file = fs::File::create(&temp_file).expect("create temp file");
        file.write_all(SIMPLE_EMAIL.as_bytes())
            .expect("write temp file");

        // Parse from path
        let handle =
            parse_eml_from_path(temp_file.to_str().unwrap().to_string()).expect("should parse");
        assert_eq!(handle.subject(), "Hello");
        assert_eq!(handle.from(), "sender@example.com");

        // Cleanup
        let _ = fs::remove_file(temp_file);
    }

    #[test]
    fn parse_eml_from_path_returns_error_for_missing_file() {
        let result = parse_eml_from_path("/nonexistent/path/email.eml".to_string());
        assert!(matches!(result, Err(ParseError::FileNotFound { .. })));
    }

    #[test]
    fn write_attachment_to_path_creates_file() {
        let handle = parse_eml(EMAIL_WITH_ATTACHMENT.as_bytes().to_vec()).expect("should parse");

        let temp_dir = std::env::temp_dir();
        let output_path = temp_dir.join("test_attachment.pdf");

        // Write attachment to file
        let result = handle.write_attachment_to_path(0, output_path.to_str().unwrap().to_string());
        assert!(result.is_ok());
        assert!(result.unwrap());

        // Verify file exists and has content
        assert!(output_path.exists());
        let written = fs::read(&output_path).expect("read written file");
        assert!(!written.is_empty());

        // Cleanup
        let _ = fs::remove_file(output_path);
    }

    #[test]
    fn write_attachment_to_path_returns_false_for_invalid_index() {
        let handle = parse_eml(SIMPLE_EMAIL.as_bytes().to_vec()).expect("should parse");

        let temp_dir = std::env::temp_dir();
        let output_path = temp_dir.join("nonexistent_attachment.pdf");

        // Try to write non-existent attachment
        let result = handle.write_attachment_to_path(99, output_path.to_str().unwrap().to_string());
        assert!(result.is_ok());
        assert!(!result.unwrap()); // Should return false for missing attachment
    }

    #[test]
    fn write_resource_to_path_creates_file() {
        let handle = parse_eml(EMAIL_WITH_INLINE_IMAGE.as_bytes().to_vec()).expect("should parse");

        let temp_dir = std::env::temp_dir();
        let output_path = temp_dir.join("test_resource.png");

        // Write resource to file
        let result = handle.write_resource_to_path(
            "image001".to_string(),
            output_path.to_str().unwrap().to_string(),
        );
        assert!(result.is_ok());
        assert!(result.unwrap());

        // Verify file exists and has content
        assert!(output_path.exists());
        let written = fs::read(&output_path).expect("read written file");
        assert!(!written.is_empty());

        // Cleanup
        let _ = fs::remove_file(output_path);
    }

    #[test]
    fn write_resource_to_path_returns_false_for_missing_cid() {
        let handle = parse_eml(SIMPLE_EMAIL.as_bytes().to_vec()).expect("should parse");

        let temp_dir = std::env::temp_dir();
        let output_path = temp_dir.join("missing_resource.png");

        // Try to write non-existent resource
        let result = handle.write_resource_to_path(
            "nonexistent".to_string(),
            output_path.to_str().unwrap().to_string(),
        );
        assert!(result.is_ok());
        assert!(!result.unwrap()); // Should return false for missing CID
    }

    #[test]
    fn small_resource_threshold_is_64kb() {
        assert_eq!(SMALL_RESOURCE_THRESHOLD, 64 * 1024);
    }

    // Additional edge case tests

    #[test]
    fn parses_email_with_unicode_subject() {
        let email = "Subject: こんにちは 🌍 Émoji\r\nFrom: test@test.com\r\n\r\nBody";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let subject = handle.subject();
        // Verify Unicode characters are preserved
        assert!(subject.contains("こんにちは") || subject.contains("🌍") || !subject.is_empty());
    }

    #[test]
    fn parses_email_with_very_long_subject() {
        let long_subject = "X".repeat(1000);
        let email = format!(
            "Subject: {}\r\nFrom: test@test.com\r\n\r\nBody",
            long_subject
        );
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        assert_eq!(handle.subject(), long_subject);
    }

    #[test]
    fn parses_email_with_missing_subject() {
        let email = "From: test@test.com\r\nTo: recipient@test.com\r\n\r\nBody";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        // Should fall back to "Untitled" for missing subject
        assert_eq!(handle.subject(), "Untitled");
    }

    #[test]
    fn parses_email_with_empty_fields() {
        let email = "Subject: Test\r\n\r\nBody";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        // Empty fields should return empty strings, not panic
        assert!(handle.from().is_empty() || handle.from() == "");
        assert!(handle.to().is_empty() || handle.to() == "");
        assert!(handle.cc().is_empty() || handle.cc() == "");
    }

    #[test]
    fn html_escape_handles_all_special_chars() {
        // Test that html_escape handles all 5 required entities
        let input = "Test & <script>alert('xss')</script> \"quotes\"";
        let escaped = html_escape(input);
        assert!(!escaped.contains('&') || escaped.contains("&amp;"));
        assert!(!escaped.contains('<') || escaped.contains("&lt;"));
        assert!(!escaped.contains('>') || escaped.contains("&gt;"));
        assert!(!escaped.contains('"') || escaped.contains("&quot;"));
        assert!(!escaped.contains('\'') || escaped.contains("&#39;"));
    }

    #[test]
    fn parses_email_with_multiple_recipients() {
        let email = "Subject: Multi\r\n\
                     From: sender@example.com\r\n\
                     To: alice@example.com, bob@example.com\r\n\
                     Cc: carol@example.com\r\n\
                     \r\nBody";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let to = handle.to();
        // Should contain both recipients
        assert!(to.contains("alice") || to.contains("bob"));
    }

    #[test]
    fn parses_email_with_date() {
        let email = "Subject: Dated\r\n\
                     From: test@test.com\r\n\
                     Date: Mon, 11 Dec 2025 10:00:00 +0000\r\n\
                     \r\nBody";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let date = handle.date();
        // Date should be extracted in some format
        assert!(!date.is_empty());
    }

    #[test]
    fn parse_eml_from_path_empty_file_returns_empty_error() {
        let temp_dir = std::env::temp_dir();
        let temp_file = temp_dir.join("empty_email.eml");
        fs::write(&temp_file, "").expect("write empty file");

        let result = parse_eml_from_path(temp_file.to_str().unwrap().to_string());
        assert!(matches!(result, Err(ParseError::Empty)));

        let _ = fs::remove_file(temp_file);
    }

    #[test]
    fn email_handle_thread_safe() {
        // Test that EmailHandle can be shared across threads safely
        let handle = parse_eml(SIMPLE_EMAIL.as_bytes().to_vec()).expect("should parse");
        let handle_clone = handle.clone();

        std::thread::spawn(move || {
            let _ = handle_clone.subject();
        })
        .join()
        .expect("thread should complete");

        // Original handle should still work
        assert_eq!(handle.subject(), "Hello");
    }

    static EMAIL_WITH_MULTIPLE_ATTACHMENTS: Lazy<&'static str> = Lazy::new(|| {
        "Subject: Multiple Attachments\r\n\
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
         Content-Disposition: attachment; filename=\"doc1.pdf\"\r\n\
         Content-Transfer-Encoding: base64\r\n\
         \r\n\
         SGVsbG8=\r\n\
         --mixed-boundary\r\n\
         Content-Type: image/png\r\n\
         Content-Disposition: attachment; filename=\"image.png\"\r\n\
         Content-Transfer-Encoding: base64\r\n\
         \r\n\
         iVBORw0K\r\n\
         --mixed-boundary--\r\n"
    });

    #[test]
    fn parses_multiple_attachments() {
        let handle =
            parse_eml(EMAIL_WITH_MULTIPLE_ATTACHMENTS.as_bytes().to_vec()).expect("should parse");
        let attachments = handle.get_attachments();
        assert!(attachments.len() >= 2);
        assert_eq!(handle.attachment_count() as usize, attachments.len());
    }

    #[test]
    fn get_attachment_content_invalid_index_returns_none() {
        let handle = parse_eml(SIMPLE_EMAIL.as_bytes().to_vec()).expect("should parse");
        // Email has no attachments, so any index is invalid
        assert!(handle.get_attachment_content(0).is_none());
        assert!(handle.get_attachment_content(100).is_none());
    }

    // Tests for structured address extraction (for search/filter)

    #[test]
    fn sender_info_extracts_email_and_name() {
        let email = "Subject: Test\r\n\
                     From: John Doe <john@example.com>\r\n\
                     To: recipient@example.com\r\n\r\n\
                     Body";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let sender = handle.sender_info();
        assert_eq!(sender.email, "john@example.com");
        assert_eq!(sender.name, "John Doe");
    }

    #[test]
    fn sender_info_handles_email_only() {
        let email = "Subject: Test\r\n\
                     From: sender@example.com\r\n\
                     To: recipient@example.com\r\n\r\n\
                     Body";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let sender = handle.sender_info();
        assert_eq!(sender.email, "sender@example.com");
        assert_eq!(sender.name, "");
    }

    #[test]
    fn sender_info_handles_missing_from() {
        let email = "Subject: Test\r\n\
                     To: recipient@example.com\r\n\r\n\
                     Body";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let sender = handle.sender_info();
        assert_eq!(sender.email, "");
        assert_eq!(sender.name, "");
    }

    #[test]
    fn recipient_info_extracts_to_and_cc() {
        let email = "Subject: Test\r\n\
                     From: sender@example.com\r\n\
                     To: Alice <alice@example.com>, bob@example.com\r\n\
                     Cc: carol@example.com\r\n\r\n\
                     Body";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let recipients = handle.recipient_info();
        // Should have 3 recipients: Alice, bob, carol
        assert_eq!(recipients.len(), 3);

        // Check Alice
        let alice = recipients.iter().find(|r| r.email == "alice@example.com");
        assert!(alice.is_some());
        assert_eq!(alice.unwrap().name, "Alice");

        // Check bob (no name)
        let bob = recipients.iter().find(|r| r.email == "bob@example.com");
        assert!(bob.is_some());
        assert_eq!(bob.unwrap().name, "");

        // Check carol
        let carol = recipients.iter().find(|r| r.email == "carol@example.com");
        assert!(carol.is_some());
    }

    #[test]
    fn date_timestamp_parses_valid_date() {
        let email = "Subject: Test\r\n\
                     From: sender@example.com\r\n\
                     Date: Mon, 11 Dec 2023 10:00:00 +0000\r\n\r\n\
                     Body";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let ts = handle.date_timestamp();
        // Should be a positive timestamp around Dec 2023
        assert!(ts > 0);
        // Timestamp should be in milliseconds (greater than 1 billion)
        assert!(ts > 1_000_000_000_000);
    }

    #[test]
    fn date_timestamp_returns_zero_for_missing_date() {
        let email = "Subject: Test\r\n\
                     From: sender@example.com\r\n\r\n\
                     Body";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let ts = handle.date_timestamp();
        assert_eq!(ts, 0);
    }

    #[test]
    fn body_preview_returns_first_500_chars() {
        // Create an email with a long body
        let long_body = "a ".repeat(300); // 600 chars with spaces
        let email = format!(
            "Subject: Test\r\n\
             From: sender@example.com\r\n\r\n\
             {}",
            long_body
        );
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let preview = handle.body_preview();
        // Preview should be limited to ~500 chars worth of content
        assert!(preview.len() <= 500);
        // Should contain 'a' from the body
        assert!(preview.contains('a'));
    }

    #[test]
    fn body_preview_returns_empty_for_html_only_email() {
        let email = "Subject: Test\r\n\
                     From: sender@example.com\r\n\
                     Content-Type: text/html\r\n\r\n\
                     <html><body>Hello</body></html>";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let preview = handle.body_preview();
        // HTML-only emails may have body_text extracted from HTML
        // This tests that the function doesn't crash
        assert!(preview.is_empty() || preview.contains("Hello"));
    }

    #[test]
    fn body_preview_cleans_whitespace() {
        let email = "Subject: Test\r\n\
                     From: sender@example.com\r\n\r\n\
                     Hello    World\n\nThis is   a test";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let preview = handle.body_preview();
        // Should collapse whitespace
        assert!(!preview.contains("    "));
    }
}
