//! Subresource/URL fetching over the WARP tunnel.
//!
//! Every request is resolved via DoH and carried over the tunnel — there is no
//! direct (non-tunnelled) network path, so the user's real IP is never exposed
//! to the servers a message references or to the update endpoint. The module
//! exposes a generic [`fetch`] serving page subresources of every type, the
//! GitHub update check and the trace probe alike, plus pure predicates over a
//! response's content type: [`is_active_content`], which is a security rule, and
//! the magic-byte helpers, which are sniffing aids.

use crate::config::FetchLimits;
use crate::error::{ProxyError, NO_HTTP_RESPONSE};
use crate::tunnel::dns::resolve;
use crate::tunnel::http1::{build_get_request, parse_response, ClientProfile, ContentCoding};
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

/// Where one hop goes, extracted from a URL once.
///
/// Replaces four locals recomputed per iteration, and makes "URL has no host" a
/// single parse failure instead of a check every user of the host had to repeat.
struct Target {
    host: String,
    port: u16,
    https: bool,
    path: String,
}

impl Target {
    /// Pure projection of a validated URL onto what a connection needs.
    fn of(url: &Url) -> Result<Self, ProxyError> {
        let host = url
            .host_str()
            .ok_or_else(|| ProxyError::InvalidUrl {
                url: url.to_string(),
                details: "URL has no host".to_string(),
            })?
            .to_string();
        let https = url.scheme() == "https";
        Ok(Self {
            host,
            port: url.port().unwrap_or(if https { 443 } else { 80 }),
            https,
            path: path_with_query(url),
        })
    }
}

/// What a response means for the exchange it belongs to.
enum Step {
    /// A redirect: continue from this URL.
    Follow(Url),
    /// The exchange is complete.
    Done(FetchOutcome),
}

/// Fetch `url` through the tunnel, following up to `limits.max_redirects`.
///
/// Content-type *filtering* is intentionally left to the caller so this can
/// serve page subresources of any type, the JSON update check and the trace
/// probe alike. What a fetch is *for* decides which types are acceptable; the
/// transport has no opinion.
///
/// The loop body performs exactly one effect — [`send`] — with the target,
/// the request bytes and the whole response policy decided by pure functions
/// either side of it. That is what lets redirect handling, status rules, size
/// ceilings and content decoding be tested without a tunnel.
pub fn fetch(
    tunnel: &mut WarpTunnel,
    url: &str,
    headers: &Headers,
    limits: &FetchLimits,
    profile: &ClientProfile,
) -> Result<FetchOutcome, ProxyError> {
    let timeout = Duration::from_secs(limits.timeout_seconds as u64);
    let read_cap = limits.max_size as usize + 64 * 1024;
    let mut current = parse_and_validate(url)?;
    let mut redirects = 0u32;

    loop {
        let target = Target::of(&current)?;
        let request = build_get_request(&target.host, &target.path, profile, headers);

        let raw = send(tunnel, &target, &request, read_cap, timeout)?;

        match interpret(parse_response(&raw)?, &current, redirects, limits)? {
            Step::Done(outcome) => return Ok(outcome),
            Step::Follow(next) => {
                current = next;
                redirects += 1;
            }
        }
    }
}

/// The one effect in a fetch: resolve, connect, write, read.
fn send(
    tunnel: &mut WarpTunnel,
    target: &Target,
    request: &[u8],
    read_cap: usize,
    timeout: Duration,
) -> Result<Vec<u8>, ProxyError> {
    let ip = resolve(tunnel, &target.host, timeout)?;
    if target.https {
        request_https(
            tunnel,
            ip,
            target.port,
            &target.host,
            request,
            read_cap,
            timeout,
        )
    } else {
        request_plain(tunnel, ip, target.port, request, read_cap, timeout)
    }
}

