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
        const val DB_NAME = "migration-test.db"

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
        helper.createDatabase(DB_NAME, 3).use { v3 ->
            v3.insertV3Email("hash-a", subject = "Quarterly budget")
            v3.insertV3Email("hash-b", subject = "Lunch plans")
            v3.insertV3Email("hash-c", subject = "Invoice 42")
        }

        val v4 = helper.runMigrationsAndValidate(DB_NAME, 4, true, MIGRATION_3_4)

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
        helper.createDatabase(DB_NAME, 3).use { v3 ->
            // Sanity: the objects being removed really are present at version 3,
            // otherwise this test could pass without proving anything.
            assertTrue("email_fts missing at v3", "email_fts" in v3.objectsOfType("table"))
            val triggersAtV3 = v3.objectsOfType("trigger")
            FTS_TRIGGERS.forEach { assertTrue("$it missing at v3", it in triggersAtV3) }

            v3.insertV3Email("hash-a", subject = "Indexed")
        }

        val v4 = helper.runMigrationsAndValidate(DB_NAME, 4, true, MIGRATION_3_4)

        val tables = v4.objectsOfType("table")
        assertFalse("email_fts survived", "email_fts" in tables)
        // Dropping a virtual table takes its shadow tables with it.
        assertTrue(
            "FTS shadow tables survived: $tables",
            tables.none { it.startsWith("email_fts") }
        )

        val triggers = v4.objectsOfType("trigger")
        FTS_TRIGGERS.forEach { assertFalse("$it survived", it in triggers) }
    }

    @Test
    fun migrate3To4_leavesTheTableWritable() {
        // A surviving trigger would reference the dropped email_fts and make the
        // next insert fail — the failure mode that motivates dropping them first.
        helper.createDatabase(DB_NAME, 3).use { v3 ->
            v3.insertV3Email("hash-a", subject = "Existing")
        }

        val v4 = helper.runMigrationsAndValidate(DB_NAME, 4, true, MIGRATION_3_4)

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
        helper.createDatabase(DB_NAME, 3).use { v3 ->
            v3.insertV3Email(
                hash = "hash-a",
                subject = "Quarterly Budget",
                senderName = "Ann Example",
                senderEmail = "ann@example.com",
                displayName = "budget-2026.eml",
                bodyPreview = "Approved by finance"
            )
        }

        val v4 = helper.runMigrationsAndValidate(DB_NAME, 4, true, MIGRATION_3_4)
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
    fun migrate3To4_backfillFoldsAsciiOnly() {
        // Pins the documented limitation rather than pretending it is absent:
        // SQLite's lower() does not fold non-ASCII, so these rows are repaired
        // later by HistoryRepository.ingest instead of by the migration.
        helper.createDatabase(DB_NAME, 3).use { v3 ->
            v3.insertV3Email("hash-a", subject = "MÜLLER REPORT")
        }

        val v4 = helper.runMigrationsAndValidate(DB_NAME, 4, true, MIGRATION_3_4)
        val searchText = v4.selectStrings("SELECT search_text FROM history_items").single()

        assertTrue("ASCII should be folded: $searchText", "report" in searchText)
        assertFalse("Ü is not folded by SQLite lower(): $searchText", "müller" in searchText)
    }

    @Test
    fun migrate3To4_leavesEmptyDatabaseUsable() {
        helper.createDatabase(DB_NAME, 3).close()

        val v4 = helper.runMigrationsAndValidate(DB_NAME, 4, true, MIGRATION_3_4)

        assertTrue(v4.selectStrings("SELECT subject FROM history_items").isEmpty())
        assertFalse("email_fts" in v4.objectsOfType("table"))
    }
}
