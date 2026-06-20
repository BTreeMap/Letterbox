//! Tunnel lifecycle manager.
//!
//! [`TunnelManager`] owns the [`WarpTunnel`] on a dedicated OS thread and
//! exposes a synchronous, thread-safe request API over a command channel. This
//! is deliberate message passing rather than shared mutable state: the tunnel —
//! and the single-threaded smoltcp/boringtun state machine inside it — is only
//! ever touched by its worker thread, so no `Mutex` guards the hot path.

use crate::config::{FetchLimits, WarpConfig};
use crate::error::ProxyError;
use crate::http::{self, FetchOutcome};
use crate::provisioning::WarpProvisioner;
use crate::tunnel::stack::WarpTunnel;
use crate::tunnel::transport::TunnelStats;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::thread::JoinHandle;
use std::time::Duration;

/// How long to wait for the initial (and any re-)handshake to complete.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(15);

/// Whether the tunnel currently has a live WireGuard session.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionState {
    /// No completed handshake.
    Disconnected,
    /// A handshake has completed and the session is usable.
    Connected,
}

/// A full diagnostics snapshot of the tunnel and its WARP identity.
#[derive(Debug, Clone)]
pub struct TunnelDiagnostics {
    /// Live connection state.
    pub connection_state: ConnectionState,
    /// WireGuard private key (base64). Sensitive — surfaced for debugging only.
    pub private_key: String,
    /// Our derived WireGuard public key (base64).
    pub public_key: String,
    /// The WARP peer's public key (base64).
    pub peer_public_key: String,
    /// Endpoint hostname.
    pub endpoint_host: String,
    /// Endpoint IPv4 address.
    pub endpoint_ipv4: String,
    /// Endpoint UDP port.
    pub endpoint_port: u16,
    /// Local tunnel IPv4 address.
    pub local_address_ipv4: String,
    /// Whether WARP is enabled on the account.
    pub warp_enabled: bool,
    /// Account type (e.g. `free`).
    pub account_type: String,
    /// Cloudflare account/device identifier.
    pub account_id: String,
    /// Seconds since the last completed handshake, if any.
    pub last_handshake_secs: Option<u64>,
    /// Plaintext bytes transmitted into the tunnel.
    pub tx_bytes: u64,
    /// Plaintext bytes received from the tunnel.
    pub rx_bytes: u64,
    /// Estimated packet loss in `[0.0, 1.0]`.
    pub estimated_loss: f32,
    /// Estimated round-trip time in milliseconds, if measured.
    pub rtt_ms: Option<u32>,
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
    /// `config` is the provisioned WARP configuration. The worker derives the
    /// public key once and retains the config for diagnostics.
    pub fn start(config: WarpConfig) -> Result<Self, ProxyError> {
        let public_key = WarpProvisioner::public_key_from_private(&config.account.private_key)?;
        let (tx, rx) = channel::<Command>();
        let (ready_tx, ready_rx) = channel::<Result<(), ProxyError>>();

        let worker = std::thread::Builder::new()
            .name("warp-tunnel".to_string())
            .spawn(move || worker_loop(config, public_key, rx, ready_tx))
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
    public_key: String,
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
                let _ = reply.send(build_diagnostics(&tunnel, &config, &public_key));
            }
        }
    }
}

/// Ensure a live WireGuard session, re-handshaking if it has lapsed.
fn ensure_connected(tunnel: &mut WarpTunnel) -> Result<(), ProxyError> {
    if tunnel.is_connected() {
        return Ok(());
    }
    tunnel.connect(HANDSHAKE_TIMEOUT)
}

/// Assemble a [`TunnelDiagnostics`] snapshot from live and configured state.
fn build_diagnostics(
    tunnel: &WarpTunnel,
    config: &WarpConfig,
    public_key: &str,
) -> TunnelDiagnostics {
    let stats: TunnelStats = tunnel.stats();
    let endpoint = tunnel.endpoint();
    TunnelDiagnostics {
        connection_state: if tunnel.is_connected() {
            ConnectionState::Connected
        } else {
            ConnectionState::Disconnected
        },
        private_key: config.account.private_key.clone(),
        public_key: public_key.to_string(),
        peer_public_key: config.peer.public_key.clone(),
        endpoint_host: config.peer.endpoint_host.clone(),
        endpoint_ipv4: endpoint.ip().to_string(),
        endpoint_port: endpoint.port(),
        local_address_ipv4: config.interface.address_ipv4.clone(),
        warp_enabled: config.warp_enabled,
        account_type: config.account_type.clone(),
        account_id: config.account.account_id.clone(),
        last_handshake_secs: stats.since_handshake.map(|d| d.as_secs()),
        tx_bytes: stats.tx_bytes,
        rx_bytes: stats.rx_bytes,
        estimated_loss: stats.estimated_loss,
        rtt_ms: stats.rtt_ms,
    }
}
