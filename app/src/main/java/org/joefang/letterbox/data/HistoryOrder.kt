package org.joefang.letterbox.data

/**
 * Ordering vocabulary for the email history list.
 *
 * These are domain values, not persistence concerns, so they live apart from
 * the Room `@Entity` declarations and carry no Room dependency. That keeps them
 * usable — and testable — without a database.
 */

/**
 * Field the history list is ordered by.
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
 * Direction the history list is ordered in.
 */
enum class SortDirection {
    /** Ascending order (A-Z, oldest first). */
    ASCENDING,
    /** Descending order (Z-A, newest first). */
    DESCENDING
}
