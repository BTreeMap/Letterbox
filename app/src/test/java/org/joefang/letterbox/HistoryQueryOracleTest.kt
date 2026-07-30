package org.joefang.letterbox

import androidx.room.Room
import org.joefang.letterbox.data.BlobEntity
import org.joefang.letterbox.data.HistoryItemEntity
import org.joefang.letterbox.data.LetterboxDatabase
import org.joefang.letterbox.data.SortDirection
import org.joefang.letterbox.data.SortField
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Asserts that the two interpreters of [HistoryQuery] agree.
 *
 * `applyTo` is the executable specification; `toSqlSelect` is the paged path.
 * Nothing forces two implementations of the same semantics to stay in step, and
 * this codebase already demonstrated what happens when nothing does — three
 * divergent copies of search, one of which ran and none of which was the
 * reference. So the SQL is not reviewed against the spec, it is *executed*
 * against it: real SQLite, real rows, every combination of query parameters, and
 * the resulting row order compared exactly.
 *
 * A failure here means the two disagree, not that either is independently wrong.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryQueryOracleTest {

    private lateinit var db: LetterboxDatabase
    private lateinit var entries: List<HistoryEntry>

    /**
     * Rows chosen to exercise everything the two interpreters could disagree
     * about: mixed case, non-ASCII case folding, an infix-only match, `LIKE`
     * metacharacters, attachment presence, the sender-name fallback, a blank
     * subject, an unparsable date falling back to `lastAccessed`, and ties on
     * every sort key.
     */
    private val fixtures: List<HistoryItemEntity> = listOf(
        row(1, subject = "Quarterly Budget", senderName = "Ann", senderEmail = "ann@example.com",
            bodyPreview = "approved by finance", emailDate = 3_000, hasAttachments = true),
        row(2, subject = "MÜLLER report", senderName = "Müller", senderEmail = "m@example.de",
            emailDate = 2_000),
        row(3, subject = "Airport transfer", senderName = "", senderEmail = "travel@example.com",
            emailDate = 2_000, hasAttachments = true),
        row(4, subject = "", displayName = "no-subject.eml", senderName = "Zach",
            emailDate = 0, lastAccessed = 9_000),
        row(5, subject = "Discount 50% off", senderName = "ann", senderEmail = "ann@example.com",
            emailDate = 1_000),
        row(6, subject = "a_b underscore", senderName = "Bob", emailDate = 1_000,
            hasAttachments = true),
        row(7, subject = "Quarterly Budget", senderName = "Ann", senderEmail = "ann@example.com",
            emailDate = 3_000),
        row(8, subject = "lunch", senderName = "bob", senderEmail = "bob@example.com",
            bodyPreview = "Airport pickup at noon", emailDate = 0, lastAccessed = 500),
        // Whitespace-only sender name: `ifBlank` treats it as absent and falls back
        // to the address, so the SQL condition must use TRIM rather than `!= ''`.
        row(9, subject = "whitespace sender", senderName = "   ",
            senderEmail = "blank@example.com", emailDate = 4_000)
    )

    private fun row(
        id: Long,
        subject: String = "",
        senderName: String = "",
        senderEmail: String = "",
        displayName: String = "message-$id.eml",
        bodyPreview: String = "",
        emailDate: Long = 0,
        lastAccessed: Long = 1_000,
        hasAttachments: Boolean = false
    ) = HistoryItemEntity(
        id = id,
        blobHash = "hash-$id",
        displayName = displayName,
        originalUri = null,
        lastAccessed = lastAccessed,
        subject = subject,
        senderEmail = senderEmail,
        senderName = senderName,
        recipientEmails = "",
        recipientNames = "",
        emailDate = emailDate,
        hasAttachments = hasAttachments,
        bodyPreview = bodyPreview,
        // The write-side half of the folding contract, exactly as the repository
        // applies it. Without it the SQL side has nothing to match against.
        searchText = searchTextOf(subject, senderName, senderEmail, displayName, bodyPreview)
    )

    private fun HistoryItemEntity.toEntry() = HistoryEntry(
        id = id,
        blobHash = blobHash,
        displayName = displayName,
        originalUri = originalUri,
        lastAccessed = lastAccessed,
        subject = subject,
        senderEmail = senderEmail,
        senderName = senderName,
        emailDate = emailDate,
        hasAttachments = hasAttachments,
        bodyPreview = bodyPreview
    )

    @Before
    fun setUp() {
        // Robolectric's own application, matching UserPreferencesRepositoryTest:
        // androidx.test:core is an androidTest dependency, not a unit-test one.
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LetterboxDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

        runBlocking {
            fixtures.forEach { item ->
                // The foreign key requires the blob row first.
                db.blobDao().insert(BlobEntity(item.blobHash, 10L, 1))
                db.historyItemDao().insert(item)
            }
        }
        entries = fixtures.map { it.toEntry() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Row ids the SQL interpreter returns, in the order SQLite returns them. */
    private fun sqlIds(query: HistoryQuery): List<Long> {
        val select = query.toSqlSelect()
        return db.openHelper.readableDatabase
            .query(select.sql, select.args.toTypedArray())
            .use { cursor ->
                val column = cursor.getColumnIndexOrThrow("id")
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(column))
                }
            }
    }

    private fun specIds(query: HistoryQuery): List<Long> =
        query.applyTo(entries).map { it.id }

    private val allQueries: List<HistoryQuery> = buildList {
        val needles = listOf(
            "",            // no text filter
            "budget",      // multi-row match, mixed case
            "müller",      // needs Unicode folding on both sides
            "MÜLLER",      // folding of the needle itself
            "port",        // infix only: matches "Airport", never a token prefix
            "50%",         // LIKE metacharacter, must be literal
            "a_b",         // LIKE metacharacter, must be literal
            "ann@example", // address fragment
            "no-subject",  // display name only
            "noon",        // body preview only
            "zzzz"         // matches nothing
        )
        for (text in needles) {
            for (attachments in listOf(false, true)) {
                for (field in SortField.entries) {
                    for (direction in SortDirection.entries) {
                        add(
                            HistoryQuery(
                                text = text,
                                onlyWithAttachments = attachments,
                                sortField = field,
                                sortDirection = direction
                            )
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `sql and in-memory interpreters agree on every query`() {
        val disagreements = allQueries.mapNotNull { query ->
            val spec = specIds(query)
            val sql = sqlIds(query)
            if (spec == sql) null else "$query\n  spec=$spec\n  sql =$sql"
        }

        assertEquals(
            "interpreters disagreed on ${disagreements.size} of ${allQueries.size} queries:\n" +
                disagreements.joinToString("\n"),
            0,
            disagreements.size
        )
    }

    @Test
    fun `the fixture actually exercises each needle`() {
        // Guards against the comparison above passing because every query returned
        // nothing on both sides.
        val matching = listOf("budget", "müller", "port", "50%", "a_b", "noon", "no-subject")
            .associateWith { specIds(HistoryQuery(text = it)) }

        matching.forEach { (needle, ids) ->
            assertTrue("\"$needle\" matched nothing", ids.isNotEmpty())
        }
        assertTrue(specIds(HistoryQuery(text = "zzzz")).isEmpty())
        assertEquals(fixtures.size, specIds(HistoryQuery()).size)
    }

    @Test
    fun `folding lets a lowercase needle match non-ascii uppercase text through SQL`() {
        // The specific regression search_text exists to prevent: SQLite's LIKE and
        // lower() fold ASCII only, so this would fail against raw columns.
        assertEquals(listOf(2L), sqlIds(HistoryQuery(text = "müller")))
    }

    @Test
    fun `like metacharacters match literally through SQL`() {
        assertEquals(listOf(5L), sqlIds(HistoryQuery(text = "50%")))
        assertEquals(listOf(6L), sqlIds(HistoryQuery(text = "a_b")))
        // A bare wildcard is a literal, not a match-everything.
        assertTrue(sqlIds(HistoryQuery(text = "%")).size < fixtures.size)
    }

    @Test
    fun `ties are ordered identically by both interpreters`() {
        // Rows 1 and 7 share a subject, a sender and a date, so every sort key ties
        // and only the id tie-break decides their order.
        for (field in SortField.entries) {
            for (direction in SortDirection.entries) {
                val query = HistoryQuery(sortField = field, sortDirection = direction)
                assertEquals("$field $direction", specIds(query), sqlIds(query))
            }
        }
    }
}
