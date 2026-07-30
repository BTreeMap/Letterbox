package org.joefang.letterbox.ui

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure UI formatters.
 *
 * These need no Robolectric runner: the formatters take the clock, zone and
 * locale as arguments, so there is no ambient Android state to emulate.
 */
class FormattersTest {

    private val utc = ZoneId.of("UTC")
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    // ---------------------------------------------------------------- timestamps

    @Test
    fun `sub-minute age reads as just now`() {
        val now = 1_000_000_000L
        assertEquals("Just now", formatRelativeTimestamp(now, now, utc, Locale.US))
        assertEquals("Just now", formatRelativeTimestamp(now - 59_999L, now, utc, Locale.US))
    }

    @Test
    fun `future timestamp stays in the just now bucket`() {
        val now = 1_000_000_000L
        assertEquals("Just now", formatRelativeTimestamp(now + day, now, utc, Locale.US))
    }

    @Test
    fun `bucket boundaries round down to the coarser unit`() {
        val now = 10 * day
        assertEquals("1m ago", formatRelativeTimestamp(now - minute, now, utc, Locale.US))
        assertEquals("59m ago", formatRelativeTimestamp(now - 59 * minute, now, utc, Locale.US))
        assertEquals("1h ago", formatRelativeTimestamp(now - hour, now, utc, Locale.US))
        assertEquals("23h ago", formatRelativeTimestamp(now - 23 * hour, now, utc, Locale.US))
        assertEquals("1d ago", formatRelativeTimestamp(now - day, now, utc, Locale.US))
        assertEquals("6d ago", formatRelativeTimestamp(now - 6 * day, now, utc, Locale.US))
    }

    @Test
    fun `age of a week or more falls back to an absolute date`() {
        val timestamp = Instant.parse("2026-03-05T12:00:00Z").toEpochMilli()

        assertEquals(
            "Mar 5",
            formatRelativeTimestamp(timestamp, timestamp + 7 * day, utc, Locale.US)
        )
    }

    @Test
    fun `absolute date is rendered in the requested zone`() {
        // 23:30 UTC is already the next day in Tokyo (UTC+9).
        val timestamp = Instant.parse("2026-03-05T23:30:00Z").toEpochMilli()
        val now = timestamp + 30 * day

        assertEquals("Mar 5", formatRelativeTimestamp(timestamp, now, utc, Locale.US))
        assertEquals(
            "Mar 6",
            formatRelativeTimestamp(timestamp, now, ZoneId.of("Asia/Tokyo"), Locale.US)
        )
    }

    // ------------------------------------------------------------- storage sizes

    @Test
    fun `sizes below a kilobyte are exact byte counts`() {
        assertEquals("0 B", formatStorageSize(0, Locale.US))
        assertEquals("1023 B", formatStorageSize(1023, Locale.US))
    }

    @Test
    fun `each unit boundary switches to the next unit`() {
        assertEquals("1.0 KB", formatStorageSize(1024, Locale.US))
        assertEquals("1.5 KB", formatStorageSize(1536, Locale.US))
        assertEquals("1.0 MB", formatStorageSize(1024L * 1024, Locale.US))
        assertEquals("1.0 GB", formatStorageSize(1024L * 1024 * 1024, Locale.US))
        assertEquals("2.5 GB", formatStorageSize(2560L * 1024 * 1024, Locale.US))
    }

    @Test
    fun `decimal separator follows the requested locale`() {
        assertEquals("1,5 KB", formatStorageSize(1536, Locale.GERMANY))
    }

    // -------------------------------------------------------------- source label

    @Test
    fun `known providers map to short names`() {
        assertEquals("Gmail", sourceLabel("content://com.google.android.gm/attachment/1"))
        assertEquals("Drive", sourceLabel("content://com.google.android.apps.docs/doc/1"))
        assertEquals("Downloads", sourceLabel("content://com.android.providers.downloads/1"))
        assertEquals("Files", sourceLabel("content://media/external/file/1"))
    }

    @Test
    fun `unknown providers collapse to external rather than leaking the uri`() {
        assertEquals("External", sourceLabel("content://com.example.unknown/1"))
        assertEquals("External", sourceLabel("file:///sdcard/mail.eml"))
        assertEquals("External", sourceLabel(""))
    }

    // ----------------------------------------------------------- shared filename

    @Test
    fun `subject becomes a filesystem-safe filename`() {
        assertEquals("Hello_world.eml", sharedEmailFilename("Hello world"))
        assertEquals("Re__Q3_report_.eml", sharedEmailFilename("Re: Q3 report!"))
    }

    @Test
    fun `long subjects are truncated to a bounded length`() {
        val name = sharedEmailFilename("a".repeat(120))

        assertEquals("${"a".repeat(50)}.eml", name)
    }

    @Test
    fun `subject with nothing left over falls back to a default name`() {
        assertEquals(DEFAULT_EMAIL_FILENAME, sharedEmailFilename(""))
    }

    @Test
    fun `punctuation-only subject still yields a visible name`() {
        assertEquals("___.eml", sharedEmailFilename("!!!"))
    }
}
