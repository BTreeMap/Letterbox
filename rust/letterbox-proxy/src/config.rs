//! Configuration management for the image proxy.
//!
//! This module handles persistence of WARP credentials and tunnel configuration.
//! Data is stored as JSON files in the application's private storage directory.

use crate::error::ProxyError;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

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
    /// Endpoint IPv6 address
    pub endpoint_ipv6: String,
    /// Endpoint port
    pub endpoint_port: u16,
}

/// Interface addresses assigned by Cloudflare.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WarpInterfaceConfig {
    /// IPv4 address for the tunnel interface
    pub address_ipv4: String,
    /// IPv6 address for the tunnel interface
    pub address_ipv6: String,
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
}

/// Proxy configuration including WARP settings and cache options.
#[derive(Debug, Clone)]
pub struct ProxyConfig {
    /// Path to the storage directory
    pub storage_path: PathBuf,
    /// WARP configuration (if provisioned)
    pub warp_config: Option<WarpConfig>,
    /// Whether WARP is enabled
    pub warp_enabled: bool,
    /// Current endpoint host
    pub endpoint_host: Option<String>,
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
            warp_enabled: false,
            endpoint_host: None,
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

        let config_file = path.join("warp_config.json");

        let mut config = ProxyConfig {
            storage_path: path,
            ..Default::default()
        };

        // Try to load existing configuration
        if config_file.exists() {
            match tokio::fs::read_to_string(&config_file).await {
                Ok(contents) => {
                    if let Ok(warp_config) = serde_json::from_str::<WarpConfig>(&contents) {
                        config.warp_enabled = warp_config.warp_enabled;
                        config.endpoint_host = Some(warp_config.peer.endpoint_host.clone());
                        config.warp_config = Some(warp_config);
                    }
                }
                Err(e) => {
                    log::warn!("Failed to read WARP config: {}", e);
                }
            }
        }

        Ok(config)
    }

    /// Save the current configuration to disk.
    pub async fn save(&self) -> Result<(), ProxyError> {
        if let Some(ref warp_config) = self.warp_config {
            let config_file = self.storage_path.join("warp_config.json");
            let contents = serde_json::to_string_pretty(warp_config)?;
            tokio::fs::write(&config_file, contents).await?;
        }
        Ok(())
    }

    /// Get the path to the WARP configuration file.
    pub fn config_file_path(&self) -> PathBuf {
        self.storage_path.join("warp_config.json")
    }

    /// Check if WARP credentials exist.
    pub fn has_credentials(&self) -> bool {
        self.warp_config.is_some()
    }

    /// Update the WARP configuration.
    pub async fn update_warp_config(&mut self, config: WarpConfig) -> Result<(), ProxyError> {
        self.warp_enabled = config.warp_enabled;
        self.endpoint_host = Some(config.peer.endpoint_host.clone());
        self.warp_config = Some(config);
        self.save().await
    }
}

/// Limits for image fetching to prevent abuse.
#[derive(Debug, Clone)]
pub struct FetchLimits {
    /// Maximum image size in bytes
    pub max_size: u64,
    /// Maximum number of redirects
    pub max_redirects: u32,
    /// Request timeout in seconds
    pub timeout_seconds: u32,
    /// Allowed content types (empty means all image/* types)
    pub allowed_content_types: Vec<String>,
}

impl Default for FetchLimits {
    fn default() -> Self {
        Self {
            max_size: 10 * 1024 * 1024, // 10MB
            max_redirects: 5,
            timeout_seconds: 30,
            allowed_content_types: vec![
                "image/jpeg".to_string(),
                "image/png".to_string(),
                "image/gif".to_string(),
                "image/webp".to_string(),
                "image/svg+xml".to_string(),
                "image/bmp".to_string(),
                "image/x-icon".to_string(),
                "image/vnd.microsoft.icon".to_string(),
            ],
        }
    }
}

impl FetchLimits {
    /// Check if a content type is allowed.
    pub fn is_content_type_allowed(&self, content_type: &str) -> bool {
        if self.allowed_content_types.is_empty() {
            // If no specific types are configured, allow any image/*
            return content_type.starts_with("image/");
        }

        // Normalize content type (remove parameters like charset)
        let normalized = content_type
            .split(';')
            .next()
            .unwrap_or(content_type)
            .trim()
            .to_lowercase();

        self.allowed_content_types
            .iter()
            .any(|t| t.to_lowercase() == normalized)
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
        assert!(!config.warp_enabled);
        assert!(config.warp_config.is_none());
        assert_eq!(config.max_image_size, 10 * 1024 * 1024);
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
                endpoint_ipv6: "2606:4700:d0::a29f:c001".to_string(),
                endpoint_port: 2408,
            },
            interface: WarpInterfaceConfig {
                address_ipv4: "172.16.0.2".to_string(),
                address_ipv6: "fd01:db8:1111:2222::2".to_string(),
            },
            warp_enabled: true,
            account_type: "free".to_string(),
            last_updated: 1704326400,
        };

        config.update_warp_config(warp_config).await.unwrap();
        assert!(config.warp_enabled);

        // Reload and verify
        let loaded = ProxyConfig::load_or_create(path).await.unwrap();
        assert!(loaded.warp_enabled);
        assert!(loaded.warp_config.is_some());
        assert_eq!(
            loaded.endpoint_host.as_deref(),
            Some("engage.cloudflareclient.com")
        );
    }

    #[test]
    fn test_fetch_limits_content_type() {
        let limits = FetchLimits::default();

        assert!(limits.is_content_type_allowed("image/png"));
        assert!(limits.is_content_type_allowed("image/jpeg"));
        assert!(limits.is_content_type_allowed("image/svg+xml"));
        assert!(limits.is_content_type_allowed("image/PNG")); // Case insensitive
        assert!(limits.is_content_type_allowed("image/png; charset=utf-8")); // With params

        assert!(!limits.is_content_type_allowed("text/html"));
        assert!(!limits.is_content_type_allowed("application/json"));
    }

    #[test]
    fn test_fetch_limits_empty_allows_all_images() {
        let limits = FetchLimits {
            allowed_content_types: vec![],
            ..Default::default()
        };

        assert!(limits.is_content_type_allowed("image/png"));
        assert!(limits.is_content_type_allowed("image/any-type"));
        assert!(!limits.is_content_type_allowed("text/html"));
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
                endpoint_ipv6: "::1".to_string(),
                endpoint_port: 51820,
            },
            interface: WarpInterfaceConfig {
                address_ipv4: "10.0.0.1".to_string(),
                address_ipv6: "fd00::1".to_string(),
            },
            warp_enabled: true,
            account_type: "free".to_string(),
            last_updated: 1234567890,
        };

        let json = serde_json::to_string(&config).unwrap();
        let parsed: WarpConfig = serde_json::from_str(&json).unwrap();

        assert_eq!(parsed.account.account_id, "id123");
        assert_eq!(parsed.peer.endpoint_port, 51820);
        assert!(parsed.warp_enabled);
    }
}
