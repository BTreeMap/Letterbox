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
use crate::tunnel::stack::Tunnel;
use crate::tunnel::stats::TunnelStats;
use std::cell::Cell;
use std::rc::Rc;
use std::sync::mpsc::{channel, Sender};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};
use tokio::sync::mpsc::{unbounded_channel, UnboundedReceiver, UnboundedSender};
use tokio::sync::Semaphore;
use tokio::task::{spawn_local, LocalSet};

/// How long to wait for the initial (and any re-)handshake to complete.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(15);

/// How long the worker may sit idle before it proves the tunnel still works.
///
/// Only *idle* time counts, because a fetch that succeeded is a stronger
/// liveness signal than any probe: while traffic flows, this never fires.
const HEALTH_INTERVAL: Duration = Duration::from_secs(30);

/// How many times a transport failure is retried before the caller sees it.
///
/// Small on purpose. A fault that survives two attempts against a tunnel the
/// driver is independently repairing is not the tunnel's.
const MAX_TRANSPORT_RETRIES: u32 = 2;

/// How many fetches may hold a socket at once.
///
/// Concurrency has to be bounded somewhere, and the binding resource is socket
/// buffers: smoltcp allocates 64 KiB per direction, so this is a ceiling of
/// about 2 MiB. The count of *pending* fetches is not bounded — a queued
/// `FetchRequest` is a URL and some headers — because blocking the dispatch
/// loop would stall the diagnostics command behind image traffic.
const MAX_INFLIGHT_FETCHES: usize = 16;

/// Pause before repeating a request that failed on transport.
///
/// A fetch no longer rebuilds the tunnel — that is the driver's job, and a
/// fetch that reconnected would tear every *other* in-flight fetch's socket out
/// from under it. This is how long it yields for the driver to notice.
const RETRY_PAUSE: Duration = Duration::from_millis(250);

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

/// A fetch the worker has accepted but whose result has not been collected.
///
/// `#[must_use]` because dropping one silently abandons work already in flight
/// on the tunnel.
#[must_use = "a submitted fetch is only useful once waited on"]
pub struct Pending {
    reply: std::sync::mpsc::Receiver<Result<FetchOutcome, ProxyError>>,
}

impl Pending {
    /// Block until this fetch finishes.
    pub fn wait(self) -> Result<FetchOutcome, ProxyError> {
        self.reply.recv().map_err(|_| ProxyError::TunnelError {
            details: "Tunnel worker dropped the request".to_string(),
        })?
    }
}

/// Owns the tunnel worker thread and dispatches commands to it.
pub struct TunnelManager {
    tx: UnboundedSender<Command>,
    worker: Option<JoinHandle<()>>,
}

impl TunnelManager {
    /// Start the worker thread and block until the first handshake completes.
    ///
    /// `config` is the provisioned WARP configuration; the worker needs it to
    /// build the tunnel, not to answer diagnostics, which read the live tunnel.
    pub fn start(config: WarpConfig) -> Result<Self, ProxyError> {
        let (tx, rx) = unbounded_channel::<Command>();
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

    /// Hand a fetch to the worker without waiting for it.
    ///
    /// Submission and collection are separate because the requests in a batch
    /// are *independent*: launching them all and then joining is what makes
    /// them concurrent, where one submit-and-wait per URL is a sequence no
    /// amount of concurrency inside the worker can unpick.
    pub fn submit(&self, request: FetchRequest) -> Result<Pending, ProxyError> {
        let (reply, reply_rx) = channel();
        self.tx
            .send(Command::Fetch { request, reply })
            .map_err(|_| ProxyError::TunnelError {
                details: "Tunnel worker is no longer running".to_string(),
            })?;
        Ok(Pending { reply: reply_rx })
    }

    /// Fetch a URL through the tunnel, retrying transport faults.
    ///
    /// Blocking to the caller and concurrent on the worker: the fetch runs as
    /// its own task, so callers share one tunnel without queueing behind each
    /// other.
    pub fn fetch(&self, request: FetchRequest) -> Result<FetchOutcome, ProxyError> {
        self.submit(request)?.wait()
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
            // Closing the channel is what ends the worker's receive loop, which
            // drops the runtime and with it every in-flight fetch.
            drop(std::mem::replace(&mut self.tx, unbounded_channel().0));
            let _ = worker.join();
        }
    }
}

/// The worker thread body: run the tunnel and its fetches on one runtime.
///
/// A current-thread runtime with a [`LocalSet`], because the stack is `!Send`
/// by design — `Rc` and `RefCell` are the right cost for state only this thread
/// ever touches, and a work-stealing runtime would demand `Arc` and `Mutex` for
/// concurrency it cannot actually use on a single smoltcp interface.
fn worker_loop(
    config: WarpConfig,
    rx: UnboundedReceiver<Command>,
    ready_tx: Sender<Result<(), ProxyError>>,
) {
    let runtime = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(e) => {
            let _ = ready_tx.send(Err(ProxyError::InitializationFailed {
                details: format!("Failed to create tunnel runtime: {e}"),
            }));
            return;
        }
    };

    LocalSet::new().block_on(&runtime, serve(config, rx, ready_tx));
}

