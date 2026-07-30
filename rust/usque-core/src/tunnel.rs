//! One MASQUE session: QUIC handshake, CONNECT-IP flow, and the datagram loop.
//!
//! The session runs as four phases, each a named function below:
//!
//! 1. [`complete_handshake`] — QUIC until the connection is established.
//! 2. [`verify_endpoint`] — SPKI pinning against the presented certificate.
//! 3. [`open_connect_ip_flow`] — extended CONNECT, yielding a flow id.
//! 4. [`forward_packets`] — IP packets in both directions until the peer goes.
//!
//! Phases 1, 3 and 4 all have to push whatever QUIC has queued onto the socket
//! after every event; that is [`flush_egress`], and it is the same operation in
//! each, not three similar ones. What differs is only how they answer a socket
//! that refuses the datagram — fatal while connecting, survivable once
//! established — so that answer is a [`Flushed`] value each phase eliminates,
//! not a policy the shared code picks for them.

use anyhow::{bail, Context, Result};
use portable_atomic::{AtomicBool, AtomicU64, Ordering};
use quiche::h3::NameValue;
use ring::rand::SecureRandom;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tokio::net::UdpSocket;

use crate::icmp;
use crate::packet;
use crate::tls;
use crate::TunnelIdentity;

const MAX_DATAGRAM_SIZE: usize = 1350;

/// Receive buffer for one UDP datagram, sized for any legal QUIC packet.
const RECV_BUFFER_SIZE: usize = 65535;

/// How long to wait for a network event when QUIC has no timer pending.
const IDLE_POLL_INTERVAL: Duration = Duration::from_millis(100);

/// How many events to service while waiting for the CONNECT response before
/// giving up. Bounded so an endpoint that accepts QUIC but never answers the
/// CONNECT cannot hold the session thread open.
const CONNECT_RESPONSE_ATTEMPTS: usize = 100;

/// Context id used on every datagram: this tunnel has exactly one flow, and
/// context 0 means "the IP packet follows uncompressed".
const CONTEXT_ID_UNCOMPRESSED: u8 = 0x00;

/// MTU advertised in the ICMP replies synthesised for oversized packets.
const PMTU_FLOOR: u16 = 1280;

/// Configuration for a MASQUE tunnel session.
pub struct TunnelConfig {
    pub endpoint: SocketAddr,

    /// Name sent in the TLS ClientHello.
    ///
    /// This is the only name a passive observer sees: the `:authority` on the
    /// CONNECT request travels inside the encrypted HTTP/3 stream. Letterbox
    /// therefore sets it to a host that does not identify the connection as
    /// WARP. Peer identity does not depend on it — the endpoint is pinned by
    /// SPKI in [`crate::tls::verify_endpoint_key`], with `verify_peer(false)`.
    pub sni: String,

    pub keepalive_period: Duration,

    /// How long QUIC tolerates silence before closing the connection.
    ///
    /// Must be greater than [`Self::keepalive_period`]; see the note at the
    /// `set_max_idle_timeout` call for why this is finite here and zero
    /// upstream. [`TunnelConfig::new`] enforces the ordering.
    pub idle_timeout: Duration,

    pub mtu: u32,
}

/// Why a [`TunnelConfig`] could not be built.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum TunnelConfigError {
    #[error("idle timeout must exceed the keepalive period")]
    IdleTimeoutTooShort,
}

impl TunnelConfig {
    /// Build a session configuration, checking the one relationship between
    /// fields that silently breaks the tunnel when inverted.
    ///
    /// # Errors
    ///
    /// Returns [`TunnelConfigError::IdleTimeoutTooShort`] if `idle_timeout` does
    /// not exceed `keepalive_period` — a combination that disconnects a healthy
    /// tunnel between its own keepalives.
    pub fn new(
        endpoint: SocketAddr,
        sni: String,
        keepalive_period: Duration,
        idle_timeout: Duration,
        mtu: u32,
    ) -> Result<Self, TunnelConfigError> {
        if idle_timeout <= keepalive_period {
            return Err(TunnelConfigError::IdleTimeoutTooShort);
        }
        Ok(Self {
            endpoint,
            sni,
            keepalive_period,
            idle_timeout,
            mtu,
        })
    }
}

