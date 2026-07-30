# Provenance

This crate is derived from **[usque-rs](https://github.com/Diniboy1123/usque-rs)**
by Diniboy1123, MIT licensed. The upstream terms are reproduced verbatim in
[`LICENSE.md`](./LICENSE.md) and continue to apply to this code.

| | |
|---|---|
| Upstream | `https://github.com/Diniboy1123/usque-rs` |
| Mirror | `https://github.com/BTreeMap/usque-rs` (read-only fork) |
| Commit | `1b808bb3f2e49cc5f73dcbe764adb07af6802f16` |
| Date | 2026-03-04 (`v0.1.0`) |
| License | MIT |

## Why vendored rather than a submodule

usque-rs declares **only a `[[bin]]` target**. Every module is a private `mod`
in `main.rs`, so there is no library to link against and a path dependency on a
submodule cannot compile.

Including the files in place with `#[path]` — which would have preserved a
submodule for updates — fails on one line. `tunnel.rs` contains:

```rust
pub async fn maintain_tunnel(..., tun_dev: tun::Device) -> Result<()>
```

Rust compiles the whole file, so including it requires the Linux-only `tun`
crate, which does not build for Android, even though that function is never
called. A deletion is therefore unavoidable, and a read-only submodule cannot
carry one.

## What was taken

| File | Lines | State |
|---|---|---|
| `src/packet.rs` | 358 | **verbatim** — IPv4/IPv6 validation, TTL decrement, checksum |
| `src/icmp.rs` | 352 | **verbatim** — "Packet Too Big" synthesis for PMTU |
| `src/tls.rs` | 68 | one signature changed (below) |
| `src/tunnel.rs` | 663 | patched (below) |

`src/config.rs` (file-backed JSON config), `src/register.rs` (registration
API), `src/tun_device.rs` and `src/main.rs` (CLI) were not taken: Letterbox
already has its own configuration, provisioning and entry point.

`tests/tunnel_mtu.rs` was not taken. It requires root or `CAP_NET_ADMIN` and
creates real TUN devices, so it cannot run in CI or on Android. The 31 unit
tests inside `packet.rs` and `icmp.rs` came across unchanged and do run.

## Modifications

Every change is marked in the source with `MODIFIED FROM UPSTREAM` or `ADDED`.

**`src/tls.rs`**
1. `prepare_tls_material` takes `&TunnelIdentity` instead of usque-rs's
   file-backed `Config`. Certificate generation and SPKI pinning are unchanged.

**`src/tunnel.rs`**
1. **Removed `maintain_tunnel`** (~45 lines). It owned a `tun::Device` and
   reconnected in a loop. The `tun` crate is Linux-only; reconnection policy is
   the caller's.
2. **Removed the terminal status line** — a `tokio::spawn`ed task rendering
   `\r\x1b[2K…` once a second, plus `format_bytes` and `format_duration` and
   their two tests. A library has no terminal. Events it displayed are `log`ged.
3. **`run_tunnel_session` is public** and takes `&TunnelIdentity` rather than
   `&Config`.
4. **`Stats` is public**, is passed in rather than constructed internally, and
   gained `snapshot()`. Upstream only ever read it to print.
5. **Added `established: Arc<AtomicBool>`**, set after the CONNECT-IP response
   is accepted. Upstream printed "connected" and a human read it; a library
   caller needs the transition as a value it can poll.

Not modified: the QUIC handshake, endpoint pinning, extended-CONNECT exchange,
datagram framing (`parse_datagram`), PMTU handling, or the forwarding loop.

**Letterbox additions** (not derived from upstream): `src/lib.rs`, and the
`TunnelIdentity` / `IdentityError` types.

## Checking against upstream

```sh
git clone https://github.com/BTreeMap/usque-rs /tmp/usque-rs
git -C /tmp/usque-rs checkout 1b808bb
diff -u /tmp/usque-rs/src/packet.rs rust/usque-core/src/packet.rs   # expect no output
diff -u /tmp/usque-rs/src/icmp.rs   rust/usque-core/src/icmp.rs     # expect no output
diff -u /tmp/usque-rs/src/tunnel.rs rust/usque-core/src/tunnel.rs   # expect only the above
```

`packet.rs` and `icmp.rs` must diff empty — verified at the time of writing. If
they ever do not, this file is out of date and the discrepancy should be
resolved before shipping.

`tunnel.rs` and `tls.rs` will show more changed lines than the list above
implies, because the repository's `cargo fmt` runs over them and reflows
untouched code. Read the diff for the marked edits, not for its size.
