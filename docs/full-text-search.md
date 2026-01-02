# Full-Text Search

## Overview

Letterbox provides full-text search across email content, enabling users to quickly find emails by searching for any word or phrase in the email.

## Searchable Fields

The search indexes the following fields:

| Field | Description | Example |
|-------|-------------|---------|
| `subject` | Email subject line | "Meeting Tomorrow at 3pm" |
| `senderEmail` | Sender's email address | "john@example.com" |
| `senderName` | Sender's display name | "John Doe" |
| `recipientEmails` | Recipient email addresses | "alice@example.com, bob@example.com" |
| `recipientNames` | Recipient display names | "Alice, Bob" |
| `bodyPreview` | First 500 characters of email body | "Hi team, please review..." |
| `displayName` | File display name (fallback) | "message.eml" |

## Implementation

### Database Layer (SQLite FTS4)

For the Room database implementation, we use SQLite FTS4 (Full-Text Search 4):

```sql
CREATE VIRTUAL TABLE email_fts USING fts4(
    content="history_items",
    subject, sender_email, sender_name, 
    recipient_emails, recipient_names, body_preview
);
```

FTS4 was chosen over FTS5 because:
1. Room has better built-in support via `@Fts4` annotation
2. Available on all Android API levels we support (26+)
3. Simpler integration with Room's content sync

### In-Memory Layer (ViewModel)

For immediate UI responsiveness, the `EmailViewModel` also performs in-memory filtering:

```kotlin
private fun applyFiltersAndSort(items: List<HistoryEntry>, state: EmailUiState): List<HistoryEntry> {
    if (state.searchQuery.isNotBlank()) {
        val query = state.searchQuery.lowercase()
        result = result.filter { entry ->
            entry.subject.lowercase().contains(query) ||
            entry.senderName.lowercase().contains(query) ||
            entry.senderEmail.lowercase().contains(query) ||
            entry.displayName.lowercase().contains(query) ||
            entry.bodyPreview.lowercase().contains(query)
        }
    }
    // ...
}
```

### InMemoryHistoryRepository

For testing and simple usage without Room:

```kotlin
fun search(query: String): List<HistoryEntry> {
    val lowerQuery = query.lowercase()
    return _items.value.filter { entry ->
        entry.subject.lowercase().contains(lowerQuery) ||
        entry.senderName.lowercase().contains(lowerQuery) ||
        entry.senderEmail.lowercase().contains(lowerQuery) ||
        entry.bodyPreview.lowercase().contains(lowerQuery)
    }
}
```

## Body Preview Extraction

The body preview is extracted from the email during ingestion:

1. The Rust `mail-parser` library extracts the plain text body
2. The `body_preview()` method returns the first 500 characters
3. Whitespace is normalized (collapsed to single spaces)
4. The preview is stored in the `body_preview` column

```rust
pub fn body_preview(&self) -> String {
    self.inner
        .lock()
        .map(|msg| {
            msg.body_text
                .as_ref()
                .map(|text| {
                    let chars: String = text.chars().take(500).collect();
                    chars.split_whitespace().collect::<Vec<_>>().join(" ")
                })
                .unwrap_or_default()
        })
        .unwrap_or_default()
}
```

## Search Query Handling

### FTS4 Query Sanitization

Special FTS4 characters are escaped and prefix matching is enabled:

```kotlin
private fun sanitizeFtsQuery(query: String): String {
    return query
        .replace("\"", "\"\"")
        .split("\\s+".toRegex())
        .filter { it.isNotBlank() }
        .joinToString(" ") { "$it*" }
}
```

### Empty Query Handling

An empty search query returns all items (no filtering).

## Data Flow

```
User types search query
        ↓
EmailViewModel.setSearchQuery(query)
        ↓
applyFiltersAndSort() - in-memory filtering
        ↓
UI updates immediately
        ↓
(Optional) Database FTS query for persistence
```

## Testing

Full-text search is tested in:

- `HistoryRepositoryTest.kt`:
  - `search finds emails by subject`
  - `search finds emails by sender name`
  - `search finds emails by sender email`
  - `search finds emails by body preview - full text search`
  - `search returns empty list for no matches`
  - `search with empty query returns all items`

## Performance Considerations

- In-memory search provides instant UI updates
- FTS4 provides efficient database-level search
- Body preview is limited to 500 characters to balance search quality and storage
- Prefix matching (`word*`) enables finding partial matches
