# Architecture

## Overview

Letterbox pairs an Android/Jetpack Compose client with a Rust email parsing core. Kotlin calls into the `letterbox_core` shared library via UniFFI/JNA, keeps parsed data in Rust memory, and renders UI from lightweight DTOs. Recent files are persisted through a small content-addressable store.

## Components

- **Android UI (`app/`)**: `MainActivity` drives Compose screens for history and message detail, launches file pickers for `.eml`, and shares the current email via a FileProvider.
- **View model & data layer**: `EmailViewModel` orchestrates parsing, error handling, and history updates. `HistoryRepository` and Room entities (`data/`) deduplicate blobs, track access times, and enforce limits; `InMemoryHistoryRepository` is used for tests.
- **Rust core (`rust/letterbox-core`)**: Parses emails with `mail-parser`, returns an `EmailHandle` exposing headers, body variants, inline resource metadata, and attachment accessors; exported through UniFFI.
- **FFI bindings (`app/src/main/java/org/joefang/letterbox/ffi/`)**: Generated Kotlin bindings load `letterbox_core` via JNA and surface `parseEml`/`parseEmlFromPath` plus `EmailHandle` methods.

## Data flow

1. Android receives an `ACTION_VIEW` or `ACTION_SEND` intent for `.eml` content.
2. `EmailViewModel` ingests the bytes, asks the Rust core (`parseEml` or `parseEmlFromPath`) to parse, and projects the result into `EmailContent` for the UI.
3. The content-addressable store writes the file under its SHA-256 hash and records a history entry with timestamps and optional source URI.
4. Compose UI reads the view model state to render message headers, HTML/text bodies, inline assets, and attachment actions.

## Build integration

- Gradle task `cargoHostBuild` compiles a host `libletterbox_core.so` and sets `jna.library.path` for unit tests.
- When `-PrustBuild=true` is provided, `cargoNdkBuild` cross-compiles `letterbox_core` for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, placing `.so` files under `app/src/main/jniLibs`.
- Product flavors (`prod`, `staging`) are defined in `app/build.gradle.kts`; assemble the desired variant (e.g., `:app:assembleProdDebug`).
- Flavor differences: `prod` uses the base `applicationId`, while `staging` appends `.test`, adds a `-test` version suffix, and overrides `app_name` to “Letterbox (Test)`.
- Version codes are automatically derived from Git tags using a 30-bit schema; see [versioning.md](versioning.md) for details.
