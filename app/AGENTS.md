# AGENTS.md — Android app

Jetpack Compose front end. Opens `.eml` intents, calls `letterbox-core` over
UniFFI/JNA, renders message content, and persists a deduplicated history via
Room. Root rules apply; this file adds module-local ones.

## Commands

* Unit tests (+ host FFI): `./gradlew :app:test --no-daemon`
* Lint: `./gradlew lint --no-daemon`
* Assemble: `./gradlew :app:assembleProdDebug` (`-PrustBuild=true` to embed
  device `.so` libraries via `cargo ndk`)
* Instrumented (device/emulator): `./gradlew :app:connectedAndroidTest`

## Local rules

* Do not hand-edit generated UniFFI bindings under `ffi/`; change the Rust crate
  and regenerate.
* Persist blobs through `HistoryRepository`, never with direct file writes
  (`docs/deduplication.md`).
* Run search/filter/sort through the Room FTS4 layer
  (`docs/full-text-search.md`), not ad hoc queries.
* For host-side FFI tests, override the native library path with
  `LETTERBOX_CORE_LIB_PATH` (or the `uniffi.component.letterbox_core.libraryOverride`
  system property) when the default `target/release/` artifact is missing.
