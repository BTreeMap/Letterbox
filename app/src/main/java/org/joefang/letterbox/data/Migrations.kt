package org.joefang.letterbox.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit schema migrations.
 *
 * The database also configures `fallbackToDestructiveMigration()`, so any version
 * step *without* a migration here silently deletes every user's cached email.
 * Each migration is covered by `LetterboxDatabaseMigrationTest`, which runs on a
 * real device against the schema JSON exported for the starting version.
 */

/**
 * The content-sync triggers Room generated for the `email_fts` external-content
 * FTS4 table.
 *
 * Copied verbatim from the names in the exported version 3 schema
 * (`app/schemas/.../3.json`, `contentSyncTriggers`) rather than reconstructed from
 * Room's naming convention. They fire on `history_items`, so they must be dropped
 * before the table they write into — otherwise the next insert would fail against
 * a missing `email_fts`.
 */
private val FTS_SYNC_TRIGGERS = listOf(
    "room_fts_content_sync_email_fts_BEFORE_UPDATE",
    "room_fts_content_sync_email_fts_BEFORE_DELETE",
    "room_fts_content_sync_email_fts_AFTER_UPDATE",
    "room_fts_content_sync_email_fts_AFTER_INSERT"
)

/**
 * Backfill for `search_text` on rows that predate the column.
 *
 * Concatenates the same fields, in the same order, with the same separator as
 * `searchTextOf`. Every column involved is `NOT NULL`, so `||` cannot yield null.
 *
 * ## Known limitation
 *
 * SQLite's `lower()` folds ASCII only, so a pre-existing subject of "MÜLLER"
 * becomes "mÜller" here, which will not match the Unicode-folded needle "müller".
 * The alternative — iterating every row through Kotlin's case mapping inside the
 * migration — was rejected in favour of keeping the migration trivially correct.
 * The gap is narrow and self-healing: `HistoryRepository.ingest` re-folds
 * `search_text` with Kotlin whenever an email is opened again, so any affected row
 * repairs itself on next use. New rows are never affected, because they are folded
 * in Kotlin at write time.
 */
private const val BACKFILL_SEARCH_TEXT = """
    UPDATE history_items SET search_text = lower(
        subject || char(10) ||
        sender_name || char(10) ||
        sender_email || char(10) ||
        display_name || char(10) ||
        body_preview
    )
"""

/**
 * Version 3 to 4: drop the unused FTS4 index, add the `search_text` column.
 *
 * `email_fts` was maintained by Room on every insert, update and delete of
 * `history_items` and queried by nothing. Search now matches a case-folded column
 * on the table itself, which supports substring matching where FTS4 `MATCH` only
 * offers token prefixes. See `docs/full-text-search.md`.
 *
 * Dropping the virtual table also drops its shadow tables (`email_fts_segments`,
 * `_segdir`, `_docsize`, `_stat`); the triggers are separate objects and must go
 * explicitly.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Triggers first: they reference email_fts, and they fire on writes to
        // history_items, which the backfill below performs.
        FTS_SYNC_TRIGGERS.forEach { trigger ->
            db.execSQL("DROP TRIGGER IF EXISTS $trigger")
        }
        db.execSQL("DROP TABLE IF EXISTS email_fts")

        db.execSQL(
            "ALTER TABLE history_items ADD COLUMN search_text TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(BACKFILL_SEARCH_TEXT)
    }
}
