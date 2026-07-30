package org.joefang.letterbox

import org.joefang.letterbox.data.SortDirection
import org.joefang.letterbox.data.SortField
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Tests for the SQL interpretation of [HistoryQuery].
 *
 * [HistoryQuery.applyTo] is the executable specification and this is the second
 * interpreter of the same value; both are pure, so both are tested here without a
 * database. Their *agreement* on real rows is asserted separately against Room.
 */
class HistoryQuerySqlTest {

    private val dateDesc =
        "CASE WHEN email_date > 0 THEN email_date ELSE last_accessed END DESC, id DESC"

    // ------------------------------------------------------------------ folding

    @Test
    fun `folding is unicode-aware, unlike SQL lower`() {
        // The whole reason search_text exists: SQLite folds only ASCII, so both
        // sides must arrive already folded.
        assertEquals("müller", foldForSearch("MÜLLER"))
        assertEquals("ekaterina", foldForSearch("EKATERINA"))
        assertEquals("josé", foldForSearch("JOSÉ"))
        assertEquals("ασπρο", foldForSearch("ΑΣΠΡΟ"))
    }

    @Test
    fun `search text concatenates exactly the searchable fields`() {
        val text = searchTextOf(
            subject = "Budget",
            senderName = "Ann",
            senderEmail = "ann@x.com",
            displayName = "b.eml",
            bodyPreview = "Approved"
        )

        assertEquals("budget\nann\nann@x.com\nb.eml\napproved", text)
    }

    @Test
    fun `every searchable field is reachable through the folded text`() {
        val text = searchTextOf("Subj", "Name", "a@b.c", "file.eml", "Body")

        assertTrue(listOf("subj", "name", "a@b.c", "file.eml", "body").all { it in text })
    }

    @Test
    fun `the separator cannot be typed, so a needle cannot span two fields`() {
        // A single-line text field cannot produce a newline, and HistoryQuery
        // trims the needle, so "subj\nname" is unreachable from the search box.
        assertEquals(SEARCH_FIELD_SEPARATOR, "\n")
        assertEquals("", HistoryQuery(text = " \n ").toSqlSelect().args.joinToString(""))
    }

    // ------------------------------------------------------------- like escaping

    @Test
    fun `like metacharacters are neutralised so the needle matches literally`() {
        assertEquals("50\\%", escapeLikeWildcards("50%"))
        assertEquals("a\\_b", escapeLikeWildcards("a_b"))
        assertEquals("c\\\\d", escapeLikeWildcards("c\\d"))
    }

    @Test
    fun `the escape character is escaped first so it cannot escape itself`() {
        // "\%" must become "\\\%", not "\\%", which would escape the backslash and
        // leave % as a live wildcard.
        assertEquals("\\\\\\%", escapeLikeWildcards("\\%"))
    }

    @Test
    fun `a wildcard-only needle becomes a literal, not a match-everything`() {
        val select = HistoryQuery(text = "%").toSqlSelect()

        assertEquals(listOf("%\\%%"), select.args)
    }

    // ------------------------------------------------------------------- clauses

    @Test
    fun `a default query selects everything ordered by date descending`() {
        val select = HistoryQuery().toSqlSelect()

        assertEquals("SELECT * FROM history_items ORDER BY $dateDesc", select.sql)
        assertTrue(select.args.isEmpty())
    }

    @Test
    fun `the attachment filter becomes a where clause with no arguments`() {
        val select = HistoryQuery(onlyWithAttachments = true).toSqlSelect()

        assertEquals(
            "SELECT * FROM history_items WHERE has_attachments = 1 ORDER BY $dateDesc",
            select.sql
        )
        assertTrue(select.args.isEmpty())
    }

    @Test
    fun `text becomes a bound parameter, never interpolated`() {
        val select = HistoryQuery(text = "budget").toSqlSelect()

        assertEquals(
            "SELECT * FROM history_items WHERE search_text LIKE ? ESCAPE '\\' " +
                "ORDER BY $dateDesc",
            select.sql
        )
        assertEquals(listOf("%budget%"), select.args)
        assertTrue("budget" !in select.sql)
    }

