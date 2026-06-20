//! TLS termination for tunnelled connections using [`rustls`].
//!
//! HTTPS requests ride a [`TunnelTcpStream`] wrapped in a rustls
//! [`Stream`](rustls::Stream). Certificates are verified against the
//! `webpki-roots` trust anchors, so a compromised or malicious WARP exit cannot
//! transparently intercept the user's image/update traffic.

use crate::error::ProxyError;
use crate::tunnel::stack::WarpTunnel;
use rustls::pki_types::ServerName;
use rustls::{ClientConfig, ClientConnection, RootCertStore};
use smoltcp::wire::IpAddress;
use std::io::{Read, Write};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

/// Hard ceiling on a single response body to bound memory use.
const ABSOLUTE_MAX_RESPONSE: usize = 32 * 1024 * 1024;

/// Build (once) the shared rustls client configuration.
///
/// Uses the `ring` crypto provider explicitly so the config never depends on a
/// process-wide default provider being installed by some other crate.
fn client_config() -> Arc<ClientConfig> {
    static CONFIG: OnceLock<Arc<ClientConfig>> = OnceLock::new();
    CONFIG
        .get_or_init(|| {
            let mut roots = RootCertStore::empty();
            roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

            let config = ClientConfig::builder_with_provider(Arc::new(
                rustls::crypto::ring::default_provider(),
            ))
            .with_safe_default_protocol_versions()
            .expect("ring provider supports the default protocol versions")
            .with_root_certificates(roots)
            .with_no_client_auth();

            Arc::new(config)
        })
        .clone()
}

/// Perform a single HTTPS request/response over the tunnel.
///
/// `request` is the already-serialised HTTP/1.1 request (which must include
/// `Connection: close` so the peer closes the stream after the response). The
/// full response — headers and body — is returned as raw bytes, capped at
/// `max_body` plus generous header headroom.
pub fn request_https(
    tunnel: &mut WarpTunnel,
    ip: IpAddress,
    port: u16,
    sni: &str,
    request: &[u8],
    max_body: usize,
    timeout: Duration,
) -> Result<Vec<u8>, ProxyError> {
    let server_name = ServerName::try_from(sni.to_string()).map_err(|e| ProxyError::TlsError {
        details: format!("Invalid server name '{sni}': {e}"),
    })?;
    let mut connection =
        ClientConnection::new(client_config(), server_name).map_err(|e| ProxyError::TlsError {
            details: format!("Failed to start TLS session: {e}"),
        })?;

    let handle = tunnel.open_tcp(ip, port, timeout)?;
    let cap = max_body.min(ABSOLUTE_MAX_RESPONSE);

    let result = (|| -> Result<Vec<u8>, ProxyError> {
        let mut adapter = tunnel.stream(handle, timeout);
        let mut tls = rustls::Stream::new(&mut connection, &mut adapter);

        tls.write_all(request).map_err(|e| ProxyError::TlsError {
            details: format!("TLS write failed: {e}"),
        })?;
        tls.flush().map_err(|e| ProxyError::TlsError {
            details: format!("TLS flush failed: {e}"),
        })?;

        read_to_end(&mut tls, cap)
    })();

    tunnel.close_tcp(handle);
    result
}

/// Read a TLS stream until EOF, enforcing a size ceiling.
fn read_to_end<S: Read>(stream: &mut S, cap: usize) -> Result<Vec<u8>, ProxyError> {
    let mut buf = Vec::with_capacity(16 * 1024);
    let mut chunk = [0u8; 16 * 1024];
    loop {
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => {
                buf.extend_from_slice(&chunk[..n]);
                if buf.len() > cap {
                    return Err(ProxyError::ResponseTooLarge {
                        size: buf.len() as u64,
                        max_size: cap as u64,
                    });
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(e) => {
                return Err(ProxyError::TlsError {
                    details: format!("TLS read failed: {e}"),
                });
            }
        }
    }
    Ok(buf)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_config_is_cached() {
        let a = client_config();
        let b = client_config();
        assert!(Arc::ptr_eq(&a, &b));
    }

    #[test]
    fn invalid_sni_is_rejected() {
        // Build a tunnel-less smoke test of name validation by constructing a
        // ServerName directly; an empty name must fail.
        assert!(ServerName::try_from(String::new()).is_err());
    }
}
