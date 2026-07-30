# AGENTS.md — letterbox-proxy

Privacy-preserving image proxy: fetches remote images through Cloudflare WARP
over a `smoltcp` TCP/IP stack, exposed via UniFFI. Design and data flow:
`docs/image-proxy-design.md`, `docs/remote-images.md`. Root rules apply; this
file adds crate-local ones.

Two transports carry the same IP packets and are selected by
`tunnel::link::Link`: **MASQUE** (CONNECT-IP over HTTP/3, via the `usque-core`
crate) when the account has enrolled credentials, **WireGuard** (boringtun)
otherwise. Everything above `Link` — `stack`, `tls`, `http1`, `dns` — is
transport-agnostic and must stay that way.

## Commands

* Tests: `cargo test`
* Live WARP end-to-end (live network, non-blocking): `cargo test --test warp_real -- --ignored --nocapture`
* Lint: `cargo clippy --all-targets -- -D warnings`
* Format: `cargo fmt --all -- --check`

## Local rules

* Never log or persist WARP private keys or device tokens outside the documented
  app-private storage path.
* Keep the `warp_real` test `#[ignore]` so the live Cloudflare path never gates
  CI.
* Source is split by concern (`provisioning`, `http`, `config`, `update`,
  `tunnel/`, ...). Add a submodule instead of growing a file past the 500-line
  limit.
* Adding a transport means adding a `Link` variant. The compiler will then list
  every site that must handle it — do not reach for a trait object to avoid
  that; the exhaustiveness is the point.
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
