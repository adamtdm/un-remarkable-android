package com.unremarkable.android.storage

import com.unremarkable.core.model.DocumentItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PersistentDocumentRepositoryTest {

    @Test
    fun `test persistence to disk and reloading across instances`(@TempDir tempDir: File) {
        val docFile = File(tempDir, "library_documents.json")
        val repo1 = PersistentDocumentRepository(docFile = docFile, prefs = null)

        val doc1 = DocumentItem(
            id = "doc-1",
            visibleName = "Sprint Architecture",
            parent = "",
            pageCount = 4,
            pinned = true,
            tags = listOf("Sprint", "reMarkable")
        )
        val doc2 = DocumentItem(
            id = "doc-2",
            visibleName = "Daily Notes",
            parent = "",
            pageCount = 1,
            pinned = false,
            tags = listOf("Note")
        )

        repo1.upsertDocument(doc1)
        repo1.upsertDocument(doc2)

        assertEquals(2, repo1.getAllDocuments().size)
        assertTrue(docFile.exists())

        // Create new instance pointing to same file
        val repo2 = PersistentDocumentRepository(docFile = docFile, prefs = null)
        val loaded = repo2.getAllDocuments()
        assertEquals(2, loaded.size)
        assertEquals("doc-1", loaded[0].id)
        assertTrue(loaded[0].pinned)
        assertEquals("doc-2", loaded[1].id)

        // Mark deleted
        repo2.markDocumentDeleted("doc-2")
        assertEquals(1, repo2.getAllDocuments().size)

        // Reload to verify deleted state persisted
        val repo3 = PersistentDocumentRepository(docFile = docFile, prefs = null)
        assertEquals(1, repo3.getAllDocuments().size)
        assertNull(repo3.getDocument("doc-2"))
    }

    @Test
    fun `test atomic write cleans up tmp file on success`(@TempDir tempDir: File) {
        val docFile = File(tempDir, "library_documents.json")
        val repo = PersistentDocumentRepository(docFile = docFile, prefs = null)

        repo.upsertDocument(DocumentItem(id = "test-doc", visibleName = "Test"))
        val tmpFile = File(tempDir, "library_documents.json.tmp")
        assertFalse(tmpFile.exists(), "Temporary file should be cleanly moved/deleted")
        assertTrue(docFile.exists(), "Target file must exist")
    }

    @Test
    fun `test updateLastOpenedPage persists page position across instances`(@TempDir tempDir: File) {
        val docFile = File(tempDir, "library_documents.json")
        val repo1 = PersistentDocumentRepository(docFile = docFile, prefs = null)

        val doc = DocumentItem(
            id = "book-42",
            visibleName = "Dune",
            pageCount = 600,
            lastOpenedPage = 0
        )
        repo1.upsertDocument(doc)
        assertEquals(0, repo1.getDocument("book-42")?.lastOpenedPage)

        repo1.updateLastOpenedPage("book-42", 42)
        assertEquals(42, repo1.getDocument("book-42")?.lastOpenedPage)

        val repo2 = PersistentDocumentRepository(docFile = docFile, prefs = null)
        val loadedDoc = repo2.getDocument("book-42")
        assertNotNull(loadedDoc)
        assertEquals(42, loadedDoc?.lastOpenedPage)
    }
}
