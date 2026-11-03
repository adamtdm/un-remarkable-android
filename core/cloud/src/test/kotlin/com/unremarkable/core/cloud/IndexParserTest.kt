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

    @Test
    fun `buildDocSchema reproduces a real tablet-written v4 docSchema byte for byte`() {
        // Captured verbatim from reMarkable cloud (blob 6bfaa500...), written by the tablet
        val docId = "33d04401-0873-42c9-b286-01c20fe06ead"
        val real = "4\n" +
            "0:$docId:4:48222937\n" +
            "66c021a175ad494e59eb1aa17ad5bb24902cfc73db5900baabb30c9f8bf30476:0:$docId.content:0:25465\n" +
            "46a63ff85934ddd8d6b59edb07f0666e60de25878f8b7522cb09d6983d45450e:0:$docId.metadata:0:406\n" +
            "133f979d38aef870be0e266a3478a1dc1dbd5851309b2ff3877e8d81c14e3f61:0:$docId.pagedata:0:2160\n" +
            "310f4104709aa8d7c7076257a96dae4e86697e592f2435df262b54d32fbea78b:0:$docId.pdf:0:48194906\n"
        val expectedHash = "6bfaa500ee1fe8ce6ff9608c2fa58bc0217aec79335252526d635581dc5ca4b7"
        assertEquals(expectedHash, IndexParser.computeRootHash(real.toByteArray(Charsets.UTF_8)))

        val (entries, schema) = IndexParser.parseIndex(real)
        val (bytes, hash) = IndexParser.buildDocSchema(entries, schema, docId)

        assertEquals(real, String(bytes, Charsets.UTF_8))
        assertEquals(expectedHash, hash)
        // The legacy schema-3 hash-of-hashes does NOT match: that was the Android sync bug
        assertNotEquals(expectedHash, IndexParser.computeDocIndexHash(entries))
    }
}
