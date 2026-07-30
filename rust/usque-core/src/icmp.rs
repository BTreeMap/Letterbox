//! Synthesis of ICMP "Packet Too Big" replies, so PMTU discovery works.
//!
//! When a packet will not fit in a QUIC datagram, the sender above the tunnel
//! has to learn the smaller MTU from somewhere. Nothing on the path will tell
//! it — the tunnel *is* the constriction — so the reply is manufactured here
//! and injected back towards the source.

use crate::checksum::{self, Checksum};
use crate::wire::{
    ip_version, IpVersion, ICMP_HEADER_LEN, IPV4_CHECKSUM_OFFSET, IPV4_DST, IPV4_HEADER_LEN,
    IPV4_SRC, IPV4_TTL_OFFSET, IPV6_DST, IPV6_HEADER_LEN, IPV6_HOP_LIMIT_OFFSET,
    IPV6_NEXT_HEADER_OFFSET, IPV6_PAYLOAD_LEN, IPV6_SRC,
};

const ICMP_TYPE_DEST_UNREACHABLE: u8 = 3;
const ICMP_CODE_FRAG_NEEDED: u8 = 4;
const ICMPV6_TYPE_PACKET_TOO_BIG: u8 = 2;

/// IPv4 protocol number for ICMP.
const IPV4_PROTO_ICMP: u8 = 1;

/// IPv6 next-header value for ICMPv6, also used in its pseudo-header.
const IPV6_NEXT_HEADER_ICMPV6: u8 = 58;

/// TTL/hop limit on the replies this module mints.
const REPLY_HOP_LIMIT: u8 = 64;

/// Byte offset of the checksum field within an ICMP header.
const ICMP_CHECKSUM_OFFSET: usize = 2;

/// How much of the offending packet an ICMPv4 error quotes: RFC 792 asks for
/// the header plus the first 8 payload bytes, enough to identify the flow.
const ICMPV4_QUOTE_LEN: usize = IPV4_HEADER_LEN + 8;

/// How much an ICMPv6 error quotes: as much as fits without the reply itself
/// exceeding the IPv6 minimum MTU (RFC 4443 §2.4).
const ICMPV6_QUOTE_LEN: usize = 1232;

/// Compose an ICMP "Packet Too Big" / "Fragmentation Needed" response
/// for an IP packet that was too large to send as a QUIC datagram.
///
/// `None` when `original` is not a packet this can answer — too short to hold
/// the addresses the reply must be sent back to, or not IP at all.
pub fn compose_icmp_too_large(original: &[u8], mtu: u16) -> Option<Vec<u8>> {
    match ip_version(original)? {
        IpVersion::V4 => compose_icmpv4_too_large(original, mtu),
        IpVersion::V6 => compose_icmpv6_too_large(original, mtu),
    }
}

fn compose_icmpv4_too_large(original: &[u8], mtu: u16) -> Option<Vec<u8>> {
    if original.len() < IPV4_HEADER_LEN {
        return None;
    }
    let quote = &original[..ICMPV4_QUOTE_LEN.min(original.len())];

    let total_len = IPV4_HEADER_LEN + ICMP_HEADER_LEN + quote.len();
    let mut pkt = vec![0u8; total_len];

    pkt[0] = 0x45;
    pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    pkt[IPV4_TTL_OFFSET] = REPLY_HOP_LIMIT;
    pkt[9] = IPV4_PROTO_ICMP;
    // The reply travels back the way the packet came, so the addresses swap.
    pkt[IPV4_SRC].copy_from_slice(&original[IPV4_DST]);
    pkt[IPV4_DST].copy_from_slice(&original[IPV4_SRC]);

    let ip_checksum = checksum::of_header(&pkt[..IPV4_HEADER_LEN], IPV4_CHECKSUM_OFFSET);
    pkt[IPV4_CHECKSUM_OFFSET..IPV4_CHECKSUM_OFFSET + 2].copy_from_slice(&ip_checksum.to_be_bytes());

    let icmp = &mut pkt[IPV4_HEADER_LEN..];
    icmp[0] = ICMP_TYPE_DEST_UNREACHABLE;
    icmp[1] = ICMP_CODE_FRAG_NEEDED;
    // Next-hop MTU occupies the second half of the unused word.
    icmp[6..8].copy_from_slice(&mtu.to_be_bytes());
    icmp[ICMP_HEADER_LEN..].copy_from_slice(quote);

    // ICMPv4 checksums the message alone — no pseudo-header, unlike ICMPv6.
    let icmp_checksum = checksum::of_header(icmp, ICMP_CHECKSUM_OFFSET);
    icmp[ICMP_CHECKSUM_OFFSET..ICMP_CHECKSUM_OFFSET + 2]
        .copy_from_slice(&icmp_checksum.to_be_bytes());

    Some(pkt)
}

