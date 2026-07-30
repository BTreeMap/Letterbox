# Search, Filter and Sort

## One specification, two interpreters

`HistoryQuery` (`app/src/main/java/org/joefang/letterbox/HistoryQuery.kt`) is a
plain value describing *which* history entries to show and *in what order*:

```kotlin
HistoryQuery(
    text = "budget",
    onlyWithAttachments = true,
    sortField = SortField.DATE,
    sortDirection = SortDirection.DESCENDING,
)
```

It has two interpreters, and both are pure:

| Interpreter | Result | Role |
|---|---|---|
| `applyTo(entries)` | `List<HistoryEntry>` | The **executable specification**, and what the UI runs today |
| `toSqlSelect()` | `SqlSelect` (SQL + bound args) | The paged path: filtering and ordering done by SQLite |

Keeping the specification executable is deliberate. These semantics previously
existed in three implementations that disagreed about which fields were
searchable, precisely because none of them was defined as the reference. `applyTo`
is now that reference: the SQL interpreter is asserted to agree with it rather
than assumed to.

`SqlSelect` is intentionally not a `SupportSQLiteQuery` — that type is
Android-only, and keeping it out means query construction is unit-tested off
device.

## Searchable fields

`text` matches as a **case-insensitive substring** across:

| Field | Example |
|-------|---------|
| `subject` | "Meeting Tomorrow at 3pm" |
| `senderName` | "John Doe" |
| `senderEmail` | "john@example.com" |
| `displayName` | "message.eml" |
| `bodyPreview` | First 500 characters of the body |

Blank or whitespace-only text matches everything; surrounding whitespace is
trimmed; punctuation is matched literally and never interpreted as syntax.

## Case folding, and why `search_text` exists

**SQLite's `LIKE` and `NOCASE` collation fold ASCII only**, and how far `lower()`
folds depends on how the platform built SQLite. Translating
`contains(ignoreCase = true)` straight into `LIKE '%q%'` would therefore stop
"müller" from finding "Müller" — breaking search for every cased non-ASCII script:
accented Latin, Cyrillic, Greek, Turkish — and would do so inconsistently across
devices, which is worse than doing it wrong everywhere.

So case is folded **once at each boundary, in Kotlin**, where `lowercase()` is
Unicode-aware and locale-independent:

- **Write side.** `searchTextOf` concatenates the five searchable fields, folded,
  into the `search_text` column. Applied by `withSearchText()` at ingestion.
- **Query side.** `toSqlSelect()` folds the needle before binding it.

SQL then only ever performs a plain substring match over already-folded text,
which is correct in every script. The two sides live in one file so they cannot
drift.

Fields are joined with `\n`. A single-line search field cannot produce a newline
and the needle is trimmed, so a needle can never bridge two fields and create a
false match.

### `LIKE`, not `MATCH`

`LIKE '%needle%'` reproduces substring semantics exactly, which is what keeps
`applyTo` a valid oracle. `LIKE` metacharacters are escaped with `ESCAPE '\'`, so
a needle of `%` matches a literal percent instead of every row; the backslash is
escaped first so it cannot escape itself.

`LIKE` with a leading wildcard cannot use an index, but it scans one narrow
pre-folded column instead of materialising every row as a Kotlin object — and
under paging only the requested window is read.

### Injection safety is structural

The needle is a **bound parameter**. Every interpolated fragment comes from
eliminating a closed enum (`SortField`, `SortDirection`) through a total `when`, so
the set of statements the query can produce is finite and enumerable. A test
enumerates all twelve and asserts none contains caller text.

## Ordering

| `SortField` | Key |
|-------------|-----|
| `DATE` | `emailDate`, falling back to `lastAccessed` when the Date header was unparsable |
| `SUBJECT` | `subject`, case-insensitive |
| `SENDER` | `senderName`, falling back to `senderEmail`, case-insensitive |

`DESCENDING` reverses the *comparator*, not the sorted list. Both agree on
distinct keys; among ties, reversing the comparator preserves incoming order where
reversing the result flipped it, and it avoids a full list copy.

Every SQL ordering appends `id`. Under paging a total order is not cosmetic: with
ties, rows have no stable sequence between separately loaded pages, so an entry
could appear twice or not at all while scrolling. `id` is unique, which makes the
order total.

## Why not SQLite FTS4

An FTS4 virtual table, `email_fts`, existed until schema version 4 and **nothing
ever queried it**. `MIGRATION_3_4` dropped it. Recorded here so it is not
reintroduced:

