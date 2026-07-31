//! EML parsing for Letterbox, exposed to Kotlin via UniFFI.
//!
//! Parsing happens once, in [`parse_eml`], and produces an immutable
//! [`EmailHandle`]. Every accessor is therefore a pure read of already-decided
//! data: there is no lock, no interior mutability, and no accessor that can fail
//! for a reason unrelated to what the caller asked for.

use mail_parser::{Addr, Address, MessageParser, MessagePart, MimeHeaders};
use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::Path;
use std::sync::{Arc, LazyLock};

uniffi::setup_scaffolding!();

/// MIME type assumed for a part that declares none, per RFC 2045 §5.2.
const DEFAULT_CONTENT_TYPE: &str = "application/octet-stream";

/// Subject used when the message carries no `Subject` header.
const UNTITLED_SUBJECT: &str = "Untitled";

/// How many characters of the plain-text body feed the search index.
const PREVIEW_CHARS: usize = 500;

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

impl From<std::io::Error> for ParseError {
    fn from(err: std::io::Error) -> Self {
        ParseError::IoError {
            details: err.to_string(),
        }
    }
}

/// Holds parsed email content in Rust memory.
///
/// Kotlin holds an `Arc` of this and calls the accessors below. The parsed
/// message is immutable for the handle's whole lifetime, which is why it sits
/// behind no lock: an immutable value is already `Sync`, and a `Mutex` here
/// would buy nothing but a poisoning case that every accessor would then have to
/// invent an answer for.
#[derive(uniffi::Object)]
pub struct EmailHandle {
    message: ParsedMessage,
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
struct InlineAsset {
    content_type: String,
    content: Vec<u8>,
}

/// Represents an email attachment.
///
/// The byte count is not stored: it is `content.len()`, and a separately held
/// `size` is a second source of truth that can only ever be wrong.
struct Attachment {
    name: String,
    content_type: String,
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

/// What role one MIME part plays in the message.
///
/// The three cases are exclusive and exhaustive. They replace four interacting
/// booleans (`is_inline_cid`, `is_body_part`, `is_attachment_candidate`,
/// `should_exclude`) whose sixteen combinations described only these three
/// outcomes — the other thirteen were unreachable but nothing said so.
enum PartRole {
    /// Referenced from the body by a `cid:` URL.
    Inline { cid: String },
    /// A file the user can save, under the name the message gave it.
    Attached { name: String },
    /// A body part, a multipart container, or an empty part: carried by neither
    /// collection.
    Ignored,
}

/// Decide a part's role from its headers alone.
///
/// Pure and total: every part maps to exactly one [`PartRole`], so the caller
/// needs no fallthrough case and no ordering between the two collections.
fn classify_part(index: usize, part: &MessagePart) -> PartRole {
    // An empty part carries nothing to show or save, whatever its headers claim.
    if part.contents().is_empty() {
        return PartRole::Ignored;
    }

    if let Some(content_id) = part.content_id() {
        return PartRole::Inline {
            cid: content_id
                .trim_start_matches('<')
                .trim_end_matches('>')
                .to_string(),
        };
    }

    // Part 0 is the message itself (usually the multipart container), never a
    // file the user would save.
    if index == 0 {
        return PartRole::Ignored;
    }

    let name = part
        .attachment_name()
        .map(str::to_string)
        .or_else(|| content_type_name(part));

    match name {
        Some(name) => PartRole::Attached { name },
        // An unnamed part is a file only when it says so *and* is not the body.
        None if is_marked_attachment(part) && !is_body_part(part) => PartRole::Attached {
            name: format!("attachment_{index}"),
        },
        None => PartRole::Ignored,
    }
}

/// Filename from the `Content-Type: name=` parameter, the legacy spelling of
/// `Content-Disposition: filename=`.
fn content_type_name(part: &MessagePart) -> Option<String> {
    part.content_type()
        .and_then(|ct| ct.attribute("name"))
        .map(str::to_string)
}

/// Whether the part explicitly declares itself an attachment.
fn is_marked_attachment(part: &MessagePart) -> bool {
    part.content_disposition()
        .is_some_and(|cd| cd.ctype() == "attachment")
}

/// Whether the part is displayable message body rather than a payload.
fn is_body_part(part: &MessagePart) -> bool {
    matches!(part_content_type(part).as_str(), "text/plain" | "text/html")
}

/// A part's `type/subtype`, or the generic binary type when it declares none.
fn part_content_type(part: &MessagePart) -> String {
    part.content_type()
        .map(|ct| match ct.subtype() {
            Some(subtype) => format!("{}/{}", ct.ctype(), subtype),
            None => ct.ctype().to_string(),
        })
        .unwrap_or_else(|| DEFAULT_CONTENT_TYPE.to_string())
}

/// Parse an EML file from raw bytes.
/// Returns an opaque handle that stays in Rust memory.
#[uniffi::export]
pub fn parse_eml(data: Vec<u8>) -> Result<Arc<EmailHandle>, ParseError> {
    if data.is_empty() {
        return Err(ParseError::Empty);
    }

    let message = MessageParser::default()
        .parse(&data)
        .ok_or(ParseError::Invalid)?;

    // One traversal classifies every part; the match is exhaustive, so a part
    // can neither be counted twice nor silently fall through.
    let mut inline_assets = HashMap::new();
    let mut attachments = Vec::new();
    for (index, part) in message.parts.iter().enumerate() {
        match classify_part(index, part) {
            PartRole::Inline { cid } => {
                inline_assets.insert(
                    cid,
                    InlineAsset {
                        content_type: part_content_type(part),
                        content: part.contents().to_vec(),
                    },
                );
            }
            PartRole::Attached { name } => attachments.push(Attachment {
                name,
                content_type: part_content_type(part),
                content: part.contents().to_vec(),
            }),
            PartRole::Ignored => {}
        }
    }

    let body_text = message.body_text(0).map(|s| s.to_string());
    // Without an HTML body, present the plain text as preformatted HTML so the
    // WebView has something to render either way.
    let body_html = message.body_html(0).map(|s| s.to_string()).or_else(|| {
        body_text.as_deref().map(|text| {
            format!(
                "<html><body><pre style=\"white-space: pre-wrap; font-family: sans-serif;\">{}</pre></body></html>",
                html_escape(text)
            )
        })
    });

    let recipient_info = message
        .to()
        .into_iter()
        .chain(message.cc())
        .flat_map(addresses)
        .map(address_info)
        .collect();

    Ok(Arc::new(EmailHandle {
        message: ParsedMessage {
            subject: message
                .subject()
                .map_or_else(|| UNTITLED_SUBJECT.to_string(), str::to_string),
            from: message.from().map(format_addresses).unwrap_or_default(),
            to: message.to().map(format_addresses).unwrap_or_default(),
            cc: message.cc().map(format_addresses).unwrap_or_default(),
            reply_to: message.reply_to().map(format_addresses).unwrap_or_default(),
            message_id: message.message_id().map(str::to_string).unwrap_or_default(),
            date: message.date().map(|d| d.to_rfc3339()).unwrap_or_default(),
            // Milliseconds, matching the JVM epoch convention the Kotlin side
            // sorts on. Saturating because the header is attacker-controlled and
            // an unchecked multiply is the one arithmetic panic in this file.
            date_timestamp: message
                .date()
                .map_or(0, |d| d.to_timestamp().saturating_mul(1000)),
            body_html,
            body_text,
            inline_assets,
            attachments,
            sender_info: message.from().map(first_address_info).unwrap_or_default(),
            recipient_info,
        },
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
    parse_eml(fs::read(file_path)?)
}

/// Every address in a header, flattening away the list/group distinction.
///
/// `Address::List` and `Address::Group` differ only in nesting depth, not in
/// element type, so exactly one of the two slices below is non-empty and the
/// chain yields the same `Addr` stream either way. This is what lets the three
/// callers share one traversal instead of each matching both variants.
fn addresses<'a, 'x>(header: &'a Address<'x>) -> impl Iterator<Item = &'a Addr<'x>> + 'a {
    let (flat, grouped) = match header {
        Address::List(list) => (list.as_slice(), [].as_slice()),
        Address::Group(groups) => ([].as_slice(), groups.as_slice()),
    };
    flat.iter()
        .chain(grouped.iter().flat_map(|group| group.addresses.iter()))
}

/// Render one address as `Name <email>`, or bare `email` when unnamed.
fn format_address(addr: &Addr) -> String {
    let email = addr.address.as_deref().unwrap_or_default();
    match &addr.name {
        Some(name) => format!("{name} <{email}>"),
        None => email.to_string(),
    }
}

/// Render a whole header as a comma-separated address list.
fn format_addresses(header: &Address) -> String {
    addresses(header)
        .map(format_address)
        .collect::<Vec<_>>()
        .join(", ")
}

/// Split one address into its separately indexable parts.
fn address_info(addr: &Addr) -> AddressInfo {
    AddressInfo {
        email: addr.address.as_deref().unwrap_or_default().to_string(),
        name: addr.name.as_deref().unwrap_or_default().to_string(),
    }
}

/// The first address in a header, for `From`, where later addresses are noise.
///
/// "First" means the first address that exists, so a header opening with an
/// empty group (`undisclosed-recipients:;`) still yields the address after it
/// rather than nothing.
fn first_address_info(header: &Address) -> AddressInfo {
    addresses(header)
        .next()
        .map(address_info)
        .unwrap_or_default()
}

/// Escape the five HTML metacharacters in one pass.
///
/// Order matters only because `&` must be escaped before the escapes that
/// introduce it; doing all five in a single traversal removes the question
/// entirely, along with four intermediate `String`s.
fn html_escape(s: &str) -> String {
    s.chars()
        .fold(String::with_capacity(s.len()), |mut out, c| {
            match c {
                '&' => out.push_str("&amp;"),
                '<' => out.push_str("&lt;"),
                '>' => out.push_str("&gt;"),
                '"' => out.push_str("&quot;"),
                '\'' => out.push_str("&#39;"),
                c => out.push(c),
            }
            out
        })
}

/// The leading `PREVIEW_CHARS` characters with whitespace runs collapsed.
fn preview(text: &str) -> String {
    let end = text
        .char_indices()
        .nth(PREVIEW_CHARS)
        .map_or(text.len(), |(offset, _)| offset);
    text[..end].split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Write `bytes` to `path`, creating parent directories as needed.
fn write_file(path: &str, bytes: &[u8]) -> Result<(), ParseError> {
    let path = Path::new(path);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::File::create(path)?.write_all(bytes)?;
    Ok(())
}

#[uniffi::export]
impl EmailHandle {
    /// Get the email subject.
    pub fn subject(&self) -> String {
        self.message.subject.clone()
    }

    /// Get the "From" field formatted as a string.
    pub fn from(&self) -> String {
        self.message.from.clone()
    }

    /// Get the "To" field formatted as a string.
    pub fn to(&self) -> String {
        self.message.to.clone()
    }

    /// Get the "Cc" field formatted as a string.
    pub fn cc(&self) -> String {
        self.message.cc.clone()
    }

    /// Get the "Reply-To" field formatted as a string.
    pub fn reply_to(&self) -> String {
        self.message.reply_to.clone()
    }

    /// Get the "Message-ID" header.
    pub fn message_id(&self) -> String {
        self.message.message_id.clone()
    }

    /// Get the date as an RFC3339 string.
    pub fn date(&self) -> String {
        self.message.date.clone()
    }

    /// Get the date as epoch milliseconds.
    /// Returns 0 if the date is missing or unparseable.
    /// Used for sorting and filtering in the Kotlin layer.
    pub fn date_timestamp(&self) -> i64 {
        self.message.date_timestamp
    }

    /// Get structured sender information.
    /// Returns AddressInfo with separate email and name fields for search indexing.
    pub fn sender_info(&self) -> AddressInfo {
        self.message.sender_info.clone()
    }

    /// Get structured recipient information (To + Cc).
    /// Returns list of AddressInfo for all recipients for search indexing.
    pub fn recipient_info(&self) -> Vec<AddressInfo> {
        self.message.recipient_info.clone()
    }

    /// Get a preview of the body text for search indexing.
    /// Returns the first 500 characters of the plain text body.
    pub fn body_preview(&self) -> String {
        self.message
            .body_text
            .as_deref()
            .map(preview)
            .unwrap_or_default()
    }

    /// Get the HTML body content, if available.
    pub fn body_html(&self) -> Option<String> {
        self.message.body_html.clone()
    }

    /// Get the plain text body content, if available.
    pub fn body_text(&self) -> Option<String> {
        self.message.body_text.clone()
    }

    /// Get an inline resource by Content-ID for cid: URL resolution.
    /// Note: For large resources (>64KB), consider using write_resource_to_path instead.
    pub fn get_resource(&self, cid: String) -> Option<Vec<u8>> {
        self.message
            .inline_assets
            .get(&cid)
            .map(|asset| asset.content.clone())
    }

    /// Get the list of all inline asset Content-IDs.
    pub fn get_resource_ids(&self) -> Vec<String> {
        self.message.inline_assets.keys().cloned().collect()
    }

    /// Get metadata for all inline resources in a single call.
    /// This allows Kotlin to efficiently map cid: URLs and decide the retrieval strategy
    /// (inline for small resources, file-based for large ones).
    pub fn get_resource_metadata(&self) -> Vec<ResourceMeta> {
        self.message
            .inline_assets
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
    }

    /// Get the content type of an inline resource without returning the bytes.
    /// Useful for setting MIME types in WebResourceResponse without loading content.
    pub fn get_resource_content_type(&self, cid: String) -> Option<String> {
        self.message
            .inline_assets
            .get(&cid)
            .map(|asset| asset.content_type.clone())
    }

    /// Write an inline resource directly to a file path.
    /// This avoids copying large resources across the FFI boundary.
    /// Returns true on success, false if the Content-ID is unknown.
    ///
    /// # Security
    /// The caller is responsible for validating that `path` is a safe, sandboxed location.
    /// This function will create parent directories and write to the specified path without
    /// additional path validation. Use only with paths constructed from trusted sources
    /// (e.g., application cache directories).
    pub fn write_resource_to_path(&self, cid: String, path: String) -> Result<bool, ParseError> {
        // Borrowed straight to the writer: with no lock to release, the bytes
        // never need a temporary copy, however large the resource is.
        match self.message.inline_assets.get(&cid) {
            Some(asset) => write_file(&path, &asset.content).map(|()| true),
            None => Ok(false),
        }
    }

    /// Get a list of all attachments with their metadata.
    pub fn get_attachments(&self) -> Vec<AttachmentInfo> {
        self.message
            .attachments
            .iter()
            .map(|a| AttachmentInfo {
                name: a.name.clone(),
                content_type: a.content_type.clone(),
                size: a.content.len() as u64,
            })
            .collect()
    }

    /// Get the number of attachments.
    pub fn attachment_count(&self) -> u32 {
        self.message.attachments.len() as u32
    }

    /// Get attachment content by index.
    /// Note: For large attachments, consider using write_attachment_to_path instead.
    pub fn get_attachment_content(&self, index: u32) -> Option<Vec<u8>> {
        self.message
            .attachments
            .get(index as usize)
            .map(|a| a.content.clone())
    }

    /// Write an attachment directly to a file path.
    /// This avoids copying large attachments across the FFI boundary.
    /// Returns true on success, false if the index is out of range.
    ///
    /// # Security
    /// The caller is responsible for validating that `path` is a safe, sandboxed location.
    /// This function will create parent directories and write to the specified path without
    /// additional path validation. Use only with paths constructed from trusted sources
    /// (e.g., application cache directories).
    pub fn write_attachment_to_path(&self, index: u32, path: String) -> Result<bool, ParseError> {
        match self.message.attachments.get(index as usize) {
            Some(attachment) => write_file(&path, &attachment.content).map(|()| true),
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

/// Compiled once: the selector is a constant, so parsing it per call was pure
/// overhead on a path the message list hits for every rendered email.
static IMG_SELECTOR: LazyLock<scraper::Selector> =
    LazyLock::new(|| scraper::Selector::parse("img").expect("`img` is a valid CSS selector"));

/// Extract all remote image URLs from HTML content.
/// Uses proper HTML parsing instead of regex to handle edge cases.
///
/// Returns a list of remote image URLs found in `<img src="...">` tags.
/// Only returns http:// and https:// URLs, excludes cid: URLs.
#[uniffi::export]
pub fn extract_remote_images(html: String) -> Vec<RemoteImage> {
    let document = scraper::Html::parse_document(&html);
    document
        .select(&IMG_SELECTOR)
        .map(|element| element.value())
        .filter_map(|img| {
            let src = img.attr("src")?;
            let remote = src.starts_with("http://") || src.starts_with("https://");
            remote.then(|| RemoteImage {
                url: src.to_string(),
                is_tracking_pixel: is_tracking_pixel(img),
            })
        })
        .collect()
}

/// Whether an `<img>` declares itself 1×1 or smaller — the shape of a beacon
/// whose only purpose is to report that the mail was opened.
fn is_tracking_pixel(img: &scraper::node::Element) -> bool {
    let is_pinpoint = |attribute| {
        img.attr(attribute)
            .and_then(|value| value.parse::<u32>().ok())
            .is_some_and(|pixels| pixels <= 1)
    };
    is_pinpoint("width") && is_pinpoint("height")
}

#[cfg(test)]
mod tests {
    use super::*;

    const SIMPLE_EMAIL: &str =
        "Subject: Hello\r\nFrom: sender@example.com\r\nTo: recipient@example.com\r\n\r\n<p>Body</p>";

    const MULTIPART_EMAIL: &str = r#"Subject: Test Multipart
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
"#;

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
        assert_eq!(parse_eml(vec![]).err(), Some(ParseError::Empty));
    }

    #[test]
    fn returns_none_for_missing_cid() {
        let handle = parse_eml(SIMPLE_EMAIL.as_bytes().to_vec()).expect("should parse");
        assert_eq!(handle.get_resource("nonexistent".to_string()), None);
    }

    #[test]
    fn handles_malformed_input_gracefully() {
        // mail-parser is lenient, so this parses with empty fields rather than
        // failing; the contract is that it does not panic.
        let handle = parse_eml(b"not a valid email".to_vec()).expect("lenient parse");
        assert_eq!(handle.attachment_count(), 0);
    }

    const EMAIL_WITH_HEADERS: &str = "Subject: Full Headers\r\n\
         From: sender@example.com\r\n\
         To: recipient@example.com\r\n\
         Cc: cc@example.com\r\n\
         Reply-To: reply@example.com\r\n\
         Message-ID: <msg123@example.com>\r\n\r\n\
         Body";

    #[test]
    fn parses_extended_headers() {
        let handle = parse_eml(EMAIL_WITH_HEADERS.as_bytes().to_vec()).expect("should parse");
        assert_eq!(handle.cc(), "cc@example.com");
        assert_eq!(handle.reply_to(), "reply@example.com");
        assert_eq!(handle.message_id(), "msg123@example.com");
    }

    const EMAIL_WITH_ATTACHMENT: &str = "Subject: With Attachment\r\n\
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
         --mixed-boundary--\r\n";

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

    /// The reported size must be the byte count actually held, not a separately
    /// stored number that could drift from it.
    #[test]
    fn attachment_size_matches_its_content() {
        let handle = parse_eml(EMAIL_WITH_ATTACHMENT.as_bytes().to_vec()).expect("should parse");
        let content = handle.get_attachment_content(0).expect("content");
        assert_eq!(handle.get_attachments()[0].size, content.len() as u64);
        assert_eq!(content, b"Hello World!");
    }

    // Tests for new optimized FFI functions

    const EMAIL_WITH_INLINE_IMAGE: &str = "Subject: Email with Inline Image\r\n\
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
         --related-boundary--\r\n";

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

    /// An inline `cid:` part is a resource and *not* an attachment; the two
    /// collections must stay disjoint.
    #[test]
    fn inline_parts_are_not_also_attachments() {
        let handle = parse_eml(EMAIL_WITH_INLINE_IMAGE.as_bytes().to_vec()).expect("should parse");
        assert_eq!(handle.get_resource_ids(), vec!["image001".to_string()]);
        assert_eq!(handle.attachment_count(), 0);
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
        assert_eq!(written, b"Hello World!");

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

    /// A missing target must not leave a stray empty file behind: the absent
    /// case returns before any I/O happens.
    #[test]
    fn missing_target_writes_nothing() {
        let handle = parse_eml(SIMPLE_EMAIL.as_bytes().to_vec()).expect("should parse");
        let path = std::env::temp_dir().join("letterbox_no_such_write.bin");
        let _ = fs::remove_file(&path);

        let written = handle
            .write_attachment_to_path(0, path.to_str().unwrap().to_string())
            .expect("must not error");

        assert!(!written);
        assert!(!path.exists(), "no file may be created for a missing index");
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

    /// An unwritable destination must surface as an error, not a silent `false`:
    /// "no such resource" and "could not write it" are different facts.
    #[test]
    fn unwritable_path_is_an_error_not_a_false() {
        let handle = parse_eml(EMAIL_WITH_ATTACHMENT.as_bytes().to_vec()).expect("should parse");
        // A path whose parent is an existing *file* cannot be created.
        let blocker = std::env::temp_dir().join("letterbox_blocker_file");
        fs::write(&blocker, b"x").expect("create blocker");
        let target = blocker.join("child.bin");

        let result = handle.write_attachment_to_path(0, target.to_str().unwrap().to_string());

        assert!(matches!(result, Err(ParseError::IoError { .. })));
        let _ = fs::remove_file(blocker);
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
        assert_eq!(handle.subject(), "こんにちは 🌍 Émoji");
    }

    #[test]
    fn parses_email_with_very_long_subject() {
        let long_subject = "X".repeat(1000);
        let email = format!("Subject: {long_subject}\r\nFrom: test@test.com\r\n\r\nBody");
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        assert_eq!(handle.subject(), long_subject);
    }

    #[test]
    fn parses_email_with_missing_subject() {
        let email = "From: test@test.com\r\nTo: recipient@test.com\r\n\r\nBody";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        // Should fall back to "Untitled" for missing subject
        assert_eq!(handle.subject(), UNTITLED_SUBJECT);
    }

    #[test]
    fn parses_email_with_empty_fields() {
        let email = "Subject: Test\r\n\r\nBody";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        // Absent headers are empty strings, never a panic.
        assert!(handle.from().is_empty());
        assert!(handle.to().is_empty());
        assert!(handle.cc().is_empty());
    }

    #[test]
    fn html_escape_handles_all_special_chars() {
        assert_eq!(
            html_escape(r#"a & b < c > d " e ' f"#),
            "a &amp; b &lt; c &gt; d &quot; e &#39; f"
        );
    }

    /// The escapes must not compound: an escape's own `&` is already output and
    /// must not be escaped a second time.
    #[test]
    fn html_escape_does_not_double_escape() {
        assert_eq!(html_escape("&amp;"), "&amp;amp;");
        assert_eq!(html_escape("<b>"), "&lt;b&gt;");
    }

    #[test]
    fn parses_email_with_multiple_recipients() {
        let email = "Subject: Multi\r\n\
                     From: sender@example.com\r\n\
                     To: alice@example.com, bob@example.com\r\n\
                     Cc: carol@example.com\r\n\
                     \r\nBody";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        assert_eq!(handle.to(), "alice@example.com, bob@example.com");
    }

    #[test]
    fn parses_email_with_date() {
        let email = "Subject: Dated\r\n\
                     From: test@test.com\r\n\
                     Date: Mon, 11 Dec 2025 10:00:00 +0000\r\n\
                     \r\nBody";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        assert!(!handle.date().is_empty());
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

    const EMAIL_WITH_MULTIPLE_ATTACHMENTS: &str = "Subject: Multiple Attachments\r\n\
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
         --mixed-boundary--\r\n";

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

    /// A grouped header (`Team: a@x, b@x;`) must flatten to the same address
    /// stream a flat header produces — the list/group distinction is syntax.
    #[test]
    fn grouped_recipients_flatten_like_a_list() {
        let email = "Subject: Test\r\n\
                     From: sender@example.com\r\n\
                     To: Team: alice@example.com, bob@example.com;\r\n\r\n\
                     Body";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let emails: Vec<String> = handle
            .recipient_info()
            .into_iter()
            .map(|r| r.email)
            .collect();
        assert_eq!(emails, vec!["alice@example.com", "bob@example.com"]);
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

    /// `To` precedes `Cc`: the search index is order-sensitive, so the
    /// concatenation must not be reordered.
    #[test]
    fn recipient_info_keeps_to_before_cc() {
        let email = "Subject: Test\r\n\
                     From: sender@example.com\r\n\
                     To: alice@example.com\r\n\
                     Cc: carol@example.com\r\n\r\n\
                     Body";
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let emails: Vec<String> = handle
            .recipient_info()
            .into_iter()
            .map(|r| r.email)
            .collect();
        assert_eq!(emails, vec!["alice@example.com", "carol@example.com"]);
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
        assert_eq!(handle.date_timestamp(), 0);
    }

    #[test]
    fn body_preview_returns_first_500_chars() {
        // Create an email with a long body
        let long_body = "a ".repeat(300); // 600 chars with spaces
        let email = format!(
            "Subject: Test\r\n\
             From: sender@example.com\r\n\r\n\
             {long_body}"
        );
        let handle = parse_eml(email.as_bytes().to_vec()).expect("should parse");
        let preview = handle.body_preview();
        // Preview should be limited to ~500 chars worth of content
        assert!(preview.len() <= 500);
        // Should contain 'a' from the body
        assert!(preview.contains('a'));
    }

    /// The cut is by character, not by byte: a multi-byte body must not be
    /// sliced mid-character (which would panic) nor truncated early.
    #[test]
    fn preview_cuts_on_character_boundaries() {
        let text = "é".repeat(600);
        let cut = preview(&text);
        assert_eq!(cut.chars().count(), PREVIEW_CHARS);
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
        assert_eq!(handle.body_preview(), "Hello World This is a test");
    }

    // ---- remote image extraction ----

    #[test]
    fn extracts_only_remote_images() {
        let images = extract_remote_images(
            r#"<img src="https://a/1.png"><img src="cid:x"><img src="http://b/2.gif"><img>"#
                .to_string(),
        );
        let urls: Vec<String> = images.iter().map(|i| i.url.clone()).collect();
        assert_eq!(urls, vec!["https://a/1.png", "http://b/2.gif"]);
    }

    #[test]
    fn flags_one_by_one_images_as_tracking_pixels() {
        let images = extract_remote_images(
            r#"<img src="https://a/p.gif" width="1" height="1">
               <img src="https://a/w.png" width="1">
               <img src="https://a/b.png" width="600" height="400">"#
                .to_string(),
        );
        assert!(images[0].is_tracking_pixel, "1x1 is a beacon");
        assert!(!images[1].is_tracking_pixel, "height unstated is unknown");
        assert!(!images[2].is_tracking_pixel);
    }
}
