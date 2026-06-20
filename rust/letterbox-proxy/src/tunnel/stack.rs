//! WARP tunnel: a smoltcp TCP/IP stack riding the WireGuard transport.
//!
//! [`WarpTunnel`] owns every piece of the userspace network stack for the whole
//! lifetime of the tunnel, so all storage is plain owned [`Vec`]s — there is no
//! `Box::leak` and no `'static` smuggling. A single worker thread owns the
//! tunnel; callers obtain a [`TunnelTcpStream`] that implements [`Read`]/[`Write`]
//! by repeatedly driving the poll loop until the requested I/O can make progress.
//!
//! ```text
//! TLS / HTTP  <->  TunnelTcpStream  <->  smoltcp TCP  <->  WireGuard  <->  UDP
//! ```

use crate::config::WarpConfig;
use crate::error::ProxyError;
use crate::tunnel::device::VirtualDevice;
use crate::tunnel::transport::{TunnelStats, WireGuardTransport};
use smoltcp::iface::{Config, Interface, SocketHandle, SocketSet};
use smoltcp::socket::tcp::{Socket as TcpSocket, SocketBuffer, State as TcpState};
use smoltcp::time::Instant as SmoltcpInstant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, Ipv4Address};
use std::io::{self, Read, Write};
use std::time::{Duration, Instant};

/// Per-direction TCP buffer size (64 KiB).
const TCP_BUFFER_SIZE: usize = 65_535;

/// Cloudflare WARP's tunnel-side default gateway.
const WARP_GATEWAY: Ipv4Address = Ipv4Address::new(172, 16, 0, 1);

/// Granularity of a single poll iteration while waiting on socket readiness.
const POLL_SLICE: Duration = Duration::from_millis(20);

/// Monotonic smoltcp clock anchored at first use.
fn smoltcp_now() -> SmoltcpInstant {
    static START: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
    let start = START.get_or_init(Instant::now);
    SmoltcpInstant::from_micros(start.elapsed().as_micros() as i64)
}

/// Parse a possibly CIDR-suffixed dotted-quad into raw octets.
fn parse_ipv4_octets(addr: &str) -> Result<[u8; 4], ProxyError> {
    let host = addr.split('/').next().unwrap_or(addr);
    let parts: Vec<u8> = host.split('.').filter_map(|p| p.parse().ok()).collect();
    match parts.as_slice() {
        [a, b, c, d] => Ok([*a, *b, *c, *d]),
        _ => Err(ProxyError::TunnelError {
            details: format!("Invalid local IPv4 address: {addr}"),
        }),
    }
}

/// A WireGuard-backed userspace TCP/IP stack to Cloudflare WARP.
pub struct WarpTunnel {
    transport: WireGuardTransport,
    interface: Interface,
    sockets: SocketSet<'static>,
    device: VirtualDevice,
    local_ipv4: [u8; 4],
    next_local_port: u16,
}

impl WarpTunnel {
    /// Build a tunnel from provisioned WARP configuration (no I/O yet).
    pub fn new(config: &WarpConfig) -> Result<Self, ProxyError> {
        let transport = WireGuardTransport::new(config)?;
        let local_ipv4 = parse_ipv4_octets(&config.interface.address_ipv4)?;

        let mut device = VirtualDevice::new();
        let mut iface_config = Config::new(HardwareAddress::Ip);
        iface_config.random_seed = rand::random();
        let mut interface = Interface::new(iface_config, &mut device, smoltcp_now());

        interface.update_ip_addrs(|addrs| {
            let addr = IpAddress::v4(local_ipv4[0], local_ipv4[1], local_ipv4[2], local_ipv4[3]);
            let _ = addrs.push(IpCidr::new(addr, 32));
        });
        interface
            .routes_mut()
            .add_default_ipv4_route(WARP_GATEWAY)
            .map_err(|_| ProxyError::TunnelError {
                details: "Failed to install default route".to_string(),
            })?;

        Ok(Self {
            transport,
            interface,
            sockets: SocketSet::new(Vec::new()),
            device,
            local_ipv4,
            next_local_port: 49_152,
        })
    }

