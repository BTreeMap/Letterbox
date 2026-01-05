//! Error types for the image proxy.
//!
//! This module defines all error types that can occur during proxy operations,
//! including initialization, provisioning, tunnel establishment, and image fetching.

use thiserror::Error;

/// Errors that can occur during proxy operations.
#[derive(Debug, Error, Clone, PartialEq, Eq, uniffi::Error)]
pub enum ProxyError {
    /// The proxy has not been initialized. Call `proxy_init()` first.
    #[error("Proxy not initialized")]
    NotInitialized,

    /// Failed to initialize the proxy.
    #[error("Initialization failed: {details}")]
    InitializationFailed {
        /// Detailed error message
        details: String,
    },

    /// Failed to provision WARP credentials.
    #[error("WARP provisioning failed: {details}")]
    ProvisioningFailed {
        /// Detailed error message
        details: String,
    },

    /// Failed to establish WireGuard tunnel.
    #[error("Tunnel error: {details}")]
    TunnelError {
        /// Detailed error message
        details: String,
    },

    /// Invalid URL provided.
    #[error("Invalid URL '{url}': {details}")]
    InvalidUrl {
        /// The invalid URL
        url: String,
        /// Detailed error message
        details: String,
    },

    /// HTTP request failed.
    #[error("HTTP error: {status_code} - {details}")]
    HttpError {
        /// HTTP status code (0 if connection failed)
        status_code: u16,
        /// Detailed error message
        details: String,
    },

    /// The response content type is not an image.
    #[error("Invalid content type: expected image, got {content_type}")]
    InvalidContentType {
        /// The actual content type received
        content_type: String,
    },

    /// The response is too large.
    #[error("Response too large: {size} bytes (max: {max_size})")]
    ResponseTooLarge {
        /// Actual size in bytes
        size: u64,
        /// Maximum allowed size in bytes
        max_size: u64,
    },

    /// Too many redirects.
    #[error("Too many redirects: {count} (max: {max_count})")]
    TooManyRedirects {
        /// Number of redirects encountered
        count: u32,
        /// Maximum allowed redirects
        max_count: u32,
    },

    /// Connection timeout.
    #[error("Connection timeout after {seconds} seconds")]
    Timeout {
        /// Timeout duration in seconds
        seconds: u32,
    },

    /// DNS resolution failed.
    #[error("DNS resolution failed for {host}: {details}")]
    DnsError {
        /// The host that failed to resolve
        host: String,
        /// Detailed error message
        details: String,
    },

    /// TLS handshake failed.
    #[error("TLS error: {details}")]
    TlsError {
        /// Detailed error message
        details: String,
    },

    /// Failed to read or write configuration.
    #[error("Storage error: {details}")]
    StorageError {
        /// Detailed error message
        details: String,
    },

    /// WireGuard cryptographic operation failed.
    #[error("Crypto error: {details}")]
    CryptoError {
        /// Detailed error message
        details: String,
    },

    /// Network is unavailable.
    #[error("Network unavailable: {details}")]
    NetworkUnavailable {
        /// Detailed error message
        details: String,
    },
}

impl From<std::io::Error> for ProxyError {
    fn from(err: std::io::Error) -> Self {
        ProxyError::StorageError {
            details: err.to_string(),
        }
    }
}

impl From<serde_json::Error> for ProxyError {
    fn from(err: serde_json::Error) -> Self {
        ProxyError::StorageError {
            details: format!("JSON error: {}", err),
        }
    }
}

impl From<reqwest::Error> for ProxyError {
    fn from(err: reqwest::Error) -> Self {
        if err.is_timeout() {
            ProxyError::Timeout { seconds: 30 }
        } else if err.is_connect() {
            ProxyError::NetworkUnavailable {
                details: err.to_string(),
            }
        } else if let Some(status) = err.status() {
            ProxyError::HttpError {
                status_code: status.as_u16(),
                details: err.to_string(),
            }
        } else {
            ProxyError::HttpError {
                status_code: 0,
                details: err.to_string(),
            }
        }
    }
}

impl From<url::ParseError> for ProxyError {
    fn from(err: url::ParseError) -> Self {
        ProxyError::InvalidUrl {
            url: String::new(),
            details: err.to_string(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_error_display() {
        let error = ProxyError::NotInitialized;
        assert_eq!(error.to_string(), "Proxy not initialized");

        let error = ProxyError::InvalidUrl {
            url: "bad-url".to_string(),
            details: "missing scheme".to_string(),
        };
        assert!(error.to_string().contains("bad-url"));
        assert!(error.to_string().contains("missing scheme"));
    }

    #[test]
    fn test_error_from_io() {
        let io_error = std::io::Error::new(std::io::ErrorKind::NotFound, "file not found");
        let proxy_error: ProxyError = io_error.into();
        assert!(matches!(proxy_error, ProxyError::StorageError { .. }));
    }

    #[test]
    fn test_error_clone() {
        let error = ProxyError::HttpError {
            status_code: 404,
            details: "Not found".to_string(),
        };
        let cloned = error.clone();
        assert_eq!(error, cloned);
    }
}
