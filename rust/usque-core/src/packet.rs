//! IP packet validation and TTL handling for the tunnel's two directions.

use crate::checksum;
use crate::wire::{
    ip_version, IpVersion, IPV4_CHECKSUM_OFFSET, IPV4_HEADER_LEN, IPV4_TTL_OFFSET, IPV6_HEADER_LEN,
    IPV6_HOP_LIMIT_OFFSET,
};
use thiserror::Error;

#[derive(Debug, Error, Clone, Copy, PartialEq, Eq)]
pub enum PacketError {
    #[error("empty packet")]
    Empty,
    #[error("unknown IP version: {0}")]
    UnknownVersion(u8),
    #[error("packet too short for IPv{version} header (got {len} bytes)")]
    TooShort { version: u8, len: usize },
    #[error("TTL/hop limit too small: {0}")]
    TtlExpired(u8),
}

/// Recognise the version and confirm the buffer holds a whole fixed header.
///
/// The one gate through which a byte buffer becomes a packet this module will
/// touch. Both directions run it first, so neither indexes a header field it
/// has not established is present — the offsets below are total *because* this
/// returned `Ok`.
fn parse_version(buf: &[u8]) -> Result<IpVersion, PacketError> {
    // "Nothing arrived" and "something arrived that is not IP" are different
    // faults with different causes, so they stay different errors.
    let first = *buf.first().ok_or(PacketError::Empty)?;
    let version = ip_version(buf).ok_or(PacketError::UnknownVersion(first >> 4))?;
    let required = match version {
        IpVersion::V4 => IPV4_HEADER_LEN,
        IpVersion::V6 => IPV6_HEADER_LEN,
    };
    if buf.len() < required {
        return Err(PacketError::TooShort {
            version: version.number(),
            len: buf.len(),
        });
    }
    Ok(version)
}

/// Validate an IP packet and decrement TTL/Hop Limit in-place.
/// Returns the IP version (4 or 6) on success.
pub fn prepare_outgoing(buf: &mut [u8]) -> Result<u8, PacketError> {
    let version = parse_version(buf)?;
    let hop_offset = match version {
        IpVersion::V4 => IPV4_TTL_OFFSET,
        IpVersion::V6 => IPV6_HOP_LIMIT_OFFSET,
    };

    let hops = buf[hop_offset];
    if hops <= 1 {
        return Err(PacketError::TtlExpired(hops));
    }
    buf[hop_offset] = hops - 1;

    // Only IPv4 checksums its header, and only the header — so decrementing the
    // TTL means recomputing it. IPv6 dropped the field precisely to avoid this.
    if version == IpVersion::V4 {
        let checksum = ipv4_header_checksum(&buf[..IPV4_HEADER_LEN]);
        buf[IPV4_CHECKSUM_OFFSET..IPV4_CHECKSUM_OFFSET + 2]
            .copy_from_slice(&checksum.to_be_bytes());
    }

    Ok(version.number())
}

/// Validate an incoming IP packet (basic checks only).
pub fn validate_incoming(buf: &[u8]) -> Result<u8, PacketError> {
    parse_version(buf).map(IpVersion::number)
}

