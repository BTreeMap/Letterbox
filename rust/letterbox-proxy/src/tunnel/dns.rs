//! DNS resolution over the tunnel via DNS-over-HTTPS (DoH).
//!
//! Resolving names through Cloudflare's `1.1.1.1` DoH endpoint keeps name
//! resolution *inside* the encrypted tunnel: the host's real resolver — and
//! therefore the user's ISP — never sees which image servers are queried. The
//! resolver IP (`1.1.1.1`) is a literal, so DoH itself needs no bootstrap DNS.
//!
//! # Why every [`ProxyError::DnsError`] is terminal
//!
//! A DoH query that never completes fails as [`ProxyError::TlsError`],
//! [`ProxyError::Timeout`] or [`ProxyError::TunnelError`] — `query` propagates
//! those untouched. `DnsError` is constructed only by [`interpret`], which is
//! pure. So a `DnsError` always means the resolver spoke, and retrying it
//! against a fresh tunnel would only hear the same thing.

use crate::error::ProxyError;
use crate::tunnel::http1::{build_get_request, parse_response, ClientProfile};
use crate::tunnel::stack::Tunnel;
use crate::tunnel::tls::request_https;
use lru::LruCache;
use serde::Deserialize;
use smoltcp::wire::IpAddress;
use std::cell::RefCell;
use std::net::Ipv4Addr;
use std::num::NonZeroUsize;
use std::time::{Duration, Instant};

/// Cloudflare's DoH resolver address (a literal, needs no resolution itself).
const DOH_RESOLVER: IpAddress = IpAddress::v4(1, 1, 1, 1);

/// SNI / `Host` used for the DoH resolver (valid on Cloudflare's certificate).
const DOH_HOST: &str = "one.one.one.one";

/// DNS `A` record type code in the DoH JSON API.
const DNS_TYPE_A: u16 = 1;

/// Maximum DoH response size (answers are tiny).
const MAX_DOH_RESPONSE: usize = 64 * 1024;

/// How many hostnames the cache remembers.
///
/// A mail message draws on a handful of hosts; this is sized so a hostile one
/// cannot grow the map without bound, not so that it never evicts.
const CACHE_ENTRIES: usize = 256;

/// Floor and ceiling applied to a resolver's TTL.
///
/// A zero TTL would make the cache pointless and a very long one would outlive
/// a legitimate DNS change, so the resolver's number is respected only within
/// bounds we are willing to be wrong for.
const MIN_TTL: Duration = Duration::from_secs(30);
const MAX_TTL: Duration = Duration::from_secs(3600);

/// How long "this name has no address" is remembered.
///
/// Short, because a name that does not resolve today may tomorrow. Long enough
/// to cover rendering one message, which is what stops five tracking pixels on
/// a dead host from costing five round trips.
const NEGATIVE_TTL: Duration = Duration::from_secs(60);

/// What the resolver said about a name.
///
/// Only *answers* are representable. A query that never completed is a
/// transport failure, and there is deliberately no variant for it: a failure to
/// reach the resolver must never be cached as though the resolver had spoken.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Answer {
    /// The name resolves here.
    Address(IpAddress),
    /// The resolver replied, and the reply carried no usable `A` record.
    NoAddress,
}

/// A resolver answer paired with how long it may be reused.
#[derive(Debug, Clone, Copy)]
struct Resolved {
    answer: Answer,
    ttl: Duration,
}

/// One cached answer and the instant it stops being usable.
#[derive(Debug, Clone, Copy)]
struct Entry {
    answer: Answer,
    expires: Instant,
}

/// A bounded, TTL-respecting cache of resolver answers.
///
/// Single-threaded by construction — one worker owns the tunnel — so the cell
/// is a [`RefCell`], not a lock. It lives *inside* the cache rather than around
/// it so no borrow guard can escape to a caller: every method takes `&self`,
/// borrows, and drops before returning. That matters because callers are async,
/// and a guard alive across `.await` is a panic waiting for the right schedule.
pub struct DnsCache {
    entries: RefCell<LruCache<String, Entry>>,
}

impl DnsCache {
    /// An empty cache.
    pub fn new() -> Self {
        Self {
            entries: RefCell::new(LruCache::new(
                NonZeroUsize::new(CACHE_ENTRIES).expect("CACHE_ENTRIES is a non-zero literal"),
            )),
        }
    }

    /// The unexpired answer for `host`, if one is held.
    fn get(&self, host: &str, now: Instant) -> Option<Answer> {
        let mut entries = self.entries.borrow_mut();
        match entries.get(host) {
            Some(entry) if entry.expires > now => Some(entry.answer),
            // Expired entries are dropped on sight rather than left to age out
            // through eviction, so a stale answer cannot be served after a
            // later lookup refreshes its recency.
            Some(_) => {
                entries.pop(host);
                None
            }
            None => None,
        }
    }

