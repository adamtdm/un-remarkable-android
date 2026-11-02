package com.unremarkable.core.converter

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

data class PdfPageImage(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int
)

object PdfImageWriter {

    fun writePdf(pages: List<PdfPageImage>, out: OutputStream) {
        val offsets = mutableListOf<Long>()
        var byteCount = 0L

        fun writeString(s: String) {
            val b = s.toByteArray(StandardCharsets.US_ASCII)
            out.write(b)
            byteCount += b.size
        }

        fun writeBytes(b: ByteArray) {
            out.write(b)
            byteCount += b.size
        }

        // Header
        writeString("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n")

        val numPages = pages.size
        // Object IDs:
        // 1: Catalog
        // 2: Pages
        // For page i (0 until numPages):
        // Page obj: 3 + i * 3
        // Content obj: 4 + i * 3
        // Image obj: 5 + i * 3
        val totalObjects = 2 + numPages * 3

        // 1 0 obj: Catalog
        offsets.add(byteCount)
        writeString("1 0 obj\n<<\n  /Type /Catalog\n  /Pages 2 0 R\n>>\nendobj\n")

        // 2 0 obj: Pages
        offsets.add(byteCount)
        val kids = (0 until numPages).joinToString(" ") { "${3 + it * 3} 0 R" }
        writeString("2 0 obj\n<<\n  /Type /Pages\n  /Kids [ $kids ]\n  /Count $numPages\n>>\nendobj\n")

        // Page objects
        for (i in 0 until numPages) {
            val page = pages[i]
            val pageObjId = 3 + i * 3
            val contentObjId = 4 + i * 3
            val imageObjId = 5 + i * 3

            // Page Object
            offsets.add(byteCount)
            writeString(
                "$pageObjId 0 obj\n<<\n  /Type /Page\n  /Parent 2 0 R\n  /MediaBox [0 0 ${page.width} ${page.height}]\n" +
                        "  /Contents $contentObjId 0 R\n  /Resources <<\n    /ProcSet [/PDF /ImageC]\n" +
                        "    /XObject << /Im1 $imageObjId 0 R >>\n  >>\n>>\nendobj\n"
            )

            // Content Stream Object
            offsets.add(byteCount)
            val streamContent = "q\n${page.width} 0 0 ${page.height} 0 0 cm\n/Im1 Do\nQ\n"
            val streamBytes = streamContent.toByteArray(StandardCharsets.US_ASCII)
            writeString(
                "$contentObjId 0 obj\n<<\n  /Length ${streamBytes.size}\n>>\nstream\n"
            )
            writeBytes(streamBytes)
            writeString("endstream\nendobj\n")

            // Image Object
            offsets.add(byteCount)
            writeString(
                "$imageObjId 0 obj\n<<\n  /Type /XObject\n  /Subtype /Image\n  /Width ${page.width}\n" +
                        "  /Height ${page.height}\n  /ColorSpace /DeviceRGB\n  /BitsPerComponent 8\n" +
                        "  /Filter /DCTDecode\n  /Length ${page.jpegBytes.size}\n>>\nstream\n"
            )
            writeBytes(page.jpegBytes)
            writeString("\nendstream\nendobj\n")
        }

        // Cross-Reference Table
        val startXref = byteCount
        writeString("xref\n0 ${totalObjects + 1}\n0000000000 65535 f \n")
        for (offset in offsets) {
            writeString(String.format(java.util.Locale.US, "%010d 00000 n \n", offset))
        }

        // Trailer
        writeString(
            "trailer\n<<\n  /Size ${totalObjects + 1}\n  /Root 1 0 R\n>>\nstartxref\n$startXref\n%%EOF\n"
        )
    }

    fun toByteArray(pages: List<PdfPageImage>): ByteArray {
        val baos = ByteArrayOutputStream()
        writePdf(pages, baos)
        return baos.toByteArray()
    }
}
