//! Tunnel lifecycle manager.
//!
//! [`TunnelManager`] owns the [`WarpTunnel`] on a dedicated OS thread and
//! exposes a synchronous, thread-safe request API over a command channel. This
//! is deliberate message passing rather than shared mutable state: the tunnel —
//! and the single-threaded smoltcp state machine inside it — is only ever
//! touched by its worker thread, so no `Mutex` guards the hot path.

use crate::config::{FetchLimits, WarpConfig};
use crate::error::ProxyError;
use crate::http::{self, FetchOutcome};
use crate::tunnel::fault::Fault;
use crate::tunnel::http1::ClientProfile;
use crate::tunnel::stack::WarpTunnel;
use crate::tunnel::stats::TunnelStats;
use std::sync::mpsc::{channel, Receiver, RecvTimeoutError, Sender};
use std::thread::JoinHandle;
use std::time::Duration;

/// How long to wait for the initial (and any re-)handshake to complete.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(15);

/// How long the worker may sit idle before it proves the tunnel still works.
///
/// Only *idle* time counts, because a fetch that succeeded is a stronger
/// liveness signal than any probe: while traffic flows, this never fires.
const HEALTH_INTERVAL: Duration = Duration::from_secs(30);

/// How many times a transport failure is retried before the caller sees it.
///
/// Small on purpose. Each retry costs a handshake, and a fault that survives two
/// fresh tunnels is not the tunnel's.
const MAX_TRANSPORT_RETRIES: u32 = 2;

/// Whether the tunnel currently has a live session.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionState {
    /// No completed handshake.
    Disconnected,
    /// A handshake has completed and the session is usable.
    Connected,
}

impl ConnectionState {
    /// The wire name the Android diagnostics screen matches on.
    ///
    /// Lives with the variants so adding a third state is a compile error at
    /// this match rather than a silently missing case at the FFI boundary.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Connected => "connected",
            Self::Disconnected => "disconnected",
        }
    }
}

impl From<bool> for ConnectionState {
    fn from(connected: bool) -> Self {
        if connected {
            Self::Connected
        } else {
            Self::Disconnected
        }
    }
}

/// A snapshot of the **live session**, and nothing else.
///
/// Account identity lives in [`crate::types::WarpStoredConfig`] and is not
/// repeated here. The two used to overlap on ten fields, which meant the same
/// fact could be read from two places that were populated separately — and the
/// endpoint was exactly where they disagreed, one reporting the address the
/// session dialled and the other the WireGuard host registration returned.
#[derive(Debug, Clone)]
pub struct TunnelDiagnostics {
    /// Live connection state.
    pub connection_state: ConnectionState,
    /// Which transport is carrying the tunnel.
    pub protocol: &'static str,
    /// Address the session actually dials.
    pub endpoint_address: String,
    /// UDP port the session actually dials.
    pub endpoint_port: u16,
    /// Name sent in the TLS ClientHello.
    pub endpoint_sni: &'static str,
    /// Seconds since the last completed handshake, if any.
    pub last_handshake_secs: Option<u64>,
    /// Plaintext bytes transmitted into the tunnel.
    pub tx_bytes: u64,
    /// Plaintext bytes received from the tunnel.
    pub rx_bytes: u64,
}

/// Everything one fetch needs, and nothing about the tunnel that will carry it.
///
/// A value rather than four parameters because retrying means running *the
/// same* request: a struct says that once, where re-threading four arguments
/// through a retry loop would say it at every call. Nothing here is consumed by
/// running it, which is what makes a retry a repeat rather than a resume.
#[derive(Debug, Clone)]
pub struct FetchRequest {
    /// Absolute `http(s)` URL to fetch.
    pub url: String,
    /// Extra headers, minus anything the profile owns.
    pub headers: Vec<(String, String)>,
    /// Who the request presents itself as.
    pub profile: ClientProfile,
    /// Size, redirect and timeout ceilings.
    pub limits: FetchLimits,
}

impl FetchRequest {
    /// A fetch of `url` with no extra headers.
    pub fn new(url: impl Into<String>, profile: ClientProfile, limits: FetchLimits) -> Self {
        Self {
            url: url.into(),
            headers: Vec::new(),
            profile,
            limits,
        }
    }

    /// Add caller headers the profile does not already own.
    pub fn with_headers(self, headers: Vec<(String, String)>) -> Self {
        Self { headers, ..self }
    }
}

/// A unit of work for the tunnel worker thread.
enum Command {
    Fetch {
        request: FetchRequest,
        reply: Sender<Result<FetchOutcome, ProxyError>>,
    },
    Diagnostics {
        reply: Sender<TunnelDiagnostics>,
    },
}

