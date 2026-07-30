# Search, Filter and Sort

## Overview

Letterbox searches, filters and sorts the email history entirely in memory, in
one place: `HistoryQuery` (`app/src/main/java/org/joefang/letterbox/HistoryQuery.kt`).

A `HistoryQuery` is a plain value describing *which* entries to show and *in what
order*. `applyTo` is a pure total function:

```kotlin
HistoryQuery(
    text = "budget",
    onlyWithAttachments = true,
    sortField = SortField.DATE,
    sortDirection = SortDirection.DESCENDING,
).applyTo(entries)   // List<HistoryEntry> -> List<HistoryEntry>
```

Because it is pure and free of Android, Room and coroutine dependencies, it is
tested directly in `HistoryQueryTest.kt` with no runner — and it is the same code
path the UI runs, not a parallel one.

## Searchable fields

`text` matches as a **case-insensitive substring** across:

| Field | Description | Example |
|-------|-------------|---------|
| `subject` | Email subject line | "Meeting Tomorrow at 3pm" |
| `senderName` | Sender's display name | "John Doe" |
| `senderEmail` | Sender's email address | "john@example.com" |
| `displayName` | File display name | "message.eml" |
| `bodyPreview` | First 500 characters of the body | "Hi team, please review..." |

Blank or whitespace-only text matches everything. Surrounding whitespace in the
query is trimmed. Punctuation is matched literally and never interpreted as
syntax.

Matching uses `contains(ignoreCase = true)` rather than lowercasing both sides,
which avoids allocating a case-folded copy of five fields per entry per
keystroke, and sidesteps length-changing case mappings such as `ß`/`SS`.

## Why in memory and not SQLite FTS4

The database *does* contain an FTS4 virtual table, `email_fts`, declared by
`EmailFtsEntity`. **Nothing queries it.** That is deliberate.

### The decisive reason: FTS4 cannot fix the binding constraint

Search is not what limits this app's scale — the eager full-history load is.
`HistoryRepository` collects `getAllOrderedByAccess()`, so `_items` holds *every*
entry, each carrying up to 500 characters of `bodyPreview`, because the list UI
renders from it.

| Entries | In-memory search per keystroke | Heap held by the list |
|---------|-------------------------------|-----------------------|
| 10² | microseconds | negligible |
| 10³ | well under a frame | a few MB |
| 10⁴ | ~5–15 ms, borderline at 60 fps | tens of MB |
| 10⁵ | ~100 ms, janky | 100–200 MB — likely OOM first |

Moving the predicate into SQL speeds up the row *scan*, but the list is already
resident, so the memory ceiling arrives before the search ceiling. At 10⁵ entries
the app dies of the full-list load whether or not search uses an index. FTS4
would be optimising the part that is not the bottleneck.

Making the app scale means *not* holding everything: a `PagingSource`, a windowed
list, and filtering **and** ordering pushed into SQL. FTS4 belongs to that
change, not before it.

### Supporting reasons

1. **Totality.** In-memory search treats input as data and cannot fail on any
   string. `MATCH` treats input as a *query language*, so it can throw. The
   retired `sanitizeFtsQuery` proved the point: it doubled `"` without ever
   wrapping tokens in quotes — so the escaping did nothing — then appended `*` to
   every token. A query of `-` became `-*`, `(` became `(*`; both are FTS4 syntax
   errors surfacing as `SQLiteException`. Ordinary punctuation would have thrown.
   Doing it safely means *constructing* a provably valid MATCH expression, not
   escaping characters one at a time.
2. **Complexity.** In-memory search is a synchronous pure function over state
   already held. Through FTS4, every keystroke becomes a new `Flow` needing
   `flatMapLatest` to cancel the previous query, plus debounce tuning, plus one
   query variant per sort order — which is exactly why the retired layer had six
   `getAllBy*` methods and a hand-built `searchWithFilters` string builder to
   combine text, filter and order.
