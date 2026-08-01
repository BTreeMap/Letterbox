package org.joefang.letterbox.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a blob stored in the Content-Addressable Storage (CAS).
 * The hash (SHA-256) serves as both the primary key and the filename.
 */
@Entity(tableName = "blobs")
data class BlobEntity(
    @PrimaryKey
    @ColumnInfo(name = "hash")
    val hash: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    @ColumnInfo(name = "ref_count")
    val refCount: Int
)

/**
 * A history item linking a user-facing name to a blob.
 *
 * `blob_hash` has a unique constraint, so re-ingesting the same SHA-256
 * checksum updates the existing row's `lastAccessed` instead of duplicating
 * it. Metadata is denormalized onto this table rather than normalized out,
 * and search matches against the `search_text` column; the `email_fts` FTS4
 * table it replaced was dropped in version 4 (see `docs/full-text-search.md`).
 */
@Entity(
    tableName = "history_items",
    foreignKeys = [
        ForeignKey(
            entity = BlobEntity::class,
            parentColumns = ["hash"],
            childColumns = ["blob_hash"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("blob_hash", unique = true),
        Index("email_date"),
        Index("sender_email"),
        Index("has_attachments")
    ]
)
data class HistoryItemEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "blob_hash")
    val blobHash: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "original_uri")
    val originalUri: String?,

    @ColumnInfo(name = "last_accessed")
    val lastAccessed: Long,
    
    // Email metadata fields for search, filter, and sort
    
    /** Email subject line. Defaults to "Untitled" if missing. */
    @ColumnInfo(name = "subject", defaultValue = "")
    val subject: String = "",
    
    /** Sender's email address (e.g., "sender@example.com"). Empty if not available. */
    @ColumnInfo(name = "sender_email", defaultValue = "")
    val senderEmail: String = "",
    
    /** Sender's display name (e.g., "John Doe"). Empty if not available. */
    @ColumnInfo(name = "sender_name", defaultValue = "")
    val senderName: String = "",
    
    /** Comma-separated list of recipient email addresses. */
    @ColumnInfo(name = "recipient_emails", defaultValue = "")
    val recipientEmails: String = "",
    
    /** Comma-separated list of recipient display names. */
    @ColumnInfo(name = "recipient_names", defaultValue = "")
    val recipientNames: String = "",
    
    /** 
     * Email date parsed from the Date header as epoch milliseconds.
     * 0 if the date is missing or unparseable. UI should fall back to lastAccessed.
     */
    @ColumnInfo(name = "email_date", defaultValue = "0")
    val emailDate: Long = 0,
    
    /** Whether the email has attachments. */
    @ColumnInfo(name = "has_attachments", defaultValue = "0")
    val hasAttachments: Boolean = false,
    
    /** First 500 characters of the email body for search purposes. */
    @ColumnInfo(name = "body_preview", defaultValue = "")
    val bodyPreview: String = "",

    /**
     * Case-folded concatenation of every searchable field, maintained at write
     * time by `searchTextOf`.
     *
     * Exists because SQLite's `LIKE` and `NOCASE` collation fold ASCII only, and
     * how far `lower()` folds depends on the platform's SQLite build. Folding both
     * the stored text and the needle in Kotlin, where case mapping is
     * Unicode-aware, lets SQL perform a plain substring match that is correct in
     * every script and identical on every device — so "müller" finds "Müller".
     */
    @ColumnInfo(name = "search_text", defaultValue = "")
    val searchText: String = ""
)

// SortField and SortDirection live in HistoryOrder.kt: they are domain values
// with no Room dependency, so they stay out of this entity-declaration file.