/// Live counters for one session.
///
/// The owner polls [`Stats::snapshot`] for the diagnostics screen.
#[derive(Debug, Default)]
pub struct Stats {
    pub tx_packets: AtomicU64,
    pub rx_packets: AtomicU64,
    pub tx_bytes: AtomicU64,
    pub rx_bytes: AtomicU64,
    pub dropped: AtomicU64,
    pub quic_lost: AtomicU64,
    pub quic_retrans: AtomicU64,
}

/// An immutable read of [`Stats`].
///
/// The fields are sampled independently under `Relaxed`, so a snapshot is not a
/// consistent cut across counters — it is for display, not for arithmetic that
/// assumes the values agree.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct StatsSnapshot {
    pub tx_packets: u64,
    pub rx_packets: u64,
    pub tx_bytes: u64,
    pub rx_bytes: u64,
    pub dropped: u64,
    pub quic_lost: u64,
    pub quic_retrans: u64,
}

impl Stats {
    #[must_use]
    pub fn snapshot(&self) -> StatsSnapshot {
        StatsSnapshot {
            tx_packets: self.tx_packets.load(Ordering::Relaxed),
            rx_packets: self.rx_packets.load(Ordering::Relaxed),
            tx_bytes: self.tx_bytes.load(Ordering::Relaxed),
            rx_bytes: self.rx_bytes.load(Ordering::Relaxed),
            dropped: self.dropped.load(Ordering::Relaxed),
            quic_lost: self.quic_lost.load(Ordering::Relaxed),
            quic_retrans: self.quic_retrans.load(Ordering::Relaxed),
        }
    }

    /// Record one packet successfully handed to QUIC.
    fn record_tx(&self, bytes: u64) {
        self.tx_packets.fetch_add(1, Ordering::Relaxed);
        self.tx_bytes.fetch_add(bytes, Ordering::Relaxed);
    }

    /// Record one packet successfully delivered to the stack.
    fn record_rx(&self, bytes: u64) {
        self.rx_packets.fetch_add(1, Ordering::Relaxed);
        self.rx_bytes.fetch_add(bytes, Ordering::Relaxed);
    }

    /// Record one packet that never made it onto the tunnel.
    fn record_drop(&self) {
        self.dropped.fetch_add(1, Ordering::Relaxed);
    }
}

/// The endpoint and local address every `RecvInfo` needs.
///
/// Carried together because quiche wants both on every inbound datagram, and a
/// swapped pair is accepted silently and then fails the connection.
#[derive(Debug, Clone, Copy)]
struct Path {
    local: SocketAddr,
    peer: SocketAddr,
}

impl Path {
    fn recv_info(self) -> quiche::RecvInfo {
        quiche::RecvInfo {
            to: self.local,
            from: self.peer,
        }
    }
}

/// The socket and the scratch buffers every phase reads and writes through.
///
/// Grouped because they are always passed together and never independently:
/// `out` is only ever the destination of `conn.send`, `buf` only ever the
/// source of `conn.recv`, and swapping them would compile.
struct Io<'a> {
    socket: &'a UdpSocket,
    /// Egress scratch, one QUIC packet at a time.
    out: &'a mut [u8],
    /// Ingress scratch, one UDP datagram at a time.
    buf: &'a mut [u8],
    path: Path,
}

/// The CONNECT-IP flow this session carries.
struct Flow {
    id: u64,
    /// `varint(flow_id)` + context id, repeated on every datagram, so built
    /// once at the start of the flow rather than per packet.
    prefix: Vec<u8>,
}

/// What the datagram loop carries, and where it reports.
struct Session<'a> {
    flow: Flow,
    cfg: &'a TunnelConfig,
    stats: &'a Stats,
}

/// How a flush ended.
///
/// A refused datagram is not a failed session, and it is not the caller's
/// business to guess which: the two phases answer it differently, so the
/// outcome is a value they each eliminate rather than a policy baked in here.
#[must_use]
enum Flushed {
    /// quiche has nothing further to send.
    Drained,
    /// The socket would not take a datagram.
    SocketRefused(std::io::Error),
}

