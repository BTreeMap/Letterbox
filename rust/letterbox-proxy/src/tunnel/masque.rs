//! MASQUE transport: CONNECT-IP over HTTP/3, via [`usque_core`].
//!
//! # Why MASQUE
//!
//! It replaced a userspace WireGuard transport, which was trivially
//! fingerprinted and widely blocked. MASQUE carries the same IP packets inside
//! QUIC datagrams on UDP/443, indistinguishable from ordinary HTTP/3 to
//! anything short of deep behavioural analysis.
//!
//! # Shape
//!
//! [`usque_core::run_tunnel_session`] is an async loop that owns its socket and
//! runs until the connection ends. The rest of Letterbox's tunnel is a blocking
//! poll loop on one worker thread. Rather than convert either side, this module
//! runs the session on its own thread with a current-thread runtime and bridges
//! the two with [`PacketDuplex`], whose framing contract is what makes the
//! hand-off safe.
//!
//! Everything above it — the `smoltcp` interface, TLS, HTTP/1.1, DNS — works in
//! raw IP packets and is unaware of how they are carried.

use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool as StdAtomicBool, Ordering as StdOrdering};
use std::sync::mpsc::{sync_channel, Receiver, RecvTimeoutError, SyncSender};
use std::sync::{Arc, OnceLock};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use usque_core::tunnel::{Stats, TunnelConfig};
use usque_core::{AtomicBool, TunnelIdentity};

use crate::config::WarpConfig;
use crate::error::ProxyError;
use crate::tunnel::duplex::{PacketDuplex, PACKET_QUEUE_DEPTH};
use crate::tunnel::stats::TunnelStats;

/// Cloudflare's consumer MASQUE anycast endpoint (IPv4).
///
/// The registration API returns *WireGuard* endpoints; the MASQUE data plane
/// lives at a different fixed address, so this is not read from the response.
pub const MASQUE_ENDPOINT_IPV4: &str = "162.159.198.1";

/// MASQUE runs HTTP/3, which is QUIC, which is UDP/443. Not negotiable.
pub const MASQUE_ENDPOINT_PORT: u16 = 443;

/// The name sent in the TLS ClientHello.
///
/// Deliberately *not* `consumer-masque.cloudflareclient.com`, which the
/// reference implementations use. The SNI is the one identifier a passive
/// observer can read — the `:authority` on the CONNECT request is inside the
/// encrypted stream — and any `*.cloudflareclient.com` name labels the
/// connection as WARP, which defeats the purpose of tunnelling at all.
///
/// Substituting a name is safe here because peer identity does not depend on it:
/// the session runs with `verify_peer(false)` and pins the endpoint by SPKI. The
/// endpoint's willingness to serve this name is a server-side policy question,
/// verified empirically rather than assumed.
pub const MASQUE_SNI: &str = "api.cloudflare.com";

/// Interval between QUIC keepalives.
const KEEPALIVE: Duration = Duration::from_secs(25);

/// How long QUIC tolerates silence before closing.
///
/// Must exceed [`KEEPALIVE`]. Bounded rather than infinite so an unreachable
/// endpoint ends the session on its own — otherwise the session thread never
/// exits and `Drop` blocks joining it.
const IDLE_TIMEOUT: Duration = Duration::from_secs(60);

/// Path MTU for the tunnelled interface.
///
/// 1280 is the IPv6 minimum, which no conforming path may fragment below. The
/// session grows it via PMTU discovery once running.
const TUNNEL_MTU: u32 = 1280;

/// A MASQUE tunnel to Cloudflare WARP.
///
/// Owns the session thread; dropping it tears the session down.
pub struct MasqueTransport {
    endpoint: SocketAddr,
    /// Outbound packets, stack → session.
    outbound: tokio::sync::mpsc::Sender<Vec<u8>>,
    /// Inbound packets, session → stack.
    inbound: Receiver<Vec<u8>>,
    stats: Arc<Stats>,
    established: Arc<AtomicBool>,
    /// Set when the session ends, so `is_connected` cannot report a live tunnel
    /// after the thread has exited.
    finished: Arc<StdAtomicBool>,
    /// When the tunnel was first seen connected. `OnceLock` because it is
    /// written once, lazily, from `&self` in [`MasqueTransport::stats`].
    connected_at: OnceLock<Instant>,
    /// Everything the session needs, held until [`initiate_handshake`] consumes
    /// it. `Some` means "not yet started"; taking it is what makes starting
    /// idempotent without a separate flag that could disagree with reality.
    ///
    /// [`initiate_handshake`]: MasqueTransport::initiate_handshake
    pending: Option<PendingSession>,
    session: Option<JoinHandle<()>>,
}

