//! End-to-end proof that traffic is really leaving through the tunnel.
//!
//! Counters and a connection state say the session came up. They do not say
//! that a request made it out, that Cloudflare treated it as WARP traffic, or
//! that the address an image server would see is not the user's. This asks the
//! only party that can answer — the exit itself.
//!
//! `/cdn-cgi/trace` is served by every Cloudflare edge and reports, among other
//! things, the client address it observed and whether the connection arrived
//! over WARP. Fetching it *through the tunnel* therefore tests the whole path
//! the images take: DNS over the tunnel, TLS over the tunnel, and the exit's own
//! view of who is asking.

use crate::error::ProxyError;
use crate::tunnel::http1::ClientProfile;
use crate::tunnel::{FetchRequest, TunnelManager};

/// Where the check points. Any Cloudflare host serves this path; using the
/// apex keeps the request indistinguishable from ordinary browsing.
const TRACE_URL: &str = "https://cloudflare.com/cdn-cgi/trace";

/// The trace response is a handful of short lines; anything larger is not it.
const MAX_TRACE_BYTES: u64 = 64 * 1024;

/// The request both the user-facing check and the idle health probe send.
///
/// One definition, because the probe exists to exercise the path an image
/// takes: if the two drifted apart, a green check would stop meaning the tunnel
/// the images use is healthy. The browser persona is part of that — a request
/// that presents differently is measuring a different path.
pub(crate) fn trace_request() -> FetchRequest {
    FetchRequest::new(
        TRACE_URL,
        ClientProfile::browser("text/plain"),
        crate::config::FetchLimits {
            max_size: MAX_TRACE_BYTES,
            ..crate::config::FetchLimits::default()
        },
    )
}

/// What the Cloudflare edge reported about the request the tunnel made.
#[derive(Clone, Debug, uniffi::Record)]
pub struct TunnelVerification {
    /// Whether the exit treated this as WARP traffic.
    ///
    /// Derived once, here, rather than leaving every caller to remember that
    /// `plus` also counts and that anything else — including a missing field —
    /// does not.
    pub warp_active: bool,
    /// The raw `warp=` value: `on`, `plus`, `off`, or empty if absent.
    pub warp: String,
    /// The client address Cloudflare saw.
    ///
    /// This is the address an image server would be given. It must not be the
    /// user's own, which is the entire point of the proxy.
    pub egress_ip: String,
    /// Cloudflare colo that served the request (e.g. `YYZ`).
    pub colo: String,
    /// Bytes the tunnel carried for this check alone.
    ///
    /// A delta rather than a total: a total that was already non-zero proves
    /// nothing about *this* request, and zero here would mean the response came
    /// from somewhere other than the tunnel.
    pub tx_bytes: u64,
    /// Bytes received through the tunnel for this check alone.
    pub rx_bytes: u64,
}

impl TunnelVerification {
    /// Why this is not proof of a healthy WARP path, or `None` when it is.
    ///
    /// Health is more than `warp_active`. A reply that cost the tunnel no bytes
    /// did not come through it, and if the device itself is on WARP such a reply
    /// still says `warp=on` — so the byte counters are checked first, being the
    /// more damning of the two.
    ///
    /// Returned as a reason rather than a bool because every caller that fails
    /// this needs to say what was wrong, and deriving that separately is how two
    /// places come to disagree about what "healthy" means.
    pub fn unhealthy_reason(&self) -> Option<String> {
        if self.tx_bytes == 0 || self.rx_bytes == 0 {
            return Some(format!(
                "the tunnel carried no bytes for this check (tx {}, rx {}), so the reply did not come through it",
                self.tx_bytes, self.rx_bytes
            ));
        }
        if !self.warp_active {
            return Some(format!(
                "the edge reported warp={} and egress {}, which is not WARP",
                if self.warp.is_empty() {
                    "absent"
                } else {
                    &self.warp
                },
                if self.egress_ip.is_empty() {
                    "unknown"
                } else {
                    &self.egress_ip
                },
            ));
        }
        None
    }

    /// Whether the exit confirmed WARP *and* the tunnel carried the bytes.
    pub fn is_healthy(&self) -> bool {
        self.unhealthy_reason().is_none()
    }
}

/// Verify the tunnel and require the result to prove a healthy WARP path.
///
/// [`verify_tunnel`] reports what the edge said and leaves the judgement to the
/// caller, which is right for a diagnostics screen that wants to show a red row.
/// Every other caller — tests above all — only wants to proceed when the path is
/// good, and would otherwise re-derive the predicate and drift from it.
///
/// # Errors
///
/// The fetch's own failure, or a [`ProxyError::TunnelError`] naming what the
/// trace disproved.
pub fn require_healthy(manager: &TunnelManager) -> Result<TunnelVerification, ProxyError> {
    let verification = verify_tunnel(manager)?;
    match verification.unhealthy_reason() {
        None => Ok(verification),
        Some(details) => Err(ProxyError::TunnelError { details }),
    }
}

/// Read one `key=value` field out of a trace body.
///
/// The format is line-oriented `key=value` with no escaping and no continuation,
/// so this is a scan rather than a parse. Unknown keys are ignored: Cloudflare
/// adds fields over time and a check that broke when they did would be worse
/// than useless.
fn trace_field<'a>(body: &'a str, key: &str) -> Option<&'a str> {
    body.lines()
        .filter_map(|line| line.split_once('='))
        .find(|(name, _)| *name == key)
        .map(|(_, value)| value.trim())
}

