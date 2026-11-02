package com.unremarkable.android.render

import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.model.PageRef
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidDocumentOptimizerTest {

    @Test
    fun `naturalCompare orders multi-digit comic and chapter filenames numerically`() {
        val raw = listOf(
            "Vol1/page_10.jpg",
            "Vol1/page_2.jpg",
            "Vol1/page_1.jpg",
            "Vol1/page_020.jpg",
            "Vol1/page_009.jpg"
        )
        val sorted = raw.sortedWith { a, b -> AndroidDocumentOptimizer.naturalCompare(a, b) }
        assertEquals(
            listOf(
                "Vol1/page_1.jpg",
                "Vol1/page_2.jpg",
                "Vol1/page_009.jpg",
                "Vol1/page_10.jpg",
                "Vol1/page_020.jpg"
            ),
            sorted
        )
    }

    @Test
    fun `extractEpubPages strips markup and orders chapters naturally`() {
        val tmp = File.createTempFile("sample_book_", ".epub")
        try {
            ZipOutputStream(tmp.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("OEBPS/toc.xhtml"))
                zos.write("<html><body><p>Skip TOC</p></body></html>".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("OEBPS/ch10.xhtml"))
                zos.write("<html><head><title>Ignored</title></head><body><h1>Chapter Ten</h1><p>Tenth body.</p></body></html>".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("OEBPS/ch2.xhtml"))
                zos.write("<html><body><h1>Chapter Two</h1><p>Second &amp; clean.</p></body></html>".toByteArray())
                zos.closeEntry()
            }

            val pages = RmPageCanvasRenderer.extractEpubPages(tmp)
            assertTrue(pages.isNotEmpty())
            val joined = pages.flatten().joinToString("\n")
            assertTrue(!joined.contains("Skip TOC"))
            assertTrue(!joined.contains("Ignored"))
            val idxTwo = joined.indexOf("Chapter Two")
            val idxTen = joined.indexOf("Chapter Ten")
            assertTrue(idxTwo in 0 until idxTen, "Expected ch2 before ch10, got:\n$joined")
            assertTrue(joined.contains("Second & clean."))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `resolvePdfPageIndex handles both omitted redir and inserted blank note pages`() {
        val unopenedPdf = DocumentItem(
            id = "doc-1",
            visibleName = "Unopened",
            pages = listOf(PageRef("p0", -1), PageRef("p1", -1))
        )
        assertEquals(0, DocumentExporter.resolvePdfPageIndex(unopenedPdf, 0))
        assertEquals(1, DocumentExporter.resolvePdfPageIndex(unopenedPdf, 1))

        val annotatedWithInsertedNote = DocumentItem(
            id = "doc-2",
            visibleName = "Inserted",
            pages = listOf(PageRef("p0", 0), PageRef("inserted-note", -1), PageRef("p1", 1))
        )
        assertEquals(0, DocumentExporter.resolvePdfPageIndex(annotatedWithInsertedNote, 0))
        assertEquals(-1, DocumentExporter.resolvePdfPageIndex(annotatedWithInsertedNote, 1))
        assertEquals(1, DocumentExporter.resolvePdfPageIndex(annotatedWithInsertedNote, 2))
    }
}