/// Push every QUIC packet quiche has ready onto the socket.
///
/// `Error::Done` terminates the drain and is not a failure — it is quiche
/// saying there is nothing further to send right now. A quiche error *is* a
/// failure and propagates; a socket error is reported as [`Flushed`].
async fn flush_egress(
    conn: &mut quiche::Connection,
    io: &mut Io<'_>,
    phase: &str,
) -> Result<Flushed> {
    loop {
        let (len, info) = match conn.send(io.out) {
            Ok(sent) => sent,
            Err(quiche::Error::Done) => return Ok(Flushed::Drained),
            Err(e) => bail!("send during {phase}: {e}"),
        };
        if let Err(e) = io.socket.send_to(&io.out[..len], info.to).await {
            return Ok(Flushed::SocketRefused(e));
        }
    }
}

/// Flush during connection setup, where a refused datagram is fatal.
///
/// Nothing is established yet, so there is no loss recovery to absorb the
/// packet and no session worth keeping if the socket will not carry it.
async fn flush_or_fail(conn: &mut quiche::Connection, io: &mut Io<'_>, phase: &str) -> Result<()> {
    match flush_egress(conn, io, phase).await? {
        Flushed::Drained => Ok(()),
        Flushed::SocketRefused(e) => Err(e).with_context(|| format!("UDP send during {phase}")),
    }
}

/// Wait for one network event and feed it to quiche.
///
/// `fallback` is how long to wait when QUIC has no timer of its own; `cap`
/// bounds the wait regardless, so a caller with its own deadline (the keepalive)
/// still wakes on time.
async fn service_once(
    conn: &mut quiche::Connection,
    io: &mut Io<'_>,
    fallback: Duration,
    cap: Duration,
) -> Result<()> {
    let timeout = conn.timeout().unwrap_or(fallback).min(cap);
    let (socket, path) = (io.socket, io.path);

    tokio::select! {
        result = socket.recv(io.buf) => {
            let len = result.context("UDP receive")?;
            // A datagram quiche rejects is one bad datagram, not a dead
            // connection; it is dropped and the session continues.
            if let Err(e) = conn.recv(&mut io.buf[..len], path.recv_info()) {
                log::debug!("quic recv error: {e}");
            }
        }
        () = tokio::time::sleep(timeout) => conn.on_timeout(),
    }
    Ok(())
}

/// Consume any further datagrams already sitting in the socket buffer.
///
/// Without this a single event per loop iteration leaves ACKs queued behind the
/// branch that happened to win the `select!`, which shows up as needless
/// retransmission.
fn drain_socket(conn: &mut quiche::Connection, io: &mut Io<'_>) {
    while let Ok(len) = io.socket.try_recv(io.buf) {
        if let Err(e) = conn.recv(&mut io.buf[..len], io.path.recv_info()) {
            log::debug!("quic recv error while draining: {e}");
        }
    }
}

/// Drive QUIC until the connection is established.
async fn complete_handshake(conn: &mut quiche::Connection, io: &mut Io<'_>) -> Result<()> {
    flush_or_fail(conn, io, "handshake").await?;

    loop {
        service_once(conn, io, IDLE_POLL_INTERVAL, Duration::MAX).await?;
        flush_or_fail(conn, io, "handshake").await?;

        if conn.is_established() {
            return Ok(());
        }
        if conn.is_closed() {
            bail!("connection closed during handshake");
        }
    }
}

/// Check the presented certificate against the pinned endpoint key.
///
/// With `verify_peer(false)` this is the *only* thing establishing who the peer
/// is, which is what makes the SNI a free choice rather than a constraint.
fn verify_endpoint(conn: &quiche::Connection, expected_spki_der: &[u8]) -> Result<()> {
    let Some(peer_cert) = conn.peer_cert() else {
        log::warn!("No peer certificate received; skipping key pinning");
        return Ok(());
    };
    if !tls::verify_endpoint_key(peer_cert, expected_spki_der) {
        bail!("peer certificate public key does not match pinned endpoint key");
    }
    log::debug!("Endpoint key pinning verified");
    Ok(())
}

