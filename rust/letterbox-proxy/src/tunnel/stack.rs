//! WARP tunnel: a smoltcp TCP/IP stack riding the MASQUE transport.
//!
//! ```text
//! TLS / HTTP  <->  TunnelSocket  <->  smoltcp TCP  <->  MASQUE  <->  UDP
//! ```
//!
//! # Ownership
//!
//! `Interface::poll` needs `&mut` over the interface *and* the socket set, and
//! every concurrent fetch needs its own socket in that same set. An `async fn`
//! cannot carry a `&mut` across a suspension point, so the two owners are
//! reconciled the only way single-threaded async allows: one [`Rc`] over one
//! [`RefCell`]. Exactly one cell, holding exactly what has more than one
//! reader — the device, the transport and the packet source stay owned outright
//! by the [`Driver`], because nothing else touches them.
//!
//! The hazard of a `RefCell` in async code is a borrow held across `.await`,
//! which panics. Here that is unrepresentable rather than merely avoided: every
//! borrow of [`Netif`] happens inside a `poll_*` body, and `poll` is not a
//! coroutine — there is no `.await` to hold it across.

use crate::config::WarpConfig;
use crate::error::ProxyError;
use crate::tunnel::device::VirtualDevice;
use crate::tunnel::dns::DnsCache;
use crate::tunnel::masque::{MasqueTransport, PacketSource, TransportStatus};
use crate::tunnel::stats::TunnelStats;
use smoltcp::iface::{Config, Interface, SocketHandle, SocketSet};
use smoltcp::socket::tcp::{Socket as TcpSocket, SocketBuffer, State as TcpState};
use smoltcp::time::Instant as SmoltcpInstant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, Ipv4Address};
use std::cell::{Cell, RefCell};
use std::future::poll_fn;
use std::io;
use std::pin::Pin;
use std::rc::Rc;
use std::task::{Context as TaskContext, Poll};
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::Notify;

/// Per-direction TCP buffer size (64 KiB).
const TCP_BUFFER_SIZE: usize = 65_535;

/// Cloudflare WARP's tunnel-side default gateway.
const WARP_GATEWAY: Ipv4Address = Ipv4Address::new(172, 16, 0, 1);

/// Longest the driver sleeps when smoltcp has no deadline of its own.
///
/// A ceiling, not a schedule: an inbound packet or an egress notification wakes
/// the driver sooner, so this only bounds how stale a purely internal timer can
/// get.
const MAX_IDLE_SLICE: Duration = Duration::from_millis(100);

/// Ephemeral port range, per IANA.
const EPHEMERAL_FIRST: u16 = 49_152;
const EPHEMERAL_LAST: u16 = 65_535;

/// Monotonic smoltcp clock anchored at first use.
fn smoltcp_now() -> SmoltcpInstant {
    static START: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
    let start = START.get_or_init(Instant::now);
    SmoltcpInstant::from_micros(start.elapsed().as_micros() as i64)
}

/// Parse a possibly CIDR-suffixed dotted-quad into raw octets.
///
/// Delegating to [`Ipv4Addr`](std::net::Ipv4Addr) rather than splitting on `.`
/// and keeping whichever components happened to parse: the hand-rolled version
/// *discarded* unparseable labels instead of rejecting them, so `1.x.2.3.4`
/// silently became `1.2.3.4` and the tunnel came up on an address the account
/// was never assigned.
fn parse_ipv4_octets(addr: &str) -> Result<[u8; 4], ProxyError> {
    addr.split('/')
        .next()
        .unwrap_or(addr)
        .parse::<std::net::Ipv4Addr>()
        .map(|parsed| parsed.octets())
        .map_err(|e| ProxyError::TunnelError {
            details: format!("Invalid local IPv4 address '{addr}': {e}"),
        })
}

/// The routable half of the stack: what `Interface::poll` needs together.
///
/// One struct because smoltcp requires both at once; splitting them into two
/// cells would only invite borrowing one without the other.
struct Netif {
    interface: Interface,
    sockets: SocketSet<'static>,
}