/// The half of a transport that only exists before the session starts.
struct PendingSession {
    identity: TunnelIdentity,
    outbound: tokio::sync::mpsc::Receiver<Vec<u8>>,
    inbound: SyncSender<Vec<u8>>,
}

impl MasqueTransport {
    /// Name reported by diagnostics and asserted by the on-device test.
    pub const PROTOCOL: &'static str = "masque";

    /// Build a transport for the provisioned account, **without connecting**.
    ///
    /// Construction is pure: it validates and decodes credentials and nothing
    /// else. The session thread starts at
    /// [`initiate_handshake`](Self::initiate_handshake), so constructing a
    /// transport never touches the network — which is what lets the stack be
    /// unit-tested offline, and keeps "I have a transport" from meaning "I am
    /// dialling Cloudflare".
    ///
    /// # Errors
    ///
    /// Fails if the account carries no MASQUE credentials or they do not decode.
    pub fn new(config: &WarpConfig) -> Result<Self, ProxyError> {
        let credentials = config
            .masque
            .as_ref()
            .ok_or_else(|| ProxyError::TunnelError {
                details: "Account has no MASQUE credentials; re-provisioning required".to_string(),
            })?;

        let identity = TunnelIdentity::new(
            credentials.decode_private_key()?,
            credentials.decode_endpoint_key()?,
        )
        .map_err(|e| ProxyError::TunnelError {
            details: format!("Invalid MASQUE identity: {e}"),
        })?;

        let endpoint: SocketAddr = format!("{MASQUE_ENDPOINT_IPV4}:{MASQUE_ENDPOINT_PORT}")
            .parse()
            .map_err(|e| ProxyError::TunnelError {
                details: format!("Invalid MASQUE endpoint: {e}"),
            })?;

        let (out_tx, out_rx) = tokio::sync::mpsc::channel(PACKET_QUEUE_DEPTH);
        let (in_tx, in_rx) = sync_channel(PACKET_QUEUE_DEPTH);

        Ok(Self {
            endpoint,
            outbound: out_tx,
            inbound: in_rx,
            stats: Arc::new(Stats::default()),
            established: Arc::new(AtomicBool::new(false)),
            finished: Arc::new(StdAtomicBool::new(false)),
            connected_at: OnceLock::new(),
            pending: Some(PendingSession {
                identity,
                outbound: out_rx,
                inbound: in_tx,
            }),
            session: None,
        })
    }

    /// The MASQUE endpoint this transport targets.
    #[must_use]
    pub fn endpoint(&self) -> SocketAddr {
        self.endpoint
    }

    /// Whether the CONNECT-IP flow is open *and* the session is still running.
    #[must_use]
    pub fn is_connected(&self) -> bool {
        self.established.load(usque_core::Ordering::Acquire)
            && !self.finished.load(StdOrdering::Acquire)
    }

    /// Live counters, mapped onto the shared [`TunnelStats`] shape.
    ///
    /// `since_handshake` is `None` until the flow opens — "not connected" and
    /// "connected just now" are different facts. RTT and loss are not surfaced:
    /// quiche measures them per-path, and reporting a plausible-looking wrong
    /// number is worse than reporting none.
    #[must_use]
    pub fn stats(&self) -> TunnelStats {
        let snapshot = self.stats.snapshot();
        TunnelStats {
            // Measured from the first *observation* of the connected state, not
            // from the CONNECT response itself, so it can under-report by up to
            // one poll interval. A stopped clock reading zero would have looked
            // more precise and been less true.
            since_handshake: self
                .is_connected()
                .then(|| self.connected_at.get_or_init(Instant::now).elapsed()),
            tx_bytes: snapshot.tx_bytes,
            rx_bytes: snapshot.rx_bytes,
            estimated_loss: 0.0,
            rtt_ms: None,
        }
    }

    /// Start the session thread, which begins connecting immediately.
    ///
    /// Idempotent: the second call finds nothing pending and returns. Returning
    /// does not mean connected — QUIC and the CONNECT exchange happen on that
    /// thread. Poll [`is_connected`](Self::is_connected), which the session sets
    /// only once the CONNECT-IP flow is open.
    ///
    /// # Errors
    ///
    /// Fails if the session thread cannot be spawned.
    pub fn initiate_handshake(&mut self) -> Result<(), ProxyError> {
        let Some(pending) = self.pending.take() else {
            return Ok(());
        };

        let endpoint = self.endpoint;
        let stats = Arc::clone(&self.stats);
        let established = Arc::clone(&self.established);
        let finished = Arc::clone(&self.finished);

        self.session = Some(
            std::thread::Builder::new()
                .name("masque-session".to_string())
                .spawn(move || {
                    run_session(
                        &pending.identity,
                        endpoint,
                        PacketDuplex::new(pending.outbound, pending.inbound),
                        stats,
                        established,
                    );
                    // Whatever happened, the tunnel is down. Recording it here
                    // rather than inferring from silence keeps `is_connected`
                    // honest for a session that dies after establishing.
                    finished.store(true, StdOrdering::Release);
                })
                .map_err(|e| ProxyError::TunnelError {
                    details: format!("Failed to spawn MASQUE session thread: {e}"),
                })?,
        );
        Ok(())
    }

