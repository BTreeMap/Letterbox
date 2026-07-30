package org.joefang.letterbox

import org.joefang.letterbox.ui.EML_EXTENSION
import org.joefang.letterbox.ui.sharedEmailFilename
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writing the cached mailbox out as a zip of `.eml` files.
 *
 * Free of Android and Room so it can be exercised against real temporary files.
 * Streaming rather than assembling: the cache is unbounded, so nothing here may
 * hold more than one buffer's worth of a message at a time.
 */

/**
 * Earliest instant a zip entry timestamp can represent.
 *
 * The zip format stores MS-DOS date fields, whose epoch is 1980-01-01. Older
 * values cannot be encoded, so they are left unset rather than silently wrapped.
 */
internal const val MIN_ZIP_TIMESTAMP = 315_532_800_000L

/**
 * An archive name derived from [subject] that is not already in [taken].
 *
 * Two messages sharing a subject is ordinary, and a zip with duplicate entry
 * names is not something every extractor handles the same way, so collisions are
 * resolved rather than tolerated. Pure: [taken] is only read.
 *
 * Total, and guaranteed to terminate: [taken] is finite, so the candidate sequence
 * must eventually produce a name outside it.
 */
internal fun uniqueArchiveName(subject: String, taken: Set<String>): String {
    val preferred = sharedEmailFilename(subject)
    if (preferred !in taken) return preferred

    val stem = preferred.removeSuffix(EML_EXTENSION)
    return generateSequence(2) { it + 1 }
        .map { "$stem-$it$EML_EXTENSION" }
        .first { it !in taken }
}

/**
 * Counts describing a finished export.
 *
 * [skipped] is not an error: a history row whose blob file is missing — which a
 * reclamation sweep or external interference can produce — is reported rather
 * than aborting an export the user asked for.
 */
data class ArchiveSummary(val written: Int, val skipped: Int) {
    val total: Int get() = written + skipped
}

/**
 * One-line outcome of an export, for a snackbar.
 *
 * Mentions skipped messages only when there were some, so the ordinary case reads
 * as a plain success rather than as a report with a zero in it.
 */
internal fun exportSummaryMessage(summary: ArchiveSummary): String = when {
    summary.total == 0 -> "No cached emails to export"
    summary.skipped == 0 -> "Exported ${summary.written} email${plural(summary.written)}"
    else ->
        "Exported ${summary.written} of ${summary.total} emails, " +
            "${summary.skipped} missing"
}

private fun plural(count: Int): String = if (count == 1) "" else "s"

/**
 * Streams messages into a zip, one at a time.
 *
 * Stateful and imperative on purpose: a zip is a resource with a write protocol
 * (open entry, copy, close entry) and a name registry that must persist across
 * calls, none of which a fold over a list expresses honestly. Ownership is
 * explicit via [Closeable], so callers can use `use`.
 *
 * Takes ownership of [output] and closes it.
 */
internal class EmailArchiveWriter(output: OutputStream) : Closeable {

    private val zip = ZipOutputStream(BufferedOutputStream(output))
    private val taken = mutableSetOf<String>()

    var written: Int = 0
        private set

    var skipped: Int = 0
        private set

    val summary: ArchiveSummary get() = ArchiveSummary(written, skipped)

    /**
     * Add [source] under a unique name derived from [subject].
     *
     * A missing or unreadable source is counted in [skipped] and otherwise
     * ignored, so one absent blob cannot fail the whole export. The copy is
     * buffered, so peak memory does not depend on message size.
     */
    fun add(subject: String, source: File, timestamp: Long) {
        if (!source.isFile) {
            skipped++
            return
        }

        val name = uniqueArchiveName(subject, taken)
        taken += name

        val entry = ZipEntry(name)
        // Pre-1980 instants have no representation in the zip date fields.
        if (timestamp >= MIN_ZIP_TIMESTAMP) {
            entry.time = timestamp
        }

        zip.putNextEntry(entry)
        source.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
        written++
    }

    override fun close() {
        zip.close()
    }
}
