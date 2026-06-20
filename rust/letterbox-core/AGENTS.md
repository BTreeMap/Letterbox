# AGENTS.md — letterbox-core

Rust crate that parses `.eml` files with `mail-parser` and exposes an
`EmailHandle` (headers, bodies, inline resources, attachments) to Android via
UniFFI. Root rules apply; this file adds crate-local ones.

## Commands

* Tests: `cargo test`
* Lint: `cargo clippy --all-targets -- -D warnings`
* Format: `cargo fmt --all -- --check`

## Local rules

* The exported `#[uniffi]` surface here is the Android contract. When you change
  it, regenerate bindings — do not hand-edit Kotlin under
  `app/src/main/java/org/joefang/letterbox/ffi/`.
* Parsing must be pure and fallible: return `Result` and never panic on
  malformed input.
* Engineering standards (root `AGENTS.md` › Engineering Standards,
  `docs/agents/engineering-standards.md`) are mandatory here.
