package org.joefang.letterbox

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.joefang.letterbox.data.LetterboxDatabase
import org.joefang.letterbox.data.MIGRATION_3_4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests for [LetterboxDatabase].
 *
 * These exist because the database configures `fallbackToDestructiveMigration()`:
 * a version step whose migration is wrong or missing does not fail loudly, it
 * silently deletes every user's cached email. So each step is exercised against a
 * real database created from the schema JSON exported for the starting version
 * (`app/schemas/`), with real rows in it, and the result is validated against the
 * target version's schema.
 *
 * Adding a version means committing its exported JSON first — see
 * `app/schemas/README.md`.
 */
@RunWith(AndroidJUnit4::class)
class LetterboxDatabaseMigrationTest {

    private companion object {
        /**
         * Each case gets its own database file.
         *
         * Sharing one name across cases makes them order-dependent: whether a
         * file is left behind, and in what state, then decides whether the next
         * case passes. That is a flake, not a test.
         */
        fun dbName(case: String) = "migration-3-to-4-$case.db"

        /** Triggers Room generated for the FTS4 table that version 4 removes. */
        val FTS_TRIGGERS = listOf(
            "room_fts_content_sync_email_fts_BEFORE_UPDATE",
            "room_fts_content_sync_email_fts_BEFORE_DELETE",
            "room_fts_content_sync_email_fts_AFTER_UPDATE",
            "room_fts_content_sync_email_fts_AFTER_INSERT"
        )
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LetterboxDatabase::class.java
    )

    /** Names of objects of [type] present in the database. */
    private fun SupportSQLiteDatabase.objectsOfType(type: String): Set<String> =
        query("SELECT name FROM sqlite_master WHERE type = ?", arrayOf(type)).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun SupportSQLiteDatabase.selectStrings(sql: String): List<String> =
        query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun SupportSQLiteDatabase.insertV3Email(
        hash: String,
        subject: String,
        senderName: String = "",
        senderEmail: String = "",
        displayName: String = "message.eml",
        bodyPreview: String = ""
    ) {
        execSQL(
            "INSERT INTO blobs (hash, size_bytes, ref_count) VALUES (?, ?, 1)",
            arrayOf(hash, 10L)
        )
        execSQL(
            """
            INSERT INTO history_items (
                blob_hash, display_name, original_uri, last_accessed,
                subject, sender_email, sender_name,
                recipient_emails, recipient_names,
                email_date, has_attachments, body_preview
            ) VALUES (?, ?, NULL, 1700000000000, ?, ?, ?, '', '', 0, 0, ?)
            """.trimIndent(),
            arrayOf(hash, displayName, subject, senderEmail, senderName, bodyPreview)
        )
    }

    @Test
    fun migrate3To4_preservesEveryCachedEmail() {
        // The whole point: destructive migration would silently empty these tables.
        helper.createDatabase(dbName("preserves"), 3).use { v3 ->
            v3.insertV3Email("hash-a", subject = "Quarterly budget")
            v3.insertV3Email("hash-b", subject = "Lunch plans")
            v3.insertV3Email("hash-c", subject = "Invoice 42")
        }

        val v4 = helper.runMigrationsAndValidate(dbName("preserves"), 4, true, MIGRATION_3_4)

        assertEquals(
            listOf("Invoice 42", "Lunch plans", "Quarterly budget"),
            v4.selectStrings("SELECT subject FROM history_items ORDER BY subject")
        )
        assertEquals(
            listOf("hash-a", "hash-b", "hash-c"),
            v4.selectStrings("SELECT hash FROM blobs ORDER BY hash")
        )
    }

    @Test
    fun migrate3To4_dropsTheFtsTableAndItsTriggers() {
        val triggersAtV3 = helper.createDatabase(dbName("drops-fts"), 3).use { v3 ->
            // Non-vacuous: the table really is present to be dropped. Asserted
            // because it is an @Entity, so its creation is not in doubt.
            assertTrue("email_fts missing at v3", "email_fts" in v3.objectsOfType("table"))

            v3.insertV3Email("hash-a", subject = "Indexed")
            v3.objectsOfType("trigger").filter { it in FTS_TRIGGERS }.toSet()
        }

        val v4 = helper.runMigrationsAndValidate(dbName("drops-fts"), 4, true, MIGRATION_3_4)

        // Dropping a virtual table takes its shadow tables with it, so nothing
        // named after it may remain.
        val tables = v4.objectsOfType("table")
        assertTrue(
            "FTS tables survived: $tables",
            tables.none { it.startsWith("email_fts") }
        )

        // Every sync trigger the version 3 schema actually created must be gone,
        // and none of the four may exist regardless. Their presence beforehand is
        // deliberately not asserted: whether MigrationTestHelper materialises
        // Room's generated triggers is Room's business, while the guarantee that
        // matters here is that none referencing the dropped table survives.
        val triggers = v4.objectsOfType("trigger")
        (triggersAtV3 + FTS_TRIGGERS).forEach { assertFalse("$it survived", it in triggers) }
    }

