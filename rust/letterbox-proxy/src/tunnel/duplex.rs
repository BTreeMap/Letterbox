//! A packet-framed [`AsyncRead`]/[`AsyncWrite`] pair over channels.
//!
//! [`usque_core::run_tunnel_session`] was written against a TUN device, where a
//! `read` returns exactly one IP packet and a `write` delivers exactly one. That
//! framing is not a property of [`AsyncRead`] — a byte stream is free to split
//! and coalesce — but the session loop depends on it: it hands whatever one read
//! produced straight to `dgram_send_vec` as a single CONNECT-IP datagram.
//!
//! [`PacketDuplex`] restores that guarantee over a pair of channels, so the same
//! loop can drive Letterbox's userspace `smoltcp` stack instead of a kernel
//! interface. Every `poll_read` yields one whole packet or nothing; every
//! `poll_write` consumes its buffer as one whole packet.
//!
//! # Backpressure
//!
//! Both channels are bounded and both drop on overflow rather than blocking.
//! That is the correct policy for a datagram tunnel: IP is lossy by contract,
//! and TCP above the stack retransmits. Blocking instead would stall
//! the session loop, which also services QUIC timers — losing a packet costs a
//! retransmit, stalling the loop costs the connection.

use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::mpsc::error::TrySendError;
use tokio::sync::mpsc::{Receiver, Sender};

/// How many packets may queue in either direction before we start dropping.
///
/// Sized for a burst of a full TCP window's worth of segments without letting a
/// stalled peer accumulate unbounded memory.
pub const PACKET_QUEUE_DEPTH: usize = 128;

/// The session side of the packet bridge.
///
/// Reads deliver packets the stack wants to send; writes deliver packets that
/// arrived from the tunnel.
pub struct PacketDuplex {
    /// Outbound: stack → tunnel. Read by the session loop.
    outbound: Receiver<Vec<u8>>,
    /// Inbound: tunnel → stack. Written by the session loop.
    ///
    /// A tokio sender even though this side never awaits it: `try_send` needs no
    /// runtime, and the *receiving* end is the tunnel driver, which must be able
    /// to await a packet rather than block a thread on it.
    inbound: Sender<Vec<u8>>,
}

impl PacketDuplex {
    /// Build the session end of the bridge from its two channel halves.
    #[must_use]
    pub fn new(outbound: Receiver<Vec<u8>>, inbound: Sender<Vec<u8>>) -> Self {
        Self { outbound, inbound }
    }
}

