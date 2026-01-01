package org.joefang.letterbox.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
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
 * Represents a history item linking a user-facing name to a blob.
 * Multiple history items can reference the same blob (deduplication).
 * 
 * ## Email Metadata Fields
 * 
 * The following fields are extracted from the email during ingestion to support
 * search, filter, and sort functionality:
 * 
 * - **subject**: Email subject line (used for display and search)
 * - **senderEmail**: Email address of the sender (e.g., "sender@example.com")
 * - **senderName**: Display name of the sender if available (e.g., "John Doe")
 * - **recipientEmails**: Comma-separated list of recipient email addresses
 * - **recipientNames**: Comma-separated list of recipient display names
 * - **emailDate**: Timestamp parsed from the email's Date header (epoch millis, 0 if unparseable)
 * - **hasAttachments**: Whether the email has attachments
 * - **bodyPreview**: First 500 characters of body text for search purposes
 * 
 * ## Fallback Mechanisms
 * 
 * Since EML files may have missing or malformed fields:
 * - Missing subject: defaults to "Untitled"
 * - Missing sender: senderEmail and senderName will be empty strings
 * - Missing date: emailDate will be 0 (unparseable), UI should fall back to lastAccessed
 * - Missing body: bodyPreview will be empty string
 * 
 * ## Database Design Decisions
 * 
 * We chose to denormalize email metadata into the history_items table rather than
 * creating a separate normalized table for the following reasons:
 * 1. Simpler queries - no JOINs needed for common operations
 * 2. Better performance - single table scan for list display
 * 3. Pre-beta status - schema simplicity is more valuable than normalization
 * 
 * For full-text search, we use a separate FTS4 virtual table (email_fts) that
 * mirrors the searchable text content. Room's FTS4 support is used for better
 * compatibility across Android versions (API 26+).
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
        Index("blob_hash"),
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
    val bodyPreview: String = ""
)

/**
 * FTS4 virtual table for full-text search across email content.
 * 
 * ## Design Choice: FTS4 vs FTS5
 * 
 * We use FTS4 instead of FTS5 because:
 * 1. Room has better built-in support for FTS4 via @Fts4 annotation
 * 2. FTS4 is available on all Android API levels we support (26+)
 * 3. FTS5 requires manual SQL and contentSync doesn't work as well with Room
 * 
 * ## Content Synchronization
 * 
 * This table uses contentEntity to automatically sync with history_items table.
 * When a history item is inserted, updated, or deleted, the FTS index is
 * automatically updated by Room.
 * 
 * ## Searchable Fields
 * 
 * The FTS table indexes the following fields for full-text search:
 * - subject: Email subject line
 * - senderEmail: Sender's email address
 * - senderName: Sender's display name
 * - recipientEmails: All recipient email addresses
 * - recipientNames: All recipient display names
 * - bodyPreview: First 500 characters of email body
 */
@Fts4(contentEntity = HistoryItemEntity::class)
@Entity(tableName = "email_fts")
data class EmailFtsEntity(
    /** Email subject line. */
    @ColumnInfo(name = "subject")
    val subject: String,
    
    /** Sender's email address. */
    @ColumnInfo(name = "sender_email")
    val senderEmail: String,
    
    /** Sender's display name. */
    @ColumnInfo(name = "sender_name")
    val senderName: String,
    
    /** Comma-separated recipient email addresses. */
    @ColumnInfo(name = "recipient_emails")
    val recipientEmails: String,
    
    /** Comma-separated recipient display names. */
    @ColumnInfo(name = "recipient_names")
    val recipientNames: String,
    
    /** First 500 characters of email body. */
    @ColumnInfo(name = "body_preview")
    val bodyPreview: String
)

/**
 * Sort options for the email history list.
 */
enum class SortField {
    /** Sort by email date (from Date header, falling back to last accessed). */
    DATE,
    /** Sort by email subject alphabetically. */
    SUBJECT,
    /** Sort by sender name/email alphabetically. */
    SENDER
}

/**
 * Sort direction for the email history list.
 */
enum class SortDirection {
    /** Ascending order (A-Z, oldest first). */
    ASCENDING,
    /** Descending order (Z-A, newest first). */
    DESCENDING
}

/**
 * Filter criteria for the email history list.
 */
data class EmailFilter(
    /** Only show emails with attachments. Null means no filter. */
    val hasAttachments: Boolean? = null,
    
    /** Only show emails from this date onwards (epoch millis). Null means no filter. */
    val dateFrom: Long? = null,
    
    /** Only show emails up to this date (epoch millis). Null means no filter. */
    val dateTo: Long? = null,
    
    /** Only show emails from this sender (partial match). Null means no filter. */
    val senderContains: String? = null
) {
    /** Returns true if no filters are active. */
    val isEmpty: Boolean
        get() = hasAttachments == null && dateFrom == null && dateTo == null && senderContains == null
}