/// Send the extended CONNECT and wait for a 2xx, returning the flow id.
async fn open_connect_ip_flow(
    conn: &mut quiche::Connection,
    h3_conn: &mut quiche::h3::Connection,
    io: &mut Io<'_>,
) -> Result<Flow> {
    let request = [
        quiche::h3::Header::new(b":method", b"CONNECT"),
        quiche::h3::Header::new(b":protocol", b"cf-connect-ip"),
        quiche::h3::Header::new(b":scheme", b"https"),
        quiche::h3::Header::new(b":authority", b"cloudflareaccess.com"),
        quiche::h3::Header::new(b":path", b"/"),
        quiche::h3::Header::new(b"capsule-protocol", b"?1"),
        quiche::h3::Header::new(b"user-agent", b""),
    ];

    let stream_id = h3_conn
        .send_request(conn, &request, false)
        .map_err(|e| anyhow::anyhow!("send CONNECT request: {e}"))?;
    let flow_id = stream_id / 4;
    log::debug!("CONNECT request sent on stream {stream_id}, flow_id={flow_id}");

    flush_or_fail(conn, io, "CONNECT").await?;

    for _ in 0..CONNECT_RESPONSE_ATTEMPTS {
        service_once(conn, io, IDLE_POLL_INTERVAL, Duration::MAX).await?;

        let accepted = poll_connect_response(conn, h3_conn, stream_id)?;
        flush_or_fail(conn, io, "CONNECT").await?;

        if accepted {
            return Ok(Flow {
                id: flow_id,
                prefix: flow_prefix(flow_id)?,
            });
        }
        if conn.is_closed() {
            bail!("connection closed before CONNECT response");
        }
    }

    bail!("timed out waiting for CONNECT response")
}

/// Drain HTTP/3 events, reporting whether the CONNECT was accepted.
///
/// A non-2xx status is a refusal to answer for, not something to keep waiting
/// through, so it fails rather than returning `false`.
fn poll_connect_response(
    conn: &mut quiche::Connection,
    h3_conn: &mut quiche::h3::Connection,
    stream_id: u64,
) -> Result<bool> {
    let mut established = false;
    loop {
        match h3_conn.poll(conn) {
            Ok((sid, quiche::h3::Event::Headers { list, .. })) if sid == stream_id => {
                for header in list.iter().filter(|h| h.name() == b":status") {
                    let status = std::str::from_utf8(header.value()).unwrap_or("?");
                    log::debug!("CONNECT response status: {status}");
                    if !status.starts_with('2') {
                        bail!("CONNECT rejected with status {status}");
                    }
                    established = true;
                }
            }
            Ok(_) => {}
            Err(quiche::h3::Error::Done) => return Ok(established),
            Err(e) => bail!("h3 poll error: {e}"),
        }
    }
}

/// The prefix every CONNECT-IP datagram carries: `varint(flow_id)` then the
/// context id.
///
/// Built once and reused; it is constant for the life of the flow.
fn flow_prefix(flow_id: u64) -> Result<Vec<u8>> {
    let mut scratch = [0u8; 8];
    let mut cursor = octets::OctetsMut::with_slice(&mut scratch);
    cursor
        .put_varint(flow_id)
        .map_err(|e| anyhow::anyhow!("encode flow id {flow_id}: {e}"))?;
    let len = cursor.off();

    let mut prefix = Vec::with_capacity(len + 1);
    prefix.extend_from_slice(&scratch[..len]);
    prefix.push(CONTEXT_ID_UNCOMPRESSED);
    Ok(prefix)
}

/// Wrap one IP packet as a CONNECT-IP datagram and hand it to QUIC.
fn send_ip_datagram(
    conn: &mut quiche::Connection,
    prefix: &[u8],
    ip_packet: &[u8],
    stats: &Stats,
) -> Result<(), quiche::Error> {
    let mut datagram = Vec::with_capacity(prefix.len() + ip_packet.len());
    datagram.extend_from_slice(prefix);
    datagram.extend_from_slice(ip_packet);

    conn.dgram_send(&datagram)?;
    stats.record_tx(ip_packet.len() as u64);
    Ok(())
}

