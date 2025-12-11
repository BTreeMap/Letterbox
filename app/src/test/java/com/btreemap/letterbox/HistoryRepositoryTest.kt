package com.btreemap.letterbox

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class HistoryRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var repository: HistoryRepository

    @Before
    fun setUp() {
        tempDir = createTempDir(prefix = "letterbox-test")
        repository = HistoryRepository(tempDir, historyLimit = 2)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `deduplicates blobs and maintains ref counts`() {
        val bytes = "Subject: hello".toByteArray()
        val first = repository.ingest(bytes, "first", null)
        val second = repository.ingest(bytes, "second", null)

        val blobMeta = repository.blobMeta(first.blobHash)
        assertNotNull(blobMeta)
        assertEquals(2, blobMeta.refCount)
        assertEquals(first.blobHash, second.blobHash)
        assertTrue(repository.blobFor(first.blobHash)?.exists() == true)
    }

    @Test
    fun `evicts least recently used items when over limit`() {
        val first = repository.ingest("One".toByteArray(), "one", null)
        val second = repository.ingest("Two".toByteArray(), "two", null)
        val third = repository.ingest("Three".toByteArray(), "three", null)

        val items = repository.items.value
        assertEquals(2, items.size)
        assertTrue(items.any { it.displayName == "three" })
        assertTrue(items.any { it.displayName == "two" })
        assertFalse(items.any { it.displayName == "one" })

        assertEquals(null, repository.blobMeta(first.blobHash))
    }
}
