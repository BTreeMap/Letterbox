//! WireGuard transport layer using boringtun.
//!
//! This module provides a userspace WireGuard implementation for the tunnel.
//! It handles:
//! - WireGuard handshake
//! - Packet encryption/decryption
//! - UDP transport to the WARP endpoint
//!
//! ## Architecture
//!
//! The transport wraps boringtun's Tunn struct and provides:
//! - `poll()` to get decrypted IP packets from the tunnel
//! - `send_ip()` to encrypt and send IP packets through the tunnel
//! - `tick()` for periodic maintenance (keepalives, handshakes)

use crate::config::WarpConfig;
use crate::error::ProxyError;
use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use boringtun::noise::{Tunn, TunnResult};
use std::net::{SocketAddr, UdpSocket};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// WireGuard tunnel transport.
pub struct WireGuardTransport {
    /// The boringtun tunnel instance
    tunnel: Arc<Mutex<Box<Tunn>>>,
    /// UDP socket for sending/receiving encrypted packets
    socket: UdpSocket,
    /// Remote endpoint address
    endpoint: SocketAddr,
    /// Buffer for receiving packets
    recv_buf: Vec<u8>,
    /// Buffer for sending packets
    send_buf: Vec<u8>,
    /// Last time the tunnel was ticked
    last_tick: Instant,
}

impl WireGuardTransport {
    /// Create a new WireGuard transport from WARP configuration.
    pub fn new(config: &WarpConfig) -> Result<Self, ProxyError> {
        // Decode keys from base64
        let private_key_bytes: [u8; 32] = BASE64
            .decode(&config.account.private_key)
            .map_err(|e| ProxyError::CryptoError {
                details: format!("Invalid private key: {}", e),
            })?
            .try_into()
            .map_err(|_| ProxyError::CryptoError {
                details: "Private key must be 32 bytes".to_string(),
            })?;

        let peer_public_key_bytes: [u8; 32] = BASE64
            .decode(&config.peer.public_key)
            .map_err(|e| ProxyError::CryptoError {
                details: format!("Invalid peer public key: {}", e),
            })?
            .try_into()
            .map_err(|_| ProxyError::CryptoError {
                details: "Peer public key must be 32 bytes".to_string(),
            })?;

        // Create the tunnel
        // Note: boringtun uses x25519_dalek internally
        let tunnel = Tunn::new(
            private_key_bytes.into(),
            peer_public_key_bytes.into(),
            None,     // No preshared key
            Some(25), // Persistent keepalive interval in seconds
            0,        // Index
            None,     // Rate limiter
        )
        .map_err(|e| ProxyError::TunnelError {
            details: format!("Failed to create tunnel: {}", e),
        })?;

        // Create UDP socket (bind to any available port)
        let socket = UdpSocket::bind("0.0.0.0:0").map_err(|e| ProxyError::TunnelError {
            details: format!("Failed to bind UDP socket: {}", e),
        })?;

        // Set socket to non-blocking for polling
        socket
            .set_nonblocking(true)
            .map_err(|e| ProxyError::TunnelError {
                details: format!("Failed to set socket non-blocking: {}", e),
            })?;

        // Resolve endpoint address
        let endpoint = format!(
            "{}:{}",
            config.peer.endpoint_ipv4, config.peer.endpoint_port
        )
        .parse()
        .map_err(|e| ProxyError::TunnelError {
            details: format!("Invalid endpoint address: {}", e),
        })?;

        Ok(Self {
            tunnel: Arc::new(Mutex::new(Box::new(tunnel))),
            socket,
            endpoint,
            recv_buf: vec![0u8; 65535],
            send_buf: vec![0u8; 65535],
            last_tick: Instant::now(),
        })
    }

    /// Initiate the WireGuard handshake.
    pub fn initiate_handshake(&mut self) -> Result<(), ProxyError> {
        let mut tunnel = self.tunnel.lock().map_err(|_| ProxyError::TunnelError {
            details: "Tunnel lock poisoned".to_string(),
        })?;

        // Generate handshake initiation
        match tunnel.format_handshake_initiation(&mut self.send_buf, false) {
            TunnResult::WriteToNetwork(packet) => {
                self.socket.send_to(packet, self.endpoint).map_err(|e| {
                    ProxyError::TunnelError {
                        details: format!("Failed to send handshake: {}", e),
                    }
                })?;
                Ok(())
            }
            TunnResult::Err(e) => Err(ProxyError::TunnelError {
                details: format!("Handshake initiation failed: {:?}", e),
            }),
            _ => Ok(()),
        }
    }

    /// Poll for incoming packets.
    ///
    /// This method:
    /// 1. Reads encrypted packets from the UDP socket
    /// 2. Decrypts them using WireGuard
    /// 3. Returns decrypted IP packets
    pub fn poll(&mut self) -> Result<Vec<Vec<u8>>, ProxyError> {
        let mut packets = Vec::new();

        // Try to receive from UDP socket
        loop {
            match self.socket.recv_from(&mut self.recv_buf) {
                Ok((n, _addr)) => {
                    let encrypted_packet = &self.recv_buf[..n];

                    let mut tunnel = self.tunnel.lock().map_err(|_| ProxyError::TunnelError {
                        details: "Tunnel lock poisoned".to_string(),
                    })?;

                    // Process the received packet
                    let mut result = tunnel.decapsulate(None, encrypted_packet, &mut self.send_buf);

                    loop {
                        match result {
                            TunnResult::WriteToNetwork(packet) => {
                                // Send response (handshake, keepalive, etc.)
                                let _ = self.socket.send_to(packet, self.endpoint);
                                result = tunnel.decapsulate(None, &[], &mut self.send_buf);
                            }
                            TunnResult::WriteToTunnelV4(packet, _addr) => {
                                packets.push(packet.to_vec());
                                result = tunnel.decapsulate(None, &[], &mut self.send_buf);
                            }
                            TunnResult::WriteToTunnelV6(packet, _addr) => {
                                packets.push(packet.to_vec());
                                result = tunnel.decapsulate(None, &[], &mut self.send_buf);
                            }
                            TunnResult::Done => break,
                            TunnResult::Err(_) => break,
                        }
                    }
                }
                Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    // No more data available
                    break;
                }
                Err(e) => {
                    return Err(ProxyError::TunnelError {
                        details: format!("UDP receive failed: {}", e),
                    });
                }
            }
        }