/// Everything more than one task touches, and nothing else.
pub struct Shared {
    net: RefCell<Netif>,
    /// Resolver answers. Not in [`Netif`]: name resolution has nothing to do
    /// with routing, and a fetch looking a name up must not block the driver's
    /// poll. It carries its own cell, so no borrow guard escapes to a caller.
    names: DnsCache,
    /// Replaced wholesale when the session is rebuilt, so readers always see
    /// the live session's counters rather than a dead one's.
    status: RefCell<TransportStatus>,
    /// Rung by a socket that has queued bytes the driver must put on the wire.
    egress: Notify,
    /// Rung by the driver whenever the session's connectedness may have changed.
    liveness: Notify,
    /// Ephemeral port cursor. A [`Cell`] because a `u16` is `Copy` — a
    /// `RefCell` here would buy a runtime borrow check for nothing.
    next_port: Cell<u16>,
    local_ipv4: [u8; 4],
}

/// A tunnel: a handle onto the shared stack, cloneable by every fetch.
#[derive(Clone)]
pub struct Tunnel {
    shared: Rc<Shared>,
}

impl Tunnel {
    /// Build the stack and the driver that will run it. No I/O yet.
    ///
    /// Returns the handle callers fetch through and the future that must be
    /// spawned for any of it to work — separated so that "I have a tunnel" and
    /// "something is pumping it" cannot silently diverge.
    pub fn new(config: &WarpConfig) -> Result<(Self, Driver), ProxyError> {
        let (transport, source) = MasqueTransport::new(config)?;
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

        let shared = Rc::new(Shared {
            net: RefCell::new(Netif {
                interface,
                sockets: SocketSet::new(Vec::new()),
            }),
            names: DnsCache::new(),
            status: RefCell::new(transport.status()),
            egress: Notify::new(),
            liveness: Notify::new(),
            next_port: Cell::new(EPHEMERAL_FIRST),
            local_ipv4,
        });

        let driver = Driver {
            shared: Rc::clone(&shared),
            config: config.clone(),
            device,
            transport,
            source,
        };
        Ok((Self { shared }, driver))
    }

    /// Whether the session is up right now.
    pub fn is_connected(&self) -> bool {
        self.shared.status.borrow().is_connected()
    }

    /// Live transport statistics.
    pub fn stats(&self) -> TunnelStats {
        self.shared.status.borrow().stats()
    }

    /// The WARP endpoint this tunnel targets.
    pub fn endpoint(&self) -> std::net::SocketAddr {
        self.shared.status.borrow().endpoint()
    }

    /// Which transport is carrying this tunnel.
    pub fn protocol(&self) -> &'static str {
        MasqueTransport::PROTOCOL
    }

    /// The name sent in the TLS ClientHello for this tunnel.
    pub fn sni(&self) -> &'static str {
        MasqueTransport::SNI
    }

    /// The tunnel's local IPv4 address octets.
    pub fn local_ipv4(&self) -> [u8; 4] {
        self.shared.local_ipv4
    }

    /// The resolver-answer cache.
    pub fn names(&self) -> &DnsCache {
        &self.shared.names
    }

    /// Wait until the session is up, or `timeout` elapses.
    ///
    /// The driver rebuilds on its own; this is how a fetch waits for that to
    /// finish instead of racing it with a rebuild of its own.
    pub async fn ready(&self, timeout: Duration) -> Result<(), ProxyError> {
        let deadline = Instant::now() + timeout;
        while !self.is_connected() {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(ProxyError::Timeout {
                    seconds: timeout.as_secs() as u32,
                });
            }
            // Subscribing before the check would be the correct order for a
            // pure edge signal; `Notify` stores one permit, so a wake that
            // lands between the check and the await is not lost.
            let _ = tokio::time::timeout(remaining, self.shared.liveness.notified()).await;
        }
        Ok(())
    }

    /// Open a TCP connection through the tunnel.
    pub async fn connect(
        &self,
        remote: IpAddress,
        remote_port: u16,
        timeout: Duration,
    ) -> Result<TunnelSocket, ProxyError> {
        let handle = self.start_connect(remote, remote_port)?;
        let socket = TunnelSocket {
            shared: Rc::clone(&self.shared),
            handle,
        };
        self.shared.egress.notify_one();

        match tokio::time::timeout(timeout, socket.established()).await {
            Ok(Ok(())) => Ok(socket),
            Ok(Err(e)) => Err(e),
            // `socket` drops here, which closes and removes it — the timeout
            // path leaks nothing precisely because cleanup is `Drop`, not a
            // step someone has to remember on every exit.
            Err(_) => Err(ProxyError::Timeout {
                seconds: timeout.as_secs() as u32,
            }),
        }
    }

    /// Add a socket and start its handshake. Synchronous: one borrow, no await.
    fn start_connect(
        &self,
        remote: IpAddress,
        remote_port: u16,
    ) -> Result<SocketHandle, ProxyError> {
        let mut net = self.shared.net.borrow_mut();
        let local_port = self.allocate_local_port(&net.sockets)?;

        let rx = SocketBuffer::new(vec![0u8; TCP_BUFFER_SIZE]);
        let tx = SocketBuffer::new(vec![0u8; TCP_BUFFER_SIZE]);
        let handle = net.sockets.add(TcpSocket::new(rx, tx));

        let Netif { interface, sockets } = &mut *net;
        sockets
            .get_mut::<TcpSocket>(handle)
            .connect(interface.context(), (remote, remote_port), local_port)
            .map_err(|e| {
                sockets.remove(handle);
                ProxyError::TunnelError {
                    details: format!("TCP connect failed: {e}"),
                }
            })?;
        Ok(handle)
    }

    /// Claim an ephemeral port no live socket is already bound to.
    ///
    /// The cursor alone was enough while one fetch ran at a time. With several
    /// in flight it can wrap onto a port still in use, so the set is consulted:
    /// linear in open sockets, of which there are a handful.
    fn allocate_local_port(&self, sockets: &SocketSet<'static>) -> Result<u16, ProxyError> {
        let span = usize::from(EPHEMERAL_LAST - EPHEMERAL_FIRST) + 1;
        (0..span)
            .map(|_| {
                let port = self.shared.next_port.get();
                self.shared.next_port.set(if port == EPHEMERAL_LAST {
                    EPHEMERAL_FIRST
                } else {
                    port + 1
                });
                port
            })
            .find(|port| !port_in_use(sockets, *port))
            .ok_or_else(|| ProxyError::TunnelError {
                details: "No free ephemeral port".to_string(),
            })
    }
}

