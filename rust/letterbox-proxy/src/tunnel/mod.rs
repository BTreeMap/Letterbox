//! Userspace Cloudflare WARP tunnel.
//!
//! The tunnel is layered bottom-up:
//!
//! * [`masque`] — Cloudflare MASQUE (CONNECT-IP over HTTP/3) via `usque-core`.
//! * [`duplex`] — a packet-framed bridge between the async session loop and
//!   this blocking one.
//! * [`device`] — a smoltcp [`Device`](smoltcp::phy::Device) bridging IP packets
//!   to the transport.
//! * [`stack`] — the smoltcp TCP/IP interface and a blocking TCP stream adapter.
//! * [`tls`] — rustls over the tunnelled TCP stream.
//! * [`http1`] — a pure HTTP/1.1 request/response codec.
//! * [`dns`] — DNS-over-HTTPS resolution through the tunnel.
//! * [`manager`] — owns the tunnel on a worker thread and exposes a message API.

pub mod device;
pub mod dns;
pub mod duplex;
pub mod http1;
pub mod manager;
pub mod masque;
pub mod stack;
pub mod stats;
pub mod tls;

pub use manager::{ConnectionState, TunnelDiagnostics, TunnelManager};
pub use stack::WarpTunnel;
