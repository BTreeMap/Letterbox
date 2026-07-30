# Architecture

## Overview

Letterbox pairs an Android/Jetpack Compose client with a Rust email parsing core. Kotlin calls into the `letterbox_core` shared library via UniFFI/JNA, keeps parsed data in Rust memory, and renders UI from lightweight DTOs. Recent files are persisted through a small content-addressable store with full-text search capabilities.

## Components

- **Android UI (`app/`)**: `MainActivity` drives Compose screens for history and message detail, launches file pickers for `.eml`, provides search/filter/sort controls, and shares the current email via a FileProvider.
- **View model & data layer**: `EmailViewModel` orchestrates parsing, error handling, history updates, and search/filter/sort state. `HistoryRepository` and Room entities (`data/`) deduplicate blobs, track access times, and extract email metadata; the repository exposes the whole history and does not query, filter or sort — that is `HistoryQuery`'s job. `InMemoryHistoryRepository` is a test stand-in for ingestion and blob lifecycle only.
- **Rust core (`rust/letterbox-core`)**: Parses emails with `mail-parser`, returns an `EmailHandle` exposing headers, body variants, inline resource metadata, structured sender/recipient info, and attachment accessors; exported through UniFFI.
- **FFI bindings (`app/src/main/java/org/joefang/letterbox/ffi/`)**: Generated Kotlin bindings load `letterbox_core` via JNA and surface `parseEml`/`parseEmlFromPath` plus `EmailHandle` methods including `senderInfo()`, `recipientInfo()`, `dateTimestamp()`, and `bodyPreview()`.

## Data flow

1. Android receives an `ACTION_VIEW` or `ACTION_SEND` intent for `.eml` content.
2. `EmailViewModel` ingests the bytes, asks the Rust core (`parseEml` or `parseEmlFromPath`) to parse, extracts structured metadata (`EmailMetadata`), and projects the result into `EmailContent` for the UI.
3. The content-addressable store writes the file under its SHA-256 hash and records a history entry with timestamps, source URI, and email metadata (subject, sender, recipient, date, attachments, body preview).
4. Compose UI reads the view model state to render message headers, HTML/text bodies, inline assets, attachment actions, and the searchable/sortable history list.

## Search, Filter, and Sort

### Database Design

Email metadata is stored in the `history_items` table with additional indexed columns:
- `subject`: Email subject line
- `sender_email`, `sender_name`: Structured sender information
- `recipient_emails`, `recipient_names`: Comma-separated recipient lists
- `email_date`: Epoch milliseconds parsed from the Date header (0 if unparseable)
- `has_attachments`: Boolean flag for attachment presence
- `body_preview`: First 500 characters of body text

### Search, filter and sort

`HistoryQuery` is the single definition: a plain value describing which entries to show and in what order, applied by a pure `applyTo` to the entry list the UI already holds. Text matching is case-insensitive substring across subject, sender name, sender address, file display name and body preview. It carries no Android, Room or coroutine dependency and is unit-tested directly.

A virtual FTS4 table (`email_fts`) is synchronized with `history_items` by Room, but **nothing queries it**. FTS4 `MATCH` offers only token and token-prefix matching, so routing search through it would stop "port" from finding "airport". It remains registered only because dropping the entity would change Room's schema identity and, under `fallbackToDestructiveMigration()`, erase cached email. See `docs/full-text-search.md` for the rationale and the migration needed to retire it.

### Sorting Options

Users can sort the email list by:
- **Date** (newest/oldest first): Uses `email_date` with fallback to `last_accessed`
- **Subject** (A-Z/Z-A): Case-insensitive alphabetical sort
- **Sender** (A-Z/Z-A): Uses `sender_name` with fallback to `sender_email`

### Filter Options

- **Has attachments**: Filter to only show emails with attachments

### Fallback Mechanisms

Since EML files may have missing or malformed fields:
- Missing subject: defaults to "Untitled"
- Missing sender: `senderEmail` and `senderName` are empty strings
- Missing/unparseable date: `emailDate` is 0, UI falls back to `lastAccessed`
- Missing body: `bodyPreview` is empty string

## Build integration

- Gradle task `cargoHostBuild` compiles a host `libletterbox_core.so` and sets `jna.library.path` for unit tests.
- When `-PrustBuild=true` is provided, `cargoNdkBuild` cross-compiles `letterbox_core` for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, placing `.so` files under `app/src/main/jniLibs`.
- Product flavors (`prod`, `staging`) are defined in `app/build.gradle.kts`; assemble the desired variant (e.g., `:app:assembleProdDebug`).
- Flavor differences: `prod` uses the base `applicationId`, while `staging` appends `.test`, adds a `-test` version suffix, and overrides `app_name` to "Letterbox (Test)`.
- Version codes are automatically derived from Git tags using a 30-bit schema; see [versioning.md](versioning.md) for details.