    /// Remember `resolved` for `host`.
    fn put(&self, host: &str, resolved: Resolved, now: Instant) {
        self.entries.borrow_mut().put(
            host.to_string(),
            Entry {
                answer: resolved.answer,
                expires: now + resolved.ttl,
            },
        );
    }
}

impl Default for DnsCache {
    fn default() -> Self {
        Self::new()
    }
}

/// A DoH JSON response (subset of fields we care about).
#[derive(Debug, Deserialize)]
struct DohResponse {
    #[serde(rename = "Answer", default)]
    answer: Vec<DohAnswer>,
}

/// A single DoH answer record.
#[derive(Debug, Deserialize)]
struct DohAnswer {
    #[serde(rename = "type")]
    record_type: u16,
    #[serde(rename = "TTL", default)]
    ttl: u32,
    data: String,
}

/// Resolve `host` to an IPv4 [`IpAddress`] through the tunnel.
///
/// Literal IPv4 addresses are returned directly. Hostnames are answered from
/// the cache when possible and via DoH otherwise; the first `A` record wins.
///
/// # Errors
///
/// [`ProxyError::DnsError`] when the resolver answered and the answer is
/// unusable, or the query's own failure when it never completed.
pub async fn resolve(
    tunnel: &Tunnel,
    host: &str,
    timeout: Duration,
) -> Result<IpAddress, ProxyError> {
    if let Ok(addr) = host.parse::<Ipv4Addr>() {
        let o = addr.octets();
        return Ok(IpAddress::v4(o[0], o[1], o[2], o[3]));
    }

    if !is_valid_hostname(host) {
        return Err(ProxyError::DnsError {
            host: host.to_string(),
            details: "Hostname contains invalid characters".to_string(),
        });
    }

    let now = Instant::now();
    let answer = match tunnel.names().get(host, now) {
        Some(cached) => cached,
        None => {
            let resolved = query(tunnel, host, timeout).await?;
            tunnel.names().put(host, resolved, now);
            resolved.answer
        }
    };

    match answer {
        Answer::Address(ip) => Ok(ip),
        Answer::NoAddress => Err(ProxyError::DnsError {
            host: host.to_string(),
            details: "No A record in DoH response".to_string(),
        }),
    }
}

/// Ask the resolver about `host`. The only effect in this module.
///
/// Failures of the query itself propagate unchanged, which is what keeps them
/// distinguishable from — and retryable unlike — an answer.
async fn query(tunnel: &Tunnel, host: &str, timeout: Duration) -> Result<Resolved, ProxyError> {
    let path = format!("/dns-query?name={host}&type=A");
    // A protocol call, not a page load: presenting Chrome's client hints to a
    // resolver would claim a browser is asking, which is both false and, for
    // a JSON DNS endpoint, no help at all.
    let profile = ClientProfile::api("Letterbox-DoH", "application/dns-json");
    let request = build_get_request(DOH_HOST, &path, &profile, &[]);

    let raw = request_https(
        tunnel,
        DOH_RESOLVER,
        443,
        DOH_HOST,
        &request,
        MAX_DOH_RESPONSE,
        timeout,
    )
    .await?;

    interpret(host, &raw)
}

/// Read a resolver reply. Pure, and the only source of [`ProxyError::DnsError`].
fn interpret(host: &str, raw: &[u8]) -> Result<Resolved, ProxyError> {
    let fail = |details: String| ProxyError::DnsError {
        host: host.to_string(),
        details,
    };

    let response = parse_response(raw)?;
    if response.status != 200 {
        return Err(fail(format!(
            "DoH resolver returned status {}",
            response.status
        )));
    }

    let parsed: DohResponse = serde_json::from_slice(&response.body)
        .map_err(|e| fail(format!("Failed to parse DoH response: {e}")))?;

    Ok(parsed
        .answer
        .iter()
        .filter(|a| a.record_type == DNS_TYPE_A)
        .find_map(|a| a.data.parse::<Ipv4Addr>().ok().map(|ip| (ip, a.ttl)))
        .map_or(
            Resolved {
                answer: Answer::NoAddress,
                ttl: NEGATIVE_TTL,
            },
            |(addr, ttl)| {
                let o = addr.octets();
                Resolved {
                    answer: Answer::Address(IpAddress::v4(o[0], o[1], o[2], o[3])),
                    ttl: Duration::from_secs(u64::from(ttl)).clamp(MIN_TTL, MAX_TTL),
                }
            },
        ))
}

