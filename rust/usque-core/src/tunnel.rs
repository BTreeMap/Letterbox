use anyhow::{bail, Result};
use portable_atomic::{AtomicBool, AtomicU64, Ordering};
use quiche::h3::NameValue;
use ring::rand::SecureRandom;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use crate::icmp;
use crate::packet;
use crate::tls;
use crate::TunnelIdentity;

const MAX_DATAGRAM_SIZE: usize = 1350;

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
/// MODIFIED FROM UPSTREAM: made public, and the once-per-second terminal
/// renderer that consumed it was removed. Upstream is a foreground CLI; here the
/// owner polls [`Stats::snapshot`] for the diagnostics screen.
#[derive(Debug)]
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
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            tx_packets: AtomicU64::new(0),
            rx_packets: AtomicU64::new(0),
            tx_bytes: AtomicU64::new(0),
            rx_bytes: AtomicU64::new(0),
            dropped: AtomicU64::new(0),
            quic_lost: AtomicU64::new(0),
            quic_retrans: AtomicU64::new(0),
        })
    }

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
}

/// Run one MASQUE session until the connection closes or errors.
///
/// `reader` supplies outbound IP packets and `writer` receives inbound ones.
/// **Both are packet-framed, not byte streams**: each successful read must yield
/// exactly one whole IP packet, and each write delivers exactly one. Upstream
/// gets this for free from a TUN device's read/write semantics; any other
/// implementation must preserve it, because a read that returns two concatenated
/// packets is forwarded as a single malformed datagram.
///
/// MODIFIED FROM UPSTREAM:
/// * `maintain_tunnel`, which owned a `tun::Device` and reconnected in a loop,
///   is removed. It pulled in the Linux-only `tun` crate, which cannot build for
///   Android, and its retry policy belongs to the caller.
/// * Takes `&TunnelIdentity` rather than usque-rs's file-backed `Config`.
/// * `stats` is passed in rather than created here, so the owner can read
///   counters while a session runs, and `established` reports readiness.
/// * The once-per-second terminal status line is gone; the events it displayed
///   are logged instead.
///
/// The QUIC handshake, SPKI pinning, extended-CONNECT exchange, datagram
/// framing, and PMTU handling below are unchanged from upstream.
pub async fn run_tunnel_session<R, W>(
    identity: &TunnelIdentity,
    tunnel_cfg: &TunnelConfig,
    tun_reader: &mut R,
    tun_writer: &mut W,
    pending_pkt: &mut Option<Vec<u8>>,
    stats: Arc<Stats>,
    established: Arc<AtomicBool>,
) -> Result<()>
where
    R: tokio::io::AsyncRead + Unpin,
    W: tokio::io::AsyncWrite + Unpin,
{
    let tls_material = tls::prepare_tls_material(identity)?;

    let mut quic_config = quiche::Config::new(quiche::PROTOCOL_VERSION)
        .map_err(|e| anyhow::anyhow!("quiche config: {e}"))?;

    quic_config.verify_peer(false);
    quic_config
        .set_application_protos(quiche::h3::APPLICATION_PROTOCOL)
        .map_err(|e| anyhow::anyhow!("set ALPN: {e}"))?;
    quic_config
        .load_cert_chain_from_pem_file(tls_material.cert_pem_file.path().to_str().unwrap())
        .map_err(|e| anyhow::anyhow!("load cert: {e}"))?;
    quic_config
        .load_priv_key_from_pem_file(tls_material.key_pem_file.path().to_str().unwrap())
        .map_err(|e| anyhow::anyhow!("load key: {e}"))?;

    // MODIFIED FROM UPSTREAM: upstream sets 0, meaning *no* idle timeout. That
    // suits a daemon an operator can kill, but here it makes an unreachable
    // endpoint unkillable: the handshake loop below has no exit condition, so
    // the session thread spins forever and anything joining it — including
    // `Drop` — blocks with it. On Android that is an ANR.
    //
    // A finite timeout gives QUIC its own exit. It must exceed the keepalive
    // period or an idle-but-healthy tunnel would tear itself down between
    // keepalives.
    quic_config.set_max_idle_timeout(tunnel_cfg.idle_timeout.as_millis() as u64);
    quic_config.set_max_recv_udp_payload_size(MAX_DATAGRAM_SIZE);
    quic_config.set_max_send_udp_payload_size(MAX_DATAGRAM_SIZE);
    quic_config.set_initial_max_data(10_000_000);
    quic_config.set_initial_max_stream_data_bidi_local(1_000_000);
    quic_config.set_initial_max_stream_data_bidi_remote(1_000_000);
    quic_config.set_initial_max_stream_data_uni(1_000_000);
    quic_config.set_initial_max_streams_bidi(100);
    quic_config.set_initial_max_streams_uni(100);
    quic_config.set_disable_active_migration(true);
    quic_config.enable_dgram(true, 1000, 1000);

    let bind_addr: SocketAddr = match tunnel_cfg.endpoint {
        SocketAddr::V4(_) => "0.0.0.0:0".parse().unwrap(),
        SocketAddr::V6(_) => "[::]:0".parse().unwrap(),
    };

    let socket = tokio::net::UdpSocket::bind(bind_addr).await?;
    socket.connect(tunnel_cfg.endpoint).await?;
    let local_addr = socket.local_addr()?;

    let mut scid = [0u8; quiche::MAX_CONN_ID_LEN];
    ring::rand::SystemRandom::new()
        .fill(&mut scid)
        .map_err(|_| anyhow::anyhow!("RNG failure"))?;
    let scid = quiche::ConnectionId::from_ref(&scid);

    let mut conn = quiche::connect(
        Some(&tunnel_cfg.sni),
        &scid,
        local_addr,
        tunnel_cfg.endpoint,
        &mut quic_config,
    )
    .map_err(|e| anyhow::anyhow!("quiche connect: {e}"))?;

    let mut out = vec![0u8; MAX_DATAGRAM_SIZE];
    let mut buf = vec![0u8; 65535];

    let (write, send_info) = conn
        .send(&mut out)
        .map_err(|e| anyhow::anyhow!("initial send: {e}"))?;
    socket.send_to(&out[..write], send_info.to).await?;

    // Complete handshake
    loop {
        let timeout = conn.timeout().unwrap_or(Duration::from_millis(100));

        tokio::select! {
            result = socket.recv(&mut buf) => {
                let len = result?;
                let recv_info = quiche::RecvInfo {
                    to: local_addr,
                    from: tunnel_cfg.endpoint,
                };
                conn.recv(&mut buf[..len], recv_info).ok();
            }
            () = tokio::time::sleep(timeout) => {
                conn.on_timeout();
            }
        }

        loop {
            match conn.send(&mut out) {
                Ok((write, send_info)) => {
                    socket.send_to(&out[..write], send_info.to).await?;
                }
                Err(quiche::Error::Done) => break,
                Err(e) => bail!("send during handshake: {e}"),
            }
        }

        if conn.is_established() {
            break;
        }
        if conn.is_closed() {
            bail!("connection closed during handshake");
        }
    }

    // Verify endpoint key pinning
    if let Some(peer_cert) = conn.peer_cert() {
        if !tls::verify_endpoint_key(peer_cert, &tls_material.endpoint_pub_key_spki_der) {
            bail!("peer certificate public key does not match pinned endpoint key");
        }
        log::debug!("Endpoint key pinning verified");
    } else {
        log::warn!("No peer certificate received; skipping key pinning");
    }

    // Set up HTTP/3
    let mut h3_config = quiche::h3::Config::new().map_err(|e| anyhow::anyhow!("h3 config: {e}"))?;
    h3_config.enable_extended_connect(true);

    let mut h3_conn = quiche::h3::Connection::with_transport(&mut conn, &h3_config)
        .map_err(|e| anyhow::anyhow!("h3 connection: {e}"))?;

    // Send CONNECT request for cf-connect-ip
    let req = vec![
        quiche::h3::Header::new(b":method", b"CONNECT"),
        quiche::h3::Header::new(b":protocol", b"cf-connect-ip"),
        quiche::h3::Header::new(b":scheme", b"https"),
        quiche::h3::Header::new(b":authority", b"cloudflareaccess.com"),
        quiche::h3::Header::new(b":path", b"/"),
        quiche::h3::Header::new(b"capsule-protocol", b"?1"),
        quiche::h3::Header::new(b"user-agent", b""),
    ];

    let stream_id = h3_conn
        .send_request(&mut conn, &req, false)
        .map_err(|e| anyhow::anyhow!("send CONNECT request: {e}"))?;

    let flow_id = stream_id / 4;
    log::debug!("CONNECT request sent on stream {stream_id}, flow_id={flow_id}");

    loop {
        match conn.send(&mut out) {
            Ok((write, send_info)) => {
                socket.send_to(&out[..write], send_info.to).await?;
            }
            Err(quiche::Error::Done) => break,
            Err(e) => bail!("send after CONNECT: {e}"),
        }
    }

    // Wait for 2xx
    let mut connect_established = false;
    for _ in 0..100 {
        let timeout = conn.timeout().unwrap_or(Duration::from_millis(100));

        tokio::select! {
            result = socket.recv(&mut buf) => {
                let len = result?;
                let recv_info = quiche::RecvInfo {
                    to: local_addr,
                    from: tunnel_cfg.endpoint,
                };
                conn.recv(&mut buf[..len], recv_info).ok();
            }
            () = tokio::time::sleep(timeout) => {
                conn.on_timeout();
            }
        }

        loop {
            match h3_conn.poll(&mut conn) {
                Ok((sid, quiche::h3::Event::Headers { list, has_body: _ })) if sid == stream_id => {
                    for h in &list {
                        if h.name() == b":status" {
                            let status = std::str::from_utf8(h.value()).unwrap_or("?");
                            log::debug!("CONNECT response status: {status}");
                            if status.starts_with('2') {
                                connect_established = true;
                            } else {
                                bail!("CONNECT rejected with status {status}");
                            }
                        }
                    }
                }
                Ok(_) => {}
                Err(quiche::h3::Error::Done) => break,
                Err(e) => bail!("h3 poll error: {e}"),
            }
        }

        // Flush
        loop {
            match conn.send(&mut out) {
                Ok((write, send_info)) => {
                    socket.send_to(&out[..write], send_info.to).await?;
                }
                Err(quiche::Error::Done) => break,
                Err(e) => bail!("send during CONNECT wait: {e}"),
            }
        }

        if connect_established {
            break;
        }
        if conn.is_closed() {
            bail!("connection closed before CONNECT response");
        }
    }

    if !connect_established {
        bail!("timed out waiting for CONNECT response");
    }

    log::info!("MASQUE tunnel established to {}", tunnel_cfg.endpoint);

    // ADDED: publish readiness. Upstream printed a line here and the operator
    // read it; a library caller needs the transition as a value it can poll.
    // Set only after the CONNECT response is accepted, so `true` means the flow
    // is open and datagrams will be carried — not merely that QUIC handshook.
    established.store(true, Ordering::Release);

    // Build the flow_id varint prefix + context_id zero
    let mut flow_prefix = Vec::with_capacity(16);
    {
        let mut tmp = [0u8; 8];
        let mut b = octets::OctetsMut::with_slice(&mut tmp);
        b.put_varint(flow_id).unwrap();
        let len = b.off();
        flow_prefix.extend_from_slice(&tmp[..len]);
    }
    flow_prefix.push(0x00);

    if let Some(mut pkt) = pending_pkt.take() {
        if packet::prepare_outgoing(&mut pkt).is_ok() {
            let mut dgram = Vec::with_capacity(flow_prefix.len() + pkt.len());
            dgram.extend_from_slice(&flow_prefix);
            dgram.extend_from_slice(&pkt);
            let pkt_len = pkt.len() as u64;
            if conn.dgram_send_vec(dgram).is_ok() {
                stats.tx_packets.fetch_add(1, Ordering::Relaxed);
                stats.tx_bytes.fetch_add(pkt_len, Ordering::Relaxed);
            }
        }
    }

    // Main data forwarding loop
    let mtu = tunnel_cfg.mtu as usize;
    let mut tun_buf = vec![0u8; mtu + 128];
    let keepalive_interval = tunnel_cfg.keepalive_period;

    let result: Result<()> = loop {
        let timeout = conn
            .timeout()
            .unwrap_or(keepalive_interval)
            .min(keepalive_interval);

        tokio::select! {
            // Read from TUN -> send to QUIC
            result = tokio::io::AsyncReadExt::read(tun_reader, &mut tun_buf) => {
                let n = result?;
                if n == 0 {
                    bail!("TUN device closed");
                }

                let pkt = &mut tun_buf[..n];
                match packet::prepare_outgoing(pkt) {
                    Ok(_) => {
                        let pkt_len = n as u64;
                        let mut dgram = Vec::with_capacity(flow_prefix.len() + n);
                        dgram.extend_from_slice(&flow_prefix);
                        dgram.extend_from_slice(pkt);

                        match conn.dgram_send_vec(dgram) {
                            Ok(()) => {
                                stats.tx_packets.fetch_add(1, Ordering::Relaxed);
                                stats.tx_bytes.fetch_add(pkt_len, Ordering::Relaxed);
                            }
                            Err(quiche::Error::InvalidState) => {
                                log::warn!("datagram send: peer doesn't support datagrams");
                            }
                            Err(quiche::Error::Done) => {
                                stats.dropped.fetch_add(1, Ordering::Relaxed);
                                log::trace!("datagram send queue full, dropping packet");
                            }
                            Err(e) => {
                                stats.dropped.fetch_add(1, Ordering::Relaxed);
                                log::debug!("datagram send error: {e}, generating ICMP");
                                if let Some(icmp_pkt) = icmp::compose_icmp_too_large(&tun_buf[..n], 1280) {
                                    tokio::io::AsyncWriteExt::write_all(tun_writer, &icmp_pkt).await.ok();
                                }
                            }
                        }
                    }
                    Err(e) => {
                        stats.dropped.fetch_add(1, Ordering::Relaxed);
                        log::trace!("dropping outgoing packet: {e}");
                    }
                }
            }

            // Read from QUIC socket
            result = socket.recv(&mut buf) => {
                let len = result?;
                let recv_info = quiche::RecvInfo {
                    to: local_addr,
                    from: tunnel_cfg.endpoint,
                };
                if let Err(e) = conn.recv(&mut buf[..len], recv_info) {
                    log::debug!("quic recv error: {e}");
                }
            }

            // Timeout handling
            () = tokio::time::sleep(timeout) => {
                conn.on_timeout();
            }
        }

        // After any event, drain all pending UDP packets from the socket.
        // This prevents stale ACKs and reduces unnecessary retransmissions
        // when the TUN or timeout branch wins the select.
        while let Ok(len) = socket.try_recv(&mut buf) {
            let recv_info = quiche::RecvInfo {
                to: local_addr,
                from: tunnel_cfg.endpoint,
            };
            conn.recv(&mut buf[..len], recv_info).ok();
        }

        // Process H3 events (capsules, etc.)
        loop {
            match h3_conn.poll(&mut conn) {
                Ok(_) => {}
                Err(quiche::h3::Error::Done) => break,
                Err(e) => {
                    log::warn!("h3 poll error: {e}");
                    break;
                }
            }
        }

        // Drain received datagrams -> TUN
        loop {
            match conn.dgram_recv_vec() {
                Ok(dgram) => {
                    if let Some(ip_payload) = parse_datagram(&dgram, flow_id) {
                        if packet::validate_incoming(ip_payload).is_ok() {
                            stats.rx_packets.fetch_add(1, Ordering::Relaxed);
                            stats
                                .rx_bytes
                                .fetch_add(ip_payload.len() as u64, Ordering::Relaxed);
                            tokio::io::AsyncWriteExt::write_all(tun_writer, ip_payload)
                                .await
                                .ok();
                        }
                    }
                }
                Err(quiche::Error::Done) => break,
                Err(e) => {
                    log::debug!("dgram recv error: {e}");
                    break;
                }
            }
        }

        // Always flush outgoing QUIC packets
        loop {
            match conn.send(&mut out) {
                Ok((write, send_info)) => {
                    if let Err(e) = socket.send_to(&out[..write], send_info.to).await {
                        log::warn!("UDP send error: {e}");
                        break;
                    }
                }
                Err(quiche::Error::Done) => break,
                Err(e) => {
                    log::error!("quic send error: {e}");
                    bail!("quic send error: {e}");
                }
            }
        }

        // Update QUIC-level stats
        let qs = conn.stats();
        stats.quic_lost.store(qs.lost as u64, Ordering::Relaxed);
        stats
            .quic_retrans
            .store(qs.retrans as u64, Ordering::Relaxed);

        if conn.is_closed() {
            break Ok(());
        }
    };

    result
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
        let stats = Stats::new();
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
