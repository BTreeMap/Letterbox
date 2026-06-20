//! WireGuard transport layer built on [`boringtun`].
//!
//! This is a userspace WireGuard endpoint that:
//!
//! * performs the Noise handshake with the Cloudflare WARP peer,
//! * encrypts outbound IP packets ([`send_ip`](WireGuardTransport::send_ip)),
//! * decrypts inbound datagrams ([`poll_incoming`](WireGuardTransport::poll_incoming)),
//! * drives keepalive/handshake timers ([`tick`](WireGuardTransport::tick)).
//!
//! The transport is owned exclusively by a single worker thread (see
//! [`crate::tunnel::manager`]). Because there is no cross-thread sharing it holds
//! the boringtun [`Tunn`] directly — no `Arc`, no `Mutex`, no lock poisoning.

use crate::config::WarpConfig;
use crate::error::ProxyError;
use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use boringtun::noise::{Tunn, TunnResult};
use std::net::{SocketAddr, UdpSocket};
use std::time::{Duration, Instant};

/// Maximum WireGuard datagram size (IPv6 jumbo headroom).
const MAX_DATAGRAM: usize = 65_535;

/// Fixed Cloudflare WARP UDP endpoint.
///
/// The provisioning response advertises a rotating endpoint, but a single stable
/// anycast address/port is used for the data plane so connectivity does not
/// depend on whatever the control plane happened to return. WARP accepts the
/// WireGuard data channel on several well-known ports; `500` is chosen because
/// UDP/500 (IKE) is rarely throttled on mobile carrier networks.
pub const WARP_ENDPOINT_IPV4: &str = "162.159.192.8";

/// Fixed Cloudflare WARP UDP endpoint port (see [`WARP_ENDPOINT_IPV4`]).
pub const WARP_ENDPOINT_PORT: u16 = 500;

/// Persistent keepalive interval negotiated with the WARP peer (seconds).
const PERSISTENT_KEEPALIVE_SECS: u16 = 25;

/// Minimum spacing between [`tick`](WireGuardTransport::tick) timer updates.
const TICK_INTERVAL: Duration = Duration::from_millis(100);

/// Decode a base64 WireGuard key into its 32 raw bytes.
fn decode_key(label: &str, encoded: &str) -> Result<[u8; 32], ProxyError> {
    BASE64
        .decode(encoded)
        .map_err(|e| ProxyError::CryptoError {
            details: format!("Invalid {label}: {e}"),
        })?
        .try_into()
        .map_err(|_| ProxyError::CryptoError {
            details: format!("{label} must be exactly 32 bytes"),
        })
}

/// Live transport statistics sourced from boringtun.
#[derive(Debug, Clone, Default)]
pub struct TunnelStats {
    /// Time since the last completed handshake, if any.
    pub since_handshake: Option<Duration>,
    /// Total plaintext bytes transmitted into the tunnel.
    pub tx_bytes: u64,
    /// Total plaintext bytes received from the tunnel.
    pub rx_bytes: u64,
    /// Estimated packet loss in `[0.0, 1.0]`.
    pub estimated_loss: f32,
    /// Estimated round-trip time in milliseconds, if measured.
    pub rtt_ms: Option<u32>,
}

/// A userspace WireGuard transport to a single peer endpoint.
pub struct WireGuardTransport {
    tunnel: Box<Tunn>,
    socket: UdpSocket,
    endpoint: SocketAddr,
    recv_buf: Vec<u8>,
    send_buf: Vec<u8>,
    last_tick: Instant,
}

