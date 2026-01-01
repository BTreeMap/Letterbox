# Architecture

## Overview

Letterbox pairs an Android/Jetpack Compose client with a Rust email parsing core. Kotlin calls into the `letterbox_core` shared library via UniFFI/JNA, keeps parsed data in Rust memory, and renders UI from lightweight DTOs. Recent files are persisted through a small content-addressable store with full-text search capabilities.

## Components

- **Android UI (`app/`)**: `MainActivity` drives Compose screens for history and message detail, launches file pickers for `.eml`, provides search/filter/sort controls, and shares the current email via a FileProvider.
- **View model & data layer**: `EmailViewModel` orchestrates parsing, error handling, history updates, and search/filter/sort state management. `HistoryRepository` and Room entities (`data/`) deduplicate blobs, track access times, extract email metadata for indexing, and provide search queries; `InMemoryHistoryRepository` is used for tests.
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

### Full-Text Search (FTS4)

A virtual FTS4 table (`email_fts`) is synchronized with `history_items` for efficient text search across:
- Subject
- Sender email and name
- Recipient emails and names
- Body preview

**Design Choice: FTS4 vs FTS5**: We chose FTS4 because:
1. Room has better built-in support for FTS4 via `@Fts4` annotation
2. FTS4 is available on all Android API levels we support (26+)
3. FTS5 requires manual SQL and content synchronization doesn't integrate as well with Room

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
