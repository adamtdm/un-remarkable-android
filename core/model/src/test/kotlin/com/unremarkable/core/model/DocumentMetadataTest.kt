package com.unremarkable.core.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DocumentMetadataTest {

    @Test
    fun `parseFromRaw reads lastOpenedPage from metadataJson`() {
        val metaJson = """
            {
                "visibleName": "Book One",
                "type": "DocumentType",
                "parent": "",
                "lastModified": "1730000000000",
                "lastOpened": "1730005000000",
                "lastOpenedPage": 4,
                "pinned": true,
                "deleted": false
            }
        """.trimIndent()

        val parsed = DocumentMetadata.parseFromRaw(metaJson, null)
        assertEquals("Book One", parsed.visibleName)
        assertEquals(4, parsed.lastOpenedPage)
        assertEquals("1730005000000", parsed.lastOpened)
    }

    @Test
    fun `parseFromRaw falls back to contentJson for lastOpenedPage`() {
        val contentJson = """
            {
                "visibleName": "Fallback Doc",
                "lastOpenedPage": 7,
                "pageCount": 10
            }
        """.trimIndent()

        val parsed = DocumentMetadata.parseFromRaw(null, contentJson)
        assertEquals(7, parsed.lastOpenedPage)
        assertEquals(10, parsed.pageCount)
    }

    @Test
    fun `parseFromRaw resolves lastOpened pageId from cPages`() {
        val contentJson = """
            {
                "visibleName": "Modern Notebook",
                "cPages": {
                    "lastOpened": {
                        "value": "page-uuid-3",
                        "timestamp": "1:3"
                    },
                    "pages": [
                        {"id": "page-uuid-1", "idx": {"value": "aa"}},
                        {"id": "page-uuid-2", "idx": {"value": "ab"}},
                        {"id": "page-uuid-3", "idx": {"value": "ac"}}
                    ]
                }
            }
        """.trimIndent()

        val parsed = DocumentMetadata.parseFromRaw(null, contentJson)
        assertEquals(3, parsed.pageCount)
        assertEquals(2, parsed.lastOpenedPage) // 0-indexed page for page-uuid-3
    }

    @Test
    fun `DocumentItem retains and updates lastOpenedPage`() {
        val doc = DocumentItem(
            id = "doc-123",
            visibleName = "Novel",
            pageCount = 100,
            lastOpenedPage = 15
        )
        assertEquals(15, doc.lastOpenedPage)

        val updated = doc.copy(lastOpenedPage = 20)
        assertEquals(20, updated.lastOpenedPage)
    }

    @Test
    fun `InMemorySyncRepository tracks pending page syncs`() {
        val repo = InMemorySyncRepository()
        val doc = DocumentItem(id = "doc-1", visibleName = "Test", lastOpenedPage = 0, localOnly = false)
        repo.upsertDocument(doc)

        assertEquals(0, repo.getPendingPageSyncDocIds().size)

        repo.updateLastOpenedPage("doc-1", 5)
        assertEquals(5, repo.getDocument("doc-1")?.lastOpenedPage)
        assertTrue(repo.getPendingPageSyncDocIds().contains("doc-1"))

        repo.clearPageSyncPending("doc-1")
        assertFalse(repo.getPendingPageSyncDocIds().contains("doc-1"))
    }
}