    @Test
    fun migrate3To4_leavesTheTableWritable() {
        // A surviving trigger would reference the dropped email_fts and make the
        // next insert fail — the failure mode that motivates dropping them first.
        helper.createDatabase(dbName("writable"), 3).use { v3 ->
            v3.insertV3Email("hash-a", subject = "Existing")
        }

        val v4 = helper.runMigrationsAndValidate(dbName("writable"), 4, true, MIGRATION_3_4)

        v4.execSQL("INSERT INTO blobs (hash, size_bytes, ref_count) VALUES ('hash-b', 5, 1)")
        v4.execSQL(
            """
            INSERT INTO history_items (
                blob_hash, display_name, original_uri, last_accessed,
                subject, sender_email, sender_name, recipient_emails,
                recipient_names, email_date, has_attachments, body_preview, search_text
            ) VALUES ('hash-b', 'new.eml', NULL, 1, 'New', '', '', '', '', 0, 0, '', 'new')
            """.trimIndent()
        )
        v4.execSQL("UPDATE history_items SET last_accessed = 2 WHERE blob_hash = 'hash-b'")
        v4.execSQL("DELETE FROM history_items WHERE blob_hash = 'hash-b'")

        assertEquals(
            listOf("Existing"),
            v4.selectStrings("SELECT subject FROM history_items")
        )
    }

    @Test
    fun migrate3To4_backfillsSearchTextFromEverySearchableField() {
        helper.createDatabase(dbName("backfill"), 3).use { v3 ->
            v3.insertV3Email(
                hash = "hash-a",
                subject = "Quarterly Budget",
                senderName = "Ann Example",
                senderEmail = "ann@example.com",
                displayName = "budget-2026.eml",
                bodyPreview = "Approved by finance"
            )
        }

        val v4 = helper.runMigrationsAndValidate(dbName("backfill"), 4, true, MIGRATION_3_4)
        val searchText = v4.selectStrings("SELECT search_text FROM history_items").single()

        // Folded, and every field reachable — the fields HistoryQuery searches.
        listOf(
            "quarterly budget",
            "ann example",
            "ann@example.com",
            "budget-2026.eml",
            "approved by finance"
        ).forEach {
            assertTrue("missing \"$it\" in \"$searchText\"", it in searchText)
        }
    }

    @Test
    fun migrate3To4_backfillFoldsAscii() {
        helper.createDatabase(dbName("folding"), 3).use { v3 ->
            v3.insertV3Email("hash-a", subject = "MÜLLER REPORT")
        }

        val v4 = helper.runMigrationsAndValidate(dbName("folding"), 4, true, MIGRATION_3_4)
        val searchText = v4.selectStrings("SELECT search_text FROM history_items").single()

        assertTrue("ASCII should be folded: $searchText", "report" in searchText)

        // Whether SQLite's lower() *also* folds non-ASCII is deliberately not
        // asserted either way. It depends on how the platform built SQLite —
        // Android links ICU for collation, a plain build does not — and the
        // migration is correct under both: HistoryRepository.ingest re-folds
        // search_text with Kotlin's Unicode-aware rule when an email is reopened,
        // so a row that SQL left partly folded repairs itself and one it folded
        // fully is already right.
    }

    @Test
    fun migrate3To4_leavesEmptyDatabaseUsable() {
        helper.createDatabase(dbName("empty"), 3).close()

        val v4 = helper.runMigrationsAndValidate(dbName("empty"), 4, true, MIGRATION_3_4)

        assertTrue(v4.selectStrings("SELECT subject FROM history_items").isEmpty())
        assertFalse("email_fts" in v4.objectsOfType("table"))
    }
}
