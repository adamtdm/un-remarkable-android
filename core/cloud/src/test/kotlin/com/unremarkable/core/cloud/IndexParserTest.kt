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
}