/// IPv4 header checksum (RFC 791), computed over the IHL-declared length.
///
/// Capped at the slice: a header claiming options that the caller did not hand
/// over is summed over what is actually present rather than read past.
fn ipv4_header_checksum(header: &[u8]) -> u16 {
    let ihl = usize::from(header[0] & 0x0F) * 4;
    checksum::of_header(&header[..ihl.min(header.len())], IPV4_CHECKSUM_OFFSET)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ipv4_checksum() {
        let mut hdr = [0u8; 20];
        hdr[0] = 0x45;
        hdr[8] = 64;
        hdr[9] = 6;
        hdr[12..16].copy_from_slice(&[10, 0, 0, 1]);
        hdr[16..20].copy_from_slice(&[10, 0, 0, 2]);

        let cksum = ipv4_header_checksum(&hdr);
        hdr[10..12].copy_from_slice(&cksum.to_be_bytes());

        let mut v: u32 = (0..20)
            .step_by(2)
            .map(|i| u32::from(u16::from_be_bytes([hdr[i], hdr[i + 1]])))
            .sum();
        while v >> 16 != 0 {
            v = (v & 0xFFFF) + (v >> 16);
        }
        assert_eq!(v as u16, 0xFFFF);
    }

    #[test]
    fn test_prepare_outgoing_ipv4() {
        let mut pkt = vec![0u8; 20];
        pkt[0] = 0x45;
        pkt[8] = 64;
        assert!(prepare_outgoing(&mut pkt).is_ok());
        assert_eq!(pkt[8], 63);
    }

    #[test]
    fn test_prepare_outgoing_ipv6() {
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x60;
        pkt[7] = 64;
        assert!(prepare_outgoing(&mut pkt).is_ok());
        assert_eq!(pkt[7], 63);
    }

    #[test]
    fn test_ttl_expired() {
        let mut pkt = vec![0u8; 20];
        pkt[0] = 0x45;
        pkt[8] = 1;
        assert!(matches!(
            prepare_outgoing(&mut pkt),
            Err(PacketError::TtlExpired(1))
        ));
    }

    #[test]
    fn test_ttl_zero() {
        let mut pkt = vec![0u8; 20];
        pkt[0] = 0x45;
        pkt[8] = 0;
        assert!(matches!(
            prepare_outgoing(&mut pkt),
            Err(PacketError::TtlExpired(0))
        ));
    }

    #[test]
    fn test_empty_packet() {
        let mut pkt = vec![];
        assert!(matches!(
            prepare_outgoing(&mut pkt),
            Err(PacketError::Empty)
        ));
    }

    #[test]
    fn test_unknown_version() {
        let mut pkt = vec![0x30; 40]; // version = 3
        assert!(matches!(
            prepare_outgoing(&mut pkt),
            Err(PacketError::UnknownVersion(3))
        ));
    }

    #[test]
    fn test_ipv4_too_short() {
        let mut pkt = vec![0x45; 10]; // version=4 but only 10 bytes
        assert!(matches!(
            prepare_outgoing(&mut pkt),
            Err(PacketError::TooShort {
                version: 4,
                len: 10
            })
        ));
    }

    #[test]
    fn test_ipv6_too_short() {
        let mut pkt = vec![0x60; 20]; // version=6 but only 20 bytes
        assert!(matches!(
            prepare_outgoing(&mut pkt),
            Err(PacketError::TooShort {
                version: 6,
                len: 20
            })
        ));
    }

    // ---- MTU boundary tests ----

    fn make_ipv4(total_len: usize) -> Vec<u8> {
        assert!(total_len >= 20);
        let mut pkt = vec![0u8; total_len];
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
        pkt[8] = 64;
        pkt[9] = 17;
        pkt[12..16].copy_from_slice(&[10, 0, 0, 1]);
        pkt[16..20].copy_from_slice(&[10, 0, 0, 2]);
        pkt
    }

    fn make_ipv6(total_len: usize) -> Vec<u8> {
        assert!(total_len >= 40);
        let mut pkt = vec![0u8; total_len];
        pkt[0] = 0x60;
        let payload_len = (total_len - 40) as u16;
        pkt[4..6].copy_from_slice(&payload_len.to_be_bytes());
        pkt[6] = 17;
        pkt[7] = 64;
        pkt
    }

    #[test]
    fn test_ipv4_small_mtu_packet() {
        let mut pkt = make_ipv4(68);
        assert_eq!(prepare_outgoing(&mut pkt).unwrap(), 4);
        assert_eq!(pkt[8], 63);
    }

    #[test]
    fn test_ipv4_576_mtu_packet() {
        let mut pkt = make_ipv4(576);
        assert_eq!(prepare_outgoing(&mut pkt).unwrap(), 4);
        assert_eq!(pkt[8], 63);
        // Verify checksum is correct after decrement
        let mut sum: u32 = (0..20)
            .step_by(2)
            .map(|i| u32::from(u16::from_be_bytes([pkt[i], pkt[i + 1]])))
            .sum();
        while sum >> 16 != 0 {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        assert_eq!(sum as u16, 0xFFFF);
    }

    #[test]
    fn test_ipv4_1280_mtu_packet() {
        let mut pkt = make_ipv4(1280);
        assert_eq!(prepare_outgoing(&mut pkt).unwrap(), 4);
        assert_eq!(pkt[8], 63);
    }

    #[test]
    fn test_ipv4_1500_mtu_packet() {
        let mut pkt = make_ipv4(1500);
        assert_eq!(prepare_outgoing(&mut pkt).unwrap(), 4);
        assert_eq!(pkt[8], 63);
    }

    #[test]
    fn test_ipv4_9000_jumbo_mtu_packet() {
        let mut pkt = make_ipv4(9000);
        assert_eq!(prepare_outgoing(&mut pkt).unwrap(), 4);
        assert_eq!(pkt[8], 63);
    }

    #[test]
    fn test_ipv6_1280_minimum_mtu() {
        let mut pkt = make_ipv6(1280);
        assert_eq!(prepare_outgoing(&mut pkt).unwrap(), 6);
        assert_eq!(pkt[7], 63);
    }

    #[test]
    fn test_ipv6_1500_mtu_packet() {
        let mut pkt = make_ipv6(1500);
        assert_eq!(prepare_outgoing(&mut pkt).unwrap(), 6);
        assert_eq!(pkt[7], 63);
    }

    #[test]
    fn test_ipv6_9000_jumbo_mtu_packet() {
        let mut pkt = make_ipv6(9000);
        assert_eq!(prepare_outgoing(&mut pkt).unwrap(), 6);
        assert_eq!(pkt[7], 63);
    }

    #[test]
    fn test_validate_incoming_ipv4_various_sizes() {
        for size in [20, 68, 576, 1280, 1500, 9000] {
            let pkt = make_ipv4(size);
            assert_eq!(validate_incoming(&pkt).unwrap(), 4, "size={size}");
        }
    }

    #[test]
    fn test_validate_incoming_ipv6_various_sizes() {
        for size in [40, 1280, 1500, 9000] {
            let pkt = make_ipv6(size);
            assert_eq!(validate_incoming(&pkt).unwrap(), 6, "size={size}");
        }
    }

    #[test]
    fn test_ipv4_checksum_stability_across_ttl_decrements() {
        let mut pkt = make_ipv4(1500);
        pkt[8] = 255;
        for expected_ttl in (0..255).rev() {
            if expected_ttl == 0 {
                assert!(prepare_outgoing(&mut pkt).is_err());
                break;
            }
            assert!(prepare_outgoing(&mut pkt).is_ok());
            assert_eq!(pkt[8], expected_ttl);
            let mut sum: u32 = (0..20)
                .step_by(2)
                .map(|i| u32::from(u16::from_be_bytes([pkt[i], pkt[i + 1]])))
                .sum();
            while sum >> 16 != 0 {
                sum = (sum & 0xFFFF) + (sum >> 16);
            }
            assert_eq!(sum as u16, 0xFFFF, "checksum invalid at ttl={expected_ttl}");
        }
    }
}
