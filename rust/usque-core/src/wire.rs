//! IP header layout shared by the packet and ICMP paths.
//!
//! Both modules previously carried their own copies of these offsets and their
//! own `buf[0] >> 4` dispatch. Two transcriptions of the same RFC are two
//! chances to mistype an offset, and the mistake shows up as a checksum that
//! fails on a real network rather than as anything a test would catch.

/// Length of an IPv4 header with no options.
pub const IPV4_HEADER_LEN: usize = 20;

/// Byte offset of the IPv4 TTL field.
pub const IPV4_TTL_OFFSET: usize = 8;

/// Byte offset of the IPv4 header checksum field.
pub const IPV4_CHECKSUM_OFFSET: usize = 10;

/// Byte range of the IPv4 source address.
pub const IPV4_SRC: std::ops::Range<usize> = 12..16;

/// Byte range of the IPv4 destination address.
pub const IPV4_DST: std::ops::Range<usize> = 16..20;

/// Length of the fixed IPv6 header.
pub const IPV6_HEADER_LEN: usize = 40;

/// Byte range of the IPv6 payload-length field.
pub const IPV6_PAYLOAD_LEN: std::ops::Range<usize> = 4..6;

/// Byte offset of the IPv6 next-header field.
pub const IPV6_NEXT_HEADER_OFFSET: usize = 6;

/// Byte offset of the IPv6 hop-limit field.
pub const IPV6_HOP_LIMIT_OFFSET: usize = 7;

/// Byte range of the IPv6 source address.
pub const IPV6_SRC: std::ops::Range<usize> = 8..24;

/// Byte range of the IPv6 destination address.
pub const IPV6_DST: std::ops::Range<usize> = 24..40;

/// Length of an ICMP/ICMPv6 header.
pub const ICMP_HEADER_LEN: usize = 8;

/// The two IP versions this tunnel carries.
///
/// A closed sum rather than a `u8`: every match on it is exhaustive, so adding
/// a version would be a compile error at each site that must handle it instead
/// of a silent fallthrough. Values outside the set are not *this* type — they
/// are rejected at [`ip_version`], which is where untrusted bytes stop.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IpVersion {
    V4,
    V6,
}

impl IpVersion {
    /// The number as it appears on the wire and in error messages.
    #[must_use]
    pub fn number(self) -> u8 {
        match self {
            Self::V4 => 4,
            Self::V6 => 6,
        }
    }
}

/// Read the version nibble, if the buffer has one and it names a version we
/// carry.
///
/// Total, where indexing byte 0 directly is not: an empty buffer is a real
/// input here, since it is whatever arrived off the tunnel.
#[must_use]
pub fn ip_version(buf: &[u8]) -> Option<IpVersion> {
    match buf.first()? >> 4 {
        4 => Some(IpVersion::V4),
        6 => Some(IpVersion::V6),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_both_versions() {
        assert_eq!(ip_version(&[0x45]), Some(IpVersion::V4));
        assert_eq!(ip_version(&[0x60]), Some(IpVersion::V6));
    }

    /// An empty buffer must be `None`, not a panic: it is ordinary input from
    /// an untrusted peer.
    #[test]
    fn rejects_empty_and_unknown_versions() {
        assert_eq!(ip_version(&[]), None);
        for nibble in [0u8, 1, 2, 3, 5, 7, 8, 15] {
            assert_eq!(ip_version(&[nibble << 4]), None, "version {nibble}");
        }
    }

    /// The offsets must agree with the header lengths they index into.
    ///
    /// `const` blocks, so a mistyped offset fails to compile rather than
    /// waiting for the test binary to run.
    #[test]
    fn offsets_lie_inside_their_headers() {
        const { assert!(IPV4_TTL_OFFSET < IPV4_HEADER_LEN) };
        const { assert!(IPV4_CHECKSUM_OFFSET + 2 <= IPV4_HEADER_LEN) };
        const { assert!(IPV4_DST.end == IPV4_HEADER_LEN) };
        const { assert!(IPV6_HOP_LIMIT_OFFSET < IPV6_HEADER_LEN) };
        const { assert!(IPV6_DST.end == IPV6_HEADER_LEN) };
        // The checksum accumulator splits on this offset, so it must be even.
        const { assert!(IPV4_CHECKSUM_OFFSET.is_multiple_of(2)) };
    }
}
