package com.unremarkable.core.converter

import com.unremarkable.core.model.DeviceProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class ComicConverterEngineTest {

    @Test
    fun `writePdf produces compliant PDF bytes`() {
        val testJpeg = ByteArray(128) { (it % 256).toByte() }
        val pages = listOf(
            PdfPageImage(testJpeg, 1404, 1872),
            PdfPageImage(testJpeg, 1404, 1872)
        )

        val pdfBytes = PdfImageWriter.toByteArray(pages)
        val text = String(pdfBytes, Charsets.US_ASCII)

        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.contains("/Count 2"))
        assertTrue(text.contains("/MediaBox [0 0 1404 1872]"))
        assertTrue(text.contains("/Filter /DCTDecode"))
        assertTrue(text.endsWith("%%EOF\n"))
    }

    @Test
    fun `convertCbz splits double spread and downscales to target`(@TempDir tempDir: File) {
        val cbzFile = File(tempDir, "sample_comic.cbz")

        // Create sample images: 1 portrait (800x1200) and 1 landscape spread (2400x1200)
        val portrait = BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB)
        val g1 = portrait.createGraphics()
        g1.color = Color.BLUE
        g1.fillRect(0, 0, 800, 1200)
        g1.dispose()

        val spread = BufferedImage(2400, 1200, BufferedImage.TYPE_INT_RGB)
        val g2 = spread.createGraphics()
        g2.color = Color.RED
        g2.fillRect(0, 0, 1200, 1200)
        g2.color = Color.GREEN
        g2.fillRect(1200, 0, 1200, 1200)
        g2.dispose()

        // Pack into CBZ
        ZipOutputStream(FileOutputStream(cbzFile)).use { zos ->
            zos.putNextEntry(ZipEntry("001.jpg"))
            ImageIO.write(portrait, "jpg", zos)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("002_spread.jpg"))
            ImageIO.write(spread, "jpg", zos)
            zos.closeEntry()
        }

        val engine = ComicConverterEngine()
        val config = ConverterConfig(
            targetProfile = DeviceProfile.PAPER_PRO, // 2160x1620
            qualityStop = 3,
            splitSpreads = true,
            mangaOrder = false
        )

        val pdfBytes = engine.convertCbz(cbzFile, config)
        assertNotNull(pdfBytes)
        assertTrue(pdfBytes.isNotEmpty())

        val text = String(pdfBytes, Charsets.US_ASCII)
        // 1 portrait + 1 split spread = 3 total pages
        assertTrue(text.contains("/Count 3"), "Expected 3 pages after spread split, but PDF was: $text")
        assertTrue(text.endsWith("%%EOF\n"))
    }
}
