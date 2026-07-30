//! Minimal, allocation-conscious HTTP/1.1 wire codec.
//!
//! This module is pure: it builds request bytes and parses response bytes with
//! no networking, so it is trivially unit-testable and shared by both the image
//! fetcher and the DNS-over-HTTPS resolver. Only the small subset of HTTP/1.1
//! needed for `GET` requests with `Connection: close` is implemented.

use crate::error::ProxyError;

/// A parsed HTTP/1.1 response.
#[derive(Debug, Clone)]
pub struct HttpResponse {
    /// Status code (e.g. `200`).
    pub status: u16,
    /// Header name/value pairs, with names lowercased for case-insensitive lookup.
    pub headers: Vec<(String, String)>,
    /// Decoded response body.
    pub body: Vec<u8>,
}

impl HttpResponse {
    /// First header value matching `name` (case-insensitive), if present.
    pub fn header(&self, name: &str) -> Option<&str> {
        let needle = name.to_ascii_lowercase();
        self.headers
            .iter()
            .find(|(k, _)| *k == needle)
            .map(|(_, v)| v.as_str())
    }

    /// Whether the status code denotes a redirect with a `Location` header.
    pub fn redirect_location(&self) -> Option<&str> {
        matches!(self.status, 301 | 302 | 303 | 307 | 308)
            .then(|| self.header("location"))
            .flatten()
    }
}

/// Headers this codec owns outright.
///
/// They frame the message rather than describe the client, so a caller-supplied
/// copy is never merely redundant: a second `Content-Length` or
/// `Transfer-Encoding` is how a request is smuggled past a parser, and a second
/// `Host` picks a different origin than the one the connection was opened to.
/// A value supplied for any of these is dropped.
const RESERVED_HEADERS: &[&str] = &[
    "host",
    "connection",
    "accept-encoding",
    "content-length",
    "transfer-encoding",
];