1. **Wrong semantics.** `MATCH` matches whole tokens, and `term*` matches token
   prefixes; neither matches infixes. "port" finds "airport" today and would not
   through FTS4. Incremental typing ("bud" → "budget") is covered by prefix
   matching, so the loss is narrow — but it is a loss, not a gain.
2. **`MATCH` turns input into a query language.** Substring matching treats input
   as data and cannot fail on any string. The retired `sanitizeFtsQuery` proved the
   hazard: it doubled `"` without ever wrapping tokens in quotes — so the escaping
   did nothing — then appended `*` to every token. A query of `-` became `-*`, `(`
   became `(*`; both are FTS4 syntax errors surfacing as `SQLiteException`.
   Ordinary punctuation would have thrown. Doing it safely requires *constructing*
   a provably valid MATCH expression, not escaping characters one at a time.
3. **It could not search more text than the alternative.** `email_fts` indexed
   `body_preview` — the same truncated 500 characters `search_text` holds. FTS4's
   real advantage is searching text too large to keep resident, and this table did
   not do that. Full-body search would require indexing full bodies at ingestion,
   which nothing has asked for.
4. **No relevance ranking is used.** The app orders by date, subject or sender and
   never by `matchinfo`.

### How it was retired

Removing the entity changes Room's schema identity, and the database falls back to
`fallbackToDestructiveMigration()`, so dropping it without a migration would have
erased every user's cached email.

`MIGRATION_3_4` drops the virtual table — which also drops its shadow tables
`email_fts_segments`, `_segdir`, `_docsize` and `_stat` — plus the four
`room_fts_content_sync_email_fts_*` triggers. The triggers are separate objects
and go first, because they fire on writes to `history_items` and the migration
writes to that table when backfilling. Their names were taken verbatim from the
exported version 3 schema rather than reconstructed from Room's naming convention.

The backfill uses SQL `lower()`. How far that folds depends on the platform's
SQLite build — a plain build folds ASCII only, an ICU-linked one folds more — so a
pre-existing "MÜLLER" may be stored as either "mÜller" or "müller". The migration
is correct under both and deliberately does not iterate every row through Kotlin to
find out, which keeps it trivially correct. Any partly folded row repairs itself:
`HistoryRepository.ingest` re-folds `search_text` whenever an email is opened
again, and writes nothing when the value already agrees.

## Why the query is moving into SQL

Search was never the constraint; the eager full-history load is.
`HistoryRepository` collects every row into memory because the list UI renders
from it, at roughly 1–1.5 KB per entry dominated by `bodyPreview`, held twice as
`history` and `filteredHistory`:

| Entries | In-memory match per keystroke | Heap held by the list |
|---------|-------------------------------|-----------------------|
| 10² | microseconds | negligible |
| 10³ | well under a frame | a few MB |
| 10⁴ | ~5–15 ms, borderline at 60 fps | tens of MB |
| 10⁵ | ~100 ms, janky | 100–200 MB — likely OOM first |

The cache never evicts, so entry count only grows. An index would speed the row
scan, but the memory ceiling arrives first — at 10⁵ the app dies of the full-list
load regardless. Bounding it means *not* holding everything: a `PagingSource`, a
windowed list, and both filtering and ordering pushed into SQL. `toSqlSelect()` is
that path.

## Data flow

Today, in memory:

```
user types
    ↓  EmailViewModel.setSearchQuery(text)
state.copy(searchQuery = text).refiltered()
    ↓  HistoryQuery(...).applyTo(state.history)
EmailUiState.filteredHistory  →  UI recomposes
```

`filteredHistory` is a cached function of `(history, query)`, and exactly one place
recomputes it — `refiltered()` — so the derived value cannot drift from its inputs.

## Testing

- `HistoryQueryTest` — the specification: per-field matching, case-insensitivity,
  infix matching, whitespace trimming, literal punctuation, the attachment filter,
  filter conjunction, every sort field and direction, `effectiveDate` and
  `displaySender` fallbacks, tie ordering, empty input, and the algebraic
  properties (filtering only shrinks; sorting preserves cardinality and elements;
  `applyTo` does not mutate its input).
- `HistoryQuerySqlTest` — the SQL interpreter: Unicode folding, `search_text`
  composition, `LIKE` escaping, clause construction, argument binding, every
  ordering, total-order guarantee, and the finite-statement-set property.

### History

These semantics once existed in three divergent implementations: the unused
FTS4/SQL layer (subject, sender, recipients, body), `EmailViewModel` (subject,
sender, display name, body), and `InMemoryHistoryRepository` (subject, sender,
body). Only the view model's copy ran, and it had no tests — while the search
tests in `HistoryRepositoryTest` asserted against `InMemoryHistoryRepository`, a
class production never instantiated. They could stay green while real search
misbehaved.