/// Bring the tunnel up, then dispatch commands until every handle is gone.
async fn serve(
    config: WarpConfig,
    mut rx: UnboundedReceiver<Command>,
    ready_tx: Sender<Result<(), ProxyError>>,
) {
    let (tunnel, driver) = match Tunnel::new(&config) {
        Ok(pair) => pair,
        Err(e) => {
            let _ = ready_tx.send(Err(e));
            return;
        }
    };

    // The driver owns reconnection for the tunnel's whole life, so it starts
    // before anything can ask for a fetch and outlives every one of them.
    spawn_local(driver.run(HANDSHAKE_TIMEOUT));

    if let Err(e) = tunnel.ready(HANDSHAKE_TIMEOUT).await {
        let _ = ready_tx.send(Err(e));
        return;
    }
    let _ = ready_tx.send(Ok(()));

    let last_activity = Rc::new(Cell::new(Instant::now()));
    spawn_local(watch_health(tunnel.clone(), Rc::clone(&last_activity)));

    // Acquired inside each task rather than before spawning, so a full window
    // delays sockets without also delaying the loop that serves diagnostics.
    let sockets = Rc::new(Semaphore::new(MAX_INFLIGHT_FETCHES));

    while let Some(command) = rx.recv().await {
        match command {
            Command::Fetch { request, reply } => {
                let tunnel = tunnel.clone();
                let activity = Rc::clone(&last_activity);
                let sockets = Rc::clone(&sockets);
                // Spawned rather than awaited: awaiting here is exactly the
                // serialization this exists to remove.
                spawn_local(async move {
                    let outcome = match sockets.acquire().await {
                        // Held for the whole fetch, released by `Drop` on every
                        // path out of it — including a panic.
                        Ok(_permit) => serve_fetch(&tunnel, &request).await,
                        // Only closure can fail, and nothing closes it while
                        // this task holds an `Rc` to it.
                        Err(_) => Err(ProxyError::TunnelError {
                            details: "Tunnel worker is shutting down".to_string(),
                        }),
                    };
                    activity.set(Instant::now());
                    let _ = reply.send(outcome);
                });
            }
            Command::Diagnostics { reply } => {
                let _ = reply.send(build_diagnostics(&tunnel));
            }
        }
    }
}

/// Run `request`, repeating it on a transport fault.
///
/// Safe to repeat because a [`FetchRequest`] is a description, not a session:
/// it is a `GET` over a connection that closes afterwards, so it carries no
/// progress that a second attempt would duplicate or resume. The retry is
/// therefore a plain re-run, and the only question is whether it could help —
/// which is what [`Fault`] answers.
async fn serve_fetch(tunnel: &Tunnel, request: &FetchRequest) -> Result<FetchOutcome, ProxyError> {
    for attempt in 0..=MAX_TRANSPORT_RETRIES {
        tunnel.ready(HANDSHAKE_TIMEOUT).await?;

        let outcome = http::fetch(
            tunnel,
            &request.url,
            &request.headers,
            &request.limits,
            &request.profile,
        )
        .await;

        match outcome.map_err(Fault::from) {
            Ok(outcome) => return Ok(outcome),
            Err(fault) if !fault.is_transport() => return Err(fault.into_error()),
            Err(fault) if attempt == MAX_TRANSPORT_RETRIES => return Err(fault.into_error()),
            Err(fault) => {
                log::warn!(
                    "transport fault on attempt {}: {}",
                    attempt + 1,
                    fault.into_error()
                );
                tokio::time::sleep(RETRY_PAUSE).await;
            }
        }
    }

    // `0..=MAX` always returns from inside the loop; this is unreachable without
    // an empty range, which the type forbids.
    Err(ProxyError::TunnelError {
        details: "fetch retry loop ended without a verdict".to_string(),
    })
}

/// Prove the tunnel still carries traffic while nothing else is asking it to.
///
/// The probe is a real fetch through the tunnel rather than a look at local
/// state: `is_connected` reports what the session *believes*, and the failure
/// this exists to catch is exactly a session that believes wrongly. Only idle
/// time counts, because a fetch that succeeded is a stronger liveness signal
/// than any probe.
async fn watch_health(tunnel: Tunnel, last_activity: Rc<Cell<Instant>>) {
    loop {
        tokio::time::sleep(HEALTH_INTERVAL).await;
        if last_activity.get().elapsed() < HEALTH_INTERVAL {
            continue;
        }

        let probe = crate::verify::trace_request();
        let outcome = http::fetch(
            &tunnel,
            &probe.url,
            &probe.headers,
            &probe.limits,
            &probe.profile,
        )
        .await;
        last_activity.set(Instant::now());

        // An endpoint fault answers the question the probe asked: something at
        // the far end replied, so the tunnel carried it. Only silence is a
        // finding, and repairing it is the driver's job, not this task's.
        match outcome.map_err(Fault::from) {
            Ok(_) => log::debug!("tunnel health probe succeeded"),
            Err(fault) if fault.is_transport() => {
                log::warn!("tunnel health probe failed: {}", fault.into_error());
            }
            Err(fault) => log::debug!(
                "health probe reached the far end and was refused: {}",
                fault.into_error()
            ),
        }
    }
}

/// Assemble a [`TunnelDiagnostics`] snapshot.
///
/// Every field is read from the live tunnel. Nothing is taken from the stored
/// configuration, which is what keeps the two records from disagreeing.
fn build_diagnostics(tunnel: &Tunnel) -> TunnelDiagnostics {
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
