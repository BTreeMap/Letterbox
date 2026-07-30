//! Configuration management for the image proxy.
//!
//! This module handles persistence of WARP credentials and tunnel configuration.
//! Data is stored as JSON files in the application's private storage directory.

use crate::error::ProxyError;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

/// Name of the persisted WARP configuration inside the storage directory.
const WARP_CONFIG_FILE: &str = "warp_config.json";

/// WARP account data persisted per user.
///
/// This contains the minimum data needed to recreate the WireGuard tunnel.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WarpAccountData {
    /// Device/account ID from Cloudflare
    pub account_id: String,
    /// Access token for API calls
    pub access_token: String,
    /// WireGuard private key (base64 encoded)
    pub private_key: String,
    /// License key (may be empty for free accounts)
    pub license_key: String,
}

/// WireGuard peer configuration from Cloudflare.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WarpPeerConfig {
    /// Peer's public key (base64 encoded)
    pub public_key: String,
    /// Endpoint host (domain name)
    pub endpoint_host: String,
    /// Endpoint IPv4 address
    pub endpoint_ipv4: String,
    /// Endpoint port
    pub endpoint_port: u16,
}

/// Interface addresses assigned by Cloudflare.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WarpInterfaceConfig {
    /// IPv4 address for the tunnel interface
    pub address_ipv4: String,
}

/// Key material for a MASQUE tunnel.
///
/// Separate from [`WarpAccountData`] because it is a different key for a
/// different protocol: registration mints a throwaway X25519 key to obtain a
/// device identity, then enrolment PATCHes a P-256 key which is what the MASQUE
/// session actually authenticates with. Storing both under one `private_key`
/// invites using the wrong one, and the resulting failure appears deep inside
/// the TLS handshake rather than at the boundary.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MasqueCredentials {
    /// This device's enrolled P-256 private key: base64 of PKCS#8 DER.
    pub ec_private_key_der: String,
    /// The endpoint's public key: base64 of `SubjectPublicKeyInfo` DER.
    pub endpoint_pub_key_spki: String,
}

impl MasqueCredentials {
    /// Decode the device private key to DER.
    ///
    /// # Errors
    ///
    /// Returns [`ProxyError::CryptoError`] if the stored value is not base64.
    pub fn decode_private_key(&self) -> Result<Vec<u8>, ProxyError> {
        decode_der("MASQUE private key", &self.ec_private_key_der)
    }

    /// Decode the endpoint public key to SPKI DER.
    ///
    /// # Errors
    ///
    /// Returns [`ProxyError::CryptoError`] if the stored value is not base64.
    pub fn decode_endpoint_key(&self) -> Result<Vec<u8>, ProxyError> {
        decode_der("MASQUE endpoint key", &self.endpoint_pub_key_spki)
    }
}

/// Decode one base64 field, naming it in any error.
fn decode_der(label: &str, encoded: &str) -> Result<Vec<u8>, ProxyError> {
    use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
    BASE64.decode(encoded).map_err(|e| ProxyError::CryptoError {
        details: format!("Invalid {label}: {e}"),
    })
}

/// Complete WARP configuration.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WarpConfig {
    /// Account credentials
    pub account: WarpAccountData,
    /// Peer configuration
    pub peer: WarpPeerConfig,
    /// Interface configuration
    pub interface: WarpInterfaceConfig,
    /// Whether WARP is enabled
    pub warp_enabled: bool,
    /// Account type (free, unlimited, etc.)
    pub account_type: String,
    /// Timestamp when this configuration was last updated
    pub last_updated: i64,
    /// MASQUE key material, absent on accounts provisioned before MASQUE
    /// support existed.
    ///
    /// `#[serde(default)]` makes reading an older config file total rather than
    /// an error: those accounts keep working over WireGuard and are upgraded by
    /// enrolling a key, not by being discarded.
    #[serde(default)]
    pub masque: Option<MasqueCredentials>,
}

