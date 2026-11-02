package com.unremarkable.android.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.unremarkable.core.converter.ConversionProgress
import com.unremarkable.core.converter.ConverterConfig
import com.unremarkable.core.converter.PdfImageWriter
import com.unremarkable.core.converter.PdfPageImage
import com.unremarkable.core.converter.QualityProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Native Android document/comic optimizer & Quick Capture PDF generator.
 * Replaces desktop-only `java.awt.BufferedImage` with Android `Bitmap` + `PdfRenderer`.
 */
object AndroidDocumentOptimizer {

    data class OptimizedResult(
        val pdfBytes: ByteArray,
        val pageCount: Int
    )

    suspend fun optimizeUri(
        context: Context,
        uri: Uri,
        fileName: String,
        config: ConverterConfig,
        onProgress: (ConversionProgress) -> Unit = {}
    ): OptimizedResult = withContext(Dispatchers.Default) {
        val tmpDir = File(context.cacheDir, "optimizer_tmp").apply { mkdirs() }
        val ext = fileName.substringAfterLast('.', "cbz").lowercase(Locale.US)
        val tempInput = File(tmpDir, "input_${System.currentTimeMillis()}.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempInput).use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("Unable to read selected file")

            optimizeFile(tempInput, fileName, config, onProgress)
        } finally {
            tempInput.delete()
        }
    }

    suspend fun optimizeFile(
        inputFile: File,
        fileName: String,
        config: ConverterConfig,
        onProgress: (ConversionProgress) -> Unit = {}
    ): OptimizedResult = withContext(Dispatchers.Default) {
        val quality = QualityProfiles.getProfile(config.qualityStop)
        val maxW = quality.maxWidth.coerceAtMost(config.targetProfile.width)
        val maxH = quality.maxHeight.coerceAtMost(config.targetProfile.height)
        val jpegQualityInt = (quality.jpegQuality * 100f).toInt().coerceIn(30, 96)

        val lower = fileName.lowercase(Locale.US)
        val pages = mutableListOf<PdfPageImage>()

        if (lower.endsWith(".pdf") || isPdfHeader(inputFile)) {
            val renderer = PdfDocumentRenderer.open(inputFile)
                ?: throw IllegalArgumentException("Could not open PDF document")
            renderer.use { pdf ->
                val total = pdf.pageCount.coerceAtLeast(1)
                for (i in 0 until total) {
                    onProgress(ConversionProgress(i + 1, total, "Optimizing page ${i + 1} of $total..."))
                    // Render high enough to split landscape spreads cleanly
                    val renderW = if (config.splitSpreads) maxW * 2 else maxW
                    val bmp = pdf.renderPage(i, renderW, maxH)
                    appendProcessedBitmap(bmp, config, maxW, maxH, jpegQualityInt, pages)
                    bmp.recycle()
                }
            }
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")) {
            onProgress(ConversionProgress(1, 1, "Optimizing image..."))
            val bmp = decodeSampledBitmap(inputFile.readBytes(), maxW * 2, maxH * 2)
                ?: throw IllegalArgumentException("Unsupported image format")
            appendProcessedBitmap(bmp, config, maxW, maxH, jpegQualityInt, pages)
            bmp.recycle()
        } else {
            // CBZ / ZIP comic archive
            val imageExts = setOf("jpg", "jpeg", "png", "webp", "bmp")
            ZipFile(inputFile).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { e ->
                        !e.isDirectory &&
                            !e.name.contains("__MACOSX") &&
                            e.name.substringAfterLast('.', "").lowercase(Locale.US) in imageExts
                    }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                    .toList()

                if (entries.isEmpty()) {
                    throw IllegalArgumentException("No supported comic pages found in archive")
                }

                val total = entries.size
                for ((idx, entry) in entries.withIndex()) {
                    onProgress(ConversionProgress(idx + 1, total, "Optimizing page ${idx + 1} of $total..."))
                    val raw = zip.getInputStream(entry).use { it.readBytes() }
                    val bmp = decodeSampledBitmap(raw, maxW * 2, maxH * 2) ?: continue
                    appendProcessedBitmap(bmp, config, maxW, maxH, jpegQualityInt, pages)
                    bmp.recycle()
                }
            }
        }

        if (pages.isEmpty()) {
            throw IllegalStateException("Conversion produced zero pages")
        }

