package org.joefang.letterbox

import org.joefang.letterbox.data.SortDirection
import org.joefang.letterbox.data.SortField
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Unit tests for [HistoryQuery], the single definition of the app's search,
 * filter and sort semantics.
 *
 * No runner is needed: a query is a pure function over plain values, so these
 * tests exercise the code path the UI actually runs. Previously the equivalent
 * assertions ran against `InMemoryHistoryRepository`, a class production never
 * instantiated and whose searchable-field set differed from the real one.
 */
class HistoryQueryTest {

    private fun entry(
        id: Long,
        displayName: String = "file.eml",
        subject: String = "",
        senderName: String = "",
        senderEmail: String = "",
        bodyPreview: String = "",
        emailDate: Long = 0,
        lastAccessed: Long = 0,
        hasAttachments: Boolean = false
    ) = HistoryEntry(
        id = id,
        blobHash = "hash$id",
        displayName = displayName,
        originalUri = null,
        lastAccessed = lastAccessed,
        subject = subject,
        senderEmail = senderEmail,
        senderName = senderName,
        emailDate = emailDate,
        hasAttachments = hasAttachments,
        bodyPreview = bodyPreview
    )

    // -------------------------------------------------------------- text search

    @Test
    fun `blank text admits everything`() {
        val entries = listOf(entry(1, subject = "a"), entry(2, subject = "b"))

        assertEquals(2, HistoryQuery(text = "").applyTo(entries).size)
        assertEquals(2, HistoryQuery(text = "   ").applyTo(entries).size)
    }