/// Proxy configuration including WARP settings and cache options.
///
/// "Is WARP enabled" and "which endpoint" are *questions about* [`Self::warp_config`],
/// not independent settings, so they are accessors rather than fields. Held as
/// fields they were copies that four separate assignments had to keep in step
/// with their source, and a missed one is invisible: the proxy simply reports a
/// stale endpoint for the rest of the session.
#[derive(Debug, Clone)]
pub struct ProxyConfig {
    /// Path to the storage directory
    pub storage_path: PathBuf,
    /// WARP configuration (if provisioned)
    pub warp_config: Option<WarpConfig>,
    /// Maximum image size in bytes (default: 10MB)
    pub max_image_size: u64,
    /// Maximum number of redirects (default: 5)
    pub max_redirects: u32,
    /// Request timeout in seconds (default: 30)
    pub timeout_seconds: u32,
}

impl Default for ProxyConfig {
    fn default() -> Self {
        Self {
            storage_path: PathBuf::new(),
            warp_config: None,
            max_image_size: 10 * 1024 * 1024, // 10MB
            max_redirects: 5,
            timeout_seconds: 30,
        }
    }
}

impl ProxyConfig {
    /// Load existing configuration or create a new one.
    ///
    /// If a configuration file exists at the storage path, it will be loaded.
    /// Otherwise, a new configuration will be created (WARP provisioning is deferred).
    pub async fn load_or_create(storage_path: &str) -> Result<Self, ProxyError> {
        let path = PathBuf::from(storage_path);

        // Ensure the storage directory exists
        if !path.exists() {
            tokio::fs::create_dir_all(&path)
                .await
                .map_err(|e| ProxyError::StorageError {
                    details: format!("Failed to create storage directory: {}", e),
                })?;
        }

        let config = ProxyConfig {
            storage_path: path,
            ..Default::default()
        };
        let config_file = config.config_file_path();

        // An unreadable or unparseable file is not fatal: the caller provisions
        // a fresh account instead, so a corrupt config self-heals rather than
        // bricking the proxy.
        if !config_file.exists() {
            return Ok(config);
        }
        let warp_config = match tokio::fs::read_to_string(&config_file).await {
            Ok(contents) => serde_json::from_str::<WarpConfig>(&contents).ok(),
            Err(e) => {
                log::warn!("Failed to read WARP config: {e}");
                None
            }
        };

        Ok(ProxyConfig {
            warp_config,
            ..config
        })
    }

    /// Save the current configuration to disk.
    ///
    /// A configuration with nothing provisioned has nothing to persist; this is
    /// a no-op rather than an error so callers need not ask first.
    pub async fn save(&self) -> Result<(), ProxyError> {
        let Some(warp_config) = &self.warp_config else {
            return Ok(());
        };
        let contents = serde_json::to_string_pretty(warp_config)?;
        tokio::fs::write(self.config_file_path(), contents).await?;
        Ok(())
    }

    /// Get the path to the WARP configuration file.
    pub fn config_file_path(&self) -> PathBuf {
        self.storage_path.join(WARP_CONFIG_FILE)
    }

    /// Whether WARP is enabled on the provisioned account.
    ///
    /// An unprovisioned proxy is not "enabled with no endpoint": it has no
    /// account at all, which reads as `false`.
    pub fn warp_enabled(&self) -> bool {
        self.warp_config.as_ref().is_some_and(|c| c.warp_enabled)
    }

    /// The endpoint host of the provisioned account, if there is one.
    pub fn endpoint_host(&self) -> Option<&str> {
        self.warp_config
            .as_ref()
            .map(|c| c.peer.endpoint_host.as_str())
    }

    /// Install a freshly provisioned configuration and persist it.
    ///
    /// Installing and persisting are one operation because a configuration held
    /// only in memory would re-provision on the next launch, leaving an orphaned
    /// device registration behind each time.
    pub async fn update_warp_config(&mut self, config: WarpConfig) -> Result<(), ProxyError> {
        self.warp_config = Some(config);
        self.save().await
    }
}

/// Limits for image fetching to prevent abuse.
///
/// `Copy`, because a fetch request carries a copy of these across a channel to
/// the tunnel worker: three integers move by register, where the previous shape
/// also cloned eight heap `String`s per request that nothing ever read.
#[derive(Debug, Clone, Copy)]
pub struct FetchLimits {
    /// Maximum image size in bytes
    pub max_size: u64,
    /// Maximum number of redirects
    pub max_redirects: u32,
    /// Request timeout in seconds
    pub timeout_seconds: u32,
}

