package org.joefang.letterbox

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests for the file-system half of the blob store.
 *
 * These run against a real temporary directory: no Room, no Robolectric, no
 * device. Reconciling `cas/` against the database is the only thing standing
 * between a schema bump and a permanently stranded cache, so it is worth pinning
 * precisely.
 */
class BlobFilesTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = createTempDirectory(prefix = "letterbox-blobs").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun blob(name: String, size: Int): File =
        File(dir, name).apply { writeBytes(ByteArray(size)) }

    @Test
    fun `keeps every file the database knows about`() {
        blob("aaa", 10)
        blob("bbb", 20)

        val reclaimed = reclaimUnknownFiles(dir, setOf("aaa", "bbb"))

        assertEquals(0L, reclaimed)
        assertTrue(File(dir, "aaa").exists())
        assertTrue(File(dir, "bbb").exists())
    }

    @Test
    fun `deletes unknown files and reports the bytes freed`() {
        blob("known", 10)
        blob("orphan1", 100)
        blob("orphan2", 250)

        val reclaimed = reclaimUnknownFiles(dir, setOf("known"))

        assertEquals(350L, reclaimed)
        assertTrue(File(dir, "known").exists())
        assertFalse(File(dir, "orphan1").exists())
        assertFalse(File(dir, "orphan2").exists())
    }

    @Test
    fun `an empty known-set reclaims everything`() {
        // What a destructive migration leaves behind: files, no rows.
        blob("a", 1)
        blob("b", 2)
        blob("c", 3)

        assertEquals(6L, reclaimUnknownFiles(dir, emptySet()))
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test
    fun `an empty directory reclaims nothing`() {
        assertEquals(0L, reclaimUnknownFiles(dir, setOf("aaa")))
    }

    @Test
    fun `a missing directory reclaims nothing rather than failing`() {
        val missing = File(dir, "does-not-exist")

        assertEquals(0L, reclaimUnknownFiles(missing, emptySet()))
    }

    @Test
    fun `sub-directories are left alone`() {
        // The store is flat, so a directory was not created by it.
        File(dir, "nested").mkdirs()
        blob("orphan", 42)

        assertEquals(42L, reclaimUnknownFiles(dir, emptySet()))
        assertTrue(File(dir, "nested").isDirectory)
    }

    @Test
    fun `reclaiming is idempotent`() {
        blob("known", 10)
        blob("orphan", 90)

        assertEquals(90L, reclaimUnknownFiles(dir, setOf("known")))
        assertEquals(0L, reclaimUnknownFiles(dir, setOf("known")))
        assertTrue(File(dir, "known").exists())
    }

    @Test
    fun `zero-length orphans are removed and contribute nothing`() {
        blob("empty", 0)

        assertEquals(0L, reclaimUnknownFiles(dir, emptySet()))
        assertFalse(File(dir, "empty").exists())
    }
}
