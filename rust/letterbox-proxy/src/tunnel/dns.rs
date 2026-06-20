//! DNS resolution over the tunnel via DNS-over-HTTPS (DoH).
//!
//! Resolving names through Cloudflare's `1.1.1.1` DoH endpoint keeps name
//! resolution *inside* the encrypted tunnel: the host's real resolver — and
//! therefore the user's ISP — never sees which image servers are queried. The
//! resolver IP (`1.1.1.1`) is a literal, so DoH itself needs no bootstrap DNS.

use crate::error::ProxyError;
use crate::tunnel::http1::{build_get_request, parse_response};
use crate::tunnel::stack::WarpTunnel;
use crate::tunnel::tls::request_https;
use serde::Deserialize;
use smoltcp::wire::IpAddress;
use std::net::Ipv4Addr;
use std::time::Duration;

/// Cloudflare's DoH resolver address (a literal, needs no resolution itself).
const DOH_RESOLVER: IpAddress = IpAddress::v4(1, 1, 1, 1);

/// SNI / `Host` used for the DoH resolver (valid on Cloudflare's certificate).
const DOH_HOST: &str = "one.one.one.one";

/// DNS `A` record type code in the DoH JSON API.
const DNS_TYPE_A: u16 = 1;

/// Maximum DoH response size (answers are tiny).
const MAX_DOH_RESPONSE: usize = 64 * 1024;

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
    data: String,
}

/// Resolve `host` to an IPv4 [`IpAddress`] through the tunnel.
///
/// Literal IPv4 addresses are returned directly. Hostnames are resolved via
/// DoH; the first `A` record is used.
pub fn resolve(
    tunnel: &mut WarpTunnel,
    host: &str,
    timeout: Duration,
) -> Result<IpAddress, ProxyError> {
    if let Ok(addr) = host.parse::<Ipv4Addr>() {
        return Ok(IpAddress::v4(
            addr.octets()[0],
            addr.octets()[1],
            addr.octets()[2],
            addr.octets()[3],
        ));
    }

    if !is_valid_hostname(host) {
        return Err(ProxyError::DnsError {
            host: host.to_string(),
            details: "Hostname contains invalid characters".to_string(),
        });
    }

    let path = format!("/dns-query?name={host}&type=A");
    let request = build_get_request(DOH_HOST, &path, "application/dns-json", &[]);

    let raw = request_https(
        tunnel,
        DOH_RESOLVER,
        443,
        DOH_HOST,
        &request,
        MAX_DOH_RESPONSE,
        timeout,
    )?;

    let response = parse_response(&raw)?;
    if response.status != 200 {
        return Err(ProxyError::DnsError {
            host: host.to_string(),
            details: format!("DoH resolver returned status {}", response.status),
        });
    }

    let parsed: DohResponse =
        serde_json::from_slice(&response.body).map_err(|e| ProxyError::DnsError {
            host: host.to_string(),
            details: format!("Failed to parse DoH response: {e}"),
        })?;

    parsed
        .answer
        .iter()
        .filter(|a| a.record_type == DNS_TYPE_A)
        .find_map(|a| a.data.parse::<Ipv4Addr>().ok())
        .map(|addr| {
            let o = addr.octets();
            IpAddress::v4(o[0], o[1], o[2], o[3])
        })
        .ok_or_else(|| ProxyError::DnsError {
            host: host.to_string(),
            details: "No A record in DoH response".to_string(),
        })
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
    fn deserializes_doh_response() {
        let json = br#"{"Status":0,"Answer":[{"name":"x","type":5,"data":"cname.example."},{"name":"x","type":1,"data":"93.184.216.34"}]}"#;
        let parsed: DohResponse = serde_json::from_slice(json).unwrap();
        let ip = parsed
            .answer
            .iter()
            .filter(|a| a.record_type == DNS_TYPE_A)
            .find_map(|a| a.data.parse::<Ipv4Addr>().ok());
        assert_eq!(ip, Some(Ipv4Addr::new(93, 184, 216, 34)));
    }
}
