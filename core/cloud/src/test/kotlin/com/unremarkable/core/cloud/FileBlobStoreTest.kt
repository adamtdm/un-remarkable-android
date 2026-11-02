package com.unremarkable.core.cloud

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileBlobStoreTest {

    @Test
    fun `put and get blob with sharding`(@TempDir tempDir: File) {
        val store = FileBlobStore(tempDir)
        val testData = "Blob Content 12345".toByteArray()
        val hash = "a1b2c3d4e5f6"

        assertFalse(store.hasBlob(hash))
        assertNull(store.getBlob(hash))

        val writtenFile = store.putBlob(hash, testData)
        assertTrue(writtenFile.exists())
        assertEquals("a1", writtenFile.parentFile.name)
        assertTrue(store.hasBlob(hash))

        val retrieved = store.getBlob(hash)
        assertNotNull(retrieved)
        assertArrayEquals(testData, retrieved)

        assertEquals(1, store.blobCount())
        assertEquals(testData.size.toLong(), store.totalSizeBytes())
    }

    @Test
    fun `sha256 matches expected digest`() {
        val data = "hello world".toByteArray()
        val hash = FileBlobStore.sha256(data)
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash)
    }
}