/// What woke the worker.
///
/// The idle case is a first-class event rather than an error branch on `recv`:
/// having nothing to do for [`HEALTH_INTERVAL`] is exactly when the tunnel needs
/// checking, so the timeout *is* the schedule and no timer thread is needed.
enum Wakeup {
    /// Something asked for work.
    Serve(Command),
    /// Nobody asked for anything for a whole [`HEALTH_INTERVAL`].
    Idle,
    /// Every handle is gone; the worker is done.
    ShutDown,
}

impl Wakeup {
    fn receive(rx: &Receiver<Command>, within: Duration) -> Self {
        match rx.recv_timeout(within) {
            Ok(command) => Self::Serve(command),
            Err(RecvTimeoutError::Timeout) => Self::Idle,
            Err(RecvTimeoutError::Disconnected) => Self::ShutDown,
        }
    }
}

/// Owns the tunnel worker thread and dispatches commands to it.
pub struct TunnelManager {
    tx: Sender<Command>,
    worker: Option<JoinHandle<()>>,
}

impl TunnelManager {
    /// Start the worker thread and block until the first handshake completes.
    ///
    /// `config` is the provisioned WARP configuration; the worker needs it to
    /// build the tunnel, not to answer diagnostics, which read the live tunnel.
    pub fn start(config: WarpConfig) -> Result<Self, ProxyError> {
        let (tx, rx) = channel::<Command>();
        let (ready_tx, ready_rx) = channel::<Result<(), ProxyError>>();

        let worker = std::thread::Builder::new()
            .name("warp-tunnel".to_string())
            .spawn(move || worker_loop(config, rx, ready_tx))
            .map_err(|e| ProxyError::TunnelError {
                details: format!("Failed to spawn tunnel thread: {e}"),
            })?;

        match ready_rx.recv() {
            Ok(Ok(())) => Ok(Self {
                tx,
                worker: Some(worker),
            }),
            Ok(Err(e)) => {
                let _ = worker.join();
                Err(e)
            }
            Err(_) => Err(ProxyError::TunnelError {
                details: "Tunnel worker exited before signalling readiness".to_string(),
            }),
        }
    }

    /// Fetch a URL through the tunnel, retrying transport faults.
    pub fn fetch(&self, request: FetchRequest) -> Result<FetchOutcome, ProxyError> {
        let (reply, reply_rx) = channel();
        self.tx
            .send(Command::Fetch { request, reply })
            .map_err(|_| ProxyError::TunnelError {
                details: "Tunnel worker is no longer running".to_string(),
            })?;
        reply_rx.recv().map_err(|_| ProxyError::TunnelError {
            details: "Tunnel worker dropped the request".to_string(),
        })?
    }

    /// Collect a diagnostics snapshot from the worker.
    pub fn diagnostics(&self) -> Result<TunnelDiagnostics, ProxyError> {
        let (reply, reply_rx) = channel();
        self.tx
            .send(Command::Diagnostics { reply })
            .map_err(|_| ProxyError::TunnelError {
                details: "Tunnel worker is no longer running".to_string(),
            })?;
        reply_rx.recv().map_err(|_| ProxyError::TunnelError {
            details: "Tunnel worker dropped the request".to_string(),
        })
    }
}

impl Drop for TunnelManager {
    fn drop(&mut self) {
        // Dropping the sender closes the channel; the worker loop then exits.
        if let Some(worker) = self.worker.take() {
            // Detach: we cannot block indefinitely in Drop, but closing the
            // channel above guarantees the loop terminates promptly.
            drop(std::mem::replace(&mut self.tx, channel().0));
            let _ = worker.join();
        }
    }
}

/// The worker thread body: own the tunnel and service commands until the
/// command channel closes.
fn worker_loop(
    config: WarpConfig,
    rx: Receiver<Command>,
    ready_tx: Sender<Result<(), ProxyError>>,
) {
    let mut tunnel = match WarpTunnel::new(&config) {
        Ok(tunnel) => tunnel,
        Err(e) => {
            let _ = ready_tx.send(Err(e));
            return;
        }
    };

    match tunnel.connect(HANDSHAKE_TIMEOUT) {
        Ok(()) => {
            let _ = ready_tx.send(Ok(()));
        }
        Err(e) => {
            let _ = ready_tx.send(Err(e));
            return;
        }
    }

    loop {
        match Wakeup::receive(&rx, HEALTH_INTERVAL) {
            Wakeup::Serve(Command::Fetch { request, reply }) => {
                let _ = reply.send(serve_fetch(&mut tunnel, &request));
            }
            Wakeup::Serve(Command::Diagnostics { reply }) => {
                let _ = reply.send(build_diagnostics(&tunnel));
            }
            Wakeup::Idle => check_health(&mut tunnel),
            Wakeup::ShutDown => return,
        }
    }
}