/// Validate a hostname so it cannot smuggle characters into the DoH URL.
fn is_valid_hostname(host: &str) -> bool {
    !host.is_empty()
        && host.len() <= 253
        && host
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-' || b == b'_')
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Wrap a DoH JSON body in the minimal HTTP response `interpret` parses.
    fn doh_reply(status: u16, body: &str) -> Vec<u8> {
        format!(
            "HTTP/1.1 {status} OK\r\nContent-Type: application/dns-json\r\nContent-Length: {}\r\n\r\n{body}",
            body.len()
        )
        .into_bytes()
    }

    fn ipv4(a: u8, b: u8, c: u8, d: u8) -> IpAddress {
        IpAddress::v4(a, b, c, d)
    }

    #[test]
    fn accepts_valid_hostnames() {
        assert!(is_valid_hostname("example.com"));
        assert!(is_valid_hostname("sub-domain.example.co.uk"));
        assert!(is_valid_hostname("a_b.example.com"));
    }

    #[test]
    fn rejects_invalid_hostnames() {
        assert!(!is_valid_hostname(""));
        assert!(!is_valid_hostname("example.com/evil"));
        assert!(!is_valid_hostname("example.com?x=1"));
        assert!(!is_valid_hostname("exa mple.com"));
        assert!(!is_valid_hostname(&"a".repeat(254)));
    }

    #[test]
    fn takes_the_first_a_record_and_its_ttl() {
        let reply = doh_reply(
            200,
            r#"{"Status":0,"Answer":[{"name":"x","type":5,"TTL":300,"data":"cname.example."},{"name":"x","type":1,"TTL":300,"data":"93.184.216.34"}]}"#,
        );
        let resolved = interpret("example.com", &reply).expect("must interpret");
        assert_eq!(resolved.answer, Answer::Address(ipv4(93, 184, 216, 34)));
        assert_eq!(resolved.ttl, Duration::from_secs(300));
    }

    /// The screenshot's failure: a name the resolver answers about, with
    /// nothing usable in the answer. It is an answer, so it is cacheable.
    #[test]
    fn an_empty_answer_is_a_negative_answer_not_an_error() {
        for body in [
            r#"{"Status":3}"#,
            r#"{"Status":0,"Answer":[]}"#,
            r#"{"Status":0,"Answer":[{"name":"x","type":5,"TTL":60,"data":"cname.example."}]}"#,
        ] {
            let resolved = interpret("pixel.example.net", &doh_reply(200, body))
                .unwrap_or_else(|e| panic!("{body} must interpret, got {e}"));
            assert_eq!(resolved.answer, Answer::NoAddress);
            assert_eq!(resolved.ttl, NEGATIVE_TTL);
        }
    }

    /// A resolver TTL is respected only within bounds we accept being wrong for.
    #[test]
    fn resolver_ttl_is_clamped() {
        let short = interpret(
            "x.example",
            &doh_reply(200, r#"{"Answer":[{"type":1,"TTL":1,"data":"1.2.3.4"}]}"#),
        )
        .expect("interpret");
        assert_eq!(short.ttl, MIN_TTL);

        let long = interpret(
            "x.example",
            &doh_reply(
                200,
                r#"{"Answer":[{"type":1,"TTL":999999,"data":"1.2.3.4"}]}"#,
            ),
        )
        .expect("interpret");
        assert_eq!(long.ttl, MAX_TTL);
    }

    #[test]
    fn a_resolver_that_refuses_or_babbles_is_a_dns_error() {
        assert!(matches!(
            interpret("x.example", &doh_reply(502, "upstream down")),
            Err(ProxyError::DnsError { .. })
        ));
        assert!(matches!(
            interpret("x.example", &doh_reply(200, "not json at all")),
            Err(ProxyError::DnsError { .. })
        ));
    }

    #[test]
    fn cache_serves_within_ttl_and_forgets_after() {
        let cache = DnsCache::new();
        let now = Instant::now();
        let resolved = Resolved {
            answer: Answer::Address(ipv4(1, 2, 3, 4)),
            ttl: Duration::from_secs(60),
        };
        cache.put("example.com", resolved, now);

        assert_eq!(
            cache.get("example.com", now + Duration::from_secs(59)),
            Some(Answer::Address(ipv4(1, 2, 3, 4)))
        );
        assert_eq!(
            cache.get("example.com", now + Duration::from_secs(61)),
            None
        );
    }

    /// Negative answers cache too — that is what stops a message full of
    /// pixels on one dead host from paying for a round trip each.
    #[test]
    fn a_negative_answer_is_cached() {
        let cache = DnsCache::new();
        let now = Instant::now();
        cache.put(
            "pixel.example.net",
            Resolved {
                answer: Answer::NoAddress,
                ttl: NEGATIVE_TTL,
            },
            now,
        );
        assert_eq!(cache.get("pixel.example.net", now), Some(Answer::NoAddress));
    }

    #[test]
    fn cache_stays_bounded() {
        let cache = DnsCache::new();
        let now = Instant::now();
        for i in 0..(CACHE_ENTRIES + 50) {
            cache.put(
                &format!("h{i}.example"),
                Resolved {
                    answer: Answer::NoAddress,
                    ttl: NEGATIVE_TTL,
                },
                now,
            );
        }
        assert_eq!(cache.entries.borrow().len(), CACHE_ENTRIES);
    }
}
