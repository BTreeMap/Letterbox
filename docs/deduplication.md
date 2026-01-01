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
assertEquals(1, repository.items.value.size)
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

## Performance Considerations

- SHA-256 computation is fast for typical email sizes
- Single database lookup to check for duplicates
- No additional storage for duplicate files
- Efficient cache usage

## Testing

The deduplication behavior is tested in `HistoryRepositoryTest.kt`:

- `deduplicates emails with same content - returns existing entry`
- `deduplication updates lastAccessed timestamp`
- `getCacheStats with full deduplication`