/// Carry IP packets both ways until the connection closes.
async fn forward_packets<R, W>(
    conn: &mut quiche::Connection,
    h3_conn: &mut quiche::h3::Connection,
    io: &mut Io<'_>,
    tun_reader: &mut R,
    tun_writer: &mut W,
    session: &Session<'_>,
) -> Result<()>
where
    R: tokio::io::AsyncRead + Unpin,
    W: tokio::io::AsyncWrite + Unpin,
{
    let Session { flow, cfg, stats } = session;
    let keepalive = cfg.keepalive_period;
    let mut tun_buf = vec![0u8; cfg.mtu as usize + 128];
    // Inbound CONNECT-IP datagrams land here. Sized to the largest datagram
    // QUIC can deliver rather than to the tunnel MTU, because `dgram_recv`
    // reports `BufferTooShort` instead of truncating, and silently dropping
    // an oversized datagram would be a stall with no symptom.
    let mut dgram_buf = vec![0u8; RECV_BUFFER_SIZE];

    loop {
        let (socket, path) = (io.socket, io.path);
        tokio::select! {
            // Stack -> tunnel. One read yields exactly one whole IP packet;
            // see the contract on `run_tunnel_session`.
            result = tokio::io::AsyncReadExt::read(tun_reader, &mut tun_buf) => {
                let n = result.context("read from packet source")?;
                if n == 0 {
                    bail!("TUN device closed");
                }
                forward_outbound(conn, &mut tun_buf[..n], &flow.prefix, tun_writer, stats).await;
            }

            // Tunnel -> stack.
            result = socket.recv(io.buf) => {
                let len = result.context("UDP receive")?;
                if let Err(e) = conn.recv(&mut io.buf[..len], path.recv_info()) {
                    log::debug!("quic recv error: {e}");
                }
            }

            () = tokio::time::sleep(conn.timeout().unwrap_or(keepalive).min(keepalive)) => {
                conn.on_timeout();
            }
        }

        drain_socket(conn, io);

        // Capsules and control frames. Nothing here is acted on, but the
        // connection stalls if its events are never consumed.
        loop {
            match h3_conn.poll(conn) {
                Ok(_) => {}
                Err(quiche::h3::Error::Done) => break,
                Err(e) => {
                    log::warn!("h3 poll error: {e}");
                    break;
                }
            }
        }

        deliver_inbound(conn, tun_writer, flow.id, stats, &mut dgram_buf).await;

        // A refused datagram does not end an established session. quiche has
        // already accounted for that packet as sent and will retransmit it
        // under loss recovery, so this is a lost packet — which QUIC is built
        // to absorb — not a lost tunnel. `ENOBUFS` under load and
        // `ENETUNREACH` across a WiFi/cellular handoff are both routine and
        // both transient; tearing the session down would force a full
        // handshake and CONNECT rebuild for a condition that clears itself. A
        // fault that does *not* clear stops the keepalives, and the finite
        // idle timeout closes the connection within `idle_timeout`.
        if let Flushed::SocketRefused(e) = flush_egress(conn, io, "session").await? {
            log::warn!("UDP send error: {e}");
        }

        let quic = conn.stats();
        stats.quic_lost.store(quic.lost as u64, Ordering::Relaxed);
        stats
            .quic_retrans
            .store(quic.retrans as u64, Ordering::Relaxed);

        if conn.is_closed() {
            return Ok(());
        }
    }
}

/// Validate, decrement and enqueue one outbound packet.
///
/// Every failure here is per-packet: the tunnel survives a packet it cannot
/// carry, so nothing propagates.
async fn forward_outbound<W>(
    conn: &mut quiche::Connection,
    ip_packet: &mut [u8],
    prefix: &[u8],
    tun_writer: &mut W,
    stats: &Stats,
) where
    W: tokio::io::AsyncWrite + Unpin,
{
    if let Err(e) = packet::prepare_outgoing(ip_packet) {
        stats.record_drop();
        log::trace!("dropping outgoing packet: {e}");
        return;
    }

    match send_ip_datagram(conn, prefix, ip_packet, stats) {
        Ok(()) => {}
        Err(quiche::Error::InvalidState) => {
            log::warn!("datagram send: peer doesn't support datagrams");
        }
        Err(quiche::Error::Done) => {
            stats.record_drop();
            log::trace!("datagram send queue full, dropping packet");
        }
        Err(e) => {
            stats.record_drop();
            log::debug!("datagram send error: {e}, generating ICMP");
            // The sender above the tunnel has no other way to learn the MTU:
            // the tunnel is the constriction, so it must report it itself.
            if let Some(icmp_reply) = icmp::compose_icmp_too_large(ip_packet, PMTU_FLOOR) {
                tokio::io::AsyncWriteExt::write_all(tun_writer, &icmp_reply)
                    .await
                    .ok();
            }
        }
    }
}