    /// The WARP endpoint this tunnel targets.
    pub fn endpoint(&self) -> std::net::SocketAddr {
        self.transport.endpoint()
    }

    /// Whether the WireGuard handshake has completed.
    pub fn is_connected(&self) -> bool {
        self.transport.is_connected()
    }

    /// Live WireGuard statistics.
    pub fn stats(&self) -> TunnelStats {
        self.transport.stats()
    }

    /// Run one poll iteration, blocking up to `wait` for inbound datagrams.
    fn poll_once(&mut self, wait: Duration) -> Result<(), ProxyError> {
        for packet in self.transport.poll_incoming(wait)? {
            self.device.push_inbound(packet);
        }
        self.interface
            .poll(smoltcp_now(), &mut self.device, &mut self.sockets);
        while let Some(packet) = self.device.pop_outbound() {
            self.transport.send_ip(&packet)?;
        }
        self.transport.tick()?;
        Ok(())
    }

    /// Initiate the handshake and pump the loop until connected or timed out.
    pub fn connect(&mut self, timeout: Duration) -> Result<(), ProxyError> {
        self.transport.initiate_handshake()?;
        let deadline = Instant::now() + timeout;
        while Instant::now() < deadline {
            self.poll_once(POLL_SLICE)?;
            if self.transport.is_connected() {
                return Ok(());
            }
        }
        Err(ProxyError::Timeout {
            seconds: timeout.as_secs() as u32,
        })
    }

    /// Allocate the next ephemeral local TCP port (wraps within 49152-65535).
    fn allocate_local_port(&mut self) -> u16 {
        let port = self.next_local_port;
        self.next_local_port = if port == 65_535 { 49_152 } else { port + 1 };
        port
    }

    /// Open a TCP connection through the tunnel and wait until it is established.
    pub fn open_tcp(
        &mut self,
        remote: IpAddress,
        remote_port: u16,
        timeout: Duration,
    ) -> Result<SocketHandle, ProxyError> {
        let rx = SocketBuffer::new(vec![0u8; TCP_BUFFER_SIZE]);
        let tx = SocketBuffer::new(vec![0u8; TCP_BUFFER_SIZE]);
        let socket = TcpSocket::new(rx, tx);
        let handle = self.sockets.add(socket);

        let local_port = self.allocate_local_port();
        {
            let socket = self.sockets.get_mut::<TcpSocket>(handle);
            socket
                .connect(self.interface.context(), (remote, remote_port), local_port)
                .map_err(|e| ProxyError::TunnelError {
                    details: format!("TCP connect failed: {e}"),
                })?;
        }

        let deadline = Instant::now() + timeout;
        loop {
            self.poll_once(POLL_SLICE)?;
            let state = self.sockets.get::<TcpSocket>(handle).state();
            match state {
                TcpState::Established => return Ok(handle),
                TcpState::Closed => {
                    self.sockets.remove(handle);
                    return Err(ProxyError::TunnelError {
                        details: "TCP connection refused".to_string(),
                    });
                }
                _ if Instant::now() >= deadline => {
                    self.sockets.remove(handle);
                    return Err(ProxyError::Timeout {
                        seconds: timeout.as_secs() as u32,
                    });
                }
                _ => {}
            }
        }
    }

    /// Gracefully close and drop a TCP socket.
    pub fn close_tcp(&mut self, handle: SocketHandle) {
        {
            let socket = self.sockets.get_mut::<TcpSocket>(handle);
            socket.close();
        }
        // Give the FIN a chance to flush.
        for _ in 0..16 {
            if self.poll_once(Duration::from_millis(5)).is_err() {
                break;
            }
            if self.sockets.get::<TcpSocket>(handle).state() == TcpState::Closed {
                break;
            }
        }
        self.sockets.remove(handle);
    }

