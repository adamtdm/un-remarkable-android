package com.unremarkable.core.cloud

import com.unremarkable.core.model.Entry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IndexParserTest {

    @Test
    fun `parse and format schema 4 index`() {
        val raw = """
            4
            0:.:2:1500
            hash1:80000000:doc-a:1:1000
            hash2:0:doc-b.content:0:500
        """.trimIndent()

        val (entries, schema) = IndexParser.parseIndex(raw)
        assertEquals(Entry.SCHEMA_V4, schema)
        assertEquals(2, entries.size)

        assertEquals("hash1", entries[0].hash)
        assertEquals("doc-a", entries[0].docId)
        assertTrue(entries[0].isDocument)

        assertEquals("hash2", entries[1].hash)
        assertEquals("doc-b.content", entries[1].docId)
        assertTrue(entries[1].isFile)

        val formatted = IndexParser.formatIndex(entries, Entry.SCHEMA_V4)
        val (reparsed, schema2) = IndexParser.parseIndex(formatted)

        assertEquals(schema, schema2)
        assertEquals(entries, reparsed)
    }

    @Test
    fun `parse schema 3 index`() {
        val raw = """
            3
            hash1:80000000:doc-a:1:1000
            hash2:0:doc-b.content:0:500
        """.trimIndent()

        val (entries, schema) = IndexParser.parseIndex(raw)
        assertEquals(Entry.SCHEMA_V3, schema)
        assertEquals(2, entries.size)
    }

    @Test
    fun `empty index throws exception`() {
        assertThrows(IllegalArgumentException::class.java) {
            IndexParser.parseIndex("")
        }
    }

    @Test
    fun `computeDocIndexHash is deterministic regardless of entry order`() {
        val h1 = "a".repeat(64)
        val h2 = "b".repeat(64)
        val e1 = Entry(hash = h1, type = Entry.FILE_TYPE, docId = "doc.content", subfiles = 0, size = 100)
        val e2 = Entry(hash = h2, type = Entry.FILE_TYPE, docId = "doc.metadata", subfiles = 0, size = 80)

        val hashForward = IndexParser.computeDocIndexHash(listOf(e1, e2))
        val hashReverse = IndexParser.computeDocIndexHash(listOf(e2, e1))

        assertEquals(64, hashForward.length)
        assertEquals(hashForward, hashReverse)
    }

    @Test
    fun `computeRootHash returns 64-char lowercase sha256`() {
        val digest = IndexParser.computeRootHash("3\n".toByteArray(Charsets.UTF_8))
        assertEquals(64, digest.length)
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
