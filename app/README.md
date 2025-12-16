# Letterbox Android app

## Purpose
Android application that opens `.eml` files, parses them through the UniFFI-generated `letterbox_core` bindings, and renders message content with Jetpack Compose. History, sharing, and basic settings are handled in `MainActivity`, `EmailViewModel`, and related UI/data classes.

## How it fits in the repo
This module is the Compose front end. It depends on the Rust `letterbox-core` crate (built as a shared library) and on Room/in-memory repositories for persisting recently opened files.

## Build/Run
- Assemble an APK: `./gradlew :app:assembleProdDebug` (or `:app:assembleStagingDebug` for the staging flavor).
- Build native libraries for device targets: add `-PrustBuild=true` to run `cargo ndk` via the `cargoNdkBuild` task (requires `cargo-ndk` and Rust toolchains for `arm64-v8a`, `armeabi-v7a`, `x86_64`).
- Host-native library for unit tests is built automatically by `cargoHostBuild` before test tasks run.

## Configuration
- Set `rustBuild=true` to emit JNI libraries into `app/src/main/jniLibs`; leave it unset to skip the Rust NDK build.
- Tests can override the native library location with `LETTERBOX_CORE_LIB_PATH` or the `uniffi.component.letterbox_core.libraryOverride` system property.
- `MainActivity` declares intent filters for `ACTION_VIEW` and `ACTION_SEND` with MIME types `message/rfc822`, `application/eml`, and `*/*`, and exposes a FileProvider at `${applicationId}.fileprovider` for sharing cached `.eml` files.

## Tests
- Unit tests for all flavors: `./gradlew :app:test` (runs Kotlin unit tests plus host-side FFI checks).
- Instrumented UI tests under `app/src/androidTest`: run with an attached emulator/device via `./gradlew :app:connectedAndroidTest`.

## Interfaces
- UniFFI bindings in `org.joefang.letterbox.ffi` expose `parseEml`, `parseEmlFromPath`, and the `EmailHandle` API for headers, bodies, inline resources, and attachments.
- Intent interface: accepts `ACTION_VIEW` and `ACTION_SEND` for `.eml`-compatible MIME types; uses `FileProvider` for outbound sharing.

## Troubleshooting
- If Gradle reports missing native libraries when building for devices, ensure `cargo-ndk` is installed and rebuild with `-PrustBuild=true`.
- If FFI unit tests skip or fail to load the library, confirm `target/release/libletterbox_core.so` exists or set `LETTERBOX_CORE_LIB_PATH` to the compiled artifact.
