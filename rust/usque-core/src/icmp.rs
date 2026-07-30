const IPV4_HEADER_LEN: usize = 20;
const IPV6_HEADER_LEN: usize = 40;
const ICMP_HEADER_LEN: usize = 8;

const ICMP_TYPE_DEST_UNREACHABLE: u8 = 3;
const ICMP_CODE_FRAG_NEEDED: u8 = 4;
const ICMPV6_TYPE_PACKET_TOO_BIG: u8 = 2;

/// Compose an ICMP "Packet Too Big" / "Fragmentation Needed" response
/// for an IP packet that was too large to send as a QUIC datagram.
pub fn compose_icmp_too_large(original: &[u8], mtu: u16) -> Option<Vec<u8>> {
    if original.is_empty() {
        return None;
    }

    match original[0] >> 4 {
        4 => compose_icmpv4_too_large(original, mtu),
        6 => compose_icmpv6_too_large(original, mtu),
        _ => None,
    }
}

fn compose_icmpv4_too_large(original: &[u8], mtu: u16) -> Option<Vec<u8>> {
    if original.len() < IPV4_HEADER_LEN {
        return None;
    }

    // ICMP payload: original IP header + first 8 bytes of original payload
    let icmp_payload_len = (IPV4_HEADER_LEN + 8).min(original.len());
    let icmp_data = &original[..icmp_payload_len];

    let total_len = IPV4_HEADER_LEN + ICMP_HEADER_LEN + icmp_data.len();
    let mut pkt = vec![0u8; total_len];

    pkt[0] = 0x45;
    pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    pkt[8] = 64;
    pkt[9] = 1; // ICMP
    pkt[12..16].copy_from_slice(&original[16..20]); // swap src/dst
    pkt[16..20].copy_from_slice(&original[12..16]);

    let ip_checksum = ipv4_checksum(&pkt[..IPV4_HEADER_LEN]);
    pkt[10..12].copy_from_slice(&ip_checksum.to_be_bytes());

    let icmp_start = IPV4_HEADER_LEN;
    pkt[icmp_start] = ICMP_TYPE_DEST_UNREACHABLE;
    pkt[icmp_start + 1] = ICMP_CODE_FRAG_NEEDED;
    pkt[icmp_start + 6..icmp_start + 8].copy_from_slice(&mtu.to_be_bytes()); // next-hop MTU
    pkt[icmp_start + ICMP_HEADER_LEN..].copy_from_slice(icmp_data);

    let icmp_cksum = internet_checksum(&pkt[icmp_start..]);
    pkt[icmp_start + 2..icmp_start + 4].copy_from_slice(&icmp_cksum.to_be_bytes());

    Some(pkt)
}

fn compose_icmpv6_too_large(original: &[u8], mtu: u16) -> Option<Vec<u8>> {
    if original.len() < IPV6_HEADER_LEN {
        return None;
    }

    // ICMPv6 payload: as much of the original as will fit (max ~1232 bytes)
    let max_icmp_payload = 1232;
    let icmp_payload_len = original.len().min(max_icmp_payload);
    let icmp_data = &original[..icmp_payload_len];

    let payload_len = ICMP_HEADER_LEN + icmp_data.len();
    let total_len = IPV6_HEADER_LEN + payload_len;
    let mut pkt = vec![0u8; total_len];

    pkt[0] = 0x60;
    pkt[4..6].copy_from_slice(&(payload_len as u16).to_be_bytes());
    pkt[6] = 58; // ICMPv6
    pkt[7] = 64;
    pkt[8..24].copy_from_slice(&original[24..40]); // swap src/dst
    pkt[24..40].copy_from_slice(&original[8..24]);

    let icmp_start = IPV6_HEADER_LEN;
    pkt[icmp_start] = ICMPV6_TYPE_PACKET_TOO_BIG;
    pkt[icmp_start + 1] = 0;
    pkt[icmp_start + 4..icmp_start + 8].copy_from_slice(&u32::from(mtu).to_be_bytes());
    pkt[icmp_start + ICMP_HEADER_LEN..].copy_from_slice(icmp_data);
    let cksum = icmpv6_checksum(
        &pkt[8..24],  // src
        &pkt[24..40], // dst
        &pkt[icmp_start..],
    );
    pkt[icmp_start + 2..icmp_start + 4].copy_from_slice(&cksum.to_be_bytes());

    Some(pkt)
}

