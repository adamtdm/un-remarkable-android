package com.unremarkable.android.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.unremarkable.core.render.Page
import com.unremarkable.core.render.ParagraphStyle
import com.unremarkable.core.render.RmPalette
import com.unremarkable.core.render.Stroke
import com.unremarkable.core.render.ToolType
import com.unremarkable.core.render.WritingTools
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/**
 * Renders native reMarkable `.rm` v6/v5 pages (strokes, highlights, typed text) and EPUB text fallbacks
 * directly onto Android Bitmaps/Canvases.
 */
object RmPageCanvasRenderer {

    private const val TEXT_TOP_Y = -88f

    fun renderNotebookPage(
        rmPage: Page?,
        darkMode: Boolean,
        targetWidth: Int = 1404,
        targetHeight: Int = 1872
    ): Bitmap {
        val pageW = rmPage?.width ?: targetWidth
        val pageH = rmPage?.height ?: targetHeight
        val scale = minOf(targetWidth.toFloat() / pageW, targetHeight.toFloat() / pageH)
        val bmpW = (pageW * scale).toInt().coerceAtLeast(1)
        val bmpH = (pageH * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (darkMode) Color.parseColor("#18181B") else Color.WHITE)

        if (rmPage != null && rmPage.hasContent) {
            drawRmOverlay(canvas, rmPage, darkMode, bmpW, bmpH)
        } else {
            // Subtle ruled lines on genuinely blank pages
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (darkMode) Color.parseColor("#27272A") else Color.parseColor("#ECECEF")
                strokeWidth = 1.5f
            }
            val spacing = bmpH / 26f
            for (i in 3..23) {
                val y = i * spacing
                canvas.drawLine(bmpW * 0.06f, y, bmpW * 0.94f, y, linePaint)
            }
        }
        return bitmap
    }

    fun drawRmOverlay(
        canvas: Canvas,
        rmPage: Page,
        darkMode: Boolean,
        targetWidth: Int,
        targetHeight: Int
    ) {
        val saveCount = canvas.save()
        try {
            val sx = targetWidth.toFloat() / rmPage.width.coerceAtLeast(1)
            val sy = targetHeight.toFloat() / rmPage.height.coerceAtLeast(1)
            canvas.scale(sx, sy)
            if (rmPage.centerOriginX) {
                canvas.translate(rmPage.width / 2f, 0f)
            }

            // 1. Highlight glyph rectangles (PDF smart highlights)
            if (rmPage.highlights.isNotEmpty()) {
                val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    xfermode = PorterDuffXfermode(
                        if (darkMode) PorterDuff.Mode.SCREEN else PorterDuff.Mode.MULTIPLY
                    )
                }
                for (hl in rmPage.highlights) {
                    val rgba = RmPalette.getColor(hl.color.value, darkMode, hl.explicitColor)
                    hlPaint.color = rgba.toArgbInt(if (darkMode) 0.45f else 0.38f)
                    canvas.drawRect(hl.x, hl.y, hl.x + hl.w, hl.y + hl.h, hlPaint)
                }
            }

            // 2. Typed text paragraphs (Type Folio / Quick Notes / Tasks)
            if (rmPage.typedParagraphs.isNotEmpty()) {
                val inkColor = if (darkMode) Color.parseColor("#F4F4F5") else Color.parseColor("#18181B")
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inkColor }
                val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = inkColor
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f
                }

                var yOffset = rmPage.textPosY + TEXT_TOP_Y
                val startX = rmPage.textPosX
                val maxWidth = rmPage.textWidth.coerceAtLeast(400f)

                for (p in rmPage.typedParagraphs) {
                    yOffset += p.style.lineHeight
                    val raw = p.text.trimEnd()
                    if (raw.isBlank()) continue

                    textPaint.isFakeBoldText = p.style == ParagraphStyle.HEADING || p.style == ParagraphStyle.BOLD
                    textPaint.isStrikeThruText = p.style == ParagraphStyle.CHECKBOX_CHECKED
                    textPaint.textSize = when (p.style) {
                        ParagraphStyle.HEADING -> 42f
                        ParagraphStyle.BOLD -> 28f
                        else -> 26f
                    }

                    // Draw prefix markers on first line
                    when (p.style) {
                        ParagraphStyle.BULLET, ParagraphStyle.BULLET2 -> {
                            val indent = if (p.style == ParagraphStyle.BULLET2) 24f else 0f
                            textPaint.style = Paint.Style.FILL
                            canvas.drawCircle(startX + indent - 20f, yOffset - 9f, 4.5f, textPaint)
                        }
                        ParagraphStyle.CHECKBOX, ParagraphStyle.CHECKBOX_CHECKED -> {
                            val rect = RectF(startX - 32f, yOffset - 22f, startX - 12f, yOffset - 2f)
                            canvas.drawRoundRect(rect, 3f, 3f, boxPaint)
                            if (p.style == ParagraphStyle.CHECKBOX_CHECKED) {
                                val check = Path().apply {
                                    moveTo(startX - 28f, yOffset - 12f)
                                    lineTo(startX - 23f, yOffset - 6f)
                                    lineTo(startX - 15f, yOffset - 18f)
                                }
                                canvas.drawPath(check, boxPaint)
                            }
                        }
                        else -> {}
                    }

                    val wrapped = wrapLine(raw, maxWidth, textPaint)
                    val xIndent = if (p.style == ParagraphStyle.BULLET2) 24f else 0f
                    for ((lineIdx, lineStr) in wrapped.withIndex()) {
                        if (lineIdx > 0) yOffset += textPaint.textSize * 1.35f
                        canvas.drawText(lineStr, startX + xIndent, yOffset, textPaint)
                    }
                }
            }

            // 3. Handwritten vector strokes
            for (layer in rmPage.layers) {
                for (stroke in layer.strokes) {
                    drawStroke(canvas, stroke, darkMode)
                }
            }
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke, darkMode: Boolean) {
        if (stroke.points.isEmpty() || stroke.tool == ToolType.ERASER) return

        val rgba = RmPalette.getColor(stroke.color.value, darkMode, stroke.explicitColor)
        val isPressureSensitive = stroke.tool == ToolType.BALLPOINT ||
            stroke.tool == ToolType.PENCIL ||
            stroke.tool == ToolType.PAINTBRUSH ||
            stroke.tool == ToolType.CALLIGRAPHY

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = when (stroke.tool) {
                ToolType.HIGHLIGHTER -> Paint.Cap.SQUARE
                ToolType.CALLIGRAPHY -> Paint.Cap.BUTT
                else -> Paint.Cap.ROUND
            }
            if (stroke.tool == ToolType.HIGHLIGHTER || stroke.tool == ToolType.SHADER) {
                xfermode = PorterDuffXfermode(
                    if (darkMode) PorterDuff.Mode.SCREEN else PorterDuff.Mode.MULTIPLY
                )
            }
        }

        if (stroke.points.size == 1) {
            val pt = stroke.points[0]
            val st = WritingTools.computeStyle(stroke.tool, stroke.thicknessScale, pt.pressure)
            if (st.opacity <= 0.01f) return
            paint.style = Paint.Style.FILL
            paint.color = rgba.toArgbInt(st.opacity)
            canvas.drawCircle(pt.x, pt.y, (st.strokeWidth / 2f).coerceAtLeast(1f), paint)
            return
        }

        val segLen = if (isPressureSensitive) 6 else 1024
        var i = 0
        while (i < stroke.points.size - 1) {
            val end = minOf(i + segLen, stroke.points.size - 1)
            val samplePt = stroke.points[i]
            val st = WritingTools.computeStyle(stroke.tool, stroke.thicknessScale, samplePt.pressure)
            if (st.opacity > 0.01f) {
                val effectiveAlpha = if (darkMode && stroke.tool == ToolType.HIGHLIGHTER) 0.52f else st.opacity
                paint.color = rgba.toArgbInt(effectiveAlpha)
                paint.strokeWidth = st.strokeWidth
                val path = Path()
                path.moveTo(samplePt.x, samplePt.y)
                for (j in (i + 1)..end) {
                    val p = stroke.points[j]
                    path.lineTo(p.x, p.y)
                }
                canvas.drawPath(path, paint)
            }
            i = end
        }
    }

    /**
     * Fallback text extractor for un-rendered `.epub` archives so EPUB documents display readable pages.
     */
    fun extractEpubPages(epubFile: File, linesPerPage: Int = 34): List<List<String>> {
        if (!epubFile.exists() || epubFile.length() == 0L) return emptyList()
        return try {
            val allLines = mutableListOf<String>()
            val measurePaint = Paint().apply { textSize = 30f }
            val maxLineWidth = 1200f

            ZipFile(epubFile).use { zip ->
                val htmlEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && (it.name.endsWith(".xhtml", true) || it.name.endsWith(".html", true) || it.name.endsWith(".htm", true)) }
                    .filterNot { it.name.contains("nav", true) || it.name.contains("toc", true) }
                    .sortedWith { a, b -> AndroidDocumentOptimizer.naturalCompare(a.name, b.name) }
                    .toList()

                for (entry in htmlEntries) {
                    val rawHtml = zip.getInputStream(entry).use { String(it.readBytes(), StandardCharsets.UTF_8) }
                    val plain = rawHtml
                        .replace(Regex("(?is)<(script|style|head).*?>.*?</\\1>"), "")
                        .replace(Regex("(?i)</(p|div|h[1-6]|li|br|tr)>"), "\n")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace("&nbsp;", " ")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace("&#39;", "'")

                    for (para in plain.lines()) {
                        val cleaned = para.trim().replace(Regex("\\s+"), " ")
                        if (cleaned.isNotEmpty()) {
                            allLines.addAll(wrapLine(cleaned, maxLineWidth, measurePaint))
                            allLines.add("")
                        }
                    }
                }
            }
            if (allLines.isEmpty()) emptyList() else allLines.chunked(linesPerPage)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun renderEpubTextPage(lines: List<String>, pageNum: Int, totalPages: Int, darkMode: Boolean): Bitmap {
        val w = 1404
        val h = 1872
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(if (darkMode) Color.parseColor("#18181B") else Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (darkMode) Color.parseColor("#F4F4F5") else Color.parseColor("#18181B")
            textSize = 30f
        }
        var y = 120f
        for (line in lines) {
            if (line.isNotEmpty()) {
                canvas.drawText(line, 100f, y, paint)
            }
            y += 46f
        }
        paint.textSize = 22f
        paint.color = Color.parseColor("#71717A")
        canvas.drawText("$pageNum / $totalPages", w / 2f - 40f, h - 56f, paint)
        return bmp
    }

    private fun wrapLine(text: String, maxWidth: Float, paint: Paint): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val out = mutableListOf<String>()
        val words = text.split(" ")
        val cur = StringBuilder()
        for (w in words) {
            val candidate = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(candidate) <= maxWidth) {
                cur.clear()
                cur.append(candidate)
            } else {
                if (cur.isNotEmpty()) out.add(cur.toString())
                cur.clear()
                cur.append(w)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}
