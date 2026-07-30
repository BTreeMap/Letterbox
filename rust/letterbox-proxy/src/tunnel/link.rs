//! The tunnel's data link: which protocol carries our IP packets.
//!
//! Both transports do the same job — take an IP packet, get it to Cloudflare,
//! bring replies back — and differ only in how they wrap it. Everything above
//! this module works in raw IP packets and does not care which is in use.
//!
//! # Why an enum and not a trait object
//!
//! The set of transports is closed and known at compile time. An enum says so:
//! adding a third variant turns every dispatch site into a compile error listing
//! exactly what must be handled, whereas `Box<dyn Transport>` would accept the
//! new type silently and defer the question to runtime. It also avoids a vtable
//! indirection and a heap allocation on a path that runs per packet batch.

use std::net::SocketAddr;
use std::time::Duration;

use crate::config::WarpConfig;
use crate::error::ProxyError;
use crate::tunnel::masque::MasqueTransport;
use crate::tunnel::transport::{TunnelStats, WireGuardTransport};

/// Which protocol is carrying the tunnel.
pub enum Link {
    /// Userspace WireGuard over UDP. Widely blocked, but needs no enrolment
    /// beyond registration.
    WireGuard(WireGuardTransport),
    /// MASQUE CONNECT-IP over HTTP/3. Indistinguishable from ordinary QUIC
    /// traffic on UDP/443, so it survives firewalls that drop WireGuard.
    Masque(MasqueTransport),
}

impl Link {
    /// Choose a transport for this account.
    ///
    /// Prefers MASQUE whenever the account carries enrolled credentials, and
    /// falls back to WireGuard otherwise — which is what an account provisioned
    /// before MASQUE support, or one whose enrolment failed, looks like. The
    /// fallback is deliberate: a blocked-but-configured tunnel is still better
    /// than no tunnel, and the alternative is a proxy that refuses to start.
    ///
    /// # Errors
    ///
    /// Propagates whichever transport's construction fails.
    pub fn for_config(config: &WarpConfig) -> Result<Self, ProxyError> {
        if config.masque.is_some() {
            match MasqueTransport::new(config) {
                Ok(transport) => return Ok(Self::Masque(transport)),
                Err(e) => log::warn!("MASQUE transport unavailable, using WireGuard: {e}"),
            }
        }
        Ok(Self::WireGuard(WireGuardTransport::new(config)?))
    }

    /// A short name for logs and diagnostics.
    #[must_use]
    pub fn protocol(&self) -> &'static str {
        match self {
            Self::WireGuard(_) => "wireguard",
            Self::Masque(_) => "masque",
        }
    }

    /// The remote endpoint this link targets.
    #[must_use]
    pub fn endpoint(&self) -> SocketAddr {
        match self {
            Self::WireGuard(t) => t.endpoint(),
            Self::Masque(t) => t.endpoint(),
        }
    }

    /// Whether the link is ready to carry packets.
    #[must_use]
    pub fn is_connected(&self) -> bool {
        match self {
            Self::WireGuard(t) => t.is_connected(),
            Self::Masque(t) => t.is_connected(),
        }
    }

    /// Live counters.
    #[must_use]
    pub fn stats(&self) -> TunnelStats {
        match self {
            Self::WireGuard(t) => t.stats(),
            Self::Masque(t) => t.stats(),
        }
    }

    /// Begin connecting, if the protocol has a distinct handshake step.
    ///
    /// # Errors
    ///
    /// Propagates the transport's failure.
    pub fn initiate_handshake(&mut self) -> Result<(), ProxyError> {
        match self {
            Self::WireGuard(t) => t.initiate_handshake(),
            Self::Masque(t) => t.initiate_handshake(),
        }
    }

    /// Collect packets that arrived, waiting up to `timeout`.
    ///
    /// # Errors
    ///
    /// Propagates the transport's failure.
    pub fn poll_incoming(&mut self, timeout: Duration) -> Result<Vec<Vec<u8>>, ProxyError> {
        match self {
            Self::WireGuard(t) => t.poll_incoming(timeout),
            Self::Masque(t) => t.poll_incoming(timeout),
        }
    }

    /// Send one IP packet.
    ///
    /// # Errors
    ///
    /// Propagates the transport's failure.
    pub fn send_ip(&mut self, packet: &[u8]) -> Result<(), ProxyError> {
        match self {
            Self::WireGuard(t) => t.send_ip(packet),
            Self::Masque(t) => t.send_ip(packet),
        }
    }

    /// Advance protocol timers.
    ///
    /// # Errors
    ///
    /// Propagates the transport's failure.
    pub fn tick(&mut self) -> Result<(), ProxyError> {
        match self {
            Self::WireGuard(t) => t.tick(),
            Self::Masque(t) => t.tick(),
        }
    }
}