fn ipv4_checksum(header: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    let mut i = 0;
    while i < header.len() {
        if i == 10 {
            i += 2;
            continue;
        }
        let word = if i + 1 < header.len() {
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

fn internet_checksum(data: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    let mut i = 0;
    while i < data.len() {
        let word = if i + 1 < data.len() {
            u16::from_be_bytes([data[i], data[i + 1]])
        } else {
            u16::from_be_bytes([data[i], 0])
        };
        sum += u32::from(word);
        i += 2;
    }
    while sum >> 16 != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !(sum as u16)
}

fn icmpv6_checksum(src: &[u8], dst: &[u8], icmpv6_data: &[u8]) -> u16 {
    let mut sum: u32 = 0;

    for i in (0..16).step_by(2) {
        sum += u32::from(u16::from_be_bytes([src[i], src[i + 1]]));
    }
    for i in (0..16).step_by(2) {
        sum += u32::from(u16::from_be_bytes([dst[i], dst[i + 1]]));
    }
    let len = icmpv6_data.len() as u32;
    sum += len >> 16;
    sum += len & 0xFFFF;
    sum += 58u32; // next header

    let mut i = 0;
    while i < icmpv6_data.len() {
        if i == 2 {
            i += 2; // skip checksum field
            continue;
        }
        let word = if i + 1 < icmpv6_data.len() {
            u16::from_be_bytes([icmpv6_data[i], icmpv6_data[i + 1]])
        } else {
            u16::from_be_bytes([icmpv6_data[i], 0])
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

    fn make_ipv4_packet(total_len: usize, src: [u8; 4], dst: [u8; 4]) -> Vec<u8> {
        assert!(total_len >= IPV4_HEADER_LEN);
        let mut pkt = vec![0u8; total_len];
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
        pkt[8] = 64;
        pkt[9] = 17;
        pkt[12..16].copy_from_slice(&src);
        pkt[16..20].copy_from_slice(&dst);
        pkt
    }

    fn make_ipv6_packet(total_len: usize, src: [u8; 16], dst: [u8; 16]) -> Vec<u8> {
        assert!(total_len >= IPV6_HEADER_LEN);
        let mut pkt = vec![0u8; total_len];
        pkt[0] = 0x60;
        let payload_len = (total_len - IPV6_HEADER_LEN) as u16;
        pkt[4..6].copy_from_slice(&payload_len.to_be_bytes());
        pkt[6] = 17;
        pkt[7] = 64;
        pkt[8..24].copy_from_slice(&src);
        pkt[24..40].copy_from_slice(&dst);
        pkt
    }

    #[test]
    fn icmpv4_too_large_small_mtu() {
        let original = make_ipv4_packet(576, [10, 0, 0, 1], [10, 0, 0, 2]);
        let resp = compose_icmp_too_large(&original, 512).expect("should produce ICMP");

        // Outer IP header checks
        assert_eq!(resp[0] >> 4, 4, "IPv4 response");
        assert_eq!(resp[9], 1, "protocol = ICMP");
        // Src/Dst swapped
        assert_eq!(&resp[12..16], &[10, 0, 0, 2], "src = original dst");
        assert_eq!(&resp[16..20], &[10, 0, 0, 1], "dst = original src");

        // ICMP header
        let icmp = &resp[IPV4_HEADER_LEN..];
        assert_eq!(icmp[0], ICMP_TYPE_DEST_UNREACHABLE);
        assert_eq!(icmp[1], ICMP_CODE_FRAG_NEEDED);
        // Next-hop MTU in bytes 6-7
        let mtu_val = u16::from_be_bytes([icmp[6], icmp[7]]);
        assert_eq!(mtu_val, 512);

        // Verify ICMP checksum
        let cksum = internet_checksum(icmp);
        assert_eq!(cksum, 0, "ICMP checksum should verify to 0");
    }

    #[test]
    fn icmpv4_too_large_big_mtu() {
        let original = make_ipv4_packet(9000, [192, 168, 1, 1], [8, 8, 8, 8]);
        let resp = compose_icmp_too_large(&original, 1280).expect("should produce ICMP");

        let icmp = &resp[IPV4_HEADER_LEN..];
        let mtu_val = u16::from_be_bytes([icmp[6], icmp[7]]);
        assert_eq!(mtu_val, 1280);
        // ICMP payload should be original header + 8 bytes
        assert_eq!(icmp.len() - ICMP_HEADER_LEN, IPV4_HEADER_LEN + 8);
    }

    #[test]
    fn icmpv4_minimum_header_only() {
        // Exactly 20-byte packet (header only, no payload beyond header)
        let original = make_ipv4_packet(20, [1, 2, 3, 4], [5, 6, 7, 8]);
        let resp = compose_icmp_too_large(&original, 576).expect("should produce ICMP");

        let icmp = &resp[IPV4_HEADER_LEN..];
        // ICMP payload = min(20+8, 20) = 20 bytes (just the header)
        assert_eq!(icmp.len() - ICMP_HEADER_LEN, 20);
    }

    #[test]
    fn icmpv4_too_short_returns_none() {
        let buf = vec![0x45; 10]; // Too short for IPv4 header
        assert!(compose_icmp_too_large(&buf, 1280).is_none());
    }

    #[test]
    fn icmpv6_too_large_small_mtu() {
        let src = [0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1];
        let dst = [0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2];
        let original = make_ipv6_packet(1500, src, dst);
        let resp = compose_icmp_too_large(&original, 1280).expect("should produce ICMPv6");

        // Outer IPv6 header
        assert_eq!(resp[0] >> 4, 6, "IPv6 response");
        assert_eq!(resp[6], 58, "next header = ICMPv6");
        // Src/Dst swapped
        assert_eq!(&resp[8..24], &dst, "src = original dst");
        assert_eq!(&resp[24..40], &src, "dst = original src");

        // ICMPv6 header
        let icmp = &resp[IPV6_HEADER_LEN..];
        assert_eq!(icmp[0], ICMPV6_TYPE_PACKET_TOO_BIG);
        assert_eq!(icmp[1], 0, "code = 0");
        // MTU field (32-bit, bytes 4-7)
        let mtu_val = u32::from_be_bytes([icmp[4], icmp[5], icmp[6], icmp[7]]);
        assert_eq!(mtu_val, 1280);
    }

    #[test]
    fn icmpv6_too_large_big_packet() {
        let src = [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1];
        let dst = [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2];
        let original = make_ipv6_packet(9000, src, dst);
        let resp = compose_icmp_too_large(&original, 1500).expect("should produce ICMPv6");

        let icmp = &resp[IPV6_HEADER_LEN..];
        // Payload should be capped at 1232 bytes
        assert_eq!(icmp.len() - ICMP_HEADER_LEN, 1232);

        let mtu_val = u32::from_be_bytes([icmp[4], icmp[5], icmp[6], icmp[7]]);
        assert_eq!(mtu_val, 1500);
    }

    #[test]
    fn icmpv6_minimum_header_only() {
        let src = [0; 16];
        let dst = [1; 16];
        let original = make_ipv6_packet(40, src, dst); // header only
        let resp = compose_icmp_too_large(&original, 1280).expect("should produce ICMPv6");

        let icmp = &resp[IPV6_HEADER_LEN..];
        assert_eq!(icmp.len() - ICMP_HEADER_LEN, 40);
    }

    #[test]
    fn icmpv6_too_short_returns_none() {
        let mut buf = vec![0x60; 20]; // Too short for IPv6 header
        buf[0] = 0x60;
        assert!(compose_icmp_too_large(&buf, 1280).is_none());
    }

    #[test]
    fn empty_packet_returns_none() {
        assert!(compose_icmp_too_large(&[], 1280).is_none());
    }

    #[test]
    fn unknown_version_returns_none() {
        let buf = vec![0x30; 40]; // version = 3, not valid
        assert!(compose_icmp_too_large(&buf, 1280).is_none());
    }

    #[test]
    fn icmpv4_checksum_validates() {
        // Ensure the full response (IP + ICMP) has valid checksums
        let original = make_ipv4_packet(1500, [172, 16, 0, 1], [1, 1, 1, 1]);
        let resp = compose_icmp_too_large(&original, 1280).unwrap();

        // Validate IP header checksum
        let mut ip_sum: u32 = (0..IPV4_HEADER_LEN)
            .step_by(2)
            .map(|i| u32::from(u16::from_be_bytes([resp[i], resp[i + 1]])))
            .sum();
        while ip_sum >> 16 != 0 {
            ip_sum = (ip_sum & 0xFFFF) + (ip_sum >> 16);
        }
        assert_eq!(ip_sum as u16, 0xFFFF, "IP header checksum should validate");

        // Validate ICMP checksum (sum entire ICMP message including checksum = 0xFFFF)
        let icmp = &resp[IPV4_HEADER_LEN..];
        let mut icmp_sum: u32 = 0;
        let mut i = 0;
        while i < icmp.len() {
            let word = if i + 1 < icmp.len() {
                u16::from_be_bytes([icmp[i], icmp[i + 1]])
            } else {
                u16::from_be_bytes([icmp[i], 0])
            };
            icmp_sum += u32::from(word);
            i += 2;
        }
        while icmp_sum >> 16 != 0 {
            icmp_sum = (icmp_sum & 0xFFFF) + (icmp_sum >> 16);
        }
        assert_eq!(icmp_sum as u16, 0xFFFF, "ICMP checksum should validate");
    }
}
