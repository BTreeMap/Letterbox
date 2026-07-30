package org.joefang.letterbox

import org.joefang.letterbox.ui.DEFAULT_EMAIL_FILENAME
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests for exporting the cached mailbox as a zip.
 *
 * Runs against real temporary files and a real zip stream — no Android, no Room —
 * and reads the archive back to assert on what a user would actually extract.
 */
class EmailArchiveTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = createTempDirectory(prefix = "letterbox-archive").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun blob(name: String, content: String): File =
        File(dir, name).apply { writeText(content) }

    /** Entry names and contents, in archive order. */
    private fun readArchive(bytes: ByteArray): List<Pair<String, String>> =
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }
                .map { it.name to zip.readBytes().decodeToString() }
                .toList()
        }

    private fun archive(build: EmailArchiveWriter.() -> Unit): Pair<ByteArray, ArchiveSummary> {
        val out = ByteArrayOutputStream()
        var summary = ArchiveSummary(0, 0)
        EmailArchiveWriter(out).use { writer ->
            writer.build()
            summary = writer.summary
        }
        return out.toByteArray() to summary
    }

    // ------------------------------------------------------------------- naming

    @Test
    fun `a name is derived from the subject`() {
        assertEquals("Quarterly_budget.eml", uniqueArchiveName("Quarterly budget", emptySet()))
    }

    @Test
    fun `a subject with nothing usable falls back to the default name`() {
        assertEquals(DEFAULT_EMAIL_FILENAME, uniqueArchiveName("", emptySet()))
    }

    @Test
    fun `colliding subjects get distinct names`() {
        val taken = mutableSetOf<String>()
        val names = (1..4).map {
            uniqueArchiveName("Budget", taken).also { name -> taken += name }
        }

        assertEquals(listOf("Budget.eml", "Budget-2.eml", "Budget-3.eml", "Budget-4.eml"), names)
        assertEquals(4, names.toSet().size)
    }

    @Test
    fun `disambiguation skips names already taken`() {
        val name = uniqueArchiveName("Budget", setOf("Budget.eml", "Budget-2.eml"))

        assertEquals("Budget-3.eml", name)
    }

    @Test
    fun `even entries with no subject at all stay distinct`() {
        val taken = mutableSetOf<String>()
        val names = (1..3).map {
            uniqueArchiveName("", taken).also { name -> taken += name }
        }

        assertEquals(3, names.toSet().size)
        assertEquals(DEFAULT_EMAIL_FILENAME, names.first())
    }

    // ------------------------------------------------------------------ writing

    @Test
    fun `each message becomes an archive entry with its content intact`() {
        val (bytes, summary) = archive {
            add("First", blob("h1", "Subject: First\r\n\r\nbody one"), 1_700_000_000_000L)
            add("Second", blob("h2", "Subject: Second\r\n\r\nbody two"), 1_700_000_000_000L)
        }

        assertEquals(ArchiveSummary(written = 2, skipped = 0), summary)
        assertEquals(
            listOf(
                "First.eml" to "Subject: First\r\n\r\nbody one",
                "Second.eml" to "Subject: Second\r\n\r\nbody two"
            ),
            readArchive(bytes)
        )
    }

    @Test
    fun `a missing blob is skipped rather than failing the export`() {
        val (bytes, summary) = archive {
            add("Present", blob("h1", "kept"), 1_700_000_000_000L)
            add("Gone", File(dir, "does-not-exist"), 1_700_000_000_000L)
        }

        assertEquals(ArchiveSummary(written = 1, skipped = 1), summary)
        assertEquals(listOf("Present.eml"), readArchive(bytes).map { it.first })
        assertEquals(2, summary.total)
    }

    @Test
    fun `a directory in place of a blob is skipped`() {
        File(dir, "adirectory").mkdirs()

        val (_, summary) = archive {
            add("Bad", File(dir, "adirectory"), 1_700_000_000_000L)
        }

        assertEquals(ArchiveSummary(written = 0, skipped = 1), summary)
    }

    @Test
    fun `an empty export produces a readable, empty archive`() {
        val (bytes, summary) = archive { }

        assertEquals(ArchiveSummary(0, 0), summary)
        assertTrue(readArchive(bytes).isEmpty())
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun `duplicate subjects produce distinct entries in the archive`() {
        val (bytes, _) = archive {
            add("Budget", blob("h1", "one"), 1_700_000_000_000L)
            add("Budget", blob("h2", "two"), 1_700_000_000_000L)
        }

        assertEquals(
            listOf("Budget.eml" to "one", "Budget-2.eml" to "two"),
            readArchive(bytes)
        )
    }

    @Test
    fun `entry timestamps carry the message date`() {
        val when1980Plus = 1_700_000_000_000L
        val out = ByteArrayOutputStream()
        EmailArchiveWriter(out).use { it.add("Dated", blob("h1", "x"), when1980Plus) }

        val entry = ZipInputStream(out.toByteArray().inputStream()).use { it.nextEntry }

        // Zip stores DOS time at two-second resolution, so allow that slack.
        assertTrue(kotlin.math.abs(entry!!.time - when1980Plus) <= 2_000, "${entry.time}")
    }

    @Test
    fun `a pre-1980 date is left unset rather than wrapped`() {
        // The zip date fields cannot represent it; writing must still succeed.
        val (bytes, summary) = archive {
            add("Ancient", blob("h1", "x"), 0L)
        }

        assertEquals(1, summary.written)
        assertEquals(listOf("Ancient.eml"), readArchive(bytes).map { it.first })
    }

    // ------------------------------------------------------------------ summary

    @Test
    fun `the ordinary outcome reads as a plain success`() {
        assertEquals("Exported 5 emails", exportSummaryMessage(ArchiveSummary(5, 0)))
        assertEquals("Exported 1 email", exportSummaryMessage(ArchiveSummary(1, 0)))
    }

    @Test
    fun `skipped messages are reported only when there are some`() {
        assertEquals(
            "Exported 3 of 4 emails, 1 missing",
            exportSummaryMessage(ArchiveSummary(written = 3, skipped = 1))
        )
    }

    @Test
    fun `an empty cache says so rather than claiming zero exports`() {
        assertEquals("No cached emails to export", exportSummaryMessage(ArchiveSummary(0, 0)))
    }

    @Test
    fun `large content round-trips without being held whole`() {
        // 4 MB is far past the copy buffer, so this exercises streaming.
        val content = "x".repeat(4 * 1024 * 1024)
        val (bytes, summary) = archive {
            add("Big", blob("h1", content), 1_700_000_000_000L)
        }

        assertEquals(1, summary.written)
        val (name, restored) = readArchive(bytes).single()
        assertEquals("Big.eml", name)
        assertEquals(content.length, restored.length)
        // Highly repetitive content must actually be deflated, not stored.
        assertTrue(bytes.size < content.length / 10, "archive was ${bytes.size} bytes")
    }
}
