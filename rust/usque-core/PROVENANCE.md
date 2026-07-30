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
| `src/packet.rs` | 358 | IPv4/IPv6 validation, TTL decrement, checksum |
| `src/icmp.rs` | 352 | "Packet Too Big" synthesis for PMTU |
| `src/tls.rs` | 68 | certificate generation and SPKI pinning |
| `src/tunnel.rs` | 663 | QUIC handshake, CONNECT-IP, datagram loop |

`src/config.rs` (file-backed JSON config), `src/register.rs` (registration
API), `src/tun_device.rs` and `src/main.rs` (CLI) were not taken: Letterbox
already has its own configuration, provisioning and entry point.

`tests/tunnel_mtu.rs` was not taken. It requires root or `CAP_NET_ADMIN` and
creates real TUN devices, so it cannot run in CI or on Android. The unit tests
inside `packet.rs` and `icmp.rs` came across and do run.

## Status: forked, not vendored

**This is Letterbox's code now.** It is not synced with upstream in either
direction: no changes are contributed back, and no upstream changes are pulled
in. The table above records where the code came from and the licence it arrived
under — obligations that do not expire — and nothing more.

That distinction decides how the code is maintained. While a rebase was on the
table, staying close to upstream had real value: every divergence was a merge
conflict deferred, so "verbatim" was worth protecting even where the code fell
short of the standards applied elsewhere in this repository. With no rebase to
protect, that value is zero, and the cost of holding the line — code held to a
weaker standard than the modules that call it — is all that remains.

So this crate is held to the same standard as the rest of the repository:
clippy-clean, no `unwrap` outside tests, invariants in types rather than in
comments, and duplication removed rather than preserved out of deference. The
per-change `MODIFIED FROM UPSTREAM` markers that used to annotate the source
have been dropped for the same reason: they tracked a diff nobody will ever
apply, and they were already drifting out of date.

The behavioural decisions that were made *for Letterbox specifically* are
recorded below, because those are design choices a future reader needs, not
bookkeeping against a diff.

## Deliberate divergences from upstream behaviour

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
6. **`set_max_idle_timeout` is finite**, configured via a new
   `TunnelConfig::idle_timeout`, where upstream passes `0`. In quiche `0` means
   *no* idle timeout, which is defensible for a daemon an operator can kill but
   not for a library: the handshake loop has no other exit, so an unreachable
   endpoint spins the session thread forever and anything joining it blocks
   with it. On Android that is an ANR. `TunnelConfig::new` rejects an idle
   timeout that does not exceed the keepalive period, which would otherwise
   disconnect a healthy tunnel between its own keepalives.
The protocol itself is unchanged: the QUIC handshake, endpoint pinning,
extended-CONNECT exchange, datagram framing (`parse_datagram`) and PMTU
handling all behave as upstream does.

**Letterbox additions** (not derived from upstream): `src/lib.rs`,
`src/checksum.rs`, `src/wire.rs`, and the `TunnelIdentity` / `IdentityError`
types.

## Structural changes

These preserve behaviour and exist to hold the crate to the repository's
standard. They are listed because they move code between files, which a reader
comparing against upstream will notice.

- **`src/checksum.rs`** replaces four separate transcriptions of the internet
  checksum (two in `packet.rs`, three in `icmp.rs`, overlapping). Ones'-
  complement addition is a commutative monoid, so one accumulator serves the
  IPv4 header, the ICMP message and the ICMPv6 pseudo-header alike — the last
  of which no longer has to be assembled in memory to be summed.
- **`src/wire.rs`** holds the IP header offsets and an `IpVersion` sum that
  `packet.rs` and `icmp.rs` previously each declared for themselves. Two
  transcriptions of the same RFC are two chances to mistype an offset, and the
  mistake shows up as a checksum that fails on a real network rather than as
  anything a test catches.
- **`src/tunnel.rs`** is split into named phases (`complete_handshake`,
  `open_connect_ip_flow`, `forward_packets`) with one `flush_egress` where
  there were five copies of the same drain loop. Those copies were not quite
  identical: a refused UDP datagram is fatal while connecting and survivable
  once established, and that difference is preserved as a `Flushed` value each
  phase eliminates for itself. `ip_version` returns
  `Option<IpVersion>` rather than indexing byte 0 of a possibly-empty buffer,
  and the `pending_pkt` in/out parameter is gone: it existed for
  `maintain_tunnel`'s reconnect path and has been dead since that was removed.

## Dependency divergence: quiche 0.29

Upstream pins quiche `0.22`. This fork tracks `0.29.3`, because 0.22 carries two
advisories — an infinite loop on connection-ID retirement (GHSA-m3hh-f9gh-74c2,
high, fixed in 0.24.5) and a use-after-free in the connection-ID iterator
(GHSA-mh64-ph39-mrc9, fixed in 0.29.2). Both are in the QUIC layer this tunnel
depends on, so staying on upstream's pin was not an option.

Two API changes came with it, and both narrow rather than widen what we do:

- The `boringssl-vendored` feature is gone; vendoring BoringSSL is what the
  default build does now, so the feature list is empty.
- `dgram_send_vec`/`dgram_recv_vec` are gone in favour of `dgram_send`/
  `dgram_recv`, which take a caller's buffer instead of allocating one per
  datagram. `deliver_inbound` therefore takes a `scratch: &mut [u8]` owned by
  `forward_packets`, alongside the `tun_buf` already living there — one
  allocation per session where the old signature cost one per datagram. It is
  sized to `RECV_BUFFER_SIZE` rather than the tunnel MTU because `dgram_recv`
  answers `BufferTooShort` rather than truncating, and a dropped oversized
  datagram would be a stall with no symptom.

## Consulting upstream

Upstream remains useful as a reference when reading the protocol code — it is
the implementation this was checked against on a live Cloudflare endpoint — and
it can be fetched for that:

```sh
git clone https://github.com/BTreeMap/usque-rs /tmp/usque-rs
git -C /tmp/usque-rs checkout 1b808bb
```

Do **not** expect the files to correspond. There is no diff to keep small and
no expectation that any file matches; a divergence is not a discrepancy to
resolve. Consult upstream to understand *why* the protocol does something, and
change this crate on its own merits.

Should upstream ever fix a protocol bug worth having, port the fix
deliberately — read it, decide it applies, write it in this crate's idiom, and
add a test. Do not merge.
