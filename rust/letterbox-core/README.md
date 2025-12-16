# letterbox-core

## Purpose

Rust library that parses `.eml` messages with `mail-parser` and exposes a UniFFI surface for Kotlin/Android consumers. It returns an `EmailHandle` that keeps parsed content in Rust while Kotlin reads headers, bodies, inline resources, and attachments via JNA.

## How it fits in the repo

This crate is the workspace member referenced from the root `Cargo.toml` and is built by Gradle tasks (`cargoNdkBuild`, `cargoHostBuild`) so the Android app can ship or test against the compiled `letterbox_core` shared library.

## Build/Run

- Build for the host: `cargo build --release --lib`.
- Run tests: `cargo test`.
- Android builds invoke `cargo ndk` for `arm64-v8a`, `armeabi-v7a`, and `x86_64` targets when Gradle sets `rustBuild=true`, placing `.so` outputs under `app/src/main/jniLibs`.

## Tests

- `cargo test` exercises parsing, attachments, inline resource metadata, and file-path parsing (`src/lib.rs` tests).

## Interfaces

- Exported functions (`src/lib.rs`): `parse_eml(data: Vec<u8>)` and `parse_eml_from_path(path: String)` returning `Arc<EmailHandle>` or `ParseError` (`Invalid`, `Empty`, `FileNotFound`, `IoError`).
- `EmailHandle` methods expose header accessors (`subject`, `from`, `to`, `cc`, `reply_to`, `message_id`, `date`), bodies (`body_html`, `body_text`), inline resource queries (`get_resource*`, `get_resource_metadata`, `write_resource_to_path`), and attachment access (`get_attachments`, `attachment_count`, `get_attachment_content`, `write_attachment_to_path`).
- `SMALL_RESOURCE_THRESHOLD` (64 KB) flags inline resources suitable for direct return over FFI.
- UniFFI Kotlin bindings are configured in `uniffi.toml` with package `org.joefang.letterbox.ffi` and cdylib name `letterbox_core`.

## Troubleshooting

- `parse_eml_from_path` returns `ParseError::FileNotFound` when the file is missing; ensure paths are valid before invoking over FFI.
- Large resources or attachments can be streamed to disk via `write_resource_to_path` or `write_attachment_to_path` to avoid copying across the FFI boundary.
