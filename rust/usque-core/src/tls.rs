use anyhow::{Context, Result};
use p256::ecdsa::SigningKey;
use p256::pkcs8::DecodePrivateKey;
use rcgen::{CertificateParams, KeyPair};
use std::io::Write;
use std::time::Duration;
use tempfile::NamedTempFile;

use crate::TunnelIdentity;

/// Holds temporary PEM files for quiche TLS config and the pinned endpoint key.
pub struct TlsMaterial {
    pub cert_pem_file: NamedTempFile,
    pub key_pem_file: NamedTempFile,
    pub endpoint_pub_key_spki_der: Vec<u8>,
}

/// Generate self-signed client cert from the enrolled private key and prepare
/// temp PEM files that quiche can load.
///
/// MODIFIED FROM UPSTREAM: takes a [`TunnelIdentity`] instead of usque-rs's
/// file-backed `Config`, so the caller decides where the key material lives.
/// The certificate generation and pinning logic below is unchanged.
pub fn prepare_tls_material(identity: &TunnelIdentity) -> Result<TlsMaterial> {
    let priv_key_der = identity.ec_private_key_der.clone();
    let signing_key = SigningKey::from_pkcs8_der(&priv_key_der)
        .context("failed to parse ECDSA private key from config")?;

    let key_pair_pem =
        p256::pkcs8::EncodePrivateKey::to_pkcs8_pem(&signing_key, p256::pkcs8::LineEnding::LF)
            .context("failed to encode private key to PEM")?;

    let key_pair =
        KeyPair::from_pem(key_pair_pem.as_ref()).context("failed to load key pair into rcgen")?;

    let mut params = CertificateParams::new(Vec::<String>::new())
        .context("failed to create certificate params")?;
    params.not_before = time::OffsetDateTime::now_utc();
    params.not_after = time::OffsetDateTime::now_utc() + Duration::from_secs(24 * 60 * 60);

    let cert = params
        .self_signed(&key_pair)
        .context("failed to generate self-signed certificate")?;

    let cert_pem = cert.pem();

    let mut cert_file = NamedTempFile::new().context("failed to create temp cert file")?;
    cert_file.write_all(cert_pem.as_bytes())?;
    cert_file.flush()?;

    let mut key_file = NamedTempFile::new().context("failed to create temp key file")?;
    key_file.write_all(key_pair_pem.as_bytes())?;
    key_file.flush()?;

    let endpoint_pub_key_spki_der = identity.endpoint_pub_key_spki_der.clone();

    Ok(TlsMaterial {
        cert_pem_file: cert_file,
        key_pem_file: key_file,
        endpoint_pub_key_spki_der,
    })
}

/// Verify a peer's DER certificate against the pinned SPKI public key.
/// Returns true if the peer cert's `SubjectPublicKeyInfo` matches.
pub fn verify_endpoint_key(peer_cert_der: &[u8], expected_spki_der: &[u8]) -> bool {
    let Ok((_, cert)) = x509_parser::parse_x509_certificate(peer_cert_der) else {
        log::warn!("failed to parse peer certificate for key pinning");
        return false;
    };
    cert.tbs_certificate.subject_pki.raw == expected_spki_der
}