        OptimizedResult(
            pdfBytes = PdfImageWriter.toByteArray(pages),
            pageCount = pages.size
        )
    }

    private fun appendProcessedBitmap(
        source: Bitmap,
        config: ConverterConfig,
        maxW: Int,
        maxH: Int,
        jpegQuality: Int,
        out: MutableList<PdfPageImage>
    ) {
        if (config.splitSpreads && source.width > source.height * 1.15f) {
            val halfW = source.width / 2
            val left = Bitmap.createBitmap(source, 0, 0, halfW, source.height)
            val right = Bitmap.createBitmap(source, halfW, 0, source.width - halfW, source.height)
            val ordered = if (config.mangaOrder) listOf(right, left) else listOf(left, right)
            for (half in ordered) {
                out.add(scaleAndCompress(half, maxW, maxH, jpegQuality))
                if (half !== source) half.recycle()
            }
        } else {
            out.add(scaleAndCompress(source, maxW, maxH, jpegQuality))
        }
    }

    private fun scaleAndCompress(src: Bitmap, maxW: Int, maxH: Int, jpegQuality: Int): PdfPageImage {
        val scale = minOf(maxW.toFloat() / src.width, maxH.toFloat() / src.height, 1.0f)
        val dstW = (src.width * scale).toInt().coerceAtLeast(1)
        val dstH = (src.height * scale).toInt().coerceAtLeast(1)

        // Composite onto white RGB canvas so transparent PNGs never turn black in JPEG
        val target = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.RGB_565)
        val canvas = Canvas(target)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            src,
            null,
            android.graphics.Rect(0, 0, dstW, dstH),
            paint
        )

        val baos = ByteArrayOutputStream()
        target.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
        target.recycle()
        return PdfPageImage(baos.toByteArray(), dstW, dstH)
    }

    private fun decodeSampledBitmap(bytes: ByteArray, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun isPdfHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 5) return false
        return try {
            val head = ByteArray(5)
            file.inputStream().use { it.read(head) }
            String(head, Charsets.US_ASCII) == "%PDF-"
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Chunked numeric natural sort so `page_2.jpg` comes before `page_10.jpg`.
     */
    internal fun naturalCompare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        while (ia < a.length && ib < b.length) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                var ea = ia
                while (ea < a.length && a[ea].isDigit()) ea++
                var eb = ib
                while (eb < b.length && b[eb].isDigit()) eb++
                val na = a.substring(ia, ea).trimStart('0')
                val nb = b.substring(ib, eb).trimStart('0')
                if (na.length != nb.length) return na.length.compareTo(nb.length)
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
                ia = ea
                ib = eb
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                ia++
                ib++
            }
        }
        return a.length.compareTo(b.length)
    }

    /**
     * Generates a clean 1-page note/task PDF for Quick Capture so captured items render and upload to Cloud.
     */
    fun generateQuickCapturePdf(text: String, isTask: Boolean, timestamp: Long = System.currentTimeMillis()): ByteArray {
        val pageW = 1404
        val pageH = 1872
        val margin = 96f
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#71717A")
                textSize = 26f
                isFakeBoldText = true
            }
            val dateStr = SimpleDateFormat("EEE, MMM d, yyyy • h:mm a", Locale.US).format(Date(timestamp))
            canvas.drawText(if (isTask) "QUICK TASK • $dateStr" else "QUICK NOTE • $dateStr", margin, 110f, headerPaint)

            val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E4E4E7")
                strokeWidth = 2f
            }
            canvas.drawLine(margin, 136f, pageW - margin, 136f, dividerPaint)

            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#18181B")
                textSize = 36f
            }

            var y = 210f
            val bodyText = if (isTask) "☐  ${text.trim()}" else text.trim()
            val maxW = pageW - margin * 2
            for (para in bodyText.lines()) {
                val words = para.split(" ")
                val line = StringBuilder()
                for (w in words) {
                    val cand = if (line.isEmpty()) w else "$line $w"
                    if (bodyPaint.measureText(cand) <= maxW) {
                        line.clear().append(cand)
                    } else {
                        canvas.drawText(line.toString(), margin, y, bodyPaint)
                        y += 54f
                        line.clear().append(w)
                    }
                }
                if (line.isNotEmpty()) {
                    canvas.drawText(line.toString(), margin, y, bodyPaint)
                    y += 54f
                }
                y += 16f
            }

            // Ruled workspace below captured text for tablet stylus follow-up
            y = (y + 60f).coerceAtLeast(420f)
            while (y < pageH - 100f) {
                canvas.drawLine(margin, y, pageW - margin, y, dividerPaint)
                y += 58f
            }

            doc.finishPage(page)
            val baos = ByteArrayOutputStream()
            doc.writeTo(baos)
            return baos.toByteArray()
        } finally {
            doc.close()
        }
    }
}
