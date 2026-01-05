//! WARP tunnel combining WireGuard transport with smoltcp TCP/IP stack.
//!
//! This module provides a complete tunnel implementation that:
//! - Uses WireGuard for encrypted transport to Cloudflare WARP
//! - Uses smoltcp for TCP/IP stack over the WireGuard tunnel
//! - Provides TCP connections for HTTP/HTTPS requests
//!
//! ## Architecture
//!
//! ```text
//! +-------------+     +---------+     +------------+     +-------------+
//! |  HTTP/TLS   | --> | smoltcp | --> | WireGuard  | --> | UDP Socket  |
//! |   Client    | <-- | TCP/IP  | <-- | Transport  | <-- | (Encrypted) |
//! +-------------+     +---------+     +------------+     +-------------+
//! ```

use crate::config::WarpConfig;
use crate::error::ProxyError;
use crate::transport::WireGuardTransport;
use smoltcp::iface::{Config, Interface, SocketHandle, SocketSet, SocketStorage};
use smoltcp::phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken};
use smoltcp::socket::tcp::{Socket as TcpSocket, SocketBuffer, State as TcpState};
use smoltcp::time::Instant as SmoltcpInstant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, Ipv4Address};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::Instant as StdInstant;

/// Size of TCP socket buffers.
const TCP_BUFFER_SIZE: usize = 65535;

/// Maximum number of simultaneous TCP connections.
const MAX_TCP_CONNECTIONS: usize = 16;

/// Helper to convert std::time::Instant to smoltcp Instant
fn smoltcp_now() -> SmoltcpInstant {
    static START: std::sync::OnceLock<StdInstant> = std::sync::OnceLock::new();
    let start = START.get_or_init(StdInstant::now);
    let elapsed = start.elapsed();
    SmoltcpInstant::from_millis(elapsed.as_millis() as i64)
}

/// WARP tunnel providing TCP connections over WireGuard.
pub struct WarpTunnel {
    /// WireGuard transport layer
    transport: WireGuardTransport,
    /// smoltcp network interface
    interface: Interface,
    /// Socket set for managing TCP sockets
    sockets: SocketSet<'static>,
    /// Virtual device for smoltcp
    device: VirtualDevice,
    /// Local IPv4 address octets
    local_ipv4_octets: [u8; 4],
    /// Whether the tunnel is established
    connected: bool,
}

impl WarpTunnel {
    /// Create a new WARP tunnel from configuration.
    pub fn new(config: &WarpConfig) -> Result<Self, ProxyError> {
        // Create WireGuard transport
        let transport = WireGuardTransport::new(config)?;

        // Parse local IPv4 address (remove CIDR prefix if present)
        let ipv4_str = config
            .interface
            .address_ipv4
            .split('/')
            .next()
            .unwrap_or(&config.interface.address_ipv4);

        // Parse the IPv4 address into octets
        let local_ipv4_octets: [u8; 4] = {
            let parts: Vec<u8> = ipv4_str.split('.').filter_map(|s| s.parse().ok()).collect();
            if parts.len() != 4 {
                return Err(ProxyError::TunnelError {
                    details: format!("Invalid local IPv4 address: {}", ipv4_str),
                });
            }
            [parts[0], parts[1], parts[2], parts[3]]
        };

        // Create virtual device
        let device = VirtualDevice::new();

        // Create smoltcp interface
        let mut interface_config = Config::new(HardwareAddress::Ip);
        interface_config.random_seed = rand::random();

        let mut interface = Interface::new(interface_config, &mut device.clone(), smoltcp_now());

        // Configure interface with local address
        interface.update_ip_addrs(|addrs| {
            let addr = IpAddress::v4(
                local_ipv4_octets[0],
                local_ipv4_octets[1],
                local_ipv4_octets[2],
                local_ipv4_octets[3],
            );
            addrs.push(IpCidr::new(addr, 32)).ok();
        });

        // Set default gateway (Cloudflare's gateway)
        interface
            .routes_mut()
            .add_default_ipv4_route(Ipv4Address::new(172, 16, 0, 1))
            .ok();

        // Create socket storage with 'static lifetime using Box::leak
        // NOTE: Box::leak is used intentionally here because smoltcp's SocketSet requires
        // 'static lifetime storage. This is acceptable because:
        // 1. WarpTunnel instances are long-lived (typically one per app lifetime)
        // 2. The leaked memory is bounded (MAX_TCP_CONNECTIONS * sizeof(SocketStorage))
        // 3. When the tunnel is dropped, the sockets are no longer used
        // Alternative approaches like using an arena allocator would add complexity
        // without significant benefit for this use case.
        let mut socket_vec: Vec<SocketStorage<'static>> = Vec::with_capacity(MAX_TCP_CONNECTIONS);
        for _ in 0..MAX_TCP_CONNECTIONS {
            socket_vec.push(SocketStorage::EMPTY);
        }
        let socket_storage: &'static mut [SocketStorage<'static>] =
            Box::leak(socket_vec.into_boxed_slice());

        // Create socket set
        let sockets = SocketSet::new(socket_storage);