/// Whether any live socket already holds `port` locally.
fn port_in_use(sockets: &SocketSet<'static>, port: u16) -> bool {
    sockets.iter().any(|(_, socket)| {
        // Irrefutable: `socket-tcp` is the only socket feature compiled in, so
        // the variant set is closed here by the manifest, not by a wildcard
        // that would silently absorb a second kind if one were ever enabled.
        let smoltcp::socket::Socket::Tcp(tcp) = socket;
        tcp.local_endpoint().is_some_and(|end| end.port == port)
    })
}

/// Owns the transport and pumps packets between it and the stack.
///
/// Separate from [`Tunnel`] because these three are single-owner: nothing else
/// reads the device, the transport or the packet source, so nothing else needs
/// them shared.
pub struct Driver {
    shared: Rc<Shared>,
    config: WarpConfig,
    device: VirtualDevice,
    transport: MasqueTransport,
    source: PacketSource,
}

impl Driver {
    /// Run until the tunnel handle is dropped.
    ///
    /// Reconnection lives here rather than in a fetch: a fetch that rebuilt the
    /// session would tear down every *other* fetch's socket to fix its own.
    pub async fn run(mut self, handshake_timeout: Duration) {
        loop {
            if let Err(e) = self.transport.initiate_handshake() {
                log::warn!("MASQUE handshake could not start: {e}");
                return;
            }
            self.shared.liveness.notify_waiters();

            self.pump(handshake_timeout).await;

            // The session is gone and cannot be restarted — `initiate_handshake`
            // consumed what it needed. A rebuilt transport is the only honest
            // reconnection, and swapping the status handle is what makes
            // diagnostics report the new session rather than the dead one.
            log::warn!("MASQUE session ended; rebuilding");
            match MasqueTransport::new(&self.config) {
                Ok((transport, source)) => {
                    *self.shared.status.borrow_mut() = transport.status();
                    self.transport = transport;
                    self.source = source;
                    self.shared.liveness.notify_waiters();
                }
                Err(e) => {
                    log::error!("cannot rebuild MASQUE transport: {e}");
                    return;
                }
            }
        }
    }