/// Deliver every queued inbound datagram to the stack.
///
/// `scratch` is the caller's, and must be at least [`RECV_BUFFER_SIZE`]: quiche
/// 0.29 replaced the allocating `dgram_recv_vec` with a fill-my-buffer
/// `dgram_recv`, which answers `BufferTooShort` rather than truncating. Owning
/// the buffer one level up keeps that to one allocation per session instead of
/// one per datagram, which is what the old signature cost.
async fn deliver_inbound<W>(
    conn: &mut quiche::Connection,
    tun_writer: &mut W,
    flow_id: u64,
    stats: &Stats,
    scratch: &mut [u8],
) where
    W: tokio::io::AsyncWrite + Unpin,
{
    loop {
        let len = match conn.dgram_recv(scratch) {
            Ok(len) => len,
            Err(quiche::Error::Done) => return,
            Err(e) => {
                log::debug!("dgram recv error: {e}");
                return;
            }
        };
        let datagram = &scratch[..len];

        // A datagram for another flow, or one that is not a valid IP packet, is
        // discarded: this is untrusted input from the network.
        let Some(ip_payload) = parse_datagram(datagram, flow_id) else {
            continue;
        };
        if packet::validate_incoming(ip_payload).is_err() {
            continue;
        }

        stats.record_rx(ip_payload.len() as u64);
        tokio::io::AsyncWriteExt::write_all(tun_writer, ip_payload)
            .await
            .ok();
    }
}

/// Build the QUIC configuration for one session.
fn build_quic_config(
    tunnel_cfg: &TunnelConfig,
    tls_material: &tls::TlsMaterial,
) -> Result<quiche::Config> {
    let mut config = quiche::Config::new(quiche::PROTOCOL_VERSION)
        .map_err(|e| anyhow::anyhow!("quiche config: {e}"))?;

    // Peer identity comes from SPKI pinning in `verify_endpoint`, not from the
    // certificate chain, so chain verification is deliberately off.
    config.verify_peer(false);
    config
        .set_application_protos(quiche::h3::APPLICATION_PROTOCOL)
        .map_err(|e| anyhow::anyhow!("set ALPN: {e}"))?;
    config
        .load_cert_chain_from_pem_file(tls_material.cert_path()?)
        .map_err(|e| anyhow::anyhow!("load cert: {e}"))?;
    config
        .load_priv_key_from_pem_file(tls_material.key_path()?)
        .map_err(|e| anyhow::anyhow!("load key: {e}"))?;

    // A finite idle timeout gives QUIC its own exit. With zero — meaning *no*
    // timeout — an unreachable endpoint leaves the session loop with no exit
    // condition, so the thread spins forever and anything joining it, `Drop`
    // included, blocks with it. On Android that is an ANR. `TunnelConfig::new`
    // guarantees this exceeds the keepalive period, or an idle-but-healthy
    // tunnel would tear itself down between keepalives.
    config.set_max_idle_timeout(tunnel_cfg.idle_timeout.as_millis() as u64);
    config.set_max_recv_udp_payload_size(MAX_DATAGRAM_SIZE);
    config.set_max_send_udp_payload_size(MAX_DATAGRAM_SIZE);
    config.set_initial_max_data(10_000_000);
    config.set_initial_max_stream_data_bidi_local(1_000_000);
    config.set_initial_max_stream_data_bidi_remote(1_000_000);
    config.set_initial_max_stream_data_uni(1_000_000);
    config.set_initial_max_streams_bidi(100);
    config.set_initial_max_streams_uni(100);
    config.set_disable_active_migration(true);
    config.enable_dgram(true, 1000, 1000);

    Ok(config)
}

