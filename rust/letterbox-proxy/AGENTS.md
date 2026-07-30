# AGENTS.md — letterbox-proxy

Privacy-preserving image proxy: fetches remote images through Cloudflare WARP
over a `smoltcp` TCP/IP stack, exposed via UniFFI. Design and data flow:
`docs/image-proxy-design.md`, `docs/remote-images.md`. Root rules apply; this
file adds crate-local ones.

The transport is **MASQUE** (CONNECT-IP over HTTP/3) via the `usque-core` crate.
WireGuard was removed on 2026-07-30. Everything above `tunnel::masque` —
`stack`, `tls`, `http1`, `dns` — works in raw IP packets and must stay
transport-agnostic; that layering is what made the swap a contained change.

## Commands

* Tests: `cargo test`
* Live MASQUE end-to-end (live network, non-blocking): `cargo test --test masque_real -- --ignored --nocapture --test-threads=1`
* Lint: `cargo clippy --all-targets -- -D warnings`
* Format: `cargo fmt --all -- --check`

## Local rules

* Never log or persist WARP private keys or device tokens outside the documented
  app-private storage path.
* Keep the `masque_real` and `masque_upgrade` tests `#[ignore]` so the live
  Cloudflare path never gates CI. They provision and delete real devices.
* Source is split by concern (`provisioning`, `http`, `config`, `update`,
  `tunnel/`, ...). Add a submodule instead of growing a file past the 500-line
  limit.
* `MasqueTransport::new` is pure — it decodes credentials and nothing else. The
  session thread starts in `initiate_handshake`. Keep that split: constructing a
  transport must never touch the network, or the stack's unit tests go online.
* Registration still sends a 32-byte `key` because Cloudflare requires one, but
  it is random, not an X25519 public key, and nothing uses it. Do not reintroduce
  a curve dependency to "fix" it.
* `tunnel::duplex::PacketDuplex` is packet-framed on purpose: one read yields one
  whole IP packet, one write consumes one. `AsyncRead` does not guarantee this.
  Anything that splits or merges a packet there corrupts every datagram, so
  changes to that module need the framing tests to stay meaningful, not just
  green.
* MASQUE sends `api.cloudflare.com` as its SNI, not the reference
  implementations' `consumer-masque.cloudflareclient.com`. This is a privacy
  decision, documented at `tunnel::masque::MASQUE_SNI`. Do not "correct" it back
  to match upstream samples.
* Do not hand-edit `rust/usque-core/src/{packet,icmp,tunnel,tls}.rs` casually.
  They are vendored from usque-rs; every deviation is marked in-source and
  listed in `rust/usque-core/PROVENANCE.md`. Update that file with any change,
  and keep `packet.rs`/`icmp.rs` byte-identical to upstream.
* Engineering standards (root `AGENTS.md` › Engineering Standards,
  `docs/agents/engineering-standards.md`) are mandatory here.
