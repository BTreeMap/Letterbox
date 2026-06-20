//! Image/URL fetching over the WARP tunnel.
//!
//! Every request is resolved via DoH and carried over WireGuard — there is no
//! direct (non-tunnelled) network path, so the user's real IP is never exposed
//! to image servers or the update endpoint. The module exposes a generic
//! [`fetch`] used both for images and for the GitHub update check, plus pure
//! magic-byte helpers for content sniffing/validation.

use crate::config::FetchLimits;
use crate::error::ProxyError;
use crate::tunnel::dns::resolve;
use crate::tunnel::http1::{build_get_request, parse_response};
use crate::tunnel::stack::WarpTunnel;
use crate::tunnel::tls::request_https;
use std::io::{Read, Write};
use std::time::Duration;
use url::Url;

/// Outcome of a successful fetch through the tunnel.
#[derive(Debug, Clone)]
pub struct FetchOutcome {
    /// HTTP status code of the final response.
    pub status: u16,
    /// Normalised MIME type (without parameters).
    pub mime_type: String,
    /// Response body bytes.
    pub body: Vec<u8>,
    /// Final URL after any redirects.
    pub final_url: String,
}

/// Custom request headers supplied by the caller.
type Headers = [(String, String)];

/// Fetch `url` through the tunnel, following up to `limits.max_redirects`.
///
/// Content-type *filtering* is intentionally left to the caller so this can
/// serve both image fetches (image/* only) and the JSON update check.
pub fn fetch(
    tunnel: &mut WarpTunnel,
    url: &str,
    headers: &Headers,
    limits: &FetchLimits,
    accept: &str,
) -> Result<FetchOutcome, ProxyError> {
    let timeout = Duration::from_secs(limits.timeout_seconds as u64);
    let mut current = parse_and_validate(url)?;
    let mut redirects = 0u32;

    loop {
        let host = current
            .host_str()
            .ok_or_else(|| ProxyError::InvalidUrl {
                url: current.to_string(),
                details: "URL has no host".to_string(),
            })?
            .to_string();
        let is_https = current.scheme() == "https";
        let port = current.port().unwrap_or(if is_https { 443 } else { 80 });
        let path = path_with_query(&current);

        let ip = resolve(tunnel, &host, timeout)?;
        let request = build_get_request(&host, &path, accept, headers);
        let read_cap = limits.max_size as usize + 64 * 1024;

        let raw = if is_https {
            request_https(tunnel, ip, port, &host, &request, read_cap, timeout)?
        } else {
            request_plain(tunnel, ip, port, &request, read_cap, timeout)?
        };

        let response = parse_response(&raw)?;

        if let Some(location) = response.redirect_location() {
            redirects += 1;
            if redirects > limits.max_redirects {
                return Err(ProxyError::TooManyRedirects {
                    count: redirects,
                    max_count: limits.max_redirects,
                });
            }
            let next = current.join(location).map_err(|e| ProxyError::InvalidUrl {
                url: location.to_string(),
                details: e.to_string(),
            })?;
            current = parse_and_validate(next.as_str())?;
            continue;
        }

        if !(200..300).contains(&response.status) {
            return Err(ProxyError::HttpError {
                status_code: response.status,
                details: format!("HTTP {}", response.status),
            });
        }

        if response.body.len() as u64 > limits.max_size {
            return Err(ProxyError::ResponseTooLarge {
                size: response.body.len() as u64,
                max_size: limits.max_size,
            });
        }

        let mime_type = response
            .header("content-type")
            .map(normalize_mime)
            .unwrap_or_else(|| "application/octet-stream".to_string());

        return Ok(FetchOutcome {
            status: response.status,
            mime_type,
            body: response.body,
            final_url: current.to_string(),
        });
    }
}

/// Parse a URL and ensure it uses a supported scheme.
fn parse_and_validate(url: &str) -> Result<Url, ProxyError> {
    let parsed = Url::parse(url).map_err(|e| ProxyError::InvalidUrl {
        url: url.to_string(),
        details: e.to_string(),
    })?;
    if parsed.scheme() != "http" && parsed.scheme() != "https" {
        return Err(ProxyError::InvalidUrl {
            url: url.to_string(),
            details: "Only http:// and https:// URLs are supported".to_string(),
        });
    }
    Ok(parsed)
}

/// Build the request target (path plus optional query).
fn path_with_query(url: &Url) -> String {
    match url.query() {
        Some(q) => format!("{}?{}", url.path(), q),
        None => url.path().to_string(),
    }
}

/// Lowercase and strip parameters from a `Content-Type` value.
fn normalize_mime(value: &str) -> String {
    value
        .split(';')
        .next()
        .unwrap_or(value)
        .trim()
        .to_ascii_lowercase()
}

