package org.joefang.letterbox

import org.joefang.letterbox.data.SortDirection
import org.joefang.letterbox.data.SortField

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
     * Descending reverses the *comparator*, not the sorted list. Both agree on
     * distinct keys; for ties, reversing the comparator leaves equal elements in
     * their incoming order, whereas reversing the result flipped them. Keeping
     * insertion order among equals is the more predictable of the two, and it
     * saves a full list copy.
     */
    fun comparator(): Comparator<HistoryEntry> {
        val ascending: Comparator<HistoryEntry> = when (sortField) {
            SortField.DATE -> compareBy { it.effectiveDate }
            SortField.SUBJECT -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.subject }
            SortField.SENDER -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displaySender }
        }
        return when (sortDirection) {
            SortDirection.ASCENDING -> ascending
            SortDirection.DESCENDING -> ascending.reversed()
        }
    }
}