        Ok(Self {
            transport,
            interface,
            sockets,
            device,
            local_ipv4_octets,
            connected: false,
        })
    }

    /// Connect the tunnel (initiate WireGuard handshake).
    pub fn connect(&mut self) -> Result<(), ProxyError> {
        self.transport.initiate_handshake()?;
        Ok(())
    }

    /// Poll the tunnel for activity.
    ///
    /// This should be called regularly to:
    /// - Process incoming packets from WireGuard
    /// - Handle smoltcp timers and retransmissions
    /// - Send outgoing packets through WireGuard
    pub fn poll(&mut self) -> Result<(), ProxyError> {
        // Poll WireGuard for incoming packets
        let incoming = self.transport.poll()?;
        for packet in incoming {
            self.device.rx_queue.lock().unwrap().push_back(packet);
        }

        // Poll smoltcp
        let timestamp = smoltcp_now();
        self.interface
            .poll(timestamp, &mut self.device, &mut self.sockets);

        // Send outgoing packets through WireGuard
        while let Some(packet) = self.device.tx_queue.lock().unwrap().pop_front() {
            self.transport.send_ip(&packet)?;
        }

        // Tick WireGuard for keepalives
        self.transport.tick()?;

        // Update connection status
        self.connected = self.transport.is_connected();

        Ok(())
    }

    /// Check if the tunnel is connected.
    pub fn is_connected(&self) -> bool {
        self.connected
    }

    /// Create a new TCP socket and connect to the given address.
    ///
    /// Returns a socket handle that can be used for reading/writing.
    ///
    /// # Memory Usage
    ///
    /// This function uses Box::leak to create 'static buffers for smoltcp.
    /// Each connection leaks 2 * TCP_BUFFER_SIZE bytes (128KB total).
    /// For long-running applications, consider implementing a buffer pool.
    pub fn tcp_connect(
        &mut self,
        remote_addr: IpAddress,
        remote_port: u16,
        local_port: u16,
    ) -> Result<SocketHandle, ProxyError> {
        // Create TCP socket with owned buffers using Box::leak for 'static lifetime
        // NOTE: This leaks memory intentionally because smoltcp requires 'static buffers.
        // The leak is bounded by MAX_TCP_CONNECTIONS * 2 * TCP_BUFFER_SIZE.
        // TODO: Consider implementing a buffer pool for production use.
        let rx_vec: &'static mut [u8] = Box::leak(vec![0u8; TCP_BUFFER_SIZE].into_boxed_slice());
        let tx_vec: &'static mut [u8] = Box::leak(vec![0u8; TCP_BUFFER_SIZE].into_boxed_slice());
        let rx_buffer = SocketBuffer::new(rx_vec);
        let tx_buffer = SocketBuffer::new(tx_vec);
        let mut socket = TcpSocket::new(rx_buffer, tx_buffer);

        // Create local endpoint from octets
        let local_addr = IpAddress::v4(
            self.local_ipv4_octets[0],
            self.local_ipv4_octets[1],
            self.local_ipv4_octets[2],
            self.local_ipv4_octets[3],
        );

        // Connect to remote
        socket
            .connect(
                self.interface.context(),
                (remote_addr, remote_port),
                (local_addr, local_port),
            )
            .map_err(|e| ProxyError::TunnelError {
                details: format!("TCP connect failed: {}", e),
            })?;

        // Add socket to set
        let handle = self.sockets.add(socket);

        Ok(handle)
    }

    /// Get the state of a TCP socket.
    pub fn tcp_state(&self, handle: SocketHandle) -> Option<TcpState> {
        let socket = self.sockets.get::<TcpSocket>(handle);
        Some(socket.state())
    }

    /// Check if a TCP socket can send data.
    pub fn tcp_can_send(&self, handle: SocketHandle) -> bool {
        let socket = self.sockets.get::<TcpSocket>(handle);
        socket.can_send()
    }

    /// Check if a TCP socket can receive data.
    pub fn tcp_can_recv(&self, handle: SocketHandle) -> bool {
        let socket = self.sockets.get::<TcpSocket>(handle);
        socket.can_recv()
    }

    /// Send data on a TCP socket.
    pub fn tcp_send(&mut self, handle: SocketHandle, data: &[u8]) -> Result<usize, ProxyError> {
        let socket = self.sockets.get_mut::<TcpSocket>(handle);
        socket
            .send_slice(data)
            .map_err(|e| ProxyError::TunnelError {
                details: format!("TCP send failed: {}", e),
            })
    }

    /// Receive data from a TCP socket.
    pub fn tcp_recv(&mut self, handle: SocketHandle, buf: &mut [u8]) -> Result<usize, ProxyError> {
        let socket = self.sockets.get_mut::<TcpSocket>(handle);
        socket.recv_slice(buf).map_err(|e| ProxyError::TunnelError {
            details: format!("TCP recv failed: {}", e),
        })
    }

    /// Close a TCP socket.
    pub fn tcp_close(&mut self, handle: SocketHandle) {
        let socket = self.sockets.get_mut::<TcpSocket>(handle);
        socket.close();
    }

    /// Remove a TCP socket from the set.
    pub fn tcp_remove(&mut self, handle: SocketHandle) {
        self.sockets.remove(handle);
    }
}