/// Send a plaintext HTTP/1.1 request over the tunnel and read the full response.
fn request_plain(
    tunnel: &mut WarpTunnel,
    ip: smoltcp::wire::IpAddress,
    port: u16,
    request: &[u8],
    max_body: usize,
    timeout: Duration,
) -> Result<Vec<u8>, ProxyError> {
    let handle = tunnel.open_tcp(ip, port, timeout)?;
    let result = (|| -> Result<Vec<u8>, ProxyError> {
        let mut stream = tunnel.stream(handle, timeout);
        stream
            .write_all(request)
            .map_err(|e| ProxyError::HttpError {
                status_code: 0,
                details: format!("Write failed: {e}"),
            })?;
        stream.flush().map_err(|e| ProxyError::HttpError {
            status_code: 0,
            details: format!("Flush failed: {e}"),
        })?;

        let mut buf = Vec::with_capacity(16 * 1024);
        let mut chunk = [0u8; 16 * 1024];
        loop {
            match stream.read(&mut chunk) {
                Ok(0) => break,
                Ok(n) => {
                    buf.extend_from_slice(&chunk[..n]);
                    if buf.len() > max_body {
                        return Err(ProxyError::ResponseTooLarge {
                            size: buf.len() as u64,
                            max_size: max_body as u64,
                        });
                    }
                }
                Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
                Err(e) => {
                    return Err(ProxyError::HttpError {
                        status_code: 0,
                        details: format!("Read failed: {e}"),
                    })
                }
            }
        }
        Ok(buf)
    })();
    tunnel.close_tcp(handle);
    result
}

/// Guess the MIME type from file magic bytes.
pub fn guess_mime_type(data: &[u8]) -> Option<&'static str> {
    if data.len() < 4 {
        return None;
    }

    match &data[..4] {
        [0x89, 0x50, 0x4E, 0x47] => Some("image/png"),
        [0xFF, 0xD8, 0xFF, _] => Some("image/jpeg"),
        [0x47, 0x49, 0x46, 0x38] => Some("image/gif"),
        [0x52, 0x49, 0x46, 0x46] if data.len() >= 12 && &data[8..12] == b"WEBP" => {
            Some("image/webp")
        }
        [0x42, 0x4D, _, _] => Some("image/bmp"),
        [0x00, 0x00, 0x01, 0x00] => Some("image/x-icon"),
        _ => {
            if data.len() >= 5 {
                let start = String::from_utf8_lossy(&data[..std::cmp::min(100, data.len())]);
                if start.contains("<svg") || start.contains("<?xml") {
                    return Some("image/svg+xml");
                }
            }
            None
        }
    }
}

/// Validate that response data matches the claimed MIME type.
pub fn validate_image_data(data: &[u8], claimed_mime: &str) -> bool {
    if data.is_empty() {
        return false;
    }

    if claimed_mime == "image/svg+xml" {
        let start = String::from_utf8_lossy(&data[..std::cmp::min(100, data.len())]);
        return start.contains("<svg")
            || start.contains("<?xml")
            || start.contains("<!DOCTYPE svg");
    }

    if let Some(detected) = guess_mime_type(data) {
        let claimed_base = claimed_mime.split('/').nth(1).unwrap_or("");
        let detected_base = detected.split('/').nth(1).unwrap_or("");
        detected == claimed_mime
            || (claimed_base.contains("icon") && detected_base.contains("icon"))
    } else {
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_and_validate_accepts_http_and_https() {
        assert!(parse_and_validate("http://example.com/x.png").is_ok());
        assert!(parse_and_validate("https://example.com/x.png").is_ok());
    }

    #[test]
    fn parse_and_validate_rejects_other_schemes() {
        for url in [
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:image/png;base64,AAAA",
            "ftp://example.com/x",
        ] {
            assert!(matches!(
                parse_and_validate(url),
                Err(ProxyError::InvalidUrl { .. })
            ));
        }
    }

    #[test]
    fn path_with_query_includes_query() {
        let url = Url::parse("https://h/a/b?x=1&y=2").unwrap();
        assert_eq!(path_with_query(&url), "/a/b?x=1&y=2");
        let url = Url::parse("https://h/a/b").unwrap();
        assert_eq!(path_with_query(&url), "/a/b");
    }

    #[test]
    fn normalize_mime_strips_params() {
        assert_eq!(normalize_mime("image/PNG; charset=binary"), "image/png");
        assert_eq!(normalize_mime("image/jpeg"), "image/jpeg");
    }

    #[test]
    fn guess_png() {
        assert_eq!(
            guess_mime_type(&[0x89, 0x50, 0x4E, 0x47]),
            Some("image/png")
        );
    }

    #[test]
    fn guess_jpeg() {
        assert_eq!(
            guess_mime_type(&[0xFF, 0xD8, 0xFF, 0xE0]),
            Some("image/jpeg")
        );
    }

    #[test]
    fn guess_webp() {
        let data = [0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, b'W', b'E', b'B', b'P'];
        assert_eq!(guess_mime_type(&data), Some("image/webp"));
    }

    #[test]
    fn validate_rejects_empty() {
        assert!(!validate_image_data(&[], "image/png"));
    }

    #[test]
    fn validate_accepts_matching_png() {
        assert!(validate_image_data(
            &[0x89, 0x50, 0x4E, 0x47, 0x0D],
            "image/png"
        ));
    }

    #[test]
    fn validate_svg_by_marker() {
        assert!(validate_image_data(
            b"<svg xmlns=...></svg>",
            "image/svg+xml"
        ));
    }
}
