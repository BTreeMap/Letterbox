//! Minimal, allocation-conscious HTTP/1.1 wire codec.
//!
//! This module is pure: it builds request bytes and parses response bytes with
//! no networking, so it is trivially unit-testable and shared by both the
//! subresource fetcher and the DNS-over-HTTPS resolver. Only the small subset of
//! HTTP/1.1 needed for `GET` requests with `Connection: close` is implemented.
//!
//! It also decides *who the request appears to be* — see [`ClientProfile`].
//! That is not decoration: a request whose headers do not look like a client
//! anybody has heard of is answered with 403 by every major bot-management
//! service, and no amount of working tunnel underneath makes up for it.

use crate::error::{ProxyError, NO_HTTP_RESPONSE};
use std::io::Read;

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
    ///
    /// Compares case-insensitively rather than lowercasing `name` first: the
    /// stored names are already lowercase, so building a lowercased needle
    /// allocated a string per lookup only to compare it against one that would
    /// have matched anyway.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(stored, _)| stored.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
    }

    /// Whether the status code denotes a redirect with a `Location` header.
    pub fn redirect_location(&self) -> Option<&str> {
        matches!(self.status, 301 | 302 | 303 | 307 | 308)
            .then(|| self.header("location"))
            .flatten()
    }
}

/// Headers a [`ClientProfile`] owns outright.
///
/// Two different reasons to reserve a name, and both end the same way — a
/// caller-supplied value is dropped.
///
/// The first four *frame the message*, so a duplicate is not merely redundant:
/// a second `Content-Length` or `Transfer-Encoding` is how a request is smuggled
/// past a parser, and a second `Host` picks a different origin than the one the
/// connection was opened to.
///
/// The rest *describe the client*, and a header set is only credible as a whole.
/// Letting a caller replace one of them piecemeal is how a request ends up
/// claiming to be Chrome in `sec-ch-ua` while its `User-Agent` names a bespoke
/// tool — an inconsistency that stands out more sharply than either header would
/// alone. Which of them are sent, and what they say, is decided once, by the
/// profile.
const RESERVED_HEADERS: &[&str] = &[
    // Framing.
    "host",
    "connection",
    "content-length",
    "transfer-encoding",
    // The persona.
    "accept",
    "accept-encoding",
    "accept-language",
    "user-agent",
    "sec-ch-ua",
    "sec-ch-ua-mobile",
    "sec-ch-ua-platform",
    "sec-fetch-site",
    "sec-fetch-mode",
    "sec-fetch-dest",
];

/// The `User-Agent` the [`ClientProfile::Browser`] persona presents.
///
/// Sending none is not the private option, it is the conspicuous one. Almost no
/// real client omits this header, so its absence both singles the request out
/// and gets it refused: Cloudflare and Akamai score a missing `User-Agent` as a
/// bot and answer 403. That is why remote images failed while the tunnel looked
/// healthy — `/cdn-cgi/trace`, which the in-app check fetches, is exempt from
/// bot management and answers anyone.
///
/// Privacy here comes from every install sending the *same* string, not from
/// sending nothing — a shared value is an anonymity set, a unique or absent one
/// is a fingerprint. This is Chrome's reduced Android user agent, in which the
/// OS version is frozen at `10` and the device model at `K` for every device on
/// every Android release, which makes it the largest such set available.
pub const USER_AGENT: &str = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 \
                              (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

/// Client hints matching [`USER_AGENT`]. Chrome sends these on every request and
/// their brand list has to agree with the version in the user agent.
const SEC_CH_UA: &str =
    "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"";

/// Advertised content codings.
///
/// `identity` was the loudest bot signal this client emitted: a real browser
/// always offers compression, so asking for none marks the request as
/// programmatic before any other header is read. Only codings
/// [`ContentCoding`] can actually undo are offered — advertising `br` or
/// `zstd` without a decoder for them would turn a 403 into a body of noise.
const ACCEPT_ENCODING: &str = "gzip, deflate";

/// Advertised natural language.
///
/// Constant, like the user agent, and for the same reason: the device's real
/// locale would narrow the anonymity set to the users who share it.
const ACCEPT_LANGUAGE: &str = "en-US,en;q=0.9";

/// How a request presents itself to the far end.
///
/// The two variants are exhaustive and mutually exclusive, which is the point:
/// every request either wants to look like the browser it is rendering for, or
/// wants to name itself to an API that asked it to. There is no third state in
/// which a request carries half of each, because that is precisely the shape
/// that reads as automation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ClientProfile {
    /// Chrome on Android: the full header set a real Chrome sends, in Chrome's
    /// order. Used for everything an email renderer asks for.
    Browser {
        /// What the renderer said it would accept. Also fixes `Sec-Fetch-Dest`,
        /// which a bot-management service checks against it.
        accept: String,
    },
    /// A named tool talking to an endpoint that wants to identify the caller —
    /// the GitHub release check, which is refused outright without a
    /// `User-Agent` naming a real client, and DNS-over-HTTPS, which is a
    /// protocol call rather than a page load.
    Api {
        /// Value for `User-Agent`.
        ///
        /// `&'static str` rather than `String`, so that the invariant
        /// [`USER_AGENT`] argues for is enforced by the type rather than by
        /// everyone remembering it: a user agent must be a compile-time
        /// constant of the build. A `String` here would admit one derived at
        /// runtime — from the device, the account, the install — and a
        /// per-install user agent is a fingerprint that identifies this user
        /// across every server they contact. That is the exact harm the tunnel
        /// exists to prevent, so it is made unrepresentable.
        user_agent: &'static str,
        /// Value for `Accept`. Ordinary data — an `Accept` computed at runtime
        /// describes the request, not the caller, and identifies nobody.
        accept: String,
    },
}

