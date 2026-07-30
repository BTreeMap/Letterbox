use thiserror::Error;

const IPV4_HEADER_LEN: usize = 20;
const IPV6_HEADER_LEN: usize = 40;

#[derive(Debug, Error)]
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

#[inline]
pub fn ip_version(buf: &[u8]) -> u8 {
    buf[0] >> 4
}

/// Validate an IP packet and decrement TTL/Hop Limit in-place.
/// Returns the IP version (4 or 6) on success.
pub fn prepare_outgoing(buf: &mut [u8]) -> Result<u8, PacketError> {
    if buf.is_empty() {
        return Err(PacketError::Empty);
    }

    match ip_version(buf) {
        4 => {
            if buf.len() < IPV4_HEADER_LEN {
                return Err(PacketError::TooShort {
                    version: 4,
                    len: buf.len(),
                });
            }
            let ttl = buf[8];
            if ttl <= 1 {
                return Err(PacketError::TtlExpired(ttl));
            }
            buf[8] -= 1;
            let checksum = calculate_ipv4_checksum(&buf[..IPV4_HEADER_LEN]);
            buf[10..12].copy_from_slice(&checksum.to_be_bytes());
            Ok(4)
        }
        6 => {
            if buf.len() < IPV6_HEADER_LEN {
                return Err(PacketError::TooShort {
                    version: 6,
                    len: buf.len(),
                });
            }
            let hop_limit = buf[7];
            if hop_limit <= 1 {
                return Err(PacketError::TtlExpired(hop_limit));
            }
            buf[7] -= 1;
            Ok(6)
        }
        v => Err(PacketError::UnknownVersion(v)),
    }
}

/// Validate an incoming IP packet (basic checks only).
pub fn validate_incoming(buf: &[u8]) -> Result<u8, PacketError> {
    if buf.is_empty() {
        return Err(PacketError::Empty);
    }
    match ip_version(buf) {
        4 => {
            if buf.len() < IPV4_HEADER_LEN {
                return Err(PacketError::TooShort {
                    version: 4,
                    len: buf.len(),
                });
            }
            Ok(4)
        }
        6 => {
            if buf.len() < IPV6_HEADER_LEN {
                return Err(PacketError::TooShort {
                    version: 6,
                    len: buf.len(),
                });
            }
            Ok(6)
        }
        v => Err(PacketError::UnknownVersion(v)),
    }
}

/// Calculate IPv4 header checksum (RFC 791).
/// The header slice must be exactly 20 bytes (no options) or at least
/// the IHL-indicated length. We compute over the full IHL length.
fn calculate_ipv4_checksum(header: &[u8]) -> u16 {
    let ihl = ((header[0] & 0x0F) as usize) * 4;
    let len = ihl.min(header.len());
    let mut sum: u32 = 0;

    let mut i = 0;
    while i < len {
        if i == 10 {
            i += 2; // skip checksum field
            continue;
        }
        let word = if i + 1 < len {
            u16::from_be_bytes([header[i], header[i + 1]])
        } else {
            u16::from_be_bytes([header[i], 0])
        };
        sum += u32::from(word);
        i += 2;
    }

    while sum >> 16 != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }

    !(sum as u16)
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

        let cksum = calculate_ipv4_checksum(&hdr);
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
