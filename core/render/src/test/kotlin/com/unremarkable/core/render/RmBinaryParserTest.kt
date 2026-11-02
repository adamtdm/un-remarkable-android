package com.unremarkable.core.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class RmBinaryParserTest {

    @Test
    fun `parses legacy v5 rm file with strokes`() {
        val out = ByteArrayOutputStream()
        out.write("reMarkable .lines file, version=5          ".toByteArray(StandardCharsets.US_ASCII))

        val body = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(1) // 1 layer
        body.putInt(1) // 1 stroke
        body.putInt(17) // FINELINER_2
        body.putInt(6) // BLUE
        body.putInt(0) // unk1
        body.putFloat(2.0f) // width
        body.putInt(0) // unk2 (v5)
        body.putInt(2) // 2 points
        // Point 1
        body.putFloat(120f); body.putFloat(240f); body.putFloat(1f); body.putFloat(0f); body.putFloat(2f); body.putFloat(0.8f)
        // Point 2
        body.putFloat(300f); body.putFloat(420f); body.putFloat(1f); body.putFloat(0f); body.putFloat(2f); body.putFloat(0.9f)

        out.write(body.array(), 0, body.position())

        val page = RmBinaryParser.parse(out.toByteArray())
        assertNotNull(page)
        assertFalse(page!!.centerOriginX)
        assertEquals(1, page.layers.size)
        assertEquals(1, page.layers[0].strokes.size)
        val stroke = page.layers[0].strokes[0]
        assertEquals(ToolType.FINELINER, stroke.tool)
        assertEquals(PenColor.BLUE, stroke.color)
        assertEquals(2, stroke.points.size)
        assertEquals(120f, stroke.points[0].x, 0.01f)
        assertEquals(420f, stroke.points[1].y, 0.01f)
    }

    @Test
    fun `parses v6 CRDT scene with stroke explicit RGBA and typed text`() {
        // Real v6 binary fixture generated from rmscene specification:
        // Contains SceneInfo (1620x2160), SceneTreeBlock, TreeNodeBlock, SceneLineItemBlock v2 with explicit BGRA, and RootTextBlock
        val out = ByteArrayOutputStream()
        out.write("reMarkable .lines file, version=6          ".toByteArray(StandardCharsets.US_ASCII))

        fun varuint(v: Long): ByteArray {
            val b = ByteArrayOutputStream()
            var cur = v
            while (true) {
                val chunk = (cur and 0x7F).toInt()
                cur = cur ushr 7
                if (cur != 0L) b.write(chunk or 0x80) else { b.write(chunk); break }
            }
            return b.toByteArray()
        }
        fun tag(index: Int, type: Int): ByteArray = varuint(((index shl 4) or type).toLong())
        fun u32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        fun f32(v: Float): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()
        fun f64(v: Double): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array()
        fun crdtId(index: Int, p1: Int, p2: Long): ByteArray =
            tag(index, 0xF) + byteArrayOf(p1.toByte()) + varuint(p2)
        fun subblock(index: Int, payload: ByteArray): ByteArray =
            tag(index, 0xC) + u32(payload.size) + payload
        fun mainBlock(type: Int, curVer: Int, payload: ByteArray): ByteArray =
            u32(payload.size) + byteArrayOf(0, 1, curVer.toByte(), type.toByte()) + payload

        // 1. SceneInfo block (0x0D) with paper_size = 1620 x 2160
        val sceneInfoPayload = subblock(5, u32(1620) + u32(2160))
        out.write(mainBlock(0x0D, 1, sceneInfoPayload))

        // 2. SceneLineItemBlock (0x05, v2) under root (0, 1)
        val ptBytes = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(-200f); putFloat(400f); putShort(10); putShort(8); put(0); put(200.toByte())
            putFloat(250f); putFloat(550f); putShort(10); putShort(8); put(0); put(220.toByte())
        }.array()
        val lineBody = ByteArrayOutputStream().apply {
            write(0x03) // ITEM_TYPE line
            write(tag(1, 0x4)); write(u32(15)) // BALLPOINT_2
            write(tag(2, 0x4)); write(u32(9))  // HIGHLIGHT
            write(tag(3, 0x8)); write(f64(2.0)) // thickness_scale
            write(tag(4, 0x4)); write(f32(0f))  // starting_length
            write(subblock(5, ptBytes))
            write(crdtId(6, 0, 1)) // timestamp
            // Tag 8 explicit BGRA color: R=37, G=99, B=235, A=255 -> packed (255<<24)|(37<<16)|(99<<8)|235
            val packedBgra = (255 shl 24) or (37 shl 16) or (99 shl 8) or 235
            write(tag(8, 0x4)); write(u32(packedBgra))
        }.toByteArray()

        val lineBlockPayload =
            crdtId(1, 0, 1) + // parentId = root
            crdtId(2, 1, 10) + // itemId
            crdtId(3, 0, 0) + // leftId
            crdtId(4, 0, 0) + // rightId
            tag(5, 0x4) + u32(0) + // deletedLength = 0
            subblock(6, lineBody)
        out.write(mainBlock(0x05, 2, lineBlockPayload))

        // 3. RootTextBlock (0x07) with "# Agenda\nShip sync fix"
        val textStr = "Agenda\nShip sync fix".toByteArray(StandardCharsets.UTF_8)
        val strSub = varuint(textStr.size.toLong()) + byteArrayOf(1) + textStr
        val textItem0 = subblock(
            0,
            crdtId(2, 1, 16) + crdtId(3, 0, 0) + crdtId(4, 0, 0) + tag(5, 0x4) + u32(0) + subblock(6, strSub)
        )
        val textItemsSection = subblock(1, subblock(1, varuint(1) + textItem0))
        val fmtEntry = byteArrayOf(0) + varuint(0) + crdtId(1, 1, 15) + subblock(2, byteArrayOf(17, 2)) // HEADING (2)
        val formatsSection = subblock(2, subblock(1, varuint(1) + fmtEntry))
        val rootTextPayload =
            crdtId(1, 0, 0) +
            subblock(2, textItemsSection + formatsSection) +
            subblock(3, f64(-468.0) + f64(234.0)) +
            tag(4, 0x4) + f32(936f)
        out.write(mainBlock(0x07, 1, rootTextPayload))

        val page = RmBinaryParser.parse(out.toByteArray())
        assertNotNull(page)
        assertTrue(page!!.centerOriginX)
        assertEquals(1620, page.width)
        assertEquals(2160, page.height)
        assertEquals(1, page.layers.size)
        val stroke = page.layers[0].strokes[0]
        assertEquals(ToolType.BALLPOINT, stroke.tool)
        assertEquals(37, stroke.explicitColor?.r)
        assertEquals(99, stroke.explicitColor?.g)
        assertEquals(235, stroke.explicitColor?.b)
        assertEquals(-200f, stroke.points[0].x, 0.01f)
        assertEquals(2, page.typedParagraphs.size)
        assertEquals("Agenda", page.typedParagraphs[0].text)
        assertEquals(ParagraphStyle.HEADING, page.typedParagraphs[0].style)
        assertEquals("Ship sync fix", page.typedParagraphs[1].text)
    }
}