impl ClientProfile {
    /// Present as the browser, accepting `accept`.
    pub fn browser(accept: impl Into<String>) -> Self {
        Self::Browser {
            accept: accept.into(),
        }
    }

    /// Present as the named tool `user_agent`, accepting `accept`.
    pub fn api(user_agent: &'static str, accept: impl Into<String>) -> Self {
        Self::Api {
            user_agent,
            accept: accept.into(),
        }
    }
}

/// What the renderer intends to do with the bytes, for `Sec-Fetch-Dest`.
///
/// Derived from `Accept` rather than asked for separately, so the two cannot
/// contradict each other — a mismatch between them is one of the cheapest
/// automation tells there is.
///
/// The derivation is deliberately partial in one direction: Chrome requests
/// fonts and `fetch()` bodies alike with a wildcard `Accept`, so both land on
/// `empty`. That is a truthful description of what this client knows, and
/// `empty` beside a wildcard is exactly what a real `fetch()` looks like.
fn sec_fetch_dest(accept: &str) -> &'static str {
    let accept = accept.trim_start().as_bytes();
    // Byte-wise so that folding case costs nothing: the alternative allocates a
    // lowercased copy of every `Accept` this client ever sends, to answer a
    // question about its first few characters. Slicing bytes rather than `str`
    // is also what makes the prefix length safe to use directly — a `str` index
    // must land on a character boundary, which a caller-supplied header need not
    // respect.
    let starts_with = |prefix: &str| {
        accept
            .get(..prefix.len())
            .is_some_and(|head| head.eq_ignore_ascii_case(prefix.as_bytes()))
    };

    if starts_with("image/") {
        "image"
    } else if starts_with("text/css") {
        "style"
    } else if starts_with("font/") {
        "font"
    } else if starts_with("text/html") {
        "document"
    } else {
        "empty"
    }
}