    /// Collect packets that arrived from the tunnel, waiting up to `timeout`.
    ///
    /// Blocks for the first packet only, then drains without blocking, so a busy
    /// tunnel is not rate-limited to one packet per call.
    pub fn poll_incoming(&mut self, timeout: Duration) -> Result<Vec<Vec<u8>>, ProxyError> {
        let mut packets = Vec::new();

        match self.inbound.recv_timeout(timeout) {
            Ok(packet) => packets.push(packet),
            Err(RecvTimeoutError::Timeout) => return Ok(packets),
            Err(RecvTimeoutError::Disconnected) => {
                return Err(ProxyError::TunnelError {
                    details: "MASQUE session ended".to_string(),
                })
            }
        }
        packets.extend(std::iter::from_fn(|| self.inbound.try_recv().ok()));

        Ok(packets)
    }

    /// Queue one IP packet for the tunnel.
    ///
    /// Drops when the queue is full rather than blocking — see [`PacketDuplex`]
    /// for why a datagram tunnel prefers loss to stalling.
    pub fn send_ip(&mut self, packet: &[u8]) -> Result<(), ProxyError> {
        match self.outbound.try_send(packet.to_vec()) {
            Ok(()) => Ok(()),
            Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {
                log::trace!("MASQUE outbound queue full, dropping packet");
                Ok(())
            }
            Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => {
                Err(ProxyError::TunnelError {
                    details: "MASQUE session ended".to_string(),
                })
            }
        }
    }

    /// No-op: QUIC timers live inside the session loop.
    ///
    /// quiche's timers are driven by `conn.timeout()` in the session's own
    /// `select!`, so there is nothing for the poll loop to advance.
    pub fn tick(&mut self) -> Result<(), ProxyError> {
        Ok(())
    }
}

impl Drop for MasqueTransport {
    /// Tear the session down by closing its packet source.
    ///
    /// Dropping `outbound` makes the session's next read return EOF, which it
    /// treats as a closed device and exits on. We then join, so the thread
    /// cannot outlive the transport and keep a tunnel open for a proxy that
    /// believes it has shut down.
    fn drop(&mut self) {
        // Replacing the sender with a closed one is what signals EOF; the
        // session is blocked on that channel, so this is what wakes it. A
        // transport that never started has no thread and nothing to wake.
        let (closed, _) = tokio::sync::mpsc::channel(1);
        self.outbound = closed;

        if let Some(handle) = self.session.take() {
            if let Err(e) = handle.join() {
                log::warn!("MASQUE session thread panicked: {e:?}");
            }
        }
    }
}

/// Drive one session to completion on a current-thread runtime.
///
/// Errors are logged rather than propagated: the caller is a thread with no one
/// to return to, and `finished` already reports the outcome the transport needs.
fn run_session(
    identity: &TunnelIdentity,
    endpoint: SocketAddr,
    duplex: PacketDuplex,
    stats: Arc<Stats>,
    established: Arc<AtomicBool>,
) {
    let runtime = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(e) => {
            log::error!("failed to build MASQUE runtime: {e}");
            return;
        }
    };

    let tunnel_cfg = match TunnelConfig::new(
        endpoint,
        MASQUE_SNI.to_string(),
        KEEPALIVE,
        IDLE_TIMEOUT,
        TUNNEL_MTU,
    ) {
        Ok(cfg) => cfg,
        Err(e) => {
            // Unreachable while the constants above are consistent; the
            // constructor exists so that ceases to be a thing to remember.
            log::error!("invalid MASQUE tunnel configuration: {e}");
            return;
        }
    };

    // `run_tunnel_session` borrows reader and writer separately; the duplex is
    // both, so it is split rather than borrowed twice.
    let (mut reader, mut writer) = tokio::io::split(duplex);

    let outcome = runtime.block_on(usque_core::run_tunnel_session(
        identity,
        &tunnel_cfg,
        &mut reader,
        &mut writer,
        stats,
        established,
    ));

    match outcome {
        Ok(()) => log::info!("MASQUE session ended cleanly"),
        Err(e) => log::warn!("MASQUE session ended: {e:#}"),
    }
}
