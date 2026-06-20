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

/// Build a serialised HTTP/1.1 `GET` request with `Connection: close`.
///
/// `extra_headers` are appended verbatim; `Host`, `Connection` and `Accept`
/// are always supplied by this function so callers cannot accidentally leak
/// identifying defaults.
pub fn build_get_request(
    host: &str,
    path: &str,
    accept: &str,
    extra_headers: &[(String, String)],
) -> Vec<u8> {
    let mut request = String::with_capacity(256);
    request.push_str("GET ");
    request.push_str(if path.is_empty() { "/" } else { path });
    request.push_str(" HTTP/1.1\r\n");
    request.push_str("Host: ");
    request.push_str(host);
    request.push_str("\r\n");
    request.push_str("Accept: ");
    request.push_str(accept);
    request.push_str("\r\n");
    request.push_str("Accept-Encoding: identity\r\n");
    request.push_str("Connection: close\r\n");
    for (name, value) in extra_headers {
        // Skip headers we manage ourselves to avoid duplicates / smuggling.
        let lower = name.to_ascii_lowercase();
        if matches!(
            lower.as_str(),
            "host" | "connection" | "accept-encoding" | "content-length" | "transfer-encoding"
        ) {
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