    /// Borrow a TCP socket as a blocking [`Read`]/[`Write`] stream.
    pub fn stream(&mut self, handle: SocketHandle, timeout: Duration) -> TunnelTcpStream<'_> {
        TunnelTcpStream {
            tunnel: self,
            handle,
            timeout,
        }
    }

    /// The tunnel's local IPv4 address octets.
    pub fn local_ipv4(&self) -> [u8; 4] {
        self.local_ipv4
    }
}

/// Blocking byte stream over a tunnelled TCP socket.
///
/// Each [`read`](Read::read)/[`write`](Write::write) drives the smoltcp poll loop
/// until the socket can make progress or the per-stream timeout elapses, turning
/// smoltcp's event model into the synchronous interface rustls expects.
pub struct TunnelTcpStream<'t> {
    tunnel: &'t mut WarpTunnel,
    handle: SocketHandle,
    timeout: Duration,
}

impl Read for TunnelTcpStream<'_> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let deadline = Instant::now() + self.timeout;
        loop {
            self.tunnel
                .poll_once(POLL_SLICE)
                .map_err(io::Error::other)?;

            let socket = self.tunnel.sockets.get_mut::<TcpSocket>(self.handle);
            if socket.can_recv() {
                return socket.recv_slice(buf).map_err(io::Error::other);
            }
            if !socket.may_recv() {
                // Peer closed the read half and no buffered data remains: EOF.
                return Ok(0);
            }
            if Instant::now() >= deadline {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "tunnel read timed out",
                ));
            }
        }
    }
}

impl Write for TunnelTcpStream<'_> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.write_bytes(buf)
    }

    fn flush(&mut self) -> io::Result<()> {
        let deadline = Instant::now() + self.timeout;
        loop {
            self.tunnel
                .poll_once(POLL_SLICE)
                .map_err(io::Error::other)?;
            let socket = self.tunnel.sockets.get::<TcpSocket>(self.handle);
            if socket.send_queue() == 0 {
                return Ok(());
            }
            if Instant::now() >= deadline {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "tunnel flush timed out",
                ));
            }
        }
    }
}

impl TunnelTcpStream<'_> {
    fn write_bytes(&mut self, buf: &[u8]) -> io::Result<usize> {
        let deadline = Instant::now() + self.timeout;
        loop {
            self.tunnel
                .poll_once(POLL_SLICE)
                .map_err(io::Error::other)?;

            let socket = self.tunnel.sockets.get_mut::<TcpSocket>(self.handle);
            if !socket.may_send() {
                return Err(io::Error::new(
                    io::ErrorKind::BrokenPipe,
                    "tunnel connection closed",
                ));
            }
            if socket.can_send() {
                let written = socket.send_slice(buf).map_err(io::Error::other)?;
                if written > 0 {
                    return Ok(written);
                }
            }
            if Instant::now() >= deadline {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "tunnel write timed out",
                ));
            }
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
    fn tunnel_creation_succeeds() {
        assert!(WarpTunnel::new(&test_config()).is_ok());
    }

    #[test]
    fn tunnel_not_connected_initially() {
        let tunnel = WarpTunnel::new(&test_config()).unwrap();
        assert!(!tunnel.is_connected());
    }

    #[test]
    fn parse_ipv4_handles_cidr() {
        assert_eq!(parse_ipv4_octets("172.16.0.2/32").unwrap(), [172, 16, 0, 2]);
        assert_eq!(parse_ipv4_octets("10.0.0.1").unwrap(), [10, 0, 0, 1]);
        assert!(parse_ipv4_octets("not-an-ip").is_err());
    }

    #[test]
    fn local_port_allocation_wraps() {
        let mut tunnel = WarpTunnel::new(&test_config()).unwrap();
        tunnel.next_local_port = 65_535;
        assert_eq!(tunnel.allocate_local_port(), 65_535);
        assert_eq!(tunnel.allocate_local_port(), 49_152);
    }
}