/// Decide what a response means, without touching the network.
///
/// The whole policy of a fetch lives here: which statuses continue, how far a
/// redirect chain may run, what a body may weigh once decoded.
fn interpret(
    response: crate::tunnel::http1::HttpResponse,
    current: &Url,
    redirects: u32,
    limits: &FetchLimits,
) -> Result<Step, ProxyError> {
    if let Some(location) = response.redirect_location() {
        if redirects >= limits.max_redirects {
            return Err(ProxyError::TooManyRedirects {
                count: redirects + 1,
                max_count: limits.max_redirects,
            });
        }
        let next = current.join(location).map_err(|e| ProxyError::InvalidUrl {
            url: location.to_string(),
            details: e.to_string(),
        })?;
        // Re-validated rather than trusted: a redirect is attacker-controlled,
        // and this is the same gate the original URL passed.
        return Ok(Step::Follow(parse_and_validate(next.as_str())?));
    }

    if !(200..300).contains(&response.status) {
        return Err(ProxyError::HttpError {
            status_code: response.status,
            details: format!("HTTP {}", response.status),
        });
    }

    let mime_type = response
        .header("content-type")
        .map(normalize_mime)
        .unwrap_or_else(|| "application/octet-stream".to_string());

    // Decode before measuring: `max_size` bounds what this client will hold, and
    // after a `Content-Encoding` the compressed length says nothing about that.
    // Both headers are read before the body moves, since reading either borrows
    // the response the body is taken out of.
    let coding = ContentCoding::parse(response.header("content-encoding").unwrap_or(""))?;
    let body = coding.decode(response.body, limits.max_size as usize)?;

    if body.len() as u64 > limits.max_size {
        return Err(ProxyError::ResponseTooLarge {
            size: body.len() as u64,
            max_size: limits.max_size,
        });
    }

    Ok(Step::Done(FetchOutcome {
        status: response.status,
        mime_type,
        body,
        final_url: current.to_string(),
    }))
}