impl WireGuardTransport {
    /// Build a transport from provisioned WARP configuration.
    ///
    /// The UDP socket is bound to an ephemeral local port and connected to the
    /// WARP endpoint so the OS routes replies back to us.
    pub fn new(config: &WarpConfig) -> Result<Self, ProxyError> {
        let private_key = decode_key("private key", &config.account.private_key)?;
        let peer_public_key = decode_key("peer public key", &config.peer.public_key)?;

        let tunnel = Tunn::new(
            private_key.into(),
            peer_public_key.into(),
            None,
            Some(PERSISTENT_KEEPALIVE_SECS),
            0,
            None,
        );

        let endpoint: SocketAddr = format!("{WARP_ENDPOINT_IPV4}:{WARP_ENDPOINT_PORT}")
            .parse()
            .map_err(|e| ProxyError::TunnelError {
                details: format!("Invalid endpoint address: {e}"),
            })?;

        let socket = UdpSocket::bind("0.0.0.0:0").map_err(|e| ProxyError::TunnelError {
            details: format!("Failed to bind UDP socket: {e}"),
        })?;
        socket
            .connect(endpoint)
            .map_err(|e| ProxyError::TunnelError {
                details: format!("Failed to connect UDP socket: {e}"),
            })?;

        Ok(Self {
            tunnel: Box::new(tunnel),
            socket,
            endpoint,
            recv_buf: vec![0u8; MAX_DATAGRAM],
            send_buf: vec![0u8; MAX_DATAGRAM],
            last_tick: Instant::now(),
        })
    }

    /// The remote WARP endpoint this transport is bound to.
    pub fn endpoint(&self) -> SocketAddr {
        self.endpoint
    }

    /// Send the first handshake initiation message to the peer.
    pub fn initiate_handshake(&mut self) -> Result<(), ProxyError> {
        match self
            .tunnel
            .format_handshake_initiation(&mut self.send_buf, false)
        {
            TunnResult::WriteToNetwork(packet) => {
                self.socket
                    .send(packet)
                    .map_err(|e| ProxyError::TunnelError {
                        details: format!("Failed to send handshake: {e}"),
                    })?;
                Ok(())
            }
            TunnResult::Err(e) => Err(ProxyError::TunnelError {
                details: format!("Handshake initiation failed: {e:?}"),
            }),
            _ => Ok(()),
        }
    }

    /// Block up to `timeout` for inbound datagrams and return decrypted IP packets.
    ///
    /// Handshake/cookie/keepalive replies are written straight back to the
    /// network; only tunnelled IP payloads are returned to the caller. Returns
    /// immediately with whatever has already been decrypted once the socket
    /// would block.
    pub fn poll_incoming(&mut self, timeout: Duration) -> Result<Vec<Vec<u8>>, ProxyError> {
        let mut packets = Vec::new();

        self.socket
            .set_read_timeout(Some(timeout))
            .map_err(|e| ProxyError::TunnelError {
                details: format!("Failed to set read timeout: {e}"),
            })?;

        loop {
            match self.socket.recv(&mut self.recv_buf) {
                Ok(n) => {
                    self.decapsulate_into(n, &mut packets);
                    // Drain any remaining buffered datagrams without blocking.
                    self.socket
                        .set_read_timeout(Some(Duration::from_millis(0)))
                        .ok();
                }
                Err(ref e)
                    if matches!(
                        e.kind(),
                        std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                    ) =>
                {
                    break;
                }
                Err(e) => {
                    return Err(ProxyError::TunnelError {
                        details: format!("UDP receive failed: {e}"),
                    });
                }
            }
        }

        Ok(packets)
    }

    /// Decrypt a single received datagram of length `n`, appending IP payloads.
    fn decapsulate_into(&mut self, n: usize, packets: &mut Vec<Vec<u8>>) {
        // SAFETY of indices: `n` comes from a successful `recv` into `recv_buf`.
        let datagram = self.recv_buf[..n].to_vec();
        let mut result = self.tunnel.decapsulate(None, &datagram, &mut self.send_buf);
        loop {
            match result {
                TunnResult::WriteToNetwork(packet) => {
                    let _ = self.socket.send(packet);
                    // boringtun requires repeated empty calls to flush its queue.
                    result = self.tunnel.decapsulate(None, &[], &mut self.send_buf);
                }
                TunnResult::WriteToTunnelV4(packet, _) | TunnResult::WriteToTunnelV6(packet, _) => {
                    packets.push(packet.to_vec());
                    result = self.tunnel.decapsulate(None, &[], &mut self.send_buf);
                }
                TunnResult::Done | TunnResult::Err(_) => break,
            }
        }
    }

