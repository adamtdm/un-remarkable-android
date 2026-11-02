package com.unremarkable.android.render

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import androidx.core.content.FileProvider
import com.unremarkable.android.storage.AnnotationStore
import com.unremarkable.android.ui.components.DrawnStroke
import com.unremarkable.core.render.Page
import com.unremarkable.core.render.RmSvgRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object DocumentExporter {

    suspend fun exportAnnotatedPdf(
        context: Context,
        docId: String,
        docTitle: String,
        totalPages: Int,
        pdfRenderer: PdfDocumentRenderer?,
        annotationStore: AnnotationStore?
    ): File = withContext(Dispatchers.Default) {
        val pdfDoc = PdfDocument()

        val safeTitle = docTitle.filter { it.isLetterOrDigit() || it == ' ' || it == '_' }.trim().ifBlank { "Notebook" }
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = if (downloadsDir != null && downloadsDir.exists()) downloadsDir else context.filesDir
        val outputFile = File(targetDir, "${safeTitle}_annotated.pdf")

        try {
            val pageW = 1404
            val pageH = 1872

            for (pageIdx in 0 until totalPages) {
                val pageNum = pageIdx + 1
                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
                val page = pdfDoc.startPage(pageInfo)
                val canvas = page.canvas

                // 1. Draw base PDF page or white background
                if (pdfRenderer != null) {
                    val baseBmp = pdfRenderer.renderPage(pageIdx, pageW, pageH)
                    canvas.drawBitmap(baseBmp, 0f, 0f, null)
                } else {
                    canvas.drawColor(android.graphics.Color.WHITE)
                    // Draw light notebook lines
                    val linePaint = Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        strokeWidth = 2f
                    }
                    val step = pageH / 24f
                    for (i in 2..22) {
                        val y = i * step
                        canvas.drawLine(80f, y, pageW - 80f, y, linePaint)
                    }
                }

                // 2. Draw user handwriting & annotations
                val strokes = annotationStore?.loadStrokes(docId, pageNum).orEmpty()
                drawStrokesToCanvas(canvas, strokes, pageW, pageH)

                pdfDoc.finishPage(page)
            }

            FileOutputStream(outputFile).use { out ->
                pdfDoc.writeTo(out)
            }
        } finally {
            pdfDoc.close()
        }

        outputFile
    }

    private fun drawStrokesToCanvas(canvas: Canvas, strokes: List<DrawnStroke>, width: Int, height: Int) {
        if (strokes.isEmpty()) return

        for (stroke in strokes) {
            if (stroke.points.size < 2) continue
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.color.value
                strokeWidth = stroke.strokeWidth * (width / 400f).coerceAtLeast(1f)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                alpha = (stroke.opacity * 255).toInt().coerceIn(0, 255)
            }

            val path = android.graphics.Path()
            path.moveTo(stroke.points[0].x, stroke.points[0].y)
            for (i in 1 until stroke.points.size) {
                path.lineTo(stroke.points[i].x, stroke.points[i].y)
            }
            canvas.drawPath(path, paint)
        }
    }

    fun shareExportedFile(context: Context, file: File, mimeType: String, title: String) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share $title"))
        } catch (_: Exception) {}
    }
}