/// Interpret the `warp=` field.
///
/// `plus` is WARP+ and is still WARP. Anything else — `off`, an unrecognised
/// value, or a missing field — is not, and is reported as such rather than
/// guessed at.
fn warp_is_active(warp: &str) -> bool {
    matches!(warp, "on" | "plus")
}

/// Build the verification result from a trace body and the byte deltas.
///
/// Pure, so the interpretation is testable without a network.
fn interpret(body: &str, tx_bytes: u64, rx_bytes: u64) -> TunnelVerification {
    let warp = trace_field(body, "warp").unwrap_or_default().to_string();
    TunnelVerification {
        warp_active: warp_is_active(&warp),
        warp,
        egress_ip: trace_field(body, "ip").unwrap_or_default().to_string(),
        colo: trace_field(body, "colo").unwrap_or_default().to_string(),
        tx_bytes,
        rx_bytes,
    }
}

/// Fetch the trace through the tunnel and report what the exit saw.
///
/// # Errors
///
/// Returns whatever the fetch failed with. A failure here is the finding: it
/// means the path the images take does not work, whatever the counters say.
pub fn verify_tunnel(manager: &TunnelManager) -> Result<TunnelVerification, ProxyError> {
    // Sampled either side of the fetch so the result reports what *this* request
    // moved, not what the session has moved since it came up.
    let before = manager.diagnostics()?;
    let outcome = manager.fetch(trace_request())?;
    let after = manager.diagnostics()?;

    let body = String::from_utf8_lossy(&outcome.body);
    Ok(interpret(
        &body,
        after.tx_bytes.saturating_sub(before.tx_bytes),
        after.rx_bytes.saturating_sub(before.rx_bytes),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = "fl=123abc\nh=cloudflare.com\nip=104.28.ximagined\nts=1.0\n\
                          visit_scheme=https\nuag=\ncolo=YYZ\nsliver=none\nhttp=http/1.1\n\
                          loc=CA\ntls=TLSv1.3\nsni=plaintext\nwarp=on\ngateway=off\n";

    #[test]
    fn reads_the_fields_the_check_depends_on() {
        let v = interpret(SAMPLE, 1200, 3400);
        assert!(v.warp_active);
        assert_eq!(v.warp, "on");
        assert_eq!(v.egress_ip, "104.28.ximagined");
        assert_eq!(v.colo, "YYZ");
        assert_eq!((v.tx_bytes, v.rx_bytes), (1200, 3400));
    }

    /// WARP+ is still WARP; anything else is not, including values invented
    /// later. Guessing "probably on" would defeat the point of asking.
    #[test]
    fn only_on_and_plus_count_as_warp() {
        assert!(warp_is_active("on"));
        assert!(warp_is_active("plus"));
        for value in ["off", "", "ON", "true", "yes", "unknown"] {
            assert!(!warp_is_active(value), "{value} must not read as active");
        }
    }

    /// A trace that omits `warp=` reports inactive rather than defaulting to
    /// something reassuring.
    #[test]
    fn a_missing_warp_field_is_not_active() {
        let v = interpret("ip=1.2.3.4\ncolo=LHR\n", 10, 20);
        assert!(!v.warp_active);
        assert_eq!(v.warp, "");
        assert_eq!(v.egress_ip, "1.2.3.4");
    }

    /// Unknown keys are ignored rather than breaking the scan — Cloudflare adds
    /// fields, and a check that failed when they did would be worse than none.
    #[test]
    fn unknown_and_malformed_lines_are_ignored() {
        let v = interpret("garbage\n\nfuture_field=1\nwarp=plus\nip=9.9.9.9\n", 1, 1);
        assert!(v.warp_active);
        assert_eq!(v.egress_ip, "9.9.9.9");
    }

    fn verification(warp: &str, tx: u64, rx: u64) -> TunnelVerification {
        interpret(&format!("warp={warp}\nip=1.2.3.4\ncolo=YYZ\n"), tx, rx)
    }

    #[test]
    fn a_warp_reply_that_moved_bytes_is_healthy() {
        for warp in ["on", "plus"] {
            let v = verification(warp, 1200, 3400);
            assert!(v.is_healthy(), "warp={warp} should be healthy");
            assert_eq!(v.unhealthy_reason(), None);
        }
    }

    #[test]
    fn warp_off_is_not_healthy() {
        for warp in ["off", "", "unknown"] {
            let reason = verification(warp, 1200, 3400)
                .unhealthy_reason()
                .expect("must be refused");
            assert!(reason.contains("not WARP"), "{reason}");
        }
    }

    /// A reply the tunnel did not carry says nothing about the tunnel, even when
    /// the edge reports WARP — which it will if the device itself is on WARP.
    #[test]
    fn a_reply_the_tunnel_did_not_carry_is_not_healthy() {
        for (tx, rx) in [(0, 3400), (1200, 0), (0, 0)] {
            let reason = verification("on", tx, rx)
                .unhealthy_reason()
                .expect("must be refused");
            assert!(reason.contains("carried no bytes"), "{reason}");
        }
    }

    /// A value containing `=` keeps everything after the first separator.
    #[test]
    fn splits_on_the_first_separator_only() {
        assert_eq!(
            trace_field("uag=Mozilla/5.0 (a=b)\n", "uag"),
            Some("Mozilla/5.0 (a=b)")
        );
    }
}
