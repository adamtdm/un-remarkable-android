package com.unremarkable.core.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EntryTest {

    @Test
    fun `parseEntryLine parses schema 4 entry correctly`() {
        val line = "a1b2c3d4:80000000:doc-uuid-1234:5:102400"
        val entry = Entry.parseEntryLine(line)

        assertEquals("a1b2c3d4", entry.hash)
        assertEquals(Entry.DOC_TYPE, entry.type)
        assertEquals("doc-uuid-1234", entry.docId)
        assertEquals(5, entry.subfiles)
        assertEquals(102400L, entry.size)
        assertTrue(entry.isDocument)
        assertFalse(entry.isFile)
        assertEquals(line, entry.toLine())
    }

    @Test
    fun `parseEntryLine parses file entry correctly`() {
        val line = "e5f6g7h8:0:doc-uuid-1234.content:0:512"
        val entry = Entry.parseEntryLine(line)

        assertEquals("e5f6g7h8", entry.hash)
        assertEquals(Entry.FILE_TYPE, entry.type)
        assertEquals("doc-uuid-1234.content", entry.docId)
        assertEquals(0, entry.subfiles)
        assertEquals(512L, entry.size)
        assertFalse(entry.isDocument)
        assertTrue(entry.isFile)
    }

    @Test
    fun `DocumentMetadata parses json correctly`() {
        val json = """
            {
                "visibleName": "Project Architecture",
                "type": "DocumentType",
                "parent": "folder-abc",
                "lastModified": "1730538000000",
                "pinned": true,
                "deleted": false,
                "pageCount": 12,
                "tags": ["Work", "Design"]
            }
        """.trimIndent()

        val meta = DocumentMetadata.fromJson(json)
        assertEquals("Project Architecture", meta.visibleName)
        assertEquals("DocumentType", meta.type)
        assertEquals("folder-abc", meta.parent)
        assertTrue(meta.pinned)
        assertFalse(meta.isFolder)
        assertEquals(12, meta.pageCount)
        assertEquals(listOf("Work", "Design"), meta.tags)
    }
}
