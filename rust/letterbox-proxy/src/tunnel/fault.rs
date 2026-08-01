//! Whether a failed fetch is worth retrying.

use crate::error::{ProxyError, NO_HTTP_RESPONSE};

/// A failure, classified by what it says about the tunnel.
///
/// This distinction *is* the retry policy. Every fetch is a `GET` carried over a
/// connection that is closed afterwards, so it is idempotent and holds no state
/// worth preserving: re-running it against a fresh tunnel is always safe. What
/// is not safe is re-running it when the far end already answered, because the
/// answer would be the same and the retry only costs time.
#[derive(Debug)]
pub enum Fault {
    /// The tunnel broke. Rebuild it and run the request again.
    Transport(ProxyError),
    /// The far end answered, or the request was never valid. Report it.
    Endpoint(ProxyError),
}

impl From<ProxyError> for Fault {
    /// Exhaustive by construction: a new [`ProxyError`] variant is a compile
    /// error here rather than silently inheriting a retry policy nobody chose.
    fn from(error: ProxyError) -> Self {
        match error {
            // The tunnel, or something that can only be reached through it.
            // DNS runs over DoH inside the tunnel, and a TLS handshake truncated
            // by a dead session is indistinguishable here from a bad
            // certificate — retrying a genuine certificate failure merely fails
            // again, whereas not retrying a tunnel-induced one is the bug this
            // classification exists to fix.
            ProxyError::TunnelError { .. }
            | ProxyError::Timeout { .. }
            | ProxyError::DnsError { .. }
            | ProxyError::TlsError { .. }
            | ProxyError::NetworkUnavailable { .. } => Self::Transport(error),

            // No response arrived, so nothing answered: a write that failed, a
            // socket that closed, a reply that would not parse.
            ProxyError::HttpError {
                status_code: NO_HTTP_RESPONSE,
                ..
            } => Self::Transport(error),

            // A real status code means a server considered the request and
            // decided. Everything below it is a verdict on the request itself.
            ProxyError::HttpError { .. }
            | ProxyError::NotInitialized
            | ProxyError::InitializationFailed { .. }
            | ProxyError::ProvisioningFailed { .. }
            | ProxyError::InvalidUrl { .. }
            | ProxyError::InvalidContentType { .. }
            | ProxyError::ActiveContentRefused { .. }
            | ProxyError::ResponseTooLarge { .. }
            | ProxyError::TooManyRedirects { .. }
            | ProxyError::StorageError { .. }
            | ProxyError::CryptoError { .. } => Self::Endpoint(error),
        }
    }
}

impl Fault {
    /// The underlying error, whatever the verdict.
    pub fn into_error(self) -> ProxyError {
        match self {
            Self::Transport(error) | Self::Endpoint(error) => error,
        }
    }

    /// Whether rebuilding the tunnel and running the request again could help.
    pub fn is_transport(&self) -> bool {
        matches!(self, Self::Transport(_))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_dead_tunnel_is_retried() {
        for error in [
            ProxyError::TunnelError {
                details: "session closed".into(),
            },
            ProxyError::Timeout { seconds: 15 },
            ProxyError::DnsError {
                host: "e.com".into(),
                details: "no answer".into(),
            },
            ProxyError::TlsError {
                details: "handshake truncated".into(),
            },
            ProxyError::NetworkUnavailable {
                details: "offline".into(),
            },
        ] {
            assert!(
                Fault::from(error.clone()).is_transport(),
                "{error} should be retried"
            );
        }
    }

    /// The sentinel is what separates "nothing answered" from "a server did".
    #[test]
    fn a_missing_response_is_transport_but_a_status_is_not() {
        let no_response = ProxyError::HttpError {
            status_code: NO_HTTP_RESPONSE,
            details: "Write failed".into(),
        };
        assert!(Fault::from(no_response).is_transport());

        for status in [403, 404, 500, 503] {
            let answered = ProxyError::HttpError {
                status_code: status,
                details: format!("HTTP {status}"),
            };
            assert!(
                !Fault::from(answered).is_transport(),
                "HTTP {status} is an answer, not a broken tunnel"
            );
        }
    }

    /// Retrying these reproduces them exactly, at the cost of the round trip.
    #[test]
    fn a_verdict_on_the_request_is_not_retried() {
        for error in [
            ProxyError::InvalidUrl {
                url: "x".into(),
                details: "bad".into(),
            },
            ProxyError::InvalidContentType {
                content_type: "text/css".into(),
            },
            ProxyError::ActiveContentRefused {
                content_type: "application/wasm".into(),
            },
            ProxyError::ResponseTooLarge {
                size: 10,
                max_size: 5,
            },
            ProxyError::TooManyRedirects {
                count: 6,
                max_count: 5,
            },
            ProxyError::NotInitialized,
        ] {
            assert!(
                !Fault::from(error.clone()).is_transport(),
                "{error} should not be retried"
            );
        }
    }

    #[test]
    fn classification_preserves_the_error() {
        let original = ProxyError::Timeout { seconds: 3 };
        assert_eq!(Fault::from(original.clone()).into_error(), original);
    }
}