/// Build a serialised HTTP/1.1 `GET` request with `Connection: close`.
///
/// `profile` decides every header that describes the client; `extra_headers`
/// may add anything else but cannot touch a [`RESERVED_HEADERS`] name. Header
/// order is Chrome's, because order is itself fingerprinted.
///
/// `Connection: close` is the one place this request knowingly differs from
/// Chrome, which keeps connections alive. It is load-bearing: the response
/// readers below take end-of-stream as end-of-message, so asking to keep the
/// connection open would block every fetch until its timeout. Correctness first.
pub fn build_get_request(
    host: &str,
    path: &str,
    profile: &ClientProfile,
    extra_headers: &[(String, String)],
) -> Vec<u8> {
    let mut request = String::with_capacity(768);
    request.push_str("GET ");
    request.push_str(if path.is_empty() { "/" } else { path });
    request.push_str(" HTTP/1.1\r\nHost: ");
    request.push_str(host);
    request.push_str("\r\nConnection: close\r\n");

    match profile {
        ClientProfile::Browser { accept } => {
            request.push_str("sec-ch-ua: ");
            request.push_str(SEC_CH_UA);
            request.push_str("\r\nsec-ch-ua-mobile: ?1\r\nsec-ch-ua-platform: \"Android\"\r\n");
            request.push_str("User-Agent: ");
            request.push_str(USER_AGENT);
            request.push_str("\r\nAccept: ");
            request.push_str(accept);
            // Cross-site and no-cors: every one of these is a subresource for a
            // document that has no origin of its own.
            request.push_str("\r\nSec-Fetch-Site: cross-site\r\nSec-Fetch-Mode: no-cors\r\n");
            request.push_str("Sec-Fetch-Dest: ");
            request.push_str(sec_fetch_dest(accept));
            request.push_str("\r\n");
        }
        ClientProfile::Api { user_agent, accept } => {
            request.push_str("User-Agent: ");
            request.push_str(user_agent);
            request.push_str("\r\nAccept: ");
            request.push_str(accept);
            request.push_str("\r\n");
        }
    }

    request.push_str("Accept-Encoding: ");
    request.push_str(ACCEPT_ENCODING);
    request.push_str("\r\nAccept-Language: ");
    request.push_str(ACCEPT_LANGUAGE);
    request.push_str("\r\n");

    for (name, value) in extra_headers {
        let reserved = RESERVED_HEADERS
            .iter()
            .any(|reserved| name.eq_ignore_ascii_case(reserved));
        if reserved {
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
        status_code: NO_HTTP_RESPONSE,
        details: "Malformed response: no header terminator".to_string(),
    })?;
    let (head, body_start) = raw.split_at(split);
    let body_bytes = &body_start[4..]; // skip the CRLFCRLF

    let head_str = std::str::from_utf8(head).map_err(|_| ProxyError::HttpError {
        status_code: NO_HTTP_RESPONSE,
        details: "Response headers are not valid UTF-8".to_string(),
    })?;

    let mut lines = head_str.split("\r\n");
    let status_line = lines.next().ok_or_else(|| ProxyError::HttpError {
        status_code: NO_HTTP_RESPONSE,
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
            status_code: NO_HTTP_RESPONSE,
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
                    status_code: NO_HTTP_RESPONSE,
                    details: "Truncated chunk header".to_string(),
                })?;
        let size_str = std::str::from_utf8(&body[..line_end]).unwrap_or("");
        let size_hex = size_str.split(';').next().unwrap_or("").trim();
        let size = usize::from_str_radix(size_hex, 16).map_err(|_| ProxyError::HttpError {
            status_code: NO_HTTP_RESPONSE,
            details: format!("Invalid chunk size: {size_hex}"),
        })?;
        body = &body[line_end + 2..];
        if size == 0 {
            break;
        }
        if body.len() < size {
            return Err(ProxyError::HttpError {
                status_code: NO_HTTP_RESPONSE,
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

/// A content coding, parsed from a `Content-Encoding` header.
///
/// The header is turned into this closed set once, at the boundary, and
/// [`ContentCoding::decode`] then eliminates it exhaustively — rather than each
/// use re-examining a string and having to remember the unsupported case.
///
/// Parsing separately from decoding also settles an ownership question. The
/// header is borrowed from the response the body is being taken out of, so a
/// combined `decode(header, response.body)` cannot borrow and move at once; the
/// borrow ends when the coding becomes a value, and the body then moves freely.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ContentCoding {
    /// No coding, or an explicit `identity`.
    Identity,
    /// RFC 1952 gzip.
    Gzip,
    /// `deflate`, in either of the two framings servers send.
    Deflate,
}

impl ContentCoding {
    /// Read a `Content-Encoding` value, rejecting anything this client cannot
    /// undo.
    ///
    /// An unrecognised coding is an error rather than a pass-through: handing
    /// the renderer bytes in an encoding neither end understands would show up
    /// as a corrupt image, which is a far worse diagnostic than saying so.
    ///
    /// A comma-separated chain (`gzip, br`) falls through to that error, which
    /// is correct rather than merely conservative: only single codings are
    /// advertised, so a chain is a server disregarding [`ACCEPT_ENCODING`].
    pub fn parse(header: &str) -> Result<Self, ProxyError> {
        let header = header.trim();
        let is = |name: &str| header.eq_ignore_ascii_case(name);

        if header.is_empty() || is("identity") {
            Ok(Self::Identity)
        } else if is("gzip") || is("x-gzip") {
            Ok(Self::Gzip)
        } else if is("deflate") {
            Ok(Self::Deflate)
        } else {
            Err(ProxyError::HttpError {
                status_code: NO_HTTP_RESPONSE,
                details: format!("Unsupported Content-Encoding: {header}"),
            })
        }
    }

    /// Name for diagnostics.
    fn name(self) -> &'static str {
        match self {
            Self::Identity => "identity",
            Self::Gzip => "gzip",
            Self::Deflate => "deflate",
        }
    }

    /// Undo this coding, refusing to produce more than `max_size` bytes.
    ///
    /// Bounded where [`decode_body`] is not, and the asymmetry is the point:
    /// that one undoes *transfer* framing, a property of the connection that
    /// cannot produce more bytes than arrived, whereas this one expands what
    /// arrived. A response is otherwise free to declare a few kilobytes on the
    /// wire and decompress to whatever memory the device has — a compression
    /// bomb — so the ceiling is applied to the output, where the cost lands,
    /// rather than to the compressed input.
    pub fn decode(self, body: Vec<u8>, max_size: usize) -> Result<Vec<u8>, ProxyError> {
        match self {
            // The body is *returned*, not copied. Taking `body` by value rather
            // than by slice is what makes that possible, and it matters: this
            // is the arm almost every response takes, and a `to_vec()` here
            // charged one full extra copy of every image the app displays.
            Self::Identity => Ok(body),
            Self::Gzip => self.inflate(flate2::read::GzDecoder::new(&body[..]), &body, max_size),
            // Two incompatible readings of `deflate` are in the wild: RFC 1950
            // zlib, which the specification requires, and RFC 1951 raw, which
            // several servers send anyway. Try the correct one, then the common
            // one.
            //
            // Only a *parse* failure earns the retry. Exceeding the ceiling is a
            // verdict on the content, and rereading the same bytes under a
            // different framing cannot make them smaller — falling back on that
            // would double the work a compression bomb costs before refusal.
            Self::Deflate => {
                match self.inflate(flate2::read::ZlibDecoder::new(&body[..]), &body, max_size) {
                    Err(ProxyError::HttpError { .. }) => self.inflate(
                        flate2::read::DeflateDecoder::new(&body[..]),
                        &body,
                        max_size,
                    ),
                    outcome => outcome,
                }
            }
        }
    }

    /// Read `decoder` to end of stream, refusing to exceed `max_size` bytes.
    ///
    /// Reads one byte past the ceiling rather than checking afterwards: the
    /// point is never to *hold* more than the limit allows, which a check on the
    /// finished buffer would already have failed to do. The `size` a refusal
    /// reports is therefore a lower bound — the true expanded length is exactly
    /// what this declined to compute.
    ///
    /// `compressed` only sizes the output buffer. Growing from empty costs a
    /// reallocation and a copy per doubling, which for a megabyte image is a
    /// dozen copies of an ever larger buffer; a typical ratio is the starting
    /// guess, clamped so a hostile ratio cannot turn the hint itself into the
    /// allocation it exists to avoid.
    fn inflate<R: Read>(
        self,
        decoder: R,
        compressed: &[u8],
        max_size: usize,
    ) -> Result<Vec<u8>, ProxyError> {
        let mut out = Vec::with_capacity(compressed.len().saturating_mul(4).min(max_size) + 1);
        decoder
            .take(max_size as u64 + 1)
            .read_to_end(&mut out)
            .map_err(|e| ProxyError::HttpError {
                status_code: NO_HTTP_RESPONSE,
                details: format!("Malformed {} body: {e}", self.name()),
            })?;
        if out.len() > max_size {
            return Err(ProxyError::ResponseTooLarge {
                size: out.len() as u64,
                max_size: max_size as u64,
            });
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The browser persona, as every subresource fetch uses it.
    fn browser(accept: &str) -> ClientProfile {
        ClientProfile::browser(accept)
    }

    /// Parse a `Content-Encoding` and apply it, which production does in two
    /// steps only because the borrow checker requires the header to be read
    /// before the body moves.
    fn decode(header: &str, body: Vec<u8>, max_size: usize) -> Result<Vec<u8>, ProxyError> {
        ContentCoding::parse(header)?.decode(body, max_size)
    }

    #[test]
    fn builds_minimal_get_request() {
        let req = build_get_request("example.com", "/img.png", &browser("image/*"), &[]);
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
    fn the_browser_persona_sends_chromes_user_agent() {
        let text = String::from_utf8(build_get_request(
            "example.com",
            "/i.png",
            &browser("image/*"),
            &[],
        ))
        .expect("request is ASCII");
        assert!(
            text.contains(&format!("User-Agent: {USER_AGENT}\r\n")),
            "no User-Agent in:\n{text}"
        );
    }

    /// `identity` was the second-loudest tell after a missing user agent: no
    /// browser refuses compression. Whatever is advertised must be something
    /// [`ContentCoding`] can undo, or a 403 becomes a body of noise.
    #[test]
    fn every_advertised_coding_can_be_decoded() {
        let text = String::from_utf8(build_get_request("e.com", "/", &browser("*/*"), &[]))
            .expect("request is ASCII");
        assert!(text.contains(&format!("Accept-Encoding: {ACCEPT_ENCODING}\r\n")));
        assert!(!text.contains("identity"));

        // Round-trip every advertised coding through a real encoder. Asserting
        // that decoding merely *errors differently* would not do: an empty body
        // decodes to an empty body under any of them, so a coding with no
        // decoder at all would pass that weaker check.
        let plain = b"\x89PNG the quick brown fox jumps over the lazy dog";
        for coding in ACCEPT_ENCODING.split(',').map(str::trim) {
            let encoded = match coding {
                "gzip" => gzip(plain),
                "deflate" => zlib(plain),
                other => panic!("advertised {other:?} but this test cannot produce it"),
            };
            assert_eq!(
                decode(coding, encoded, 1024).expect("advertised coding decodes"),
                plain,
                "round-trip failed for {coding:?}"
            );
        }
    }

    fn gzip(plain: &[u8]) -> Vec<u8> {
        use std::io::Write;
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        encoder.write_all(plain).expect("in-memory write");
        encoder.finish().expect("in-memory finish")
    }

    fn zlib(plain: &[u8]) -> Vec<u8> {
        use std::io::Write;
        let mut encoder =
            flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
        encoder.write_all(plain).expect("in-memory write");
        encoder.finish().expect("in-memory finish")
    }

    /// The two personas are alternatives, not a base plus overrides. An API
    /// request must not carry Chrome's client hints beside a tool's user agent:
    /// the contradiction is more conspicuous than either header alone.
    #[test]
    fn the_api_persona_carries_no_browser_hints() {
        let profile = ClientProfile::api("Letterbox-UpdateChecker", "application/vnd.github+json");
        let text = String::from_utf8(build_get_request("api.github.com", "/", &profile, &[]))
            .expect("request is ASCII");
        assert_eq!(text.matches("User-Agent:").count(), 1);
        assert!(text.contains("User-Agent: Letterbox-UpdateChecker\r\n"));
        assert!(!text.contains("Mozilla/5.0"));
        assert!(!text.to_ascii_lowercase().contains("sec-ch-ua"));
        assert!(!text.to_ascii_lowercase().contains("sec-fetch"));
    }

    /// `Sec-Fetch-Dest` is checked against `Accept`, so it is derived from it
    /// rather than supplied beside it and cannot disagree.
    #[test]
    fn sec_fetch_dest_follows_accept() {
        assert_eq!(sec_fetch_dest("image/avif,image/webp,*/*;q=0.8"), "image");
        assert_eq!(sec_fetch_dest("text/css,*/*;q=0.1"), "style");
        assert_eq!(sec_fetch_dest("font/woff2"), "font");
        assert_eq!(sec_fetch_dest("text/html"), "document");
        // Chrome asks for fonts and `fetch()` bodies alike with `*/*`.
        assert_eq!(sec_fetch_dest("*/*"), "empty");
    }

    /// Every header that describes the client belongs to the profile, and every
    /// header that frames the message belongs to the codec. A caller can add to
    /// neither set — a second `Content-Length` is how a request is smuggled, and
    /// a second `User-Agent` is malformed outright.
    #[test]
    fn reserved_headers_cannot_be_overridden() {
        let extra = vec![
            ("Content-Length".to_string(), "0".to_string()),
            ("Transfer-Encoding".to_string(), "chunked".to_string()),
            ("Accept-Encoding".to_string(), "br".to_string()),
            // Lowercase on purpose: header names are case-insensitive on the
            // wire, so the rule must match that way and not by spelling.
            ("accept".to_string(), "text/plain".to_string()),
            ("User-Agent".to_string(), "curl/8".to_string()),
            ("Host".to_string(), "evil.com".to_string()),
        ];
        let text = String::from_utf8(build_get_request(
            "example.com",
            "/",
            &browser("image/*"),
            &extra,
        ))
        .expect("request is ASCII");

        assert!(!text.contains("Content-Length"));
        assert!(!text.contains("chunked"));
        assert!(!text.contains("br"));
        assert!(!text.contains("text/plain"));
        assert!(!text.contains("curl/8"));
        assert!(!text.contains("evil.com"));
        for header in ["Host:", "Accept:", "Accept-Encoding:", "User-Agent:"] {
            assert_eq!(
                text.to_ascii_lowercase()
                    .matches(&header.to_ascii_lowercase())
                    .count(),
                1,
                "expected exactly one {header} in:\n{text}"
            );
        }
    }

    #[test]
    fn unreserved_headers_pass_through() {
        let extra = vec![("X-GitHub-Api-Version".to_string(), "2022-11-28".to_string())];
        let profile = ClientProfile::api("Letterbox-UpdateChecker", "*/*");
        let req =
            String::from_utf8(build_get_request("api.github.com", "/", &profile, &extra)).unwrap();
        assert!(req.contains("X-GitHub-Api-Version: 2022-11-28"));
    }

    #[test]
    fn decodes_a_gzip_body() {
        assert_eq!(
            decode("gzip", gzip(b"\x89PNG hello"), 1024).unwrap(),
            b"\x89PNG hello"
        );
    }

    /// The uncompressed body is handed back, not copied.
    ///
    /// Almost every response takes this path, and the body is an entire image,
    /// so the difference between moving and copying it is megabytes per message.
    /// Comparing the allocation is the only way to state that as a test: a
    /// `assert_eq!` on the bytes passes just as happily against a copy.
    #[test]
    fn an_uncompressed_body_is_moved_not_copied() {
        let body = vec![0xAB; 64 * 1024];
        let allocation = body.as_ptr();

        let out = decode("", body, 1024 * 1024).expect("identity always decodes");

        assert_eq!(
            out.as_ptr(),
            allocation,
            "identity reallocated the body instead of returning it"
        );
    }

    #[test]
    fn identity_and_absent_codings_pass_the_body_through() {
        assert_eq!(decode("", b"raw".to_vec(), 16).unwrap(), b"raw");
        assert_eq!(decode("identity", b"raw".to_vec(), 16).unwrap(), b"raw");
    }

    /// A few compressed kilobytes may expand to more memory than the device has.
    /// The ceiling belongs on the output, where the cost lands.
    #[test]
    fn a_compression_bomb_is_refused_at_the_limit() {
        let compressed = gzip(&vec![0u8; 1024 * 1024]);
        assert!(compressed.len() < 4096, "test needs a high ratio");

        assert!(matches!(
            decode("gzip", compressed, 4096),
            Err(ProxyError::ResponseTooLarge { .. })
        ));
    }

    /// Raw RFC 1951 `deflate` is not what the specification says `deflate`
    /// means, and is what a number of servers send regardless.
    #[test]
    fn deflate_accepts_both_readings_in_the_wild() {
        use std::io::Write;
        let mut raw =
            flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::default());
        raw.write_all(b"body").expect("in-memory write");
        let raw = raw.finish().expect("in-memory finish");

        assert_eq!(decode("deflate", zlib(b"body"), 64).unwrap(), b"body");
        assert_eq!(decode("deflate", raw, 64).unwrap(), b"body");
    }

    /// Handing the renderer bytes in an encoding nobody undid would surface as a
    /// corrupt image; saying so surfaces as a reason.
    #[test]
    fn an_unknown_coding_is_an_error_not_a_pass_through() {
        assert!(matches!(
            decode("br", b"whatever".to_vec(), 1024),
            Err(ProxyError::HttpError { .. })
        ));
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