/// Run one MASQUE session until the connection closes or errors.
///
/// `reader` supplies outbound IP packets and `writer` receives inbound ones.
/// **Both are packet-framed, not byte streams**: each successful read must yield
/// exactly one whole IP packet, and each write delivers exactly one. A read that
/// returns two concatenated packets is forwarded as a single malformed datagram.
///
/// Reconnection is the caller's: this returns when the session ends and does not
/// retry.
pub async fn run_tunnel_session<R, W>(
    identity: &TunnelIdentity,
    tunnel_cfg: &TunnelConfig,
    tun_reader: &mut R,
    tun_writer: &mut W,
    stats: Arc<Stats>,
    established: Arc<AtomicBool>,
) -> Result<()>
where
    R: tokio::io::AsyncRead + Unpin,
    W: tokio::io::AsyncWrite + Unpin,
{
    let tls_material = tls::prepare_tls_material(identity)?;
    let mut quic_config = build_quic_config(tunnel_cfg, &tls_material)?;

    let bind_addr: SocketAddr = match tunnel_cfg.endpoint {
        SocketAddr::V4(_) => ([0, 0, 0, 0], 0).into(),
        SocketAddr::V6(_) => ([0u16; 8], 0).into(),
    };

    let socket = UdpSocket::bind(bind_addr)
        .await
        .context("bind UDP socket")?;
    socket
        .connect(tunnel_cfg.endpoint)
        .await
        .context("connect UDP socket")?;
    let path = Path {
        local: socket.local_addr().context("local address")?,
        peer: tunnel_cfg.endpoint,
    };

    let mut scid = [0u8; quiche::MAX_CONN_ID_LEN];
    ring::rand::SystemRandom::new()
        .fill(&mut scid)
        .map_err(|_| anyhow::anyhow!("RNG failure"))?;
    let scid = quiche::ConnectionId::from_ref(&scid);

    let mut conn = quiche::connect(
        Some(&tunnel_cfg.sni),
        &scid,
        path.local,
        path.peer,
        &mut quic_config,
    )
    .map_err(|e| anyhow::anyhow!("quiche connect: {e}"))?;

    let mut out = vec![0u8; MAX_DATAGRAM_SIZE];
    let mut buf = vec![0u8; RECV_BUFFER_SIZE];
    let mut io = Io {
        socket: &socket,
        out: &mut out,
        buf: &mut buf,
        path,
    };

    complete_handshake(&mut conn, &mut io).await?;
    verify_endpoint(&conn, &tls_material.endpoint_pub_key_spki_der)?;

    let mut h3_config = quiche::h3::Config::new().map_err(|e| anyhow::anyhow!("h3 config: {e}"))?;
    h3_config.enable_extended_connect(true);
    let mut h3_conn = quiche::h3::Connection::with_transport(&mut conn, &h3_config)
        .map_err(|e| anyhow::anyhow!("h3 connection: {e}"))?;

    let flow = open_connect_ip_flow(&mut conn, &mut h3_conn, &mut io).await?;

    log::info!("MASQUE tunnel established to {}", tunnel_cfg.endpoint);

    // Published only after the CONNECT response is accepted, so `true` means
    // the flow is open and datagrams will be carried — not merely that QUIC
    // handshook.
    established.store(true, Ordering::Release);

    let session = Session {
        flow,
        cfg: tunnel_cfg,
        stats: &stats,
    };

    forward_packets(
        &mut conn,
        &mut h3_conn,
        &mut io,
        tun_reader,
        tun_writer,
        &session,
    )
    .await
}

/// Parse an H3 datagram: `varint(flow_id)` + `varint(context_id)` + IP packet
/// Returns the IP payload slice if `flow_id` matches and `context_id` == 0.
fn parse_datagram(dgram: &[u8], expected_flow_id: u64) -> Option<&[u8]> {
    let mut b = octets::Octets::with_slice(dgram);

    let fid = b.get_varint().ok()?;
    if fid != expected_flow_id {
        return None;
    }

    let ctx_id = b.get_varint().ok()?;
    if ctx_id != 0 {
        return None;
    }

    let off = b.off();
    if off >= dgram.len() {
        return None;
    }

    Some(&dgram[off..])
}
#[cfg(test)]
mod tests {
    use super::*;

    // ---- parse_datagram tests ----

    fn encode_varint(val: u64) -> Vec<u8> {
        let mut tmp = [0u8; 8];
        let mut b = octets::OctetsMut::with_slice(&mut tmp);
        b.put_varint(val).unwrap();
        let len = b.off();
        tmp[..len].to_vec()
    }