/// The `User-Agent` every tunnelled request carries unless the caller names its
/// own.
///
/// Sending none is not the private option, it is the conspicuous one. Almost no
/// real client omits this header, so its absence both singles the request out
/// and gets it refused: Cloudflare and Akamai score a missing `User-Agent` as a
/// bot and answer 403. That is why remote images failed while the tunnel looked
/// healthy — `/cdn-cgi/trace`, which the in-app check fetches, is exempt from
/// bot management and answers anyone. [`crate::update`] already carried its own
/// `User-Agent` for the same reason, the GitHub API rejecting requests without
/// one outright; image fetches were the only path left sending none.
///
/// Privacy here comes from every install sending the *same* string, not from
/// sending nothing — a shared value is an anonymity set, a unique or absent one
/// is a fingerprint. This is Chrome's reduced Android user agent, in which the
/// OS version is frozen at `10` and the device model at `K` for every device on
/// every Android release, which makes it the largest such set available.
pub const USER_AGENT: &str = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 \
                              (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

/// Build a serialised HTTP/1.1 `GET` request with `Connection: close`.
///
/// Headers come from two places and the rule between them is explicit:
/// [`RESERVED_HEADERS`] are always this function's and a caller-supplied value
/// is discarded, while `Accept` and `User-Agent` are *defaults* that a caller
/// may replace. Emitting a default unconditionally would have put two `Accept`
/// lines on the wire for any caller that set one.
pub fn build_get_request(
    host: &str,
    path: &str,
    accept: &str,
    extra_headers: &[(String, String)],
) -> Vec<u8> {
    let supplied = |name: &str| {
        extra_headers
            .iter()
            .any(|(header, _)| header.eq_ignore_ascii_case(name))
    };

    let mut request = String::with_capacity(512);
    request.push_str("GET ");
    request.push_str(if path.is_empty() { "/" } else { path });
    request.push_str(" HTTP/1.1\r\n");

    // Reserved: ours, unconditionally.
    request.push_str("Host: ");
    request.push_str(host);
    request.push_str("\r\nAccept-Encoding: identity\r\nConnection: close\r\n");

    // Defaulted: ours only where the caller has not spoken.
    if !supplied("accept") {
        request.push_str("Accept: ");
        request.push_str(accept);
        request.push_str("\r\n");
    }
    if !supplied("user-agent") {
        request.push_str("User-Agent: ");
        request.push_str(USER_AGENT);
        request.push_str("\r\n");
    }

    for (name, value) in extra_headers {
        let lower = name.to_ascii_lowercase();
        if RESERVED_HEADERS.contains(&lower.as_str()) {
            continue;
        }
        request.push_str(name);
        request.push_str(": ");
        request.push_str(value);
        request.push_str("\r\n");
    }
    request.push_str("\r\n");
    request.into_bytes()
}

/// Parse a complete raw HTTP/1.1 response (headers + body).
pub fn parse_response(raw: &[u8]) -> Result<HttpResponse, ProxyError> {
    let split = find_header_end(raw).ok_or_else(|| ProxyError::HttpError {
        status_code: 0,
        details: "Malformed response: no header terminator".to_string(),
    })?;
    let (head, body_start) = raw.split_at(split);
    let body_bytes = &body_start[4..]; // skip the CRLFCRLF

    let head_str = std::str::from_utf8(head).map_err(|_| ProxyError::HttpError {
        status_code: 0,
        details: "Response headers are not valid UTF-8".to_string(),
    })?;

    let mut lines = head_str.split("\r\n");
    let status_line = lines.next().ok_or_else(|| ProxyError::HttpError {
        status_code: 0,
        details: "Empty response".to_string(),
    })?;
    let status = parse_status_line(status_line)?;

    let mut headers = Vec::new();
    for line in lines {
        if line.is_empty() {
            continue;
        }
        if let Some((name, value)) = line.split_once(':') {
            headers.push((name.trim().to_ascii_lowercase(), value.trim().to_string()));
        }
    }

    let body = decode_body(&headers, body_bytes)?;
    Ok(HttpResponse {
        status,
        headers,
        body,
    })
}

/// Locate the `\r\n\r\n` header/body boundary, returning the index of its start.
fn find_header_end(raw: &[u8]) -> Option<usize> {
    raw.windows(4).position(|w| w == b"\r\n\r\n")
}

/// Parse `HTTP/1.1 200 OK` into its numeric status code.
fn parse_status_line(line: &str) -> Result<u16, ProxyError> {
    line.split_whitespace()
        .nth(1)
        .and_then(|code| code.parse::<u16>().ok())
        .ok_or_else(|| ProxyError::HttpError {
            status_code: 0,
            details: format!("Invalid status line: {line}"),
        })
}

/// Decode the body honouring `Transfer-Encoding: chunked` or `Content-Length`.
fn decode_body(headers: &[(String, String)], body: &[u8]) -> Result<Vec<u8>, ProxyError> {
    let chunked = headers
        .iter()
        .any(|(k, v)| k == "transfer-encoding" && v.to_ascii_lowercase().contains("chunked"));
    if chunked {
        return decode_chunked(body);
    }

    if let Some((_, len)) = headers.iter().find(|(k, _)| k == "content-length") {
        if let Ok(len) = len.parse::<usize>() {
            let end = len.min(body.len());
            return Ok(body[..end].to_vec());
        }
    }
    // `Connection: close` framing: the remaining bytes are the whole body.
    Ok(body.to_vec())
}

/// Decode a chunked transfer-encoded body.
fn decode_chunked(mut body: &[u8]) -> Result<Vec<u8>, ProxyError> {
    let mut out = Vec::with_capacity(body.len());
    loop {
        let line_end =
            body.windows(2)
                .position(|w| w == b"\r\n")
                .ok_or_else(|| ProxyError::HttpError {
                    status_code: 0,
                    details: "Truncated chunk header".to_string(),
                })?;
        let size_str = std::str::from_utf8(&body[..line_end]).unwrap_or("");
        let size_hex = size_str.split(';').next().unwrap_or("").trim();
        let size = usize::from_str_radix(size_hex, 16).map_err(|_| ProxyError::HttpError {
            status_code: 0,
            details: format!("Invalid chunk size: {size_hex}"),
        })?;
        body = &body[line_end + 2..];
        if size == 0 {
            break;
        }
        if body.len() < size {
            return Err(ProxyError::HttpError {
                status_code: 0,
                details: "Truncated chunk body".to_string(),
            });
        }
        out.extend_from_slice(&body[..size]);
        body = &body[size..];
        // Skip the trailing CRLF after each chunk.
        if body.len() >= 2 {
            body = &body[2..];
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_minimal_get_request() {
        let req = build_get_request("example.com", "/img.png", "image/*", &[]);
        let text = String::from_utf8(req).unwrap();
        assert!(text.starts_with("GET /img.png HTTP/1.1\r\n"));
        assert!(text.contains("Host: example.com\r\n"));
        assert!(text.contains("Connection: close\r\n"));
        assert!(text.ends_with("\r\n\r\n"));
    }

    /// The regression this file exists to prevent. A request with no
    /// `User-Agent` is answered with 403 by every major bot-management service,
    /// so remote images failed uniformly while the tunnel itself was healthy.
    #[test]
    fn always_sends_a_user_agent() {
        let text = String::from_utf8(build_get_request("example.com", "/i.png", "image/*", &[]))
            .expect("request is ASCII");
        assert!(
            text.contains(&format!("User-Agent: {USER_AGENT}\r\n")),
            "no User-Agent in:\n{text}"
        );
    }

    /// The default is a default, not a mandate: `update` identifies itself to
    /// the GitHub API. Replacing must not mean *appending*, because two
    /// `User-Agent` lines is a malformed request.
    #[test]
    fn a_caller_supplied_user_agent_replaces_the_default() {
        let extra = vec![(
            "User-Agent".to_string(),
            "Letterbox-UpdateChecker".to_string(),
        )];
        let text = String::from_utf8(build_get_request("api.github.com", "/", "*/*", &extra))
            .expect("request is ASCII");
        assert_eq!(text.matches("User-Agent:").count(), 1);
        assert!(text.contains("User-Agent: Letterbox-UpdateChecker\r\n"));
        assert!(!text.contains("Mozilla/5.0"));
    }

    /// `Accept` arrives both as a parameter and, potentially, as a caller
    /// header. Emitting both put two `Accept` lines on the wire.
    #[test]
    fn a_caller_supplied_accept_replaces_the_parameter() {
        // Lowercase on purpose: header names are case-insensitive on the wire,
        // so the override must match that way and not by spelling.
        let extra = vec![("accept".to_string(), "text/plain".to_string())];
        let text = String::from_utf8(build_get_request("example.com", "/", "image/*", &extra))
            .expect("request is ASCII");
        let accepts = text
            .lines()
            .filter(|line| line.to_ascii_lowercase().starts_with("accept:"))
            .count();
        assert_eq!(accepts, 1, "expected one Accept line in:\n{text}");
        assert!(text.contains("accept: text/plain\r\n"));
        assert!(!text.contains("image/*"));
    }

    /// Framing headers are not defaults and cannot be overridden: a second
    /// `Content-Length` or `Transfer-Encoding` is how a request is smuggled.
    #[test]
    fn reserved_headers_cannot_be_overridden() {
        let extra = vec![
            ("Content-Length".to_string(), "0".to_string()),
            ("Transfer-Encoding".to_string(), "chunked".to_string()),
            ("Accept-Encoding".to_string(), "gzip".to_string()),
        ];
        let text = String::from_utf8(build_get_request("example.com", "/", "*/*", &extra))
            .expect("request is ASCII");
        assert!(!text.contains("Content-Length"));
        assert!(!text.contains("chunked"));
        assert_eq!(text.matches("Accept-Encoding:").count(), 1);
        assert!(text.contains("Accept-Encoding: identity\r\n"));
    }

    #[test]
    fn skips_managed_headers() {
        let extra = vec![
            ("Host".to_string(), "evil.com".to_string()),
            ("X-Custom".to_string(), "yes".to_string()),
        ];
        let req = String::from_utf8(build_get_request("example.com", "/", "*/*", &extra)).unwrap();
        assert_eq!(req.matches("Host:").count(), 1);
        assert!(req.contains("X-Custom: yes"));
        assert!(!req.contains("evil.com"));
    }

    #[test]
    fn parses_simple_response() {
        let raw = b"HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 4\r\n\r\n\x89PNG";
        let resp = parse_response(raw).unwrap();
        assert_eq!(resp.status, 200);
        assert_eq!(resp.header("content-type"), Some("image/png"));
        assert_eq!(resp.body, b"\x89PNG");
    }

    #[test]
    fn parses_chunked_response() {
        let raw = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n";
        let resp = parse_response(raw).unwrap();
        assert_eq!(resp.status, 200);
        assert_eq!(resp.body, b"Wikipedia");
    }

    #[test]
    fn detects_redirect() {
        let raw = b"HTTP/1.1 302 Found\r\nLocation: https://example.com/x\r\n\r\n";
        let resp = parse_response(raw).unwrap();
        assert_eq!(resp.redirect_location(), Some("https://example.com/x"));
    }

    #[test]
    fn non_redirect_has_no_location() {
        let raw = b"HTTP/1.1 200 OK\r\nLocation: https://example.com/x\r\n\r\n";
        let resp = parse_response(raw).unwrap();
        assert_eq!(resp.redirect_location(), None);
    }

    #[test]
    fn rejects_malformed_response() {
        assert!(parse_response(b"garbage without terminator").is_err());
    }
}
