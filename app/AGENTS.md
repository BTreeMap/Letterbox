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
* Schema changes: bump `LetterboxDatabase.version` and add a `Migration` to
  `data/Migrations.kt`. CI commits the regenerated `schemas/` JSON; add the
  migration test case once it lands, because `MigrationTestHelper` loads it from
  the test APK assets (`schemas/README.md`).
* Search/filter/sort has one definition: `HistoryQuery`, a pure function tested
  in `HistoryQueryTest`. Extend it rather than adding queries elsewhere. Text
  matching goes through the folded `search_text` column, never SQL `lower()` or
  `NOCASE`, both of which fold ASCII only (`docs/full-text-search.md`).
* For host-side FFI tests, override the native library path with
  `LETTERBOX_CORE_LIB_PATH` (or the `uniffi.component.letterbox_core.libraryOverride`
  system property) when the default `target/release/` artifact is missing.
