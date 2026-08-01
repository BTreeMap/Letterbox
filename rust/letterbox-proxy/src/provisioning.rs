//! Cloudflare WARP provisioning: creates and upgrades a device's WARP identity.
//!
//! The WARP client API is accessed at `api.cloudflareclient.com`, the same
//! endpoint used by the official WARP client and wgcf.

use crate::config::{
    MasqueCredentials, WarpAccountData, WarpConfig, WarpInterfaceConfig, WarpPeerConfig,
};
use crate::error::ProxyError;
use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use chrono::Utc;
use rand::Rng;
use rustls::{ClientConfig, RootCertStore};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

/// Cloudflare WARP API version.
const API_VERSION: &str = "v0a884";

/// Base URL for the WARP API.
const API_BASE: &str = "https://api.cloudflareclient.com";

/// WireGuard endpoint port assumed when the API returns a bare host.
const DEFAULT_ENDPOINT_PORT: u16 = 2408;

/// Account tier reported when the API omits one.
const DEFAULT_ACCOUNT_TYPE: &str = "free";

/// Split `host:port` into its parts, falling back to the well-known port.
///
/// `rsplit_once` rather than `split_once`, so an IPv6 literal's inner colons
/// stay with the host.
fn split_endpoint(endpoint: &str) -> (&str, u16) {
    endpoint
        .rsplit_once(':')
        .and_then(|(host, port)| Some((host, port.parse().ok()?)))
        .unwrap_or((endpoint, DEFAULT_ENDPOINT_PORT))
}

/// Take the peer Cloudflare assigned, or say which response had none.
///
/// The API returns a list but assigns exactly one; an empty list is a protocol
/// violation, not an empty result to carry on with.
fn first_peer(peers: Vec<PeerData>, what: &str) -> Result<PeerData, ProxyError> {
    peers
        .into_iter()
        .next()
        .ok_or_else(|| ProxyError::ProvisioningFailed {
            details: format!("No peers in {what}"),
        })
}