/// Run `request`, rebuilding the tunnel and repeating it on a transport fault.
///
/// Safe to repeat because a [`FetchRequest`] is a description, not a session:
/// it is a `GET` over a connection that closes afterwards, so it carries no
/// progress that a second attempt would duplicate or resume. The retry is
/// therefore a plain re-run, and the only question is whether it could help —
/// which is what [`Fault`] answers.
fn serve_fetch(
    tunnel: &mut WarpTunnel,
    request: &FetchRequest,
) -> Result<FetchOutcome, ProxyError> {
    for attempt in 0..=MAX_TRANSPORT_RETRIES {
        // A fault may have left the tunnel *believing* it is connected, so the
        // first attempt asks and later ones rebuild unconditionally.
        let ready = if attempt == 0 {
            ensure_connected(tunnel)
        } else {
            tunnel.connect(HANDSHAKE_TIMEOUT)
        };

        let outcome = ready.and_then(|()| {
            http::fetch(
                tunnel,
                &request.url,
                &request.headers,
                &request.limits,
                &request.profile,
            )
        });

        match outcome.map_err(Fault::from) {
            Ok(outcome) => return Ok(outcome),
            Err(fault) if !fault.is_transport() => return Err(fault.into_error()),
            Err(fault) if attempt == MAX_TRANSPORT_RETRIES => return Err(fault.into_error()),
            Err(fault) => log::warn!(
                "transport fault on attempt {}, rebuilding tunnel: {}",
                attempt + 1,
                fault.into_error()
            ),
        }
    }

    // `0..=MAX` always returns from inside the loop; this is unreachable without
    // an empty range, which the type forbids.
    Err(ProxyError::TunnelError {
        details: "fetch retry loop ended without a verdict".to_string(),
    })
}

/// Prove the tunnel still carries traffic, and rebuild it when it does not.
///
/// The probe is a real fetch through the tunnel rather than a look at local
/// state: `is_connected` reports what the session *believes*, and the failure
/// this exists to catch is exactly a session that believes wrongly.
fn check_health(tunnel: &mut WarpTunnel) {
    if !tunnel.is_connected() {
        rebuild(tunnel, "session had lapsed while idle");
        return;
    }

    let probe = crate::verify::trace_request();
    let outcome = http::fetch(
        tunnel,
        &probe.url,
        &probe.headers,
        &probe.limits,
        &probe.profile,
    );

    // An endpoint fault answers the question the probe asked: something at the
    // far end replied, so the tunnel carried it. Rebuilding on a resolver
    // verdict or a 503 from Cloudflare would tear down a working session for a
    // condition it did not cause.
    match outcome.map_err(Fault::from) {
        Ok(_) => log::debug!("tunnel health probe succeeded"),
        Err(fault) if fault.is_transport() => rebuild(
            tunnel,
            &format!("health probe failed: {}", fault.into_error()),
        ),
        Err(fault) => log::debug!(
            "health probe reached the far end and was refused: {}",
            fault.into_error()
        ),
    }
}

/// Re-handshake, logging why and whether it worked.
fn rebuild(tunnel: &mut WarpTunnel, reason: &str) {
    log::warn!("rebuilding tunnel: {reason}");
    match tunnel.connect(HANDSHAKE_TIMEOUT) {
        Ok(()) => log::info!("tunnel rebuilt"),
        // Nothing to report to: the next fetch retries, and the next idle tick
        // tries again.
        Err(e) => log::warn!("tunnel rebuild failed: {e}"),
    }
}

/// Ensure a live tunnel session, re-handshaking if it has lapsed.
fn ensure_connected(tunnel: &mut WarpTunnel) -> Result<(), ProxyError> {
    if tunnel.is_connected() {
        return Ok(());
    }
    tunnel.connect(HANDSHAKE_TIMEOUT)
}

/// Assemble a [`TunnelDiagnostics`] snapshot.
///
/// Every field is read from the live tunnel. Nothing is taken from the stored
/// configuration, which is what keeps the two records from disagreeing.
fn build_diagnostics(tunnel: &WarpTunnel) -> TunnelDiagnostics {
    let stats: TunnelStats = tunnel.stats();
    let endpoint = tunnel.endpoint();
    TunnelDiagnostics {
        connection_state: tunnel.is_connected().into(),
        protocol: tunnel.protocol(),
        endpoint_address: endpoint.ip().to_string(),
        endpoint_port: endpoint.port(),
        endpoint_sni: tunnel.sni(),
        last_handshake_secs: stats.since_handshake.map(|d| d.as_secs()),
        tx_bytes: stats.tx_bytes,
        rx_bytes: stats.rx_bytes,
    }
}
