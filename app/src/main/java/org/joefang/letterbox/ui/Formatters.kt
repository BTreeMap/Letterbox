package org.joefang.letterbox.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure text formatters used by the UI shell.
 *
 * Every function here is total and depends only on its arguments: the clock, the
 * time zone and the locale are parameters (defaulted to the ambient values)
 * rather than reads buried inside. That makes them testable off-device and lets
 * a caller render a whole list against a single instant instead of taking one
 * clock reading per row.
 */

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val WEEK_MS = 7 * DAY_MS

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = BYTES_PER_KB * BYTES_PER_KB
private const val BYTES_PER_GB = BYTES_PER_MB * BYTES_PER_KB

/** Longest subject prefix used when naming a shared `.eml` file. */
private const val MAX_SHARED_FILENAME_LENGTH = 50

/** Characters replaced by `_` when turning a subject into a filename. */
private val UNSAFE_FILENAME_CHARS = Regex("[^a-zA-Z0-9]")

/** Name used for an email file with no usable subject or provider display name. */
internal const val DEFAULT_EMAIL_FILENAME = "email.eml"

/**
 * The `MMM d` pattern, parsed once. [DateTimeFormatter] is immutable and
 * thread-safe, so unlike a `SimpleDateFormat` it can be shared across list rows;
 * the locale is re-applied per call so a runtime locale change still takes
 * effect without re-parsing the pattern.
 */
private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

/**
 * Human-readable age of [timestamp] relative to [now], both epoch millis.
 *
 * Total over the whole `Long` domain: a timestamp in the future produces a
 * non-positive elapsed time, which falls into the first bucket ("Just now").
 */
internal fun formatRelativeTimestamp(
    timestamp: Long,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val elapsed = now - timestamp
    return when {
        elapsed < MINUTE_MS -> "Just now"
        elapsed < HOUR_MS -> "${elapsed / MINUTE_MS}m ago"
        elapsed < DAY_MS -> "${elapsed / HOUR_MS}h ago"
        elapsed < WEEK_MS -> "${elapsed / DAY_MS}d ago"
        else -> MONTH_DAY.withLocale(locale)
            .format(Instant.ofEpochMilli(timestamp).atZone(zone))
    }
}

/**
 * Byte count as a human-readable size.
 *
 * [locale] defaults to the *format* locale, which is what a locale-less
 * `String.format` call uses implicitly, so the decimal separator matches what
 * the user saw before.
 */
internal fun formatStorageSize(
    bytes: Long,
    locale: Locale = Locale.getDefault(Locale.Category.FORMAT),
): String = when {
    bytes < BYTES_PER_KB -> "$bytes B"
    bytes < BYTES_PER_MB -> String.format(locale, "%.1f KB", bytes.toDouble() / BYTES_PER_KB)
    bytes < BYTES_PER_GB -> String.format(locale, "%.1f MB", bytes.toDouble() / BYTES_PER_MB)
    else -> String.format(locale, "%.1f GB", bytes.toDouble() / BYTES_PER_GB)
}

/**
 * Short label for the app a history entry originally came from. Unrecognised
 * providers collapse to "External" rather than showing a raw content URI.
 */
internal fun sourceLabel(uri: String): String = when {
    uri.startsWith("content://com.google.android.gm") -> "Gmail"
    uri.startsWith("content://com.google.android.apps.docs") -> "Drive"
    uri.startsWith("content://com.android.providers.downloads") -> "Downloads"
    uri.startsWith("content://media/") -> "Files"
    else -> "External"
}

/**
 * Filename for a shared `.eml`, derived from the email subject.
 *
 * Total: every subject maps to a non-empty name. Non-alphanumeric characters
 * become `_` so the name is safe on any filesystem, and a subject that leaves
 * nothing behind falls back to [DEFAULT_EMAIL_FILENAME] instead of producing a
 * bare ".eml", which some receivers treat as a hidden, extension-less file.
 */
internal fun sharedEmailFilename(subject: String): String {
    val stem = subject.take(MAX_SHARED_FILENAME_LENGTH).replace(UNSAFE_FILENAME_CHARS, "_")
    return if (stem.isEmpty()) DEFAULT_EMAIL_FILENAME else "$stem.eml"
}