    /// Move packets in both directions until the session ends.
    async fn pump(&mut self, handshake_timeout: Duration) {
        let started = Instant::now();
        let mut was_connected = false;

        loop {
            let delay = self.poll_once();

            let connected = self.transport.is_connected();
            if connected != was_connected {
                was_connected = connected;
                self.shared.liveness.notify_waiters();
            }
            if !connected && started.elapsed() > handshake_timeout {
                return;
            }

            tokio::select! {
                packet = self.source.recv() => match packet {
                    Some(packet) => self.device.push_inbound(packet),
                    // Every sender is gone: the session thread has exited.
                    None => return,
                },
                () = self.shared.egress.notified() => {}
                () = tokio::time::sleep(delay) => {}
            }
        }
    }

    /// One synchronous turn of the stack, returning how long it may now sleep.
    ///
    /// Deliberately not `async`: the [`Netif`] borrow lives and dies inside it,
    /// so no borrow can reach an `.await`.
    fn poll_once(&mut self) -> Duration {
        while let Some(packet) = self.source.try_recv() {
            self.device.push_inbound(packet);
        }

        let now = smoltcp_now();
        let delay = {
            let mut net = self.shared.net.borrow_mut();
            let Netif { interface, sockets } = &mut *net;
            interface.poll(now, &mut self.device, sockets);
            interface.poll_delay(now, sockets)
        };

        while let Some(packet) = self.device.pop_outbound() {
            if let Err(e) = self.transport.send_ip(&packet) {
                log::debug!("dropping outbound packet: {e}");
            }
        }

        delay.map_or(MAX_IDLE_SLICE, |d| {
            Duration::from_micros(d.total_micros()).min(MAX_IDLE_SLICE)
        })
    }
}

/// One tunnelled TCP connection.
///
/// Closing is [`Drop`], so every exit path — success, error, timeout,
/// cancellation — releases the socket without a caller remembering to.
pub struct TunnelSocket {
    shared: Rc<Shared>,
    handle: SocketHandle,
}

impl TunnelSocket {
    /// Resolve once the handshake completes, or fail if the peer refused.
    async fn established(&self) -> Result<(), ProxyError> {
        poll_fn(|cx| {
            let mut net = self.shared.net.borrow_mut();
            let socket = net.sockets.get_mut::<TcpSocket>(self.handle);
            match socket.state() {
                TcpState::Established => Poll::Ready(Ok(())),
                TcpState::Closed => Poll::Ready(Err(ProxyError::TunnelError {
                    details: "TCP connection refused".to_string(),
                })),
                _ => {
                    // Both, because smoltcp wakes whichever direction the state
                    // change made ready and a handshake is neither read nor
                    // write until it completes.
                    socket.register_recv_waker(cx.waker());
                    socket.register_send_waker(cx.waker());
                    Poll::Pending
                }
            }
        })
        .await
    }
}

impl Drop for TunnelSocket {
    /// Send a FIN and drop the socket.
    ///
    /// The FIN goes out on the driver's next turn, which the notification
    /// schedules; waiting for it here would mean blocking a `Drop`, and the
    /// peer's view of a connection we have already abandoned is not worth that.
    fn drop(&mut self) {
        let mut net = self.shared.net.borrow_mut();
        net.sockets.get_mut::<TcpSocket>(self.handle).close();
        net.sockets.remove(self.handle);
        drop(net);
        self.shared.egress.notify_one();
    }
}

impl AsyncRead for TunnelSocket {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let mut net = self.shared.net.borrow_mut();
        let socket = net.sockets.get_mut::<TcpSocket>(self.handle);

        if socket.can_recv() {
            let taken = socket
                .recv_slice(buf.initialize_unfilled())
                .map_err(io::Error::other)?;
            buf.advance(taken);
            return Poll::Ready(Ok(()));
        }
        if !socket.may_recv() {
            // Peer closed the read half and nothing is buffered: EOF, which
            // `ReadBuf` expresses as a successful read of zero bytes.
            return Poll::Ready(Ok(()));
        }
        socket.register_recv_waker(cx.waker());
        Poll::Pending
    }
}

impl AsyncWrite for TunnelSocket {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        let queued = {
            let mut net = self.shared.net.borrow_mut();
            let socket = net.sockets.get_mut::<TcpSocket>(self.handle);

