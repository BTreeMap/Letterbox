package org.joefang.letterbox

/**
 * Domain values for the email history.
 *
 * Kept apart from the repositories that produce them and free of Android, Room
 * and coroutine dependencies, so the logic that consumes them — notably
 * [HistoryQuery] — can be exercised without a device or a database.
 */

/**
 * A history entry as the UI consumes it.
 *
 * Metadata fields are extracted during ingestion and default to empty rather
 * than null: a missing subject is an empty subject, not an absent one, and that
 * keeps every consumer free of null handling. [emailDate] uses `0` for "no
 * parsable date", eliminated by [effectiveDate].
 */
data class HistoryEntry(
    val id: Long,
    val blobHash: String,
    val displayName: String,
    val originalUri: String?,
    val lastAccessed: Long,
    val subject: String = "",
    val senderEmail: String = "",
    val senderName: String = "",
    val emailDate: Long = 0,
    val hasAttachments: Boolean = false,
    /** First 500 characters of the email body, for full-text search. */
    val bodyPreview: String = ""
) {
    /** Sender to show: the display name when there is one, else the address. */
    val displaySender: String
        get() = senderName.ifBlank { senderEmail }

    /**
     * Date to sort and display by. Falls back to [lastAccessed] when the Date
     * header was missing or unparsable, so the value is always meaningful.
     */
    val effectiveDate: Long
        get() = if (emailDate > 0) emailDate else lastAccessed
}

/**
 * Cache storage statistics shown in the settings sheet.
 */
data class CacheStats(
    /** Total number of cached email entries. */
    val entryCount: Int,
    /** Total size of cached blobs in bytes. */
    val totalSizeBytes: Long
)

/**
 * Email metadata extracted during parsing and persisted for search and sort.
 *
 * Every field defaults, because this is produced even when parsing fails: the
 * fallback parser supplies what it can and leaves the rest empty.
 */
data class EmailMetadata(
    val subject: String = "",
    val senderEmail: String = "",
    val senderName: String = "",
    val recipientEmails: String = "",
    val recipientNames: String = "",
    val emailDate: Long = 0,
    val hasAttachments: Boolean = false,
    val bodyPreview: String = ""
)