impl Default for FetchLimits {
    fn default() -> Self {
        Self {
            max_size: 10 * 1024 * 1024, // 10MB
            max_redirects: 5,
            timeout_seconds: 30,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[tokio::test]
    async fn test_config_load_or_create() {
        let temp = tempdir().unwrap();
        let path = temp.path().to_str().unwrap();

        let config = ProxyConfig::load_or_create(path).await.unwrap();
        assert!(!config.warp_enabled());
        assert_eq!(config.endpoint_host(), None);
        assert!(config.warp_config.is_none());
        assert_eq!(config.max_image_size, 10 * 1024 * 1024);
    }

    /// A config file that is not valid `WarpConfig` JSON must leave the proxy
    /// unprovisioned rather than failing to start: the caller then provisions a
    /// fresh account.
    #[tokio::test]
    async fn corrupt_config_file_loads_as_unprovisioned() {
        let temp = tempdir().unwrap();
        tokio::fs::write(temp.path().join("warp_config.json"), "{ not json")
            .await
            .unwrap();

        let config = ProxyConfig::load_or_create(temp.path().to_str().unwrap())
            .await
            .expect("must not fail");

        assert!(config.warp_config.is_none());
        assert!(!config.warp_enabled());
    }

    /// Saving an unprovisioned config writes nothing at all — it must not
    /// truncate or create a file that a later load would read back as corrupt.
    #[tokio::test]
    async fn saving_without_credentials_writes_no_file() {
        let temp = tempdir().unwrap();
        let config = ProxyConfig::load_or_create(temp.path().to_str().unwrap())
            .await
            .unwrap();

        config.save().await.expect("no-op save");

        assert!(!config.config_file_path().exists());
    }

    #[tokio::test]
    async fn test_config_save_and_load() {
        let temp = tempdir().unwrap();
        let path = temp.path().to_str().unwrap();

        let mut config = ProxyConfig::load_or_create(path).await.unwrap();

        let warp_config = WarpConfig {
            account: WarpAccountData {
                account_id: "test-id".to_string(),
                access_token: "test-token".to_string(),
                private_key: "test-key".to_string(),
                license_key: String::new(),
            },
            peer: WarpPeerConfig {
                public_key: "peer-key".to_string(),
                endpoint_host: "engage.cloudflareclient.com".to_string(),
                endpoint_ipv4: "162.159.192.1".to_string(),
                endpoint_port: 2408,
            },
            interface: WarpInterfaceConfig {
                address_ipv4: "172.16.0.2".to_string(),
            },
            warp_enabled: true,
            account_type: "free".to_string(),
            last_updated: 1704326400,
            masque: None,
        };

        config.update_warp_config(warp_config).await.unwrap();
        assert!(config.warp_enabled());

        // Reload and verify: the derived views must agree with what was written.
        let loaded = ProxyConfig::load_or_create(path).await.unwrap();
        assert!(loaded.warp_enabled());
        assert!(loaded.warp_config.is_some());
        assert_eq!(loaded.endpoint_host(), Some("engage.cloudflareclient.com"));
    }

    #[test]
    fn test_warp_config_serialization() {
        let config = WarpConfig {
            account: WarpAccountData {
                account_id: "id123".to_string(),
                access_token: "token456".to_string(),
                private_key: "key789".to_string(),
                license_key: "license".to_string(),
            },
            peer: WarpPeerConfig {
                public_key: "pubkey".to_string(),
                endpoint_host: "example.com".to_string(),
                endpoint_ipv4: "1.2.3.4".to_string(),
                endpoint_port: 51820,
            },
            interface: WarpInterfaceConfig {
                address_ipv4: "10.0.0.1".to_string(),
            },
            warp_enabled: true,
            account_type: "free".to_string(),
            last_updated: 1234567890,
            masque: None,
        };

        let json = serde_json::to_string(&config).unwrap();
        let parsed: WarpConfig = serde_json::from_str(&json).unwrap();

        assert_eq!(parsed.account.account_id, "id123");
        assert_eq!(parsed.peer.endpoint_port, 51820);
        assert!(parsed.warp_enabled);
    }
}