            if !socket.may_send() {
                return Poll::Ready(Err(io::Error::new(
                    io::ErrorKind::BrokenPipe,
                    "tunnel connection closed",
                )));
            }
            match socket.send_slice(buf) {
                Ok(0) => {
                    socket.register_send_waker(cx.waker());
                    return Poll::Pending;
                }
                Ok(written) => written,
                Err(e) => return Poll::Ready(Err(io::Error::other(e))),
            }
        };
        // Outside the borrow: waking the driver while holding the cell it needs
        // would be correct only by accident of scheduling order.
        self.shared.egress.notify_one();
        Poll::Ready(Ok(queued))
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        let mut net = self.shared.net.borrow_mut();
        let socket = net.sockets.get_mut::<TcpSocket>(self.handle);
        if socket.send_queue() == 0 {
            return Poll::Ready(Ok(()));
        }
        socket.register_send_waker(cx.waker());
        drop(net);
        self.shared.egress.notify_one();
        Poll::Pending
    }

    /// Shutdown is [`Drop`]'s job; a socket is never half-closed on purpose.
    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{MasqueCredentials, WarpAccountData, WarpInterfaceConfig, WarpPeerConfig};
    use crate::provisioning::WarpProvisioner;
    use base64::{engine::general_purpose::STANDARD as BASE64, Engine};

    /// A config with real, freshly generated MASQUE credentials.
    ///
    /// The keys are genuine DER — `MasqueTransport::new` decodes them — but no
    /// session starts, because construction no longer connects. These tests
    /// therefore touch no network.
    fn test_config() -> WarpConfig {
        let private_key = WarpProvisioner::generate_registration_key();
        let peer_public = WarpProvisioner::generate_registration_key();
        let (masque_private, masque_public) =
            WarpProvisioner::generate_masque_keypair().expect("generate MASQUE keypair");
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
            masque: Some(MasqueCredentials {
                ec_private_key_der: BASE64.encode(&masque_private),
                endpoint_pub_key_spki: BASE64.encode(&masque_public),
            }),
        }
    }

    #[test]
    fn tunnel_creation_succeeds() {
        assert!(Tunnel::new(&test_config()).is_ok());
    }

    #[test]
    fn tunnel_not_connected_initially() {
        let (tunnel, _driver) = Tunnel::new(&test_config()).expect("build tunnel");
        assert!(!tunnel.is_connected());
    }

    #[test]
    fn parse_ipv4_handles_cidr() {
        assert_eq!(parse_ipv4_octets("172.16.0.2/32").unwrap(), [172, 16, 0, 2]);
        assert_eq!(parse_ipv4_octets("10.0.0.1").unwrap(), [10, 0, 0, 1]);
        assert!(parse_ipv4_octets("not-an-ip").is_err());
    }

    /// A malformed address must be rejected, never silently repaired by
    /// dropping the labels that failed to parse.
    #[test]
    fn parse_ipv4_rejects_rather_than_salvages() {
        for malformed in ["1.x.2.3.4", "1.2.3", "1.2.3.4.5", "256.1.1.1", "", "1..2.3"] {
            assert!(
                parse_ipv4_octets(malformed).is_err(),
                "{malformed} must not parse"
            );
        }
    }

    #[test]
    fn local_port_allocation_wraps() {
        let (tunnel, _driver) = Tunnel::new(&test_config()).expect("build tunnel");
        let sockets = SocketSet::new(Vec::new());
        tunnel.shared.next_port.set(EPHEMERAL_LAST);
        assert_eq!(
            tunnel.allocate_local_port(&sockets).unwrap(),
            EPHEMERAL_LAST
        );
        assert_eq!(
            tunnel.allocate_local_port(&sockets).unwrap(),
            EPHEMERAL_FIRST
        );
    }

    /// Concurrency made this reachable: the cursor alone would hand the same
    /// port to a second fetch once it wrapped onto a live one.
    #[test]
    fn a_port_held_by_a_live_socket_is_skipped() {
        let (tunnel, _driver) = Tunnel::new(&test_config()).expect("build tunnel");
        let first = tunnel
            .start_connect(IpAddress::v4(1, 1, 1, 1), 443)
            .expect("open first");

        let net = tunnel.shared.net.borrow();
        let taken = net
            .sockets
            .get::<TcpSocket>(first)
            .local_endpoint()
            .expect("connected socket has a local endpoint")
            .port;

        // Rewind the cursor onto the port already in use.
        tunnel.shared.next_port.set(taken);
        let next = tunnel
            .allocate_local_port(&net.sockets)
            .expect("a free port exists");
        assert_ne!(next, taken, "must not reuse a live socket's port");
    }
}
