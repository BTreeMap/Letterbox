package org.joefang.letterbox

import org.joefang.letterbox.data.SortDirection
import org.joefang.letterbox.data.SortField

/**
 * A rendered SQL statement together with its bound arguments.
 *
 * Deliberately not a `SupportSQLiteQuery`: that type is Android-only, and
 * keeping it out of this file is what lets query construction be unit-tested off
 * device. The data layer wraps this at the boundary.
 */
data class SqlSelect(val sql: String, val args: List<Any?>)

/**
 * Case-fold text for searching.
 *
 * The single folding rule, applied on both sides — to the stored haystack at
 * ingestion and to the needle at query time. It exists because **SQLite's `LIKE`,
 * `lower()` and `NOCASE` collation only fold ASCII**, and Android's SQLite ships
 * without ICU. Matching "müller" against "Müller" in SQL therefore requires that
 * neither side still carries case by the time SQL sees it.
 *
 * Kotlin's [String.lowercase] is Unicode-aware and locale-independent, so folding
 * here and comparing raw text in SQL is both correct for all scripts and
 * equivalent to the in-memory `contains(ignoreCase = true)` path.
 */
internal fun foldForSearch(text: String): String = text.lowercase()

/**
 * The folded haystack persisted alongside a history row.
 *
 * Concatenates exactly the fields [HistoryQuery] searches, in a fixed order,
 * separated so that a needle cannot span two fields and produce a false match.
 * Written once at ingestion; queried with a plain substring match.
 */
internal fun searchTextOf(
    subject: String,
    senderName: String,
    senderEmail: String,
    displayName: String,
    bodyPreview: String
): String = foldForSearch(
    listOf(subject, senderName, senderEmail, displayName, bodyPreview)
        .joinToString(SEARCH_FIELD_SEPARATOR)
)

/**
 * Separator between concatenated searchable fields.
 *
 * A newline cannot appear in a single-line header value or in the
 * whitespace-collapsed body preview, so it cannot be typed into the search box in
 * a way that bridges two fields.
 */
internal const val SEARCH_FIELD_SEPARATOR = "\n"

/**
 * Which history entries to show, and in what order.
 *
 * A query is a plain value: [applyTo] is a pure total function from
 * `(HistoryQuery, List<HistoryEntry>)` to `List<HistoryEntry>`, so the app's
 * search, filter and sort semantics are defined in exactly one place and can be
 * tested without Android, Room or coroutines. Previously the same semantics
 * existed in three implementations — an unused FTS4/SQL layer, an in-memory
 * copy in the view model, and a second in-memory copy in the test double — and
 * they disagreed about which fields were searchable.
 *
 * ## Search semantics
 *
 * [text] matches as a case-insensitive **substring** across subject, sender
 * name, sender address, file display name and body preview. Substring rather
 * than token-prefix matching is deliberate: typing "port" finds "airport",
 * which is what a search box is expected to do.
 */
