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
use crate::tunnel::stack::WarpTunnel;
use crate::tunnel::stats::TunnelStats;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::thread::JoinHandle;
use std::time::Duration;

/// How long to wait for the initial (and any re-)handshake to complete.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(15);

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

/// A unit of work for the tunnel worker thread.
enum Command {
    Fetch {
        url: String,
        headers: Vec<(String, String)>,
        accept: String,
        limits: FetchLimits,
        reply: Sender<Result<FetchOutcome, ProxyError>>,
    },
    Diagnostics {
        reply: Sender<TunnelDiagnostics>,
    },
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

    /// Fetch a URL through the tunnel.
    pub fn fetch(
        &self,
        url: String,
        headers: Vec<(String, String)>,
        accept: String,
        limits: FetchLimits,
    ) -> Result<FetchOutcome, ProxyError> {
        let (reply, reply_rx) = channel();
        self.tx
            .send(Command::Fetch {
                url,
                headers,
                accept,
                limits,
                reply,
            })
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

    while let Ok(command) = rx.recv() {
        match command {
            Command::Fetch {
                url,
                headers,
                accept,
                limits,
                reply,
            } => {
                let result = ensure_connected(&mut tunnel)
                    .and_then(|()| http::fetch(&mut tunnel, &url, &headers, &limits, &accept));
                let _ = reply.send(result);
            }
            Command::Diagnostics { reply } => {
                let _ = reply.send(build_diagnostics(&tunnel));
            }
        }
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
