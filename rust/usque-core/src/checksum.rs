//! The internet checksum (RFC 1071), as one accumulator.
//!
//! # The algebra
//!
//! The checksum is a fold over 16-bit big-endian words under *ones'-complement
//! addition*: ordinary addition with the carry folded back into the low bits.
//! That operation is associative and commutative and has `0` as its identity,
//! which is a commutative monoid — and it is the reason the same routine can
//! total an IPv4 header, an ICMP message, and an IPv6 pseudo-header spliced
//! together from three disjoint slices, in whatever order is convenient.
//!
//! Carries are folded once at the end rather than after every word, which is
//! what keeps the inner loop a plain add. Summing into a `u32` cannot overflow
//! before then: every addend is at most `0xFFFF`, so it takes 65537 words —
//! 128 KiB — to reach `u32::MAX`. The largest input here is a 64 KiB IP packet
//! plus a 36-byte pseudo-header, so the headroom is a factor of two.
//!
//! An odd trailing byte is padded with a zero low byte, per RFC 1071 §4.1.

/// Running ones'-complement total.
///
/// Construct with [`Checksum::default`] (the monoid identity), add with the
/// `add_*` methods in any order, and read the transmitted value with
/// [`Checksum::finish`].
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Checksum {
    /// Unfolded total. Carries live above bit 15 until `finish`.
    total: u32,
}

impl Checksum {
    /// Add one 16-bit word.
    pub fn add_word(&mut self, word: u16) {
        self.total += u32::from(word);
    }

    /// Add `bytes` as big-endian 16-bit words, zero-padding an odd tail.
    pub fn add_bytes(&mut self, bytes: &[u8]) {
        let mut words = bytes.chunks_exact(2);
        for word in &mut words {
            self.add_word(u16::from_be_bytes([word[0], word[1]]));
        }
        if let &[tail] = words.remainder() {
            self.add_word(u16::from_be_bytes([tail, 0]));
        }
    }

    /// Add `bytes`, treating the two-byte field at `checksum_at` as zero.
    ///
    /// Computing a checksum requires the checksum field itself to read as zero
    /// (RFC 1071 §1). Skipping the field and zeroing it are the same operation,
    /// because zero is the identity — which is why this needs no scratch copy
    /// of the header.
    pub fn add_bytes_zeroing(&mut self, bytes: &[u8], checksum_at: usize) {
        let (before, rest) = bytes.split_at(checksum_at.min(bytes.len()));
        self.add_bytes(before);
        // `add_bytes` re-aligns on every call, so the field must sit on an even
        // offset for the split to preserve word boundaries. Every header this
        // is used with satisfies that; debug builds say so if one stops.
        debug_assert_eq!(checksum_at % 2, 0, "checksum field must be word-aligned");
        self.add_bytes(rest.get(2..).unwrap_or_default());
    }

    /// Fold the carries and complement: the value that goes on the wire.
    #[must_use]
    pub fn finish(self) -> u16 {
        let mut total = self.total;
        while total >> 16 != 0 {
            total = (total & 0xFFFF) + (total >> 16);
        }
        !(total as u16)
    }
}

/// Checksum of one contiguous block, for messages with no field to exclude.
#[must_use]
pub fn of(bytes: &[u8]) -> u16 {
    let mut checksum = Checksum::default();
    checksum.add_bytes(bytes);
    checksum.finish()
}

/// Checksum of a header whose own checksum field sits at `checksum_at`.
#[must_use]
pub fn of_header(bytes: &[u8], checksum_at: usize) -> u16 {
    let mut checksum = Checksum::default();
    checksum.add_bytes_zeroing(bytes, checksum_at);
    checksum.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Summing a message that already carries its checksum yields `0`: the
    /// verification identity every receiver relies on.
    #[test]
    fn a_checksummed_block_verifies_to_zero() {
        let mut block =
            *b"\x45\x00\x00\x28\x00\x00\x00\x00\x40\x06\x00\x00\x0a\x00\x00\x01\x0a\x00\x00\x02";
        let sum = of_header(&block, 10);
        block[10..12].copy_from_slice(&sum.to_be_bytes());
        assert_eq!(of(&block), 0);
    }

    /// Ones'-complement addition is commutative and associative, so regrouping
    /// the input must not move the result. This is the law the whole module
    /// rests on.
    #[test]
    fn splitting_the_input_anywhere_gives_the_same_total() {
        let data: Vec<u8> = (0u8..64).collect();
        let whole = of(&data);
        for split in (0..data.len()).step_by(2) {
            let (a, b) = data.split_at(split);
            let mut piecewise = Checksum::default();
            piecewise.add_bytes(a);
            piecewise.add_bytes(b);
            assert_eq!(piecewise.finish(), whole, "split at {split}");
        }
    }

    /// The identity of the monoid: an empty input contributes nothing.
    #[test]
    fn empty_input_is_the_identity() {
        let data: Vec<u8> = (0u8..16).collect();
        let mut with_empty = Checksum::default();
        with_empty.add_bytes(&[]);
        with_empty.add_bytes(&data);
        with_empty.add_bytes(&[]);
        assert_eq!(with_empty.finish(), of(&data));
        assert_eq!(Checksum::default().finish(), 0xFFFF);
    }

    /// An odd tail byte is the high half of a zero-padded word, not dropped.
    #[test]
    fn odd_tail_byte_is_padded_not_dropped() {
        assert_ne!(of(&[0x01, 0x02, 0x03]), of(&[0x01, 0x02]));
        assert_eq!(of(&[0x01, 0x02, 0x03]), of(&[0x01, 0x02, 0x03, 0x00]));
    }

    /// Zeroing the field and skipping it agree, which is what lets the
    /// accumulator avoid copying the header.
    #[test]
    fn zeroing_the_field_matches_a_header_that_already_holds_zeros() {
        let mut header = [0u8; 20];
        header[0] = 0x45;
        header[8] = 64;
        header[10..12].copy_from_slice(&[0xAB, 0xCD]); // stale checksum
        let mut zeroed = header;
        zeroed[10..12].copy_from_slice(&[0, 0]);

        assert_eq!(of_header(&header, 10), of(&zeroed));
    }

    /// Carry folding must be iterated. A single fold of `0xFFFF_FFFF` yields
    /// `0x1_FFFE`, which still carries; stopping there is off by one.
    #[test]
    fn carries_fold_to_a_fixed_point() {
        assert_eq!(Checksum { total: 0xFFFF_FFFF }.finish(), !0xFFFFu16);
        assert_eq!(Checksum { total: 0x8000_8000 }.finish(), !0x0001u16);
        assert_eq!(Checksum { total: 0x0001_0000 }.finish(), !0x0001u16);
    }

    /// The documented headroom: a maximum-size IP packet of all-ones bytes is
    /// the worst realistic input and must not overflow the accumulator.
    #[test]
    fn a_maximum_size_packet_does_not_overflow() {
        let mut checksum = Checksum::default();
        checksum.add_bytes(&[0xFF; 65535]);
        // Pseudo-header material on top, as ICMPv6 adds.
        checksum.add_bytes(&[0xFF; 36]);
        assert!(checksum.total <= u32::MAX - u32::from(u16::MAX));
        let _ = checksum.finish();
    }
}
