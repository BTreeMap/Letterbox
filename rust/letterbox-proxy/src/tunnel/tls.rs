//! TLS termination for tunnelled connections using [`rustls`].
//!
//! HTTPS requests ride a [`TunnelSocket`] wrapped by `tokio-rustls`.
//! Certificates are verified against the `webpki-roots` trust anchors, so a
//! compromised or malicious WARP exit cannot transparently intercept the user's
//! image/update traffic.

use crate::error::ProxyError;
use crate::tunnel::stack::Tunnel;
use rustls::pki_types::ServerName;
use rustls::{ClientConfig, RootCertStore};
use smoltcp::wire::IpAddress;
use std::sync::{Arc, OnceLock};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio_rustls::TlsConnector;

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

            let mut config = ClientConfig::builder_with_provider(Arc::new(
                rustls::crypto::ring::default_provider(),
            ))
            .with_safe_default_protocol_versions()
            .expect("ring provider supports the default protocol versions")
            .with_root_certificates(roots)
            .with_no_client_auth();

            // Offering no ALPN at all is a browser tell in its own right: every
            // one negotiates it, so its absence is visible in the ClientHello
            // before a single byte of HTTP is sent, and bot-management services
            // read exactly that. Only `http/1.1` is offered because it is the
            // only protocol this client speaks — advertising `h2` alongside
            // Chrome's user agent would look more convincing right up to the
            // point a server selected it and the connection failed.
            config.alpn_protocols = vec![b"http/1.1".to_vec()];

            Arc::new(config)
        })
        .clone()
}

/// Write one request and read the reply to EOF, refusing to exceed `cap`.
///
/// Generic over the stream because that is the *only* difference between a
/// plaintext exchange and a TLS one — writing it twice is how the two drift.
/// `on_io` names which failure the caller considers an I/O error to be, since a
/// broken TLS stream and a broken socket are not the same finding.
pub(crate) async fn exchange<S>(
    stream: &mut S,
    request: &[u8],
    cap: usize,
    on_io: fn(std::io::Error) -> ProxyError,
) -> Result<Vec<u8>, ProxyError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    stream.write_all(request).await.map_err(on_io)?;
    stream.flush().await.map_err(on_io)?;

    let mut buf = Vec::with_capacity(16 * 1024);
    let mut chunk = [0u8; 16 * 1024];
    loop {
        let read = match stream.read(&mut chunk).await {
            Ok(0) => break,
            Ok(n) => n,
            // A peer that closes without `close_notify` is common enough on the
            // open web that treating it as truncation would fail more responses
            // than it protected.
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(e) => return Err(on_io(e)),
        };
        buf.extend_from_slice(&chunk[..read]);
        if buf.len() > cap {
            return Err(ProxyError::ResponseTooLarge {
                size: buf.len() as u64,
                max_size: cap as u64,
            });
        }
    }
    Ok(buf)
}

/// Perform a single HTTPS request/response over the tunnel.
///
/// `request` is the already-serialised HTTP/1.1 request (which must include
/// `Connection: close` so the peer closes the stream after the response). The
/// full response — headers and body — is returned as raw bytes, capped at
/// `max_body` plus generous header headroom.
///
/// The socket closes when it drops, on every path out of here.
pub async fn request_https(
    tunnel: &Tunnel,
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

    let socket = tunnel.connect(ip, port, timeout).await?;
    let mut tls = TlsConnector::from(client_config())
        .connect(server_name, socket)
        .await
        .map_err(|e| ProxyError::TlsError {
            details: format!("TLS handshake failed: {e}"),
        })?;

    exchange(
        &mut tls,
        request,
        max_body.min(ABSOLUTE_MAX_RESPONSE),
        |e| ProxyError::TlsError {
            details: format!("TLS transfer failed: {e}"),
        },
    )
    .await
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

    /// The size ceiling is enforced while reading, not after, so an oversized
    /// body is refused without being fully buffered first.
    #[tokio::test]
    async fn oversized_response_is_refused_mid_read() {
        // One chunk-sized read that already exceeds the cap: the refusal must
        // land on the first read, not after the whole body is buffered.
        let body = vec![b'x'; 2048];
        let mut stream = tokio_test::io::Builder::new()
            .write(b"GET / HTTP/1.1\r\n\r\n")
            .read(&body)
            .build();

        let refused = exchange(&mut stream, b"GET / HTTP/1.1\r\n\r\n", 1024, |e| {
            ProxyError::TlsError {
                details: e.to_string(),
            }
        })
        .await
        .expect_err("must refuse");

        assert!(matches!(refused, ProxyError::ResponseTooLarge { .. }));
    }

    #[tokio::test]
    async fn a_reply_within_the_cap_is_returned_whole() {
        let mut stream = tokio_test::io::Builder::new()
            .write(b"PING")
            .read(b"PONG")
            .build();

        let reply = exchange(&mut stream, b"PING", 1024, |e| ProxyError::TlsError {
            details: e.to_string(),
        })
        .await
        .expect("must succeed");
        assert_eq!(reply, b"PONG");
    }
}
