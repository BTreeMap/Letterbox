//! Cloudflare WARP provisioning module.
//!
//! This module handles the creation and management of WARP identities:
//! - Generating WireGuard keypairs
//! - Registering with Cloudflare's API
//! - Fetching tunnel configuration
//! - Enabling/disabling WARP
//!
//! ## API Reference
//!
//! The WARP client API is accessed at `api.cloudflareclient.com`.
//! This is the same API used by the official WARP client and wgcf.

use crate::config::{WarpAccountData, WarpConfig, WarpInterfaceConfig, WarpPeerConfig};
use crate::error::ProxyError;
use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use chrono::Utc;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use x25519_dalek::{PublicKey, StaticSecret};

/// Cloudflare WARP API version.
const API_VERSION: &str = "v0a884";

/// Base URL for the WARP API.
const API_BASE: &str = "https://api.cloudflareclient.com";

/// Default headers for API requests.
fn default_headers() -> reqwest::header::HeaderMap {
    let mut headers = reqwest::header::HeaderMap::new();
    headers.insert(reqwest::header::ACCEPT_ENCODING, "gzip".parse().unwrap());
    headers.insert(
        reqwest::header::USER_AGENT,
        "okhttp/3.12.1".parse().unwrap(),
    );
    headers
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
    v6: String,
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
    v6: String,
}

/// Account details in config response.
#[derive(Debug, Deserialize)]
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
            .default_headers(default_headers())
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .map_err(|e| ProxyError::ProvisioningFailed {
                details: format!("Failed to create HTTP client: {}", e),
            })?;

        Ok(Self { client })
    }

    /// Generate a new WireGuard keypair.
    ///
    /// Returns (private_key_base64, public_key_base64).
    pub fn generate_keypair() -> (String, String) {
        let mut rng = rand::thread_rng();
        let mut private_key_bytes = [0u8; 32];
        rng.fill_bytes(&mut private_key_bytes);

        let secret = StaticSecret::from(private_key_bytes);
        let public = PublicKey::from(&secret);

        let private_b64 = BASE64.encode(secret.as_bytes());
        let public_b64 = BASE64.encode(public.as_bytes());

        (private_b64, public_b64)
    }

    /// Get the current timestamp in the format expected by Cloudflare.
    fn get_timestamp() -> String {
        Utc::now().format("%Y-%m-%dT%H:%M:%S%.3f%:z").to_string()
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
                details: format!("Registration request failed: {}", e),
            })?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await.unwrap_or_default();
            return Err(ProxyError::ProvisioningFailed {
                details: format!("Registration failed with status {}: {}", status, body),
            });
        }

        let reg_response: RegistrationResponse =
            response
                .json()
                .await
                .map_err(|e| ProxyError::ProvisioningFailed {
                    details: format!("Failed to parse registration response: {}", e),
                })?;

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
                details: format!("Config fetch failed: {}", e),
            })?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await.unwrap_or_default();
            return Err(ProxyError::ProvisioningFailed {
                details: format!("Config fetch failed with status {}: {}", status, body),
            });
        }

        let config_response: ConfigResponse =
            response
                .json()
                .await
                .map_err(|e| ProxyError::ProvisioningFailed {
                    details: format!("Failed to parse config response: {}", e),
                })?;

        // Extract peer configuration (use first peer)
        let peer = config_response
            .config
            .peers
            .into_iter()
            .next()
            .ok_or_else(|| ProxyError::ProvisioningFailed {
                details: "No peers in configuration".to_string(),
            })?;

        // Parse endpoint port from host (format: "host:port")
        let (endpoint_host, endpoint_port) = peer
            .endpoint
            .host
            .rsplit_once(':')
            .map(|(h, p)| (h.to_string(), p.parse().unwrap_or(2408)))
            .unwrap_or((peer.endpoint.host.clone(), 2408));

        let account_type = config_response
            .account
            .as_ref()
            .map(|a| a.account_type.clone())
            .unwrap_or_else(|| "free".to_string());

        Ok(WarpConfig {
            account: account.clone(),
            peer: WarpPeerConfig {
                public_key: peer.public_key,
                endpoint_host,
                endpoint_ipv4: peer.endpoint.v4,
                endpoint_ipv6: peer.endpoint.v6,
                endpoint_port,
            },
            interface: WarpInterfaceConfig {
                address_ipv4: config_response.config.interface.addresses.v4,
                address_ipv6: config_response.config.interface.addresses.v6,
            },
            warp_enabled: config_response.warp_enabled,
            account_type,
            last_updated: Utc::now().timestamp(),
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
                details: format!("Enable WARP request failed: {}", e),
            })?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await.unwrap_or_default();
            return Err(ProxyError::ProvisioningFailed {
                details: format!("Enable WARP failed with status {}: {}", status, body),
            });
        }

        Ok(())
    }

    /// Provision a new WARP account from scratch.
    ///
    /// This performs the complete provisioning flow:
    /// 1. Generate a WireGuard keypair
    /// 2. Register with Cloudflare
    /// 3. Fetch the tunnel configuration
    /// 4. Enable WARP if needed
    pub async fn provision_new_account(&self) -> Result<WarpConfig, ProxyError> {
        // Step 1: Generate keypair
        let (private_key, public_key) = Self::generate_keypair();

        // Step 2: Register
        let mut account = self.register(&public_key).await?;
        account.private_key = private_key;

        // Step 3: Fetch configuration
        let mut config = self.fetch_config(&account).await?;
        config.account.private_key = account.private_key.clone();

        // Step 4: Enable WARP if not enabled
        if !config.warp_enabled {
            self.enable_warp(&account).await?;
            config.warp_enabled = true;
        }

        Ok(config)
    }
}

impl Default for WarpProvisioner {
    fn default() -> Self {
        Self::new().expect("Failed to create default WarpProvisioner")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_keypair() {
        let (private, public) = WarpProvisioner::generate_keypair();

        // Keys should be base64 encoded
        assert!(BASE64.decode(&private).is_ok());
        assert!(BASE64.decode(&public).is_ok());

        // Keys should be 32 bytes
        assert_eq!(BASE64.decode(&private).unwrap().len(), 32);
        assert_eq!(BASE64.decode(&public).unwrap().len(), 32);

        // Different calls should produce different keys
        let (private2, _) = WarpProvisioner::generate_keypair();
        assert_ne!(private, private2);
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
        // Test parsing endpoint host:port
        let host_with_port = "engage.cloudflareclient.com:2408";
        let (host, port) = host_with_port
            .rsplit_once(':')
            .map(|(h, p)| (h.to_string(), p.parse::<u16>().unwrap_or(2408)))
            .unwrap_or((host_with_port.to_string(), 2408));

        assert_eq!(host, "engage.cloudflareclient.com");
        assert_eq!(port, 2408);
    }
}
