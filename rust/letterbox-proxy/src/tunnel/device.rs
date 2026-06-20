//! Virtual smoltcp [`Device`] bridging the TCP/IP stack to WireGuard.
//!
//! The device owns two packet queues:
//!
//! * `rx_queue` — decrypted IP packets produced by the WireGuard transport that
//!   smoltcp should ingest.
//! * `tx_queue` — IP packets smoltcp wants to emit, which the tunnel drains and
//!   hands to the WireGuard transport for encryption.
//!
//! Following smoltcp's own loopback device, [`VirtualRxToken`] *owns* the popped
//! buffer (so it borrows nothing) while [`VirtualTxToken`] borrows only the
//! `tx_queue` field. Because the two tokens touch disjoint fields, the device
//! needs no interior mutability — no `Rc`, `RefCell`, `Arc` or `Mutex`.

use smoltcp::phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken};
use smoltcp::time::Instant as SmoltcpInstant;
use std::collections::VecDeque;

/// WireGuard's tunnel MTU. Packets larger than this are fragmented by smoltcp.
const WIREGUARD_MTU: usize = 1420;

/// A smoltcp device whose "wire" is the WireGuard transport.
pub struct VirtualDevice {
    /// Decrypted inbound IP packets waiting to be read by smoltcp.
    rx_queue: VecDeque<Vec<u8>>,
    /// Outbound IP packets emitted by smoltcp waiting to be encrypted.
    tx_queue: VecDeque<Vec<u8>>,
}

impl VirtualDevice {
    /// Create an empty device with no queued packets.
    pub fn new() -> Self {
        Self {
            rx_queue: VecDeque::new(),
            tx_queue: VecDeque::new(),
        }
    }

    /// Enqueue a decrypted IP packet for smoltcp to ingest on the next poll.
    pub fn push_inbound(&mut self, packet: Vec<u8>) {
        self.rx_queue.push_back(packet);
    }

    /// Remove the next outbound IP packet smoltcp emitted, if any.
    pub fn pop_outbound(&mut self) -> Option<Vec<u8>> {
        self.tx_queue.pop_front()
    }

    /// Whether any decrypted packets are still waiting to be ingested.
    pub fn has_inbound(&self) -> bool {
        !self.rx_queue.is_empty()
    }
}

impl Default for VirtualDevice {
    fn default() -> Self {
        Self::new()
    }
}

impl Device for VirtualDevice {
    type RxToken<'a> = VirtualRxToken;
    type TxToken<'a> = VirtualTxToken<'a>;

    fn receive(
        &mut self,
        _timestamp: SmoltcpInstant,
    ) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let buffer = self.rx_queue.pop_front()?;
        let rx = VirtualRxToken { buffer };
        let tx = VirtualTxToken {
            queue: &mut self.tx_queue,
        };
        Some((rx, tx))
    }

    fn transmit(&mut self, _timestamp: SmoltcpInstant) -> Option<Self::TxToken<'_>> {
        Some(VirtualTxToken {
            queue: &mut self.tx_queue,
        })
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = WIREGUARD_MTU;
        caps
    }
}

/// Receive token that owns the decrypted packet it yields.
pub struct VirtualRxToken {
    buffer: Vec<u8>,
}

impl RxToken for VirtualRxToken {
    fn consume<R, F>(self, f: F) -> R
    where
        F: FnOnce(&[u8]) -> R,
    {
        f(&self.buffer)
    }
}

/// Transmit token that appends smoltcp's emitted packet to the outbound queue.
pub struct VirtualTxToken<'a> {
    queue: &'a mut VecDeque<Vec<u8>>,
}

impl TxToken for VirtualTxToken<'_> {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut buffer = vec![0u8; len];
        let result = f(&mut buffer);
        self.queue.push_back(buffer);
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capabilities_match_wireguard_mtu() {
        let device = VirtualDevice::new();
        let caps = device.capabilities();
        assert_eq!(caps.medium, Medium::Ip);
        assert_eq!(caps.max_transmission_unit, WIREGUARD_MTU);
    }

    #[test]
    fn receive_yields_queued_packet() {
        let mut device = VirtualDevice::new();
        assert!(!device.has_inbound());
        device.push_inbound(vec![0x45, 0x00, 0x00, 0x14]);
        assert!(device.has_inbound());

        let (rx, _tx) = device
            .receive(SmoltcpInstant::from_millis(0))
            .expect("packet should be available");
        rx.consume(|buf| assert_eq!(buf[0], 0x45));
        assert!(!device.has_inbound());
    }

    #[test]
    fn receive_returns_none_when_empty() {
        let mut device = VirtualDevice::new();
        assert!(device.receive(SmoltcpInstant::from_millis(0)).is_none());
    }

    #[test]
    fn transmit_enqueues_outbound_packet() {
        let mut device = VirtualDevice::new();
        let token = device
            .transmit(SmoltcpInstant::from_millis(0))
            .expect("transmit token");
        token.consume(4, |buf| {
            buf.copy_from_slice(&[0x45, 0x00, 0x00, 0x14]);
        });
        assert_eq!(device.pop_outbound(), Some(vec![0x45, 0x00, 0x00, 0x14]));
        assert_eq!(device.pop_outbound(), None);
    }
}