/// Flatten an error and its `source()` chain into a single string.
///
/// `reqwest` nests the real cause (DNS, connect, TLS handshake, ...) behind a
/// generic outer message, so the verifier-fault marker we look for in
/// [`crate::selftest`] only appears once the whole chain is walked.
fn error_chain(err: &(dyn std::error::Error + 'static)) -> String {
    let mut msg = err.to_string();
    let mut source = err.source();
    while let Some(cause) = source {
        msg.push_str(": ");
        msg.push_str(&cause.to_string());
        source = cause.source();
    }
    msg
}

/// Require a 2xx, turning any other status into an error carrying the body.
///
/// Every call below shares this shape — check the status, otherwise read the
/// body for the reason — and the body must be consumed *after* the status is
/// read, since reading it moves the response. Naming the sequence once is what
/// keeps that ordering from being re-derived at four call sites.
async fn expect_success(
    what: &str,
    response: reqwest::Response,
) -> Result<reqwest::Response, ProxyError> {
    let status = response.status();
    if status.is_success() {
        return Ok(response);
    }
    let body = response.text().await.unwrap_or_default();
    Err(ProxyError::ProvisioningFailed {
        details: format!("{what} failed with status {status}: {body}"),
    })
}

/// Decode a JSON body, naming the request that produced it.
async fn decode_json<T: serde::de::DeserializeOwned>(
    what: &str,
    response: reqwest::Response,
) -> Result<T, ProxyError> {
    response
        .json()
        .await
        .map_err(|e| ProxyError::ProvisioningFailed {
            details: format!("Failed to parse {what} response: {e}"),
        })
}

/// Default headers for API requests.
fn default_headers() -> reqwest::header::HeaderMap {
    let mut headers = reqwest::header::HeaderMap::new();
    // Note: we deliberately do NOT advertise `Accept-Encoding: gzip`. reqwest is
    // built without the `gzip` feature, so it cannot transparently decompress a
    // gzipped body. Cloudflare honours that header and returns a gzip-compressed
    // payload, which then fails JSON decoding ("error decoding response body").
    // Omitting it makes Cloudflare return plain JSON that reqwest can parse.
    headers.insert(
        reqwest::header::USER_AGENT,
        "okhttp/3.12.1".parse().unwrap(),
    );
    headers
}

/// Build the rustls client configuration used by the provisioning HTTP client.
///
/// reqwest's `rustls` feature defaults to `rustls-platform-verifier`, which on
/// Android panics ("Expect rustls-platform-verifier to be initialized") unless
/// the app first hands the crate a JNI handle to the Android trust manager. We
/// avoid that platform coupling entirely by handing reqwest a preconfigured
/// config that trusts the bundled `webpki-roots` anchors and pins the `ring`
/// crypto provider — the same trust model already used for in-tunnel TLS in
/// [`crate::tunnel::tls`]. Cloudflare's WARP API uses a public CA, so the static
/// Mozilla root set is sufficient and needs no OS integration.
fn provisioning_tls_config() -> ClientConfig {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    let mut config =
        ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_safe_default_protocol_versions()
            .expect("ring provider supports the default protocol versions")
            .with_root_certificates(roots)
            .with_no_client_auth();

    // reqwest is built without the `http2` feature, so the connection can only
    // be driven as HTTP/1.1. Advertise exactly that via ALPN so the server
    // never negotiates a protocol the client cannot speak.
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    config
}

/// Registration request sent to Cloudflare.
#[derive(Debug, Serialize)]
struct RegistrationRequest {
    install_id: String,
    tos: String,
    key: String,
    fcm_token: String,
    #[serde(rename = "type")]
    device_type: String,
    model: String,
    locale: String,
}

/// Registration response from Cloudflare.
#[derive(Debug, Deserialize)]
struct RegistrationResponse {
    id: String,
    token: String,
    account: AccountInfo,
}

/// Account information in registration response.
#[derive(Debug, Deserialize)]
struct AccountInfo {
    license: String,
}

/// Configuration response from Cloudflare.
#[derive(Debug, Deserialize)]
struct ConfigResponse {
    config: ConfigData,
    warp_enabled: bool,
    #[serde(default)]
    account: Option<AccountDetails>,
}

/// Configuration data in config response.
#[derive(Debug, Deserialize)]
struct ConfigData {
    interface: InterfaceData,
    peers: Vec<PeerData>,
}

/// Interface configuration data.
#[derive(Debug, Deserialize)]
struct InterfaceData {
    addresses: AddressData,
}

/// Address data in interface configuration.
#[derive(Debug, Deserialize)]
struct AddressData {
    v4: String,
}

/// Peer configuration data.
#[derive(Debug, Deserialize)]
struct PeerData {
    public_key: String,
    endpoint: EndpointData,
}

/// Endpoint data in peer configuration.
#[derive(Debug, Deserialize)]
struct EndpointData {
    host: String,
    v4: String,
}

/// Account details in config response.
#[derive(Debug, Deserialize)]
#[allow(dead_code)]
struct AccountDetails {
    #[serde(default)]
    account_type: String,
    #[serde(default)]
    warp_plus: bool,
    license: String,
}

/// WARP provisioner that handles account creation and configuration.
pub struct WarpProvisioner {
    client: reqwest::Client,
}

impl WarpProvisioner {
    /// Create a new WARP provisioner.
    pub fn new() -> Result<Self, ProxyError> {
        let client = reqwest::Client::builder()
            .use_preconfigured_tls(provisioning_tls_config())
            .default_headers(default_headers())
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .map_err(|e| ProxyError::ProvisioningFailed {
                details: format!("Failed to create HTTP client: {}", e),
            })?;

        Ok(Self { client })
    }

    /// Mint the throwaway key that `POST /reg` requires.
    ///
    /// Cloudflare's registration endpoint is WireGuard-shaped: it will not mint
    /// a device without a `key` field holding 32 bytes. Nothing ever uses that
    /// key — the MASQUE session authenticates with the P-256 key enrolled
    /// afterwards by [`Self::enroll_masque_key`] — so this is 32 random bytes,
    /// not a keypair. No curve arithmetic is involved, which is what let the
    /// `x25519-dalek` and `curve25519-dalek` dependencies go with WireGuard.
    ///
    /// The reference implementation does the same thing, for the same reason.
    pub fn generate_registration_key() -> String {
        let mut rng = rand::rng();
        let mut key = [0u8; 32];
        rng.fill_bytes(&mut key);
        BASE64.encode(key)
    }

    /// Get the current timestamp in the format expected by Cloudflare.
    fn get_timestamp() -> String {
        Utc::now().format("%Y-%m-%dT%H:%M:%S%.3f%:z").to_string()
    }

    /// Probe the WARP API host over TLS without mutating any Cloudflare state.
    ///
    /// This drives the **exact same** `reqwest` client (and therefore the same
    /// certificate-verifier configuration) used for real provisioning, but it
    /// only issues a `GET` and never registers a device. A response with *any*
    /// HTTP status means the TLS handshake and certificate verification both
    /// succeeded, which is all this probe asserts. Transport failures are
    /// returned with their full `source()` chain so a benign network error can
    /// be told apart from a certificate-verifier fault (see [`crate::selftest`]).
    pub async fn tls_self_test(&self) -> Result<(), ProxyError> {
        let url = format!("{}/{}/", API_BASE, API_VERSION);
        self.client
            .get(&url)
            .send()
            .await
            .map(|_| ())
            .map_err(|e| ProxyError::ProvisioningFailed {
                details: error_chain(&e),
            })
    }

    /// Register a new WARP account.
    ///
    /// This creates a new device identity with Cloudflare.
    /// The private key must be generated beforehand and only the public key
    /// is sent to Cloudflare.
    pub async fn register(&self, public_key: &str) -> Result<WarpAccountData, ProxyError> {
        let url = format!("{}/{}/reg", API_BASE, API_VERSION);

        let request = RegistrationRequest {
            install_id: String::new(),
            tos: Self::get_timestamp(),
            key: public_key.to_string(),
            fcm_token: String::new(),
            device_type: "Android".to_string(),
            model: "Letterbox".to_string(),
            locale: "en_US".to_string(),
        };

        let response = self
            .client
            .post(&url)
            .json(&request)
            .send()
            .await
            .map_err(|e| ProxyError::ProvisioningFailed {
                details: format!("Registration request failed: {e}"),
            })?;

        let reg_response: RegistrationResponse = decode_json(
            "registration",
            expect_success("Registration", response).await?,
        )
        .await?;

        // Note: private_key will be filled in by the caller
        Ok(WarpAccountData {
            account_id: reg_response.id,
            access_token: reg_response.token,
            private_key: String::new(), // Caller must set this
            license_key: reg_response.account.license,
        })
    }

    /// Fetch the tunnel configuration for an existing account.
    pub async fn fetch_config(&self, account: &WarpAccountData) -> Result<WarpConfig, ProxyError> {
        let url = format!("{}/{}/reg/{}", API_BASE, API_VERSION, account.account_id);

        let response = self
            .client
            .get(&url)
            .header("Authorization", format!("Bearer {}", account.access_token))
            .send()
            .await
            .map_err(|e| ProxyError::ProvisioningFailed {
                details: format!("Config fetch failed: {e}"),
            })?;

        let config_response: ConfigResponse =
            decode_json("config", expect_success("Config fetch", response).await?).await?;

        // Extract peer configuration (use first peer)
        let peer = first_peer(config_response.config.peers, "configuration")?;
        let (endpoint_host, endpoint_port) = split_endpoint(&peer.endpoint.host);
        // The API returns the literal with a port attached too — `162.159.192.6:0`
        // in practice — so it needs the same treatment as the host. Stored raw it
        // reappears wherever the address is rendered with a port, as
        // `162.159.192.6:0:2408`.
        let (endpoint_ipv4, _) = split_endpoint(&peer.endpoint.v4);

        let account_type = config_response
            .account
            .map_or_else(|| DEFAULT_ACCOUNT_TYPE.to_string(), |a| a.account_type);

        Ok(WarpConfig {
            account: account.clone(),
            peer: WarpPeerConfig {
                public_key: peer.public_key,
                endpoint_host: endpoint_host.to_string(),
                endpoint_ipv4: endpoint_ipv4.to_string(),
                endpoint_port,
            },
            interface: WarpInterfaceConfig {
                address_ipv4: config_response.config.interface.addresses.v4,
            },
            warp_enabled: config_response.warp_enabled,
            account_type,
            last_updated: Utc::now().timestamp(),
            // Enrolled separately by `enroll_masque_key`: fetching a config is a
            // read, and minting a key is not.
            masque: None,
        })
    }

    /// Generate a P-256 keypair for MASQUE.
    ///
    /// Returns `(pkcs8_der, spki_der)` — the private key in PKCS#8 and the
    /// public key in `SubjectPublicKeyInfo`. Both are DER but they are not
    /// interchangeable, which is why [`MasqueCredentials`] keeps them in
    /// separately named fields rather than a pair.
    ///
    /// # Errors
    ///
    /// Returns [`ProxyError::CryptoError`] if encoding fails.
    pub fn generate_masque_keypair() -> Result<(Vec<u8>, Vec<u8>), ProxyError> {
        use p256::ecdsa::SigningKey;
        use p256::elliptic_curve::Generate;
        use p256::pkcs8::{EncodePrivateKey, EncodePublicKey};

        // `try_generate` rather than `generate`: drawing from the system RNG can
        // fail, and this function already returns a `Result`, so the failure has
        // somewhere to go without a panic. p256 0.13 made the caller thread an
        // RNG in; 0.14 asks the system itself, so there is no longer a
        // randomness source to pass around or to get wrong.
        let signing_key = SigningKey::try_generate().map_err(|e| ProxyError::CryptoError {
            details: format!("System RNG unavailable for MASQUE key generation: {e}"),
        })?;

        let private_der = signing_key
            .to_pkcs8_der()
            .map_err(|e| ProxyError::CryptoError {
                details: format!("Failed to encode MASQUE private key: {e}"),
            })?
            .as_bytes()
            .to_vec();

        let public_der = signing_key
            .verifying_key()
            .to_public_key_der()
            .map_err(|e| ProxyError::CryptoError {
                details: format!("Failed to encode MASQUE public key: {e}"),
            })?
            .as_bytes()
            .to_vec();

        Ok((private_der, public_der))
    }

    /// Enrol a MASQUE key on an existing device.
    ///
    /// Registration mints a throwaway X25519 key purely to obtain a device
    /// identity; this PATCH replaces it with the P-256 key the MASQUE session
    /// authenticates with, and asks the account to be switched to the `masque`
    /// tunnel type.
    ///
    /// The returned credentials pair the new private key with the endpoint's
    /// public key taken from the PATCH response, so the two always come from the
    /// same enrolment and cannot drift apart.
    ///
    /// # Errors
    ///
    /// Returns [`ProxyError::ProvisioningFailed`] if the API rejects the request
    /// or returns no usable peer key.
    pub async fn enroll_masque_key(
        &self,
        account: &WarpAccountData,
    ) -> Result<MasqueCredentials, ProxyError> {
        use base64::{engine::general_purpose::STANDARD as BASE64, Engine};

        let (private_der, public_der) = Self::generate_masque_keypair()?;

        #[derive(Serialize)]
        struct MasqueEnrollment {
            key: String,
            key_type: String,
            tunnel_type: String,
        }

        let url = format!("{}/{}/reg/{}", API_BASE, API_VERSION, account.account_id);
        let response = self
            .client
            .patch(&url)
            .header("Authorization", format!("Bearer {}", account.access_token))
            .json(&MasqueEnrollment {
                key: BASE64.encode(&public_der),
                key_type: "secp256r1".to_string(),
                tunnel_type: "masque".to_string(),
            })
            .send()
            .await
            .map_err(|e| ProxyError::ProvisioningFailed {
                details: format!("MASQUE enrolment failed: {e}"),
            })?;

        let enrolled: ConfigResponse = decode_json(
            "MASQUE enrolment",
            expect_success("MASQUE enrolment", response).await?,
        )
        .await?;

        let peer = first_peer(enrolled.config.peers, "the MASQUE enrolment response")?;

        Ok(MasqueCredentials {
            ec_private_key_der: BASE64.encode(&private_der),
            endpoint_pub_key_spki: BASE64.encode(decode_peer_key(&peer.public_key)?),
        })
    }

    /// Enable WARP on the account.
    pub async fn enable_warp(&self, account: &WarpAccountData) -> Result<(), ProxyError> {
        let url = format!("{}/{}/reg/{}", API_BASE, API_VERSION, account.account_id);

        #[derive(Serialize)]
        struct EnableRequest {
            warp_enabled: bool,
        }

        let response = self
            .client
            .patch(&url)
            .header("Authorization", format!("Bearer {}", account.access_token))
            .json(&EnableRequest { warp_enabled: true })
            .send()
            .await
            .map_err(|e| ProxyError::ProvisioningFailed {
                details: format!("Enable WARP request failed: {e}"),
            })?;

        expect_success("Enable WARP", response).await.map(|_| ())
    }

    /// Delete a WARP device registration.
    ///
    /// Used to clean up ephemeral accounts (e.g. created by integration tests)
    /// so they do not accumulate on Cloudflare's side.
    pub async fn delete_device(&self, account: &WarpAccountData) -> Result<(), ProxyError> {
        let url = format!("{}/{}/reg/{}", API_BASE, API_VERSION, account.account_id);

        let response = self
            .client
            .delete(&url)
            .header("Authorization", format!("Bearer {}", account.access_token))
            .send()
            .await
            .map_err(|e| ProxyError::ProvisioningFailed {
                details: format!("Delete device request failed: {e}"),
            })?;

        expect_success("Delete device", response).await.map(|_| ())
    }

    /// Provision a new WARP account from scratch.
    ///
    /// This performs the complete provisioning flow:
    /// 1. Generate a WireGuard keypair
    /// 2. Register with Cloudflare
    /// 3. Fetch the tunnel configuration
    /// 4. Enable WARP if needed
    pub async fn provision_new_account(&self) -> Result<WarpConfig, ProxyError> {
        // Step 1: Mint the throwaway key registration insists on
        let registration_key = Self::generate_registration_key();

        // Step 2: Register
        let mut account = self.register(&registration_key).await?;
        account.private_key = registration_key;

        // Step 3: Fetch configuration. `fetch_config` clones `account` verbatim,
        // so the key set above is already carried through.
        let mut config = self.fetch_config(&account).await?;

        // Step 4: Enable WARP if not enabled
        if !config.warp_enabled {
            self.enable_warp(&account).await?;
            config.warp_enabled = true;
        }

        // Step 5: Enrol a MASQUE key.
        //
        // Best-effort by design. A device that cannot enrol still has a working
        // WireGuard configuration, and refusing to provision at all would turn a
        // transport preference into a hard failure of the whole proxy.
        match self.enroll_masque_key(&account).await {
            Ok(credentials) => config.masque = Some(credentials),
            Err(e) => log::warn!("MASQUE enrolment failed, continuing on WireGuard: {e}"),
        }

        Ok(config)
    }
}

/// Decode the endpoint public key Cloudflare returns into SPKI DER.
///
/// The API is inconsistent about framing: a MASQUE peer key comes back as PEM,
/// while the WireGuard field is bare base64. Accepting both here keeps the
/// caller from having to guess, and normalises to the one representation
/// [`usque_core::TunnelIdentity`] accepts.
fn decode_peer_key(encoded: &str) -> Result<Vec<u8>, ProxyError> {
    use base64::{engine::general_purpose::STANDARD as BASE64, Engine};

    let body: String = if encoded.contains("BEGIN") {
        encoded
            .lines()
            .filter(|line| !line.starts_with("-----"))
            .collect()
    } else {
        encoded.to_string()
    };

    BASE64
        .decode(body.trim())
        .map_err(|e| ProxyError::CryptoError {
            details: format!("Invalid endpoint public key: {e}"),
        })
}

// No `Default`: building the HTTP client can fail, and a `Default` that panics
// is a trap for the one caller who reaches for it out of habit. `new` returns
// the `Result` the operation actually has.

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn registration_key_is_32_random_base64_bytes() {
        let key = WarpProvisioner::generate_registration_key();

        assert!(BASE64.decode(&key).is_ok(), "must be base64");
        assert_eq!(
            BASE64.decode(&key).unwrap().len(),
            32,
            "registration rejects anything but 32 bytes"
        );
        assert_ne!(
            key,
            WarpProvisioner::generate_registration_key(),
            "each device must register a distinct key"
        );
    }

    #[test]
    fn masque_keypair_is_der_and_distinct_per_call() {
        let (private_der, public_der) =
            WarpProvisioner::generate_masque_keypair().expect("generate");

        // PKCS#8 and SPKI both start with a SEQUENCE tag; more importantly they
        // must not be the same blob, which is the mistake TunnelIdentity's two
        // separately named fields exist to prevent.
        assert_eq!(private_der[0], 0x30, "PKCS#8 DER should be a SEQUENCE");
        assert_eq!(public_der[0], 0x30, "SPKI DER should be a SEQUENCE");
        assert_ne!(private_der, public_der);

        let (second, _) = WarpProvisioner::generate_masque_keypair().expect("generate");
        assert_ne!(private_der, second, "keys must not repeat");
    }

    #[test]
    fn test_get_timestamp() {
        let ts = WarpProvisioner::get_timestamp();

        // Should be in ISO 8601 format
        assert!(ts.contains('T'));
        assert!(ts.len() > 20);

        // Should contain current year
        let year = chrono::Utc::now().format("%Y").to_string();
        assert!(ts.contains(&year));
    }

    #[test]
    fn test_registration_request_serialization() {
        let request = RegistrationRequest {
            install_id: String::new(),
            tos: "2024-01-01T00:00:00.000+00:00".to_string(),
            key: "test-public-key".to_string(),
            fcm_token: String::new(),
            device_type: "Android".to_string(),
            model: "Test".to_string(),
            locale: "en_US".to_string(),
        };

        let json = serde_json::to_string(&request).unwrap();
        assert!(json.contains("\"type\":\"Android\""));
        assert!(json.contains("\"key\":\"test-public-key\""));
    }

    #[test]
    fn test_config_response_deserialization() {
        let json = r#"{
            "config": {
                "interface": {
                    "addresses": {
                        "v4": "172.16.0.2/32",
                        "v6": "fd01:db8:1111:2222::2/128"
                    }
                },
                "peers": [{
                    "public_key": "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
                    "endpoint": {
                        "host": "engage.cloudflareclient.com:2408",
                        "v4": "162.159.192.1",
                        "v6": "2606:4700:d0::a29f:c001"
                    }
                }]
            },
            "warp_enabled": true,
            "account": {
                "account_type": "free",
                "warp_plus": false,
                "license": "test-license"
            }
        }"#;

        let response: ConfigResponse = serde_json::from_str(json).unwrap();
        assert!(response.warp_enabled);
        assert_eq!(response.config.peers.len(), 1);
        assert!(response.config.peers[0]
            .endpoint
            .host
            .contains("cloudflareclient.com"));
    }

    #[test]
    fn test_endpoint_parsing() {
        assert_eq!(
            split_endpoint("engage.cloudflareclient.com:2408"),
            ("engage.cloudflareclient.com", 2408)
        );
    }

    /// A host without a port, or with an unparseable one, must keep the whole
    /// host and fall back — never truncate the host at the colon.
    #[test]
    fn endpoint_without_a_usable_port_keeps_the_whole_host() {
        assert_eq!(
            split_endpoint("engage.cloudflareclient.com"),
            ("engage.cloudflareclient.com", DEFAULT_ENDPOINT_PORT)
        );
        assert_eq!(
            split_endpoint("host:not-a-port"),
            ("host:not-a-port", DEFAULT_ENDPOINT_PORT)
        );
        assert_eq!(
            split_endpoint("host:99999"),
            ("host:99999", DEFAULT_ENDPOINT_PORT)
        );
    }

    #[test]
    fn first_peer_names_the_response_that_had_none() {
        let err = first_peer(Vec::new(), "the MASQUE enrolment response")
            .expect_err("empty peer list is a protocol violation");
        assert!(err.to_string().contains("MASQUE enrolment response"));
    }
}