        Ok(packets)
    }

    /// Send an IP packet through the tunnel.
    ///
    /// This method:
    /// 1. Encrypts the IP packet using WireGuard
    /// 2. Sends the encrypted packet via UDP
    pub fn send_ip(&mut self, packet: &[u8]) -> Result<(), ProxyError> {
        let mut tunnel = self.tunnel.lock().map_err(|_| ProxyError::TunnelError {
            details: "Tunnel lock poisoned".to_string(),
        })?;

        match tunnel.encapsulate(packet, &mut self.send_buf) {
            TunnResult::WriteToNetwork(encrypted) => {
                self.socket.send_to(encrypted, self.endpoint).map_err(|e| {
                    ProxyError::TunnelError {
                        details: format!("Failed to send packet: {}", e),
                    }
                })?;
                Ok(())
            }
            TunnResult::Err(e) => Err(ProxyError::TunnelError {
                details: format!("Encapsulation failed: {:?}", e),
            }),
            TunnResult::Done => Ok(()),
            _ => Ok(()),
        }
    }

    /// Perform periodic maintenance (keepalives, handshake retries).
    pub fn tick(&mut self) -> Result<(), ProxyError> {
        let now = Instant::now();
        if now.duration_since(self.last_tick) < Duration::from_millis(100) {
            return Ok(());
        }
        self.last_tick = now;

        let mut tunnel = self.tunnel.lock().map_err(|_| ProxyError::TunnelError {
            details: "Tunnel lock poisoned".to_string(),
        })?;

        // Call update_timers to handle keepalives
        match tunnel.update_timers(&mut self.send_buf) {
            TunnResult::WriteToNetwork(packet) => {
                let _ = self.socket.send_to(packet, self.endpoint);
            }
            _ => {}
        }

        Ok(())
    }

    /// Check if the tunnel is established.
    pub fn is_connected(&self) -> bool {
        if let Ok(tunnel) = self.tunnel.lock() {
            tunnel.time_since_last_handshake().is_some()
        } else {
            false
        }
    }

    /// Get time since last successful handshake.
    pub fn time_since_handshake(&self) -> Option<Duration> {
        if let Ok(tunnel) = self.tunnel.lock() {
            tunnel.time_since_last_handshake()
        } else {
            None
        }
    }
}

/// Statistics about the tunnel.
#[derive(Debug, Clone, Default)]
pub struct TunnelStats {
    /// Bytes sent through the tunnel
    pub bytes_sent: u64,
    /// Bytes received from the tunnel
    pub bytes_received: u64,
    /// Number of packets sent
    pub packets_sent: u64,
    /// Number of packets received
    pub packets_received: u64,
    /// Number of handshakes completed
    pub handshakes: u64,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{WarpAccountData, WarpInterfaceConfig, WarpPeerConfig};
    use crate::provisioning::WarpProvisioner;

    fn create_test_config() -> WarpConfig {
        // Generate a real keypair for testing
        let (private_key, _public_key) = WarpProvisioner::generate_keypair();
        let (peer_private, peer_public) = WarpProvisioner::generate_keypair();

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
                endpoint_ipv6: "::1".to_string(),
                endpoint_port: 51820,
            },
            interface: WarpInterfaceConfig {
                address_ipv4: "172.16.0.2/32".to_string(),
                address_ipv6: "fd01::2/128".to_string(),
            },
            warp_enabled: true,
            account_type: "test".to_string(),
            last_updated: 0,
        }
    }

    #[test]
    fn test_transport_creation() {
        let config = create_test_config();
        let transport = WireGuardTransport::new(&config);
        assert!(transport.is_ok());
    }

    #[test]
    fn test_transport_not_connected_initially() {
        let config = create_test_config();
        let transport = WireGuardTransport::new(&config).unwrap();
        assert!(!transport.is_connected());
    }

    #[test]
    fn test_invalid_private_key() {
        let mut config = create_test_config();
        config.account.private_key = "invalid-base64!".to_string();

        let result = WireGuardTransport::new(&config);
        assert!(result.is_err());
        match result {
            Err(ProxyError::CryptoError { .. }) => {}
            _ => panic!("Expected CryptoError"),
        }
    }

    #[test]
    fn test_invalid_peer_key() {
        let mut config = create_test_config();
        config.peer.public_key = "not-valid".to_string();

        let result = WireGuardTransport::new(&config);
        assert!(result.is_err());
    }

    #[test]
    fn test_tunnel_stats_default() {
        let stats = TunnelStats::default();
        assert_eq!(stats.bytes_sent, 0);
        assert_eq!(stats.bytes_received, 0);
    }
}
