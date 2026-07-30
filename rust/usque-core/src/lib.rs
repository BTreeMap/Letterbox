//! Cloudflare MASQUE (CONNECT-IP over HTTP/3) client core.
//!
//! Derived from [usque-rs](https://github.com/Diniboy1123/usque-rs) at commit
//! `1b808bb`, MIT licensed. See `PROVENANCE.md` for the exact modifications and
//! `LICENSE.md` for the upstream terms.
//!
//! # What this crate is
//!
//! The protocol half of a WARP MASQUE tunnel: QUIC handshake with a self-signed
//! client certificate, endpoint pinning by SPKI, the extended-CONNECT exchange
//! that opens a `cf-connect-ip` flow, and the datagram loop that carries IP
//! packets in both directions.
//!
//! # What this crate is not
//!
//! It does not own a network interface. Upstream is a Linux daemon that binds a
//! TUN device; that layer is removed here, because Android cannot open one
//! without `VpnService` and Letterbox deliberately keeps its stack in userspace.
//! [`tunnel::run_tunnel_session`] instead takes any packet-framed reader/writer
//! pair, which is what lets the same session loop drive a `smoltcp` interface.
//!
//! Registration is also out of scope: the caller enrols the device and supplies
//! the resulting [`TunnelIdentity`].

#![forbid(unsafe_code)]

pub mod checksum;
pub mod icmp;
pub mod packet;
pub mod tls;
pub mod tunnel;
pub mod wire;

pub use portable_atomic::{AtomicBool, Ordering};
pub use tunnel::{run_tunnel_session, Stats, StatsSnapshot, TunnelConfig};

/// The key material one MASQUE session needs.
///
/// Both fields are DER, and which DER matters — they are different encodings for
/// different roles, and swapping them fails deep inside TLS rather than at the
/// boundary. Construct through [`TunnelIdentity::new`] so the pair is named once
/// and carried together thereafter.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TunnelIdentity {
    /// This device's enrolled P-256 private key, PKCS#8 DER.
    ///
    /// A self-signed certificate wrapping it is presented to the endpoint; that
    /// is how Cloudflare recognises the device, so it must be the key that was
    /// PATCHed during enrolment, not the throwaway X25519 key from registration.
    pub ec_private_key_der: Vec<u8>,

    /// The endpoint's public key, `SubjectPublicKeyInfo` DER.
    ///
    /// The session pins the peer against this and ignores the certificate's
    /// name, which is what makes the SNI a free choice rather than a constraint.
    pub endpoint_pub_key_spki_der: Vec<u8>,
}

impl TunnelIdentity {
    /// Build an identity from its two DER blobs.
    ///
    /// Rejects empty inputs, the one failure this constructor can detect
    /// cheaply. Well-formedness is not checked here: parsing happens in
    /// [`tls::prepare_tls_material`], which reports a precise error.
    ///
    /// # Errors
    ///
    /// Returns [`IdentityError`] if either blob is empty.
    pub fn new(
        ec_private_key_der: Vec<u8>,
        endpoint_pub_key_spki_der: Vec<u8>,
    ) -> Result<Self, IdentityError> {
        if ec_private_key_der.is_empty() {
            return Err(IdentityError::MissingPrivateKey);
        }
        if endpoint_pub_key_spki_der.is_empty() {
            return Err(IdentityError::MissingEndpointKey);
        }
        Ok(Self {
            ec_private_key_der,
            endpoint_pub_key_spki_der,
        })
    }
}

/// Why a [`TunnelIdentity`] could not be built.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum IdentityError {
    #[error("device private key is empty")]
    MissingPrivateKey,
    #[error("endpoint public key is empty")]
    MissingEndpointKey,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_requires_both_keys() {
        assert_eq!(
            TunnelIdentity::new(Vec::new(), vec![1, 2, 3]),
            Err(IdentityError::MissingPrivateKey)
        );
        assert_eq!(
            TunnelIdentity::new(vec![1, 2, 3], Vec::new()),
            Err(IdentityError::MissingEndpointKey)
        );
    }

    #[test]
    fn identity_keeps_the_two_blobs_distinct() {
        let identity = TunnelIdentity::new(vec![1, 2, 3], vec![4, 5, 6]).expect("valid identity");

        assert_eq!(identity.ec_private_key_der, vec![1, 2, 3]);
        assert_eq!(identity.endpoint_pub_key_spki_der, vec![4, 5, 6]);
    }
}