3. **Infix matching.** `MATCH` matches whole tokens, and `term*` matches token
   prefixes, but neither matches infixes. Typing "port" finds "airport" today;
   through FTS4 it would not. This is a real but narrow loss — incremental typing
   ("bud" → "budget") is covered by prefix matching.

### What FTS4 would genuinely buy — and why it does not apply yet

Being fair to the other side: FTS4 offers relevance ranking via `matchinfo`, and
it can search text too large to keep in memory.

Neither helps here. The app orders by date, subject or sender and never ranks by
relevance. And `email_fts` indexes `body_preview` — the same truncated 500
characters already in memory — so it could not search more text than the
in-memory path can. Full-body search would require indexing full bodies at
ingestion, a feature nothing has asked for.

### When to revisit

Move search into SQL when **all** of these hold, because they arrive together:

- history routinely exceeds ~10⁴ entries, **and**
- the list is paginated so the full history is no longer resident, **and**
- ordering has moved into SQL alongside filtering.

If full-body search is ever wanted, that is an independent trigger: it requires
indexing bodies at ingestion and forces the query into the database regardless of
entry count.

### Retiring the table

`email_fts` still costs index maintenance on every insert, update and delete of
`history_items`. It has not been dropped yet because removing the entity changes
Room's schema identity, and the database is built with
`fallbackToDestructiveMigration()` — so dropping it without a hand-written
migration would erase every user's cached email.

Retiring it needs a `Migration(3, 4)` that drops `email_fts` **and** the
`room_fts_content_sync_email_fts_*` triggers Room generates, verified against a
real database. Until that lands, the entity stays registered.

## Body preview extraction

The preview is extracted during ingestion by the Rust parser:

1. `mail-parser` extracts the plain-text body
2. `body_preview()` returns the first 500 characters
3. Whitespace is collapsed to single spaces
4. The result is stored in the `body_preview` column

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

## Ordering

| `SortField` | Key | Notes |
|-------------|-----|-------|
| `DATE` | `effectiveDate` | `emailDate`, falling back to `lastAccessed` when the Date header was unparsable |
| `SUBJECT` | `subject` | `String.CASE_INSENSITIVE_ORDER` |
| `SENDER` | `displaySender` | `senderName`, falling back to `senderEmail` |

`DESCENDING` reverses the *comparator*, not the sorted list. Both agree on
distinct keys; among ties, reversing the comparator preserves incoming order
where reversing the result flipped it. It also avoids a full list copy.

## Data flow

```
user types
    ↓
EmailViewModel.setSearchQuery(text)
    ↓
state.copy(searchQuery = text).refiltered()
    ↓
HistoryQuery(...).applyTo(state.history)
    ↓
EmailUiState.filteredHistory
    ↓
UI recomposes
```

`filteredHistory` is a cached function of `(history, query)`. Exactly one place
recomputes it — `refiltered()` — so the derived value cannot drift from its
inputs.

## Testing

`HistoryQueryTest.kt` covers text matching per field, case-insensitivity, infix
matching, whitespace trimming, literal punctuation, the attachment filter, filter
conjunction, every sort field and direction, the `effectiveDate` and
`displaySender` fallbacks, tie ordering, empty input, and the algebraic
properties (filtering only shrinks; sorting preserves cardinality and elements;
`applyTo` does not mutate its input).

### History

These semantics previously existed in three implementations that disagreed about
which fields were searchable: the unused FTS4/SQL layer (subject, sender,
recipients, body), `EmailViewModel` (subject, sender, display name, body), and
`InMemoryHistoryRepository` (subject, sender, body). Only the view model's copy
ran in the app, and it had no tests — while the search, sort and filter tests in
`HistoryRepositoryTest` asserted against `InMemoryHistoryRepository`, a class
production never instantiated. Those tests could stay green while real search
misbehaved. They have been retired in favour of `HistoryQueryTest`.