    fn make_datagram(flow_id: u64, context_id: u64, payload: &[u8]) -> Vec<u8> {
        let mut dgram = Vec::new();
        dgram.extend_from_slice(&encode_varint(flow_id));
        dgram.extend_from_slice(&encode_varint(context_id));
        dgram.extend_from_slice(payload);
        dgram
    }

    #[test]
    fn parse_datagram_valid() {
        let payload = b"hello world";
        let dgram = make_datagram(0, 0, payload);
        let result = parse_datagram(&dgram, 0);
        assert_eq!(result, Some(payload.as_ref()));
    }

    #[test]
    fn parse_datagram_flow_id_mismatch() {
        let dgram = make_datagram(1, 0, b"data");
        assert_eq!(parse_datagram(&dgram, 0), None);
    }

    #[test]
    fn parse_datagram_nonzero_context() {
        let dgram = make_datagram(0, 1, b"data");
        assert_eq!(parse_datagram(&dgram, 0), None);
    }

    #[test]
    fn parse_datagram_empty_payload() {
        let dgram = make_datagram(0, 0, b"");
        // Empty payload means off == dgram.len(), should return None
        assert_eq!(parse_datagram(&dgram, 0), None);
    }

    #[test]
    fn parse_datagram_large_flow_id() {
        // flow_id that requires 4-byte varint encoding
        let flow_id = 16384;
        let payload = vec![0xABu8; 1300];
        let dgram = make_datagram(flow_id, 0, &payload);
        let result = parse_datagram(&dgram, flow_id);
        assert_eq!(result, Some(payload.as_ref()));
    }

    #[test]
    fn parse_datagram_truncated() {
        // Just a single byte - can't even decode flow_id
        let dgram = vec![0xFF];
        assert_eq!(parse_datagram(&dgram, 0), None);
    }

    // Upstream's `format_bytes` / `format_duration` tests are gone with the
    // functions: they rendered the terminal status line, which has no analogue
    // in a library. The six `parse_datagram` cases above are the protocol ones
    // and are kept verbatim.

    #[test]
    fn idle_timeout_must_outlast_keepalive() {
        let endpoint = "127.0.0.1:443".parse().expect("addr");
        let keepalive = Duration::from_secs(25);

        // Equal is not enough: the tunnel would race its own keepalive.
        assert_eq!(
            TunnelConfig::new(endpoint, "h".into(), keepalive, keepalive, 1280).err(),
            Some(TunnelConfigError::IdleTimeoutTooShort)
        );
        assert_eq!(
            TunnelConfig::new(
                endpoint,
                "h".into(),
                keepalive,
                Duration::from_secs(5),
                1280
            )
            .err(),
            Some(TunnelConfigError::IdleTimeoutTooShort)
        );
        assert!(TunnelConfig::new(
            endpoint,
            "h".into(),
            keepalive,
            Duration::from_secs(60),
            1280
        )
        .is_ok());
    }

    /// A zero idle timeout means *no* timeout in quiche, which is what made an
    /// unreachable endpoint hang the session thread forever. It must not be
    /// constructible.
    #[test]
    fn zero_idle_timeout_is_rejected() {
        let endpoint = "127.0.0.1:443".parse().expect("addr");

        assert_eq!(
            TunnelConfig::new(
                endpoint,
                "h".into(),
                Duration::from_secs(25),
                Duration::ZERO,
                1280
            )
            .err(),
            Some(TunnelConfigError::IdleTimeoutTooShort)
        );
    }

    #[test]
    fn stats_snapshot_reports_every_counter() {
        let stats = Stats::default();
        stats.tx_packets.fetch_add(3, Ordering::Relaxed);
        stats.rx_bytes.fetch_add(4096, Ordering::Relaxed);
        stats.dropped.fetch_add(1, Ordering::Relaxed);

        let snapshot = stats.snapshot();

        assert_eq!(snapshot.tx_packets, 3);
        assert_eq!(snapshot.rx_bytes, 4096);
        assert_eq!(snapshot.dropped, 1);
        assert_eq!(snapshot.rx_packets, 0);
    }
}