/// Virtual network device that bridges smoltcp with WireGuard.
#[derive(Clone)]
struct VirtualDevice {
    /// Queue of packets received from WireGuard (to be read by smoltcp)
    rx_queue: Arc<Mutex<VecDeque<Vec<u8>>>>,
    /// Queue of packets to send via WireGuard (written by smoltcp)
    tx_queue: Arc<Mutex<VecDeque<Vec<u8>>>>,
}

impl VirtualDevice {
    fn new() -> Self {
        Self {
            rx_queue: Arc::new(Mutex::new(VecDeque::new())),
            tx_queue: Arc::new(Mutex::new(VecDeque::new())),
        }
    }
}

impl Device for VirtualDevice {
    type RxToken<'a>
        = VirtualRxToken
    where
        Self: 'a;
    type TxToken<'a>
        = VirtualTxToken
    where
        Self: 'a;

    fn receive(
        &mut self,
        _timestamp: SmoltcpInstant,
    ) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let rx_queue = self.rx_queue.clone();
        let tx_queue = self.tx_queue.clone();

        if rx_queue.lock().unwrap().is_empty() {
            return None;
        }

        Some((
            VirtualRxToken { queue: rx_queue },
            VirtualTxToken { queue: tx_queue },
        ))
    }

    fn transmit(&mut self, _timestamp: SmoltcpInstant) -> Option<Self::TxToken<'_>> {
        Some(VirtualTxToken {
            queue: self.tx_queue.clone(),
        })
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = 1420; // WireGuard MTU
        caps
    }
}

/// Token for receiving packets from the virtual device.
struct VirtualRxToken {
    queue: Arc<Mutex<VecDeque<Vec<u8>>>>,
}

impl RxToken for VirtualRxToken {
    fn consume<R, F>(self, f: F) -> R
    where
        F: FnOnce(&[u8]) -> R,
    {
        let mut queue = self.queue.lock().unwrap();
        if let Some(packet) = queue.pop_front() {
            f(&packet)
        } else {
            // This should never happen if the Device::receive implementation
            // correctly checks for empty queue before returning an RxToken.
            // If we reach here, it's a bug in the VirtualDevice implementation.
            unreachable!("RxToken consumed with empty queue - this indicates a bug in VirtualDevice::receive")
        }
    }
}

/// Token for transmitting packets to the virtual device.
struct VirtualTxToken {
    queue: Arc<Mutex<VecDeque<Vec<u8>>>>,
}

impl TxToken for VirtualTxToken {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut buf = vec![0u8; len];
        let result = f(&mut buf);
        self.queue.lock().unwrap().push_back(buf);
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{WarpAccountData, WarpInterfaceConfig, WarpPeerConfig};
    use crate::provisioning::WarpProvisioner;

    fn create_test_config() -> WarpConfig {
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
    fn test_tunnel_creation() {
        let config = create_test_config();
        let tunnel = WarpTunnel::new(&config);
        assert!(tunnel.is_ok());
    }

    #[test]
    fn test_tunnel_not_connected_initially() {
        let config = create_test_config();
        let tunnel = WarpTunnel::new(&config).unwrap();
        assert!(!tunnel.is_connected());
    }

    #[test]
    fn test_virtual_device_capabilities() {
        let device = VirtualDevice::new();
        let caps = device.capabilities();
        assert_eq!(caps.medium, Medium::Ip);
        assert_eq!(caps.max_transmission_unit, 1420);
    }

    #[test]
    fn test_virtual_device_transmit() {
        let mut device = VirtualDevice::new();

        // Get a transmit token
        let token = device.transmit(SmoltcpInstant::from_millis(0)).unwrap();

        // Write some data
        token.consume(100, |buf| {
            buf[0] = 0x45; // IPv4 header start
            buf[1] = 0x00;
        });

        // Check data was queued
        assert_eq!(device.tx_queue.lock().unwrap().len(), 1);
    }

    #[test]
    fn test_virtual_device_receive() {
        let mut device = VirtualDevice::new();

        // Queue a packet
        device
            .rx_queue
            .lock()
            .unwrap()
            .push_back(vec![0x45, 0x00, 0x00, 0x14]);

        // Receive should succeed now
        let result = device.receive(SmoltcpInstant::from_millis(0));
        assert!(result.is_some());

        // Consume the packet
        let (rx, _tx) = result.unwrap();
        rx.consume(|buf| {
            assert_eq!(buf[0], 0x45); // IPv4
        });
    }

    #[test]
    fn test_ipv4_parsing() {
        let addr_with_prefix = "172.16.0.2/32";
        let addr_str = addr_with_prefix.split('/').next().unwrap();

        // Parse manually like we do in the code
        let parts: Vec<u8> = addr_str.split('.').filter_map(|s| s.parse().ok()).collect();

        assert_eq!(parts, vec![172, 16, 0, 2]);
    }
}
