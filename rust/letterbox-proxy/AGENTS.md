# AGENTS.md — letterbox-proxy

Privacy-preserving image proxy: fetches remote images through Cloudflare WARP
(WireGuard via boringtun + a `smoltcp` TCP/IP stack), exposed via UniFFI. Design
and data flow: `docs/image-proxy-design.md`, `docs/remote-images.md`. Root rules
apply; this file adds crate-local ones.

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
* Engineering standards (root `AGENTS.md` › Engineering Standards,
  `docs/agents/engineering-standards.md`) are mandatory here.
