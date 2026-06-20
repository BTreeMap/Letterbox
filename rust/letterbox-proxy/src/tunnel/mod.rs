//! Userspace Cloudflare WARP tunnel.
//!
//! The tunnel is layered bottom-up:
//!
//! * [`transport`] — boringtun WireGuard over a UDP socket.
//! * [`device`] — a smoltcp [`Device`](smoltcp::phy::Device) bridging IP packets
//!   to the WireGuard transport.
//! * [`stack`] — the smoltcp TCP/IP interface and a blocking TCP stream adapter.
//! * [`tls`] — rustls over the tunnelled TCP stream.
//! * [`http1`] — a pure HTTP/1.1 request/response codec.
//! * [`dns`] — DNS-over-HTTPS resolution through the tunnel.
//! * [`manager`] — owns the tunnel on a worker thread and exposes a message API.

pub mod device;
pub mod dns;
pub mod http1;
pub mod manager;
pub mod stack;
pub mod tls;
pub mod transport;

pub use manager::{ConnectionState, TunnelDiagnostics, TunnelManager};
pub use stack::WarpTunnel;