impl AsyncRead for PacketDuplex {
    /// Yield exactly one queued packet, or pend.
    ///
    /// A packet larger than the caller's buffer is dropped rather than split. A
    /// partial packet is not a smaller packet — it is a malformed one, and
    /// forwarding it would put corrupt bytes on the wire under a valid-looking
    /// length. Callers size their buffer at MTU + headroom, so this is a
    /// defect-path branch, and it is logged as one.
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        loop {
            match self.outbound.poll_recv(cx) {
                Poll::Ready(Some(packet)) => {
                    if packet.len() > buf.remaining() {
                        log::warn!(
                            "dropping {}-byte outbound packet: exceeds {}-byte read buffer",
                            packet.len(),
                            buf.remaining()
                        );
                        continue;
                    }
                    buf.put_slice(&packet);
                    return Poll::Ready(Ok(()));
                }
                // Every sender is gone: report EOF, which the session loop
                // treats as "device closed" and shuts down cleanly.
                Poll::Ready(None) => return Poll::Ready(Ok(())),
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}

impl AsyncWrite for PacketDuplex {
    /// Accept `buf` as exactly one inbound packet.
    ///
    /// Always reports the whole buffer as written. A short write would invite
    /// the caller to send the remainder as a second packet, splitting one
    /// datagram into two malformed ones; dropping is the honest failure.
    fn poll_write(
        self: Pin<&mut Self>,
        _cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        match self.inbound.try_send(buf.to_vec()) {
            Ok(()) => Poll::Ready(Ok(buf.len())),
            Err(TrySendError::Full(_)) => {
                log::trace!("inbound packet queue full, dropping {} bytes", buf.len());
                Poll::Ready(Ok(buf.len()))
            }
            Err(TrySendError::Closed(_)) => Poll::Ready(Err(io::Error::new(
                io::ErrorKind::BrokenPipe,
                "tunnel consumer dropped",
            ))),
        }
    }

    /// Nothing buffers here: a packet is either queued or dropped by `poll_write`.
    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    fn bridge() -> (Sender<Vec<u8>>, PacketDuplex, Receiver<Vec<u8>>) {
        let (out_tx, out_rx) = tokio::sync::mpsc::channel(PACKET_QUEUE_DEPTH);
        let (in_tx, in_rx) = tokio::sync::mpsc::channel(PACKET_QUEUE_DEPTH);
        (out_tx, PacketDuplex::new(out_rx, in_tx), in_rx)
    }

    /// The framing contract: one read, one whole packet — never a merge.
    #[tokio::test]
    async fn each_read_yields_exactly_one_packet() {
        let (out_tx, mut duplex, _in_rx) = bridge();
        out_tx.send(vec![1, 2, 3]).await.expect("queue first");
        out_tx.send(vec![4, 5]).await.expect("queue second");

        let mut buf = vec![0u8; 2048];
        let first = duplex.read(&mut buf).await.expect("read first");
        assert_eq!(&buf[..first], &[1, 2, 3], "first packet must arrive whole");

        let second = duplex.read(&mut buf).await.expect("read second");
        assert_eq!(&buf[..second], &[4, 5], "packets must not coalesce");
    }

    /// A packet that cannot fit is dropped, not truncated, and does not wedge
    /// the reader: the next well-sized packet still arrives.
    #[tokio::test]
    async fn oversized_packet_is_dropped_not_split() {
        let (out_tx, mut duplex, _in_rx) = bridge();
        out_tx.send(vec![0xAA; 64]).await.expect("queue oversized");
        out_tx.send(vec![0xBB; 4]).await.expect("queue normal");

        let mut buf = vec![0u8; 8];
        let n = duplex.read(&mut buf).await.expect("read");

        assert_eq!(&buf[..n], &[0xBB; 4], "must skip to the packet that fits");
    }

    #[tokio::test]
    async fn writes_arrive_as_discrete_packets() {
        let (_out_tx, mut duplex, mut in_rx) = bridge();

        duplex.write_all(&[9, 9, 9]).await.expect("write first");
        duplex.write_all(&[7]).await.expect("write second");

        assert_eq!(in_rx.try_recv().expect("first"), vec![9, 9, 9]);
        assert_eq!(in_rx.try_recv().expect("second"), vec![7]);
    }

    /// Overflow drops rather than blocking or erroring, so a stalled consumer
    /// cannot deadlock the session loop that also drives QUIC timers.
    #[tokio::test]
    async fn inbound_overflow_drops_without_failing_the_session() {
        let (_out_tx, mut duplex, mut in_rx) = bridge();

        for _ in 0..(PACKET_QUEUE_DEPTH + 16) {
            duplex.write_all(&[1]).await.expect("write must not fail");
        }

        let drained = std::iter::from_fn(|| in_rx.try_recv().ok()).count();
        assert_eq!(drained, PACKET_QUEUE_DEPTH, "queue must stay bounded");
    }

    /// Dropping every sender is EOF, which the session loop reads as a closed
    /// device and exits on, rather than spinning.
    #[tokio::test]
    async fn closed_outbound_reads_as_eof() {
        let (out_tx, mut duplex, _in_rx) = bridge();
        drop(out_tx);

        let mut buf = vec![0u8; 64];
        assert_eq!(duplex.read(&mut buf).await.expect("read"), 0);
    }

    #[tokio::test]
    async fn disconnected_consumer_surfaces_as_broken_pipe() {
        let (_out_tx, mut duplex, in_rx) = bridge();
        drop(in_rx);

        let err = duplex.write_all(&[1]).await.expect_err("must fail");
        assert_eq!(err.kind(), io::ErrorKind::BrokenPipe);
    }
}