data class HistoryQuery(
    /** Free text; blank means "no text filter". */
    val text: String = "",
    /** When true, drop entries without attachments. */
    val onlyWithAttachments: Boolean = false,
    val sortField: SortField = SortField.DATE,
    val sortDirection: SortDirection = SortDirection.DESCENDING
) {
    /**
     * The trimmed needle, computed once per query rather than once per entry.
     *
     * Declared in the body, so it stays out of `equals`, `hashCode` and `copy`,
     * which are still defined by the constructor parameters alone.
     */
    private val needle: String = text.trim()

    /**
     * Entries this query admits, in this query's order.
     *
     * One `filter` (cardinality may shrink) followed by one `sortedWith`
     * (cardinality and every element preserved, order changed). Combining both
     * predicates into a single pass keeps this to two intermediate lists rather
     * than the four the previous filter-filter-sort-reverse chain allocated.
     *
     * Kept eager rather than routed through a `Sequence`: the terminal sort has
     * to materialise the whole collection anyway, so laziness would only add
     * iterator overhead.
     */
    fun applyTo(entries: List<HistoryEntry>): List<HistoryEntry> =
        entries.filter(::admits).sortedWith(comparator())

    /** Whether [entry] satisfies every active filter. Conjunction, short-circuiting. */
    fun admits(entry: HistoryEntry): Boolean =
        (!onlyWithAttachments || entry.hasAttachments) && matchesText(entry)

    /**
     * Whether [entry] matches [text] in any searchable field.
     *
     * Uses `contains(ignoreCase = true)` instead of lowercasing both sides. It
     * is allocation-free, where case-folding five fields per entry per keystroke
     * was not, and it sidesteps length-changing case mappings such as `ß`/`SS`.
     */
    private fun matchesText(entry: HistoryEntry): Boolean =
        needle.isEmpty() ||
            entry.subject.contains(needle, ignoreCase = true) ||
            entry.senderName.contains(needle, ignoreCase = true) ||
            entry.senderEmail.contains(needle, ignoreCase = true) ||
            entry.displayName.contains(needle, ignoreCase = true) ||
            entry.bodyPreview.contains(needle, ignoreCase = true)

    /**
     * The total order this query imposes.
     *
     * Descending reverses the *comparator*, not the sorted list, which avoids a
     * full list copy.
     *
     * `id` is the final tie-breaker, which makes the order **total**. That is not
     * cosmetic: [toSqlSelect] must break ties the same way or the two interpreters
     * disagree whenever two entries share a key, and under paging a non-total
     * order has no stable row sequence across separately loaded pages — an entry
     * can appear twice or not at all while scrolling. Reversing a comparator that
     * already includes `id` reverses the tie-break too, exactly as
     * `ORDER BY key DESC, id DESC` does.
     */
    fun comparator(): Comparator<HistoryEntry> {
        val byKey: Comparator<HistoryEntry> = when (sortField) {
            SortField.DATE -> compareBy { it.effectiveDate }
            SortField.SUBJECT -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.subject }
            SortField.SENDER -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displaySender }
        }
        val ascending = byKey.thenBy { it.id }
        return when (sortDirection) {
            SortDirection.ASCENDING -> ascending
            SortDirection.DESCENDING -> ascending.reversed()
        }
    }

    /**
     * This query as SQL over `history_items`, for paging straight out of the
     * database instead of holding the whole history in memory.
     *
     * [applyTo] remains the executable specification: the two interpret the same
     * value and must agree, which is asserted by running both over one fixture
     * rather than trusting them to stay in step.
     *
     * ## Why this is injection-safe by construction
     *
     * The only caller-supplied value, the needle, is a **bound parameter**. Every
     * interpolated fragment comes from eliminating a closed enum — [SortField] and
     * [SortDirection] — through a total `when`, so the set of statements this can
     * produce is finite and enumerable. No string from outside the process reaches
     * the SQL text.
     *
     * ## Why `LIKE` and not `MATCH`
     *
     * `LIKE '%needle%'` reproduces substring semantics exactly, so the in-memory
     * oracle stays valid. It cannot use an index, but it scans one narrow
     * pre-folded column rather than materialising every row as an object — and
     * paging means only the requested window is read. See
     * `docs/full-text-search.md`.
     */
    fun toSqlSelect(): SqlSelect {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        if (onlyWithAttachments) {
            conditions += "has_attachments = 1"
        }
        if (needle.isNotEmpty()) {
            // Both sides are already case-folded, so a plain substring match is
            // correct for every script; see foldForSearch.
            conditions += "search_text LIKE ? ESCAPE '\\'"
            args += "%${escapeLikeWildcards(foldForSearch(needle))}%"
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        return SqlSelect(
            sql = "SELECT * FROM history_items$where ORDER BY ${orderBySql()}",
            args = args
        )
    }

    /**
     * `ORDER BY` clause for this query.
     *
     * `id` is appended as a final tie-breaker. Under paging a total order is not
     * cosmetic: a query with ties has no stable row sequence across separately
     * loaded pages, so an entry could appear twice or not at all while scrolling.
     * `id` is unique, which makes the order total.
     */
    private fun orderBySql(): String {
        val key = when (sortField) {
            SortField.DATE ->
                "CASE WHEN email_date > 0 THEN email_date ELSE last_accessed END"
            SortField.SUBJECT -> "subject COLLATE NOCASE"
            // TRIM, not `!= ''`: the specification uses `ifBlank`, which treats a
            // whitespace-only name as absent. `!= ''` would not, and the two
            // interpreters would then disagree for such a row. The key itself stays
            // untrimmed, matching `ifBlank`'s non-blank branch.
            SortField.SENDER ->
                "CASE WHEN TRIM(sender_name) != '' THEN sender_name ELSE sender_email END " +
                    "COLLATE NOCASE"
        }
        val direction = when (sortDirection) {
            SortDirection.ASCENDING -> "ASC"
            SortDirection.DESCENDING -> "DESC"
        }
        return "$key $direction, id $direction"
    }
}

/**
 * Neutralise `LIKE` metacharacters so a needle matches literally.
 *
 * Without this, typing `%` would match every row and `_` would match any single
 * character — the needle is data, not a pattern. Paired with `ESCAPE '\'` in the
 * statement. The backslash is escaped first, so escaping cannot escape itself.
 */
internal fun escapeLikeWildcards(text: String): String = text
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")
