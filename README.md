# Overview

<p align="center">
  <img src="docs/letterbox_icon.svg" alt="Letterbox Icon" width="128" height="128"/>
</p>

<p align="center">
  <strong>Letterbox</strong> – An Android app for opening and inspecting <code>.eml</code> email files
</p>

---

Letterbox is an Android application built with Jetpack Compose that opens and inspects `.eml` email files. It delegates parsing to a Rust core library (exposed via UniFFI and JNA), renders message bodies and attachments, and keeps a local, deduplicated history backed by Room or an in-memory store for tests.

## Key capabilities

- Opens `.eml` files shared via `ACTION_VIEW` and `ACTION_SEND` intents declared in `app/src/main/AndroidManifest.xml`.
- Parses messages through the `letterbox-core` Rust crate using UniFFI bindings (`parseEml`, `parseEmlFromPath`) to extract headers, bodies, inline resources, and attachments.
- Displays message content with Jetpack Compose screens (`MainActivity`, `EmailDetailScreen`) and supports sharing the currently opened email via a FileProvider.
- Stores recent files with a content-addressable history that deduplicates blobs and enforces limits (`HistoryRepository`, Room entities in `data/`).
- Provides product flavors (`prod`, `staging`) and hooks to build native libraries for multiple ABIs with `cargo ndk` when enabled via a Gradle property.

## Repository layout

- `app/`: Android application module, Compose UI, Room data layer, UniFFI bindings, and Gradle tasks to build native artifacts.
- `rust/letterbox-core/`: Rust library that parses emails with `mail-parser` and exposes UniFFI bindings for Kotlin/Android.
- `gradle/`, `build.gradle.kts`, `settings.gradle.kts`: Gradle wrapper and version catalog configuration for the Android project.
- `Cargo.toml`: Rust workspace definition pointing to `rust/letterbox-core`.
- `LICENSE`: MIT license for the project.

## Quickstart

```bash
# Run Rust tests for the core library
cargo test

# Run Android unit tests (builds the host Rust library first)
./gradlew test

# Build an installable APK for the prod debug variant
./gradlew :app:assembleProdDebug

# (Optional) Build and embed Rust libraries for Android ABIs using cargo-ndk
./gradlew :app:assembleProdDebug -PrustBuild=true
```

## Development

- The `rustBuild` Gradle property controls whether `cargo ndk` runs (`cargoNdkBuild` task) to emit `.so` files into `app/src/main/jniLibs`; enable it when producing device-ready builds with native parsing.
- Unit tests depend on `cargoHostBuild`, which compiles a host `libletterbox_core.so` and sets `uniffi.component.letterbox_core.libraryOverride` and `jna.library.path` so JNA can load it.
- Product flavors (`prod`, `staging`) exist under the `channel` dimension; assemble the desired variant (e.g., `:app:assembleStagingDebug`) as needed.

## Configuration

- Override the native library path for tests with `LETTERBOX_CORE_LIB_PATH` or the `uniffi.component.letterbox_core.libraryOverride` system property (used by `RustFfiIntegrationTest`).
- Gradle uses `gradle.properties` defaults (`org.gradle.jvmargs`, `android.useAndroidX`, Kotlin code style) and `gradle/libs.versions.toml` for dependency versions.

## Dependency overrides

- Gradle forces patched transitive versions in `build.gradle.kts` to satisfy Dependabot advisories without changing application source usage.
- Overrides cover Android Gradle Plugin buildscript/runtime transitive artifacts (protobuf-java, jdom2, jose4j, commons-lang3, httpclient) and Netty modules when they appear in dependency graphs.
- If any override causes incompatibilities, remove or adjust the specific entry and re-run `./gradlew buildEnvironment` or `./gradlew :app:dependencyInsight`.

## Testing

- Rust core: `cargo test`.
- Android unit tests (all flavors): `./gradlew test`.
- Instrumented tests (connected device): `./gradlew :app:connectedAndroidTest`.
- Instrumented tests (managed device - used in CI): `./gradlew pixel7Api34StagingDebugAndroidTest`.

See [docs/troubleshooting.md](docs/troubleshooting.md) for common test issues and solutions.

## Troubleshooting

For detailed troubleshooting information and solutions to common issues, see [docs/troubleshooting.md](docs/troubleshooting.md).

Quick fixes:
- If Android builds cannot find the native library, ensure `cargo-ndk` is installed and rebuild with `-PrustBuild=true` so `cargoNdkBuild` emits ABI-specific `.so` files.
- If host-side FFI tests cannot load the library, verify `target/release/libletterbox_core.so` exists (built by `cargoHostBuild`) or set `LETTERBOX_CORE_LIB_PATH` to the compiled library.
- If the app crashes when opening email files, ensure you're running the latest version with the WebView request interception fix.

## License

This project is licensed under the MIT License (see `LICENSE`).

**Note:** The Letterbox app icon is **not** covered by the MIT License and remains proprietary. See the [LICENSE](LICENSE) file for details.