fn compose_icmpv6_too_large(original: &[u8], mtu: u16) -> Option<Vec<u8>> {
    if original.len() < IPV6_HEADER_LEN {
        return None;
    }
    let quote = &original[..ICMPV6_QUOTE_LEN.min(original.len())];

    let payload_len = ICMP_HEADER_LEN + quote.len();
    let mut pkt = vec![0u8; IPV6_HEADER_LEN + payload_len];

    pkt[0] = 0x60;
    pkt[IPV6_PAYLOAD_LEN].copy_from_slice(&(payload_len as u16).to_be_bytes());
    pkt[IPV6_NEXT_HEADER_OFFSET] = IPV6_NEXT_HEADER_ICMPV6;
    pkt[IPV6_HOP_LIMIT_OFFSET] = REPLY_HOP_LIMIT;
    pkt[IPV6_SRC].copy_from_slice(&original[IPV6_DST]);
    pkt[IPV6_DST].copy_from_slice(&original[IPV6_SRC]);

    let icmp = &mut pkt[IPV6_HEADER_LEN..];
    icmp[0] = ICMPV6_TYPE_PACKET_TOO_BIG;
    icmp[1] = 0;
    // ICMPv6 carries the MTU as a full 32-bit field, where ICMPv4 has 16 bits.
    icmp[4..8].copy_from_slice(&u32::from(mtu).to_be_bytes());
    icmp[ICMP_HEADER_LEN..].copy_from_slice(quote);

    let icmp_checksum = icmpv6_checksum(&pkt[IPV6_SRC], &pkt[IPV6_DST], &pkt[IPV6_HEADER_LEN..]);
    pkt[IPV6_HEADER_LEN + ICMP_CHECKSUM_OFFSET..IPV6_HEADER_LEN + ICMP_CHECKSUM_OFFSET + 2]
        .copy_from_slice(&icmp_checksum.to_be_bytes());

    Some(pkt)
}

/// ICMPv6 checksum: the message *plus* the IPv6 pseudo-header (RFC 4443 §2.3).
///
/// Unlike ICMPv4 this covers addresses from the enclosing header, which is what
/// stops an ICMPv6 message being replayed onto a different pair of hosts. The
/// pseudo-header is never assembled in memory — ones'-complement addition is
/// associative, so its parts are simply added to the same accumulator.
fn icmpv6_checksum(src: &[u8], dst: &[u8], message: &[u8]) -> u16 {
    let mut sum = Checksum::default();
    sum.add_bytes(src);
    sum.add_bytes(dst);
    // Upper-layer packet length, as a 32-bit field split across two words.
    let length = message.len() as u32;
    sum.add_word((length >> 16) as u16);
    sum.add_word(length as u16);
    sum.add_word(u16::from(IPV6_NEXT_HEADER_ICMPV6));
    sum.add_bytes_zeroing(message, ICMP_CHECKSUM_OFFSET);
    sum.finish()
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
        // A message carrying its own checksum sums to zero at the receiver.
        assert_eq!(checksum::of(icmp), 0, "ICMP checksum should verify to 0");
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