    @Test
    fun `text matches every searchable field`() {
        val bySubject = entry(1, subject = "Quarterly budget")
        val bySenderName = entry(2, senderName = "Budget Team")
        val bySenderEmail = entry(3, senderEmail = "budget@example.com")
        val byDisplayName = entry(4, displayName = "budget-2026.eml")
        val byBody = entry(5, bodyPreview = "the budget is approved")
        val unrelated = entry(6, subject = "Lunch")
        val all = listOf(bySubject, bySenderName, bySenderEmail, byDisplayName, byBody, unrelated)

        val matched = HistoryQuery(text = "budget").applyTo(all).map { it.id }.toSet()

        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), matched)
    }

    @Test
    fun `display name is searchable`() {
        // The retired in-memory copy in the test double omitted this field, so a
        // green suite could coexist with a real search that behaved differently.
        val entries = listOf(entry(1, displayName = "invoice.eml"), entry(2, displayName = "notes.eml"))

        assertEquals(listOf(1L), HistoryQuery(text = "invoice").applyTo(entries).map { it.id })
    }

    @Test
    fun `text matching ignores case in both directions`() {
        val entries = listOf(entry(1, subject = "MEETING Notes"))

        assertEquals(1, HistoryQuery(text = "meeting").applyTo(entries).size)
        assertEquals(1, HistoryQuery(text = "MeEtInG").applyTo(entries).size)
    }

    @Test
    fun `text matches infix, not only a token prefix`() {
        // Pins the semantic decision: substring, not FTS4-style token prefix.
        val entries = listOf(entry(1, subject = "Airport transfer"))

        assertEquals(1, HistoryQuery(text = "port").applyTo(entries).size)
    }

    @Test
    fun `surrounding whitespace in the query is ignored`() {
        val entries = listOf(entry(1, subject = "Meeting"))

        assertEquals(1, HistoryQuery(text = "  meeting  ").applyTo(entries).size)
    }

    @Test
    fun `punctuation in the query is matched literally, never interpreted`() {
        // The retired FTS4 path fed this straight into MATCH, where "-" and "("
        // are syntax errors that surfaced as SQLiteException.
        val entries = listOf(
            entry(1, subject = "Re: (draft) - v2"),
            entry(2, subject = "Final"),
            entry(3, subject = "say \"hello\"")
        )

        assertEquals(listOf(1L), HistoryQuery(text = "-").applyTo(entries).map { it.id })
        assertEquals(listOf(1L), HistoryQuery(text = "(draft)").applyTo(entries).map { it.id })
        assertEquals(listOf(3L), HistoryQuery(text = "\"hello\"").applyTo(entries).map { it.id })
        assertTrue(HistoryQuery(text = "*").applyTo(entries).isEmpty())
    }

    @Test
    fun `no matches yields an empty list`() {
        val entries = listOf(entry(1, subject = "Hello"))

        assertTrue(HistoryQuery(text = "xyzzy").applyTo(entries).isEmpty())
    }

    // ------------------------------------------------------------------ filters

    @Test
    fun `attachment filter keeps only entries with attachments`() {
        val entries = listOf(
            entry(1, hasAttachments = true),
            entry(2, hasAttachments = false),
            entry(3, hasAttachments = true)
        )

        val kept = HistoryQuery(onlyWithAttachments = true).applyTo(entries).map { it.id }.toSet()

        assertEquals(setOf(1L, 3L), kept)
    }

    @Test
    fun `text and attachment filters compose as a conjunction`() {
        val entries = listOf(
            entry(1, subject = "budget", hasAttachments = true),
            entry(2, subject = "budget", hasAttachments = false),
            entry(3, subject = "lunch", hasAttachments = true)
        )

        val kept = HistoryQuery(text = "budget", onlyWithAttachments = true).applyTo(entries)

        assertEquals(listOf(1L), kept.map { it.id })
    }

    @Test
    fun `admits agrees with applyTo`() {
        val query = HistoryQuery(text = "budget", onlyWithAttachments = true)

        assertTrue(query.admits(entry(1, subject = "budget", hasAttachments = true)))
        assertFalse(query.admits(entry(2, subject = "budget", hasAttachments = false)))
        assertFalse(query.admits(entry(3, subject = "lunch", hasAttachments = true)))
    }

    // -------------------------------------------------------------------- order

    @Test
    fun `date descending is the default order`() {
        val entries = listOf(
            entry(1, emailDate = 1_000),
            entry(2, emailDate = 3_000),
            entry(3, emailDate = 2_000)
        )

        assertEquals(listOf(2L, 3L, 1L), HistoryQuery().applyTo(entries).map { it.id })
    }

    @Test
    fun `date ascending reverses the order`() {
        val entries = listOf(
            entry(1, emailDate = 1_000),
            entry(2, emailDate = 3_000),
            entry(3, emailDate = 2_000)
        )

        val query = HistoryQuery(sortField = SortField.DATE, sortDirection = SortDirection.ASCENDING)

        assertEquals(listOf(1L, 3L, 2L), query.applyTo(entries).map { it.id })
    }

    @Test
    fun `date order falls back to lastAccessed when emailDate is absent`() {
        val parsed = entry(1, emailDate = 5_000, lastAccessed = 1)
        val unparsed = entry(2, emailDate = 0, lastAccessed = 9_000)

        val query = HistoryQuery(sortDirection = SortDirection.ASCENDING)

        assertEquals(listOf(1L, 2L), query.applyTo(listOf(unparsed, parsed)).map { it.id })
    }

    @Test
    fun `subject order is alphabetical and case-insensitive`() {
        val entries = listOf(
            entry(1, subject = "zebra"),
            entry(2, subject = "Apple"),
            entry(3, subject = "mango")
        )

        val query = HistoryQuery(sortField = SortField.SUBJECT, sortDirection = SortDirection.ASCENDING)

        assertEquals(listOf(2L, 3L, 1L), query.applyTo(entries).map { it.id })
    }

    @Test
    fun `sender order uses the display name and falls back to the address`() {
        val entries = listOf(
            entry(1, senderName = "Zach", senderEmail = "a@x.com"),
            entry(2, senderName = "Adam", senderEmail = "z@x.com"),
            entry(3, senderName = "", senderEmail = "mike@x.com")
        )

        val query = HistoryQuery(sortField = SortField.SENDER, sortDirection = SortDirection.ASCENDING)
        val order = query.applyTo(entries).map { it.displaySender }

        assertEquals(listOf("Adam", "mike@x.com", "Zach"), order)
    }

    @Test
    fun `ties are broken by id, in the direction of the sort`() {
        // The order must be total, and must match what ORDER BY key, id produces,
        // or the SQL interpreter disagrees with this one on every tie. Incoming
        // order is deliberately not the tie-break: it would make the result depend
        // on how rows happened to arrive.
        val entries = listOf(
            entry(2, emailDate = 1_000),
            entry(3, emailDate = 1_000),
            entry(1, emailDate = 1_000)
        )

        assertEquals(
            listOf(3L, 2L, 1L),
            HistoryQuery(sortDirection = SortDirection.DESCENDING).applyTo(entries).map { it.id }
        )
        assertEquals(
            listOf(1L, 2L, 3L),
            HistoryQuery(sortDirection = SortDirection.ASCENDING).applyTo(entries).map { it.id }
        )
    }

    @Test
    fun `the order is independent of the order rows arrive in`() {
        val entries = (1L..6L).map { entry(it, emailDate = it % 2) }

        val forward = HistoryQuery().applyTo(entries).map { it.id }
        val reversed = HistoryQuery().applyTo(entries.reversed()).map { it.id }

        assertEquals(forward, reversed)
    }

    // ------------------------------------------------------------------ algebra

    @Test
    fun `sorting preserves cardinality and every element`() {
        val entries = (1L..20L).map { entry(it, subject = "s$it", emailDate = it) }

        val result = HistoryQuery().applyTo(entries)

        assertEquals(entries.size, result.size)
        assertEquals(entries.toSet(), result.toSet())
    }

    @Test
    fun `filtering can only shrink the input`() {
        val entries = (1L..20L).map { entry(it, hasAttachments = it % 2 == 0L) }

        val result = HistoryQuery(onlyWithAttachments = true).applyTo(entries)

        assertTrue(result.size <= entries.size)
        assertTrue(result.all { it in entries })
    }

    @Test
    fun `empty input yields empty output for any query`() {
        val queries = listOf(
            HistoryQuery(),
            HistoryQuery(text = "anything"),
            HistoryQuery(onlyWithAttachments = true),
            HistoryQuery(sortField = SortField.SENDER, sortDirection = SortDirection.ASCENDING)
        )

        assertTrue(queries.all { it.applyTo(emptyList()).isEmpty() })
    }

    @Test
    fun `applyTo does not mutate its input`() {
        val entries = listOf(entry(1, emailDate = 1), entry(2, emailDate = 2))
        val snapshot = entries.toList()

        HistoryQuery(sortDirection = SortDirection.ASCENDING).applyTo(entries)

        assertEquals(snapshot, entries)
    }
}