    @Test
    fun `the bound needle is folded so it can match folded text`() {
        assertEquals(listOf("%müller%"), HistoryQuery(text = "MÜLLER").toSqlSelect().args)
    }

    @Test
    fun `the needle is trimmed before binding`() {
        assertEquals(listOf("%budget%"), HistoryQuery(text = "  Budget  ").toSqlSelect().args)
    }

    @Test
    fun `filters compose as a conjunction in argument order`() {
        val select = HistoryQuery(text = "q", onlyWithAttachments = true).toSqlSelect()

        assertEquals(
            "SELECT * FROM history_items WHERE has_attachments = 1 AND " +
                "search_text LIKE ? ESCAPE '\\' ORDER BY $dateDesc",
            select.sql
        )
        assertEquals(listOf("%q%"), select.args)
    }

    @Test
    fun `blank text adds no clause`() {
        assertEquals(HistoryQuery().toSqlSelect(), HistoryQuery(text = "   ").toSqlSelect())
    }

    // --------------------------------------------------------------------- order

    @Test
    fun `each sort field and direction renders its own key`() {
        fun order(field: SortField, direction: SortDirection) =
            HistoryQuery(sortField = field, sortDirection = direction)
                .toSqlSelect().sql.substringAfter("ORDER BY ")

        assertEquals(
            "CASE WHEN email_date > 0 THEN email_date ELSE last_accessed END ASC, id ASC",
            order(SortField.DATE, SortDirection.ASCENDING)
        )
        assertEquals(dateDesc, order(SortField.DATE, SortDirection.DESCENDING))
        assertEquals(
            "subject COLLATE NOCASE ASC, id ASC",
            order(SortField.SUBJECT, SortDirection.ASCENDING)
        )
        assertEquals(
            "subject COLLATE NOCASE DESC, id DESC",
            order(SortField.SUBJECT, SortDirection.DESCENDING)
        )
        assertEquals(
            "CASE WHEN sender_name != '' THEN sender_name ELSE sender_email END " +
                "COLLATE NOCASE ASC, id ASC",
            order(SortField.SENDER, SortDirection.ASCENDING)
        )
        assertEquals(
            "CASE WHEN sender_name != '' THEN sender_name ELSE sender_email END " +
                "COLLATE NOCASE DESC, id DESC",
            order(SortField.SENDER, SortDirection.DESCENDING)
        )
    }

    @Test
    fun `every order is total, so paged reads cannot skip or duplicate a row`() {
        // Without a unique tie-breaker, rows with equal keys have no stable
        // sequence between separately loaded pages.
        for (field in SortField.entries) {
            for (direction in SortDirection.entries) {
                val order = HistoryQuery(sortField = field, sortDirection = direction)
                    .toSqlSelect().sql.substringAfter("ORDER BY ")
                assertTrue(order.endsWith(", id ASC") || order.endsWith(", id DESC"), order)
            }
        }
    }

    @Test
    fun `the statement set is finite and contains no caller text`() {
        // Everything interpolated comes from eliminating a closed enum; the only
        // caller-supplied value is bound.
        val statements = SortField.entries.flatMap { field ->
            SortDirection.entries.flatMap { direction ->
                listOf(true, false).map { attachments ->
                    HistoryQuery(
                        text = "'; DROP TABLE history_items; --",
                        onlyWithAttachments = attachments,
                        sortField = field,
                        sortDirection = direction
                    ).toSqlSelect()
                }
            }
        }

        assertEquals(12, statements.size)
        assertTrue(statements.all { "DROP TABLE" !in it.sql })
        assertTrue(statements.all { it.args.size == 1 })
        assertEquals(12, statements.distinctBy { it.sql }.size)
    }
}