/// Parse a URL and ensure it uses a supported scheme.
///
/// The single admission gate for a fetchable URL: the FFI entry point and every
/// redirect hop go through it, so "what counts as fetchable" is decided once.
pub fn parse_and_validate(url: &str) -> Result<Url, ProxyError> {
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
                status_code: NO_HTTP_RESPONSE,
                details: format!("Write failed: {e}"),
            })?;
        stream.flush().map_err(|e| ProxyError::HttpError {
            status_code: NO_HTTP_RESPONSE,
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
                        status_code: NO_HTTP_RESPONSE,
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

/// Whether a MIME type names content a renderer would *execute* rather than
/// display.
///
/// Email has no business running code, and the consensus across mail clients is
/// that it never gets to: scripting is off, so the renderer will not ask for a
/// script and would not run one if handed it. This is the second lock. The
/// WebView's request is untrusted in exactly one respect — the URL comes from
/// the message — and a server is free to answer any URL with anything, so
/// "nobody will ask for a script" is an assumption about the renderer rather
/// than a property of the response. Refusing the type makes it a property of the
/// response.
///
/// Deliberately a denylist over a small closed set rather than an allowlist of
/// renderable types: mail contains image, font and stylesheet types nobody
/// enumerated in advance, and refusing an unknown-but-inert type would break
/// display for no gain. Everything genuinely dangerous here is executable, and
/// executable types are the enumerable ones.
pub fn is_active_content(mime: &str) -> bool {
    const ACTIVE: &[&str] = &[
        "text/javascript",
        "application/javascript",
        "application/x-javascript",
        "application/ecmascript",
        "text/ecmascript",
        "application/wasm",
        "text/vbscript",
        "application/x-shockwave-flash",
    ];
    // Case-folded by comparison rather than by building a lowercased copy. The
    // callers pass a type `normalize_mime` has already lowercased, so the copy
    // would usually have been identical to its input — but this is a security
    // predicate and must not depend on its caller having normalised first.
    let mime = mime.trim();
    ACTIVE
        .iter()
        .any(|active| mime.eq_ignore_ascii_case(active))
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

    use crate::tunnel::http1::HttpResponse;

    fn response(status: u16, headers: &[(&str, &str)], body: &[u8]) -> HttpResponse {
        HttpResponse {
            status,
            headers: headers
                .iter()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect(),
            body: body.to_vec(),
        }
    }

    fn limits(max_redirects: u32) -> FetchLimits {
        FetchLimits {
            max_size: 1024,
            max_redirects,
            timeout_seconds: 5,
        }
    }

    /// A relative `Location` resolves against the URL that produced it.
    #[test]
    fn a_redirect_is_followed_relative_to_the_current_url() {
        let current = Url::parse("https://a.example/one/two").unwrap();
        let step = interpret(
            response(302, &[("location", "../three")], b""),
            &current,
            0,
            &limits(5),
        )
        .expect("redirect is followed");

        match step {
            Step::Follow(next) => assert_eq!(next.as_str(), "https://a.example/three"),
            Step::Done(_) => panic!("expected a redirect"),
        }
    }

    /// A redirect is attacker-controlled and faces the same scheme gate as the
    /// URL the caller supplied.
    #[test]
    fn a_redirect_to_an_unsupported_scheme_is_refused() {
        let current = Url::parse("https://a.example/").unwrap();
        for location in ["file:///etc/passwd", "javascript:alert(1)"] {
            assert!(matches!(
                interpret(
                    response(302, &[("location", location)], b""),
                    &current,
                    0,
                    &limits(5)
                ),
                Err(ProxyError::InvalidUrl { .. })
            ));
        }
    }

    #[test]
    fn a_redirect_chain_is_bounded() {
        let current = Url::parse("https://a.example/").unwrap();
        let hop = |redirects| {
            interpret(
                response(302, &[("location", "/next")], b""),
                &current,
                redirects,
                &limits(2),
            )
        };
        assert!(matches!(hop(0), Ok(Step::Follow(_))));
        assert!(matches!(hop(1), Ok(Step::Follow(_))));
        assert!(matches!(
            hop(2),
            Err(ProxyError::TooManyRedirects {
                count: 3,
                max_count: 2
            })
        ));
    }

    #[test]
    fn a_non_success_status_is_the_endpoint_answering() {
        let current = Url::parse("https://a.example/").unwrap();
        assert!(matches!(
            interpret(response(403, &[], b""), &current, 0, &limits(5)),
            Err(ProxyError::HttpError {
                status_code: 403,
                ..
            })
        ));
    }

    /// The ceiling applies to the decoded body, and a missing type is not
    /// guessed at.
    #[test]
    fn a_success_carries_its_decoded_body_and_normalised_type() {
        let current = Url::parse("https://a.example/x").unwrap();
        let step = interpret(
            response(
                200,
                &[("content-type", "IMAGE/PNG; charset=binary")],
                b"\x89PNG",
            ),
            &current,
            0,
            &limits(5),
        )
        .expect("success");

        match step {
            Step::Done(outcome) => {
                assert_eq!(outcome.mime_type, "image/png");
                assert_eq!(outcome.body, b"\x89PNG");
                assert_eq!(outcome.final_url, "https://a.example/x");
            }
            Step::Follow(_) => panic!("expected completion"),
        }
    }

    #[test]
    fn a_body_over_the_ceiling_is_refused() {
        let current = Url::parse("https://a.example/").unwrap();
        let big = vec![0u8; 2048];
        assert!(matches!(
            interpret(response(200, &[], &big), &current, 0, &limits(5)),
            Err(ProxyError::ResponseTooLarge { .. })
        ));
    }

    #[test]
    fn a_target_projects_scheme_port_and_path() {
        let t = Target::of(&Url::parse("https://a.example/p?q=1").unwrap()).unwrap();
        assert!(t.https && t.port == 443 && t.path == "/p?q=1" && t.host == "a.example");

        let t = Target::of(&Url::parse("http://b.example:8080/").unwrap()).unwrap();
        assert!(!t.https && t.port == 8080);
    }

    #[test]
    fn parse_and_validate_accepts_http_and_https() {
        assert!(parse_and_validate("http://example.com/x.png").is_ok());
        assert!(parse_and_validate("https://example.com/x.png").is_ok());
    }

    #[test]
    fn parse_and_validate_rejects_other_schemes() {
        for url in [
            "not-a-url",
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

    /// The types a renderer would execute, which a message never gets to send.
    #[test]
    fn scripts_and_wasm_are_active_content() {
        for mime in [
            "text/javascript",
            "application/javascript",
            "APPLICATION/JavaScript",
            "  application/wasm  ",
            "text/vbscript",
        ] {
            assert!(is_active_content(mime), "{mime} should be refused");
        }
    }

    /// Everything a page legitimately needs, including the stylesheet and font
    /// types whose refusal was the bug. The list is a denylist precisely so an
    /// unfamiliar-but-inert type renders rather than vanishing.
    #[test]
    fn everything_inert_is_served() {
        for mime in [
            "image/png",
            "image/svg+xml",
            "text/css",
            "font/woff2",
            "application/font-woff",
            "application/octet-stream",
            "text/html",
            "application/json",
        ] {
            assert!(!is_active_content(mime), "{mime} should be served");
        }
    }

    /// `text/javascript` is refused; `text/` in general is not. A prefix test
    /// would have taken the stylesheet with it.
    #[test]
    fn active_content_is_matched_whole_not_by_prefix() {
        assert!(!is_active_content("text/javascript-ish"));
        assert!(!is_active_content("text/"));
    }

    #[test]
    fn validate_svg_by_marker() {
        assert!(validate_image_data(
            b"<svg xmlns=...></svg>",
            "image/svg+xml"
        ));
    }
}