    /// Encrypt one outbound IP packet and send it to the peer.
    pub fn send_ip(&mut self, packet: &[u8]) -> Result<(), ProxyError> {
        match self.tunnel.encapsulate(packet, &mut self.send_buf) {
            TunnResult::WriteToNetwork(encrypted) => {
                self.socket
                    .send(encrypted)
                    .map_err(|e| ProxyError::TunnelError {
                        details: format!("Failed to send packet: {e}"),
                    })?;
                Ok(())
            }
            TunnResult::Err(e) => Err(ProxyError::TunnelError {
                details: format!("Encapsulation failed: {e:?}"),
            }),
            _ => Ok(()),
        }
    }

    /// Drive boringtun's timers (keepalives, handshake retries). Rate-limited.
    pub fn tick(&mut self) -> Result<(), ProxyError> {
        let now = Instant::now();
        if now.duration_since(self.last_tick) < TICK_INTERVAL {
            return Ok(());
        }
        self.last_tick = now;

        if let TunnResult::WriteToNetwork(packet) = self.tunnel.update_timers(&mut self.send_buf) {
            let _ = self.socket.send(packet);
        }
        Ok(())
    }

    /// Whether a handshake has completed at least once.
    pub fn is_connected(&self) -> bool {
        self.tunnel.time_since_last_handshake().is_some()
    }

    /// Snapshot live statistics from the underlying boringtun tunnel.
    pub fn stats(&self) -> TunnelStats {
        let (since_handshake, tx_bytes, rx_bytes, estimated_loss, rtt_ms) = self.tunnel.stats();
        TunnelStats {
            since_handshake,
            tx_bytes: tx_bytes as u64,
            rx_bytes: rx_bytes as u64,
            estimated_loss,
            rtt_ms,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{WarpAccountData, WarpInterfaceConfig, WarpPeerConfig};
    use crate::provisioning::WarpProvisioner;

    fn test_config() -> WarpConfig {
        let (private_key, _) = WarpProvisioner::generate_keypair();
        let (_, peer_public) = WarpProvisioner::generate_keypair();
        WarpConfig {
            account: WarpAccountData {
                account_id: "test".to_string(),
                access_token: "test".to_string(),
                private_key,
                license_key: String::new(),
            },
            peer: WarpPeerConfig {
                public_key: peer_public,
                endpoint_host: "127.0.0.1".to_string(),
                endpoint_ipv4: "127.0.0.1".to_string(),
                endpoint_port: 51820,
            },
            interface: WarpInterfaceConfig {
                address_ipv4: "172.16.0.2/32".to_string(),
            },
            warp_enabled: true,
            account_type: "test".to_string(),
            last_updated: 0,
        }
    }

    #[test]
    fn transport_creation_succeeds() {
        assert!(WireGuardTransport::new(&test_config()).is_ok());
    }

    #[test]
    fn transport_not_connected_initially() {
        let transport = WireGuardTransport::new(&test_config()).unwrap();
        assert!(!transport.is_connected());
        assert!(transport.stats().since_handshake.is_none());
    }

    #[test]
    fn invalid_private_key_is_rejected() {
        let mut config = test_config();
        config.account.private_key = "not-base64!".to_string();
        assert!(matches!(
            WireGuardTransport::new(&config),
            Err(ProxyError::CryptoError { .. })
        ));
    }

    #[test]
    fn invalid_peer_key_is_rejected() {
        let mut config = test_config();
        config.peer.public_key = "short".to_string();
        assert!(WireGuardTransport::new(&config).is_err());
    }

    #[test]
    fn endpoint_is_fixed_warp_anycast() {
        let transport = WireGuardTransport::new(&test_config()).unwrap();
        let endpoint = transport.endpoint();
        assert_eq!(endpoint.ip().to_string(), WARP_ENDPOINT_IPV4);
        assert_eq!(endpoint.port(), WARP_ENDPOINT_PORT);
    }
}
