# Email Deduplication

## Overview

Letterbox uses Content-Addressable Storage (CAS) to deduplicate email files. Each unique EML file is identified by its SHA-256 checksum and stored only once in the cache.

## Design

### SHA-256 Based Identification

When an EML file is ingested into Letterbox:

1. The file's SHA-256 checksum is computed
2. If a blob with this checksum already exists, the file is not stored again
3. If a history entry with this checksum already exists, its `lastAccessed` timestamp is updated

### Unique Constraint

The `history_items` table enforces a unique constraint on the `blob_hash` column (added in database version 3). This ensures that:

- Each unique EML file appears exactly once in the history
- Re-opening the same file updates the existing entry rather than creating duplicates
- The cache size is minimized by avoiding redundant storage

### Behavior

#### When Opening a New Email

1. SHA-256 checksum is computed from the file bytes
2. If checksum not in database: new blob and history entry are created
3. Email is displayed to the user

#### When Opening a Previously Seen Email

1. SHA-256 checksum is computed from the file bytes
2. Existing history entry is found by checksum
3. `lastAccessed` timestamp is updated
4. Same history entry is returned (same ID)
5. Email is displayed to the user

### API

The `HistoryRepository.ingest()` method returns a `HistoryEntry` object. For duplicate files:

```kotlin
val first = repository.ingest(bytes, "file1.eml", uri1)
val second = repository.ingest(bytes, "file2.eml", uri2)

// Both return the same entry
assertEquals(first.id, second.id)
assertEquals(first.blobHash, second.blobHash)

// Only one entry in history
assertEquals(1, repository.items.value.size)   // InMemoryHistoryRepository
```

## Database Schema

The deduplication is enforced at the database level:

```sql
CREATE TABLE history_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    blob_hash TEXT NOT NULL,
    -- ... other fields ...
    FOREIGN KEY (blob_hash) REFERENCES blobs(hash) ON DELETE CASCADE
);

CREATE UNIQUE INDEX index_history_items_blob_hash ON history_items (blob_hash);
```

## Two stores that can disagree

The cache spans a database table (`blobs`) and a directory (`cas/`). Nothing
keeps them in step automatically, and they drift in one specific direction —
files without rows:

- **Schema changes.** The database is built with `fallbackToDestructiveMigration()`.
  A schema bump drops `blobs` and `history_items` but cannot touch the file
  system, so the entire previous cache is stranded on disk: not counted by
  `cacheStats`, not reachable from "Clear cache", never reclaimed.
- **Interrupted ingestion.** A crash between writing a blob file and inserting
  its row leaves the same kind of orphan.

`HistoryRepository.reclaimOrphanedBlobs()` reconciles them. Because a blob's hash
*is* its filename, the rule is set membership: delete any file in `cas/` with no
`blobs` row. It runs once at startup, off the UI path, and returns the bytes
freed.

Only files with **no** row are removed, so nothing still visible in a user's
history is ever deleted. Emails remain cached indefinitely until the user clears
them — reclamation removes unreachable content, never content the user can see.

A `blobMutex` serialises writing a blob against registering its row, and both
against the sweep, so a reclamation can never observe a freshly written file in
the window before its row exists.

### Vestigial reference counting

`blobs.ref_count` is initialised to `1` and never incremented — `incrementRefCount`
had no callers and has been removed. With the unique index on
`history_items.blob_hash`, at most one history entry can reference a blob, so the
real reference count is always `countByBlobHash(hash)`, which is what `delete`
checks. The column and the `decrementRefCount` branch are therefore unreachable
in practice; they are retained because dropping a column requires a schema
migration.

## Performance considerations

- SHA-256 computation is fast for typical email sizes
- One indexed lookup to detect a duplicate
- Duplicate content costs no additional storage
- `cacheStats` is one row from two correlated sub-selects — `COUNT(*)` over
  `history_items` and `SUM(size_bytes)` over `blobs` — so it costs the same at ten
  emails or a hundred thousand. Room re-emits it whenever either table changes. It
  previously loaded every row and then called `File.length()` once per blob, on
  every history change.
- `clearAll` is two `DELETE` statements and one directory sweep, rather than one
  statement per entry.

## Testing

Deduplication and the blob lifecycle are tested in `HistoryRepositoryTest.kt`:

- `deduplicates emails with same content - returns existing entry`
- `deduplication updates lastAccessed timestamp`
- `delete removes single entry and cleans up orphan blob`
- `getCacheStats returns correct entry count and size`
- `getCacheStats with full deduplication`

Reconciliation is tested in `BlobFilesTest.kt`, against a real temporary
directory with no database: retention of known files, reclamation of unknown
ones, the empty-known-set case a destructive migration produces, missing
directories, sub-directories, idempotence, and zero-length orphans.
