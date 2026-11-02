package com.unremarkable.android.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets

class PdfOutlineParserTest {

    @Test
    fun `test fallback landmark generation when outline is missing`(@TempDir tempDir: File) {
        val dummyFile = File(tempDir, "sample.pdf")
        dummyFile.writeText("%PDF-1.4\nsome content", StandardCharsets.ISO_8859_1)

        val toc = PdfOutlineParser.extractToc(dummyFile, totalPages = 35)

        assertTrue(toc.isNotEmpty())
        assertEquals("Beginning", toc[0].title)
        assertEquals(1, toc[0].pageNumber)
        assertEquals("Page 10", toc[1].title)
        assertEquals(10, toc[1].pageNumber)
        assertEquals("Page 20", toc[2].title)
        assertEquals(20, toc[2].pageNumber)
        assertEquals("Page 30", toc[3].title)
        assertEquals(30, toc[3].pageNumber)
        assertEquals("End (Page 35)", toc[4].title)
        assertEquals(35, toc[4].pageNumber)
    }

    @Test
    fun `test outline parsing from mock pdf stream`(@TempDir tempDir: File) {
        val pdfFile = File(tempDir, "book_with_outlines.pdf")
        val pdfContent = """
            %PDF-1.4
            1 0 obj
            << /Type /Outlines /First 2 0 R /Last 3 0 R >>
            endobj
            2 0 obj
            << /Title (Chapter 1 - Introduction) /Dest [0 /XYZ 0 0 0] >>
            endobj
            3 0 obj
            << /Title (Chapter 2 - Advanced Inking) /Dest [9 /XYZ 0 0 0] >>
            endobj
            trailer
            << /Root 1 0 R >>
            %%EOF
        """.trimIndent()
        pdfFile.writeText(pdfContent, StandardCharsets.ISO_8859_1)

        val toc = PdfOutlineParser.extractToc(pdfFile, totalPages = 20)

        assertEquals(2, toc.size)
        assertEquals("Chapter 1 - Introduction", toc[0].title)
        assertEquals(1, toc[0].pageNumber)
        assertEquals("Chapter 2 - Advanced Inking", toc[1].title)
        assertEquals(10, toc[1].pageNumber)
    }
}
