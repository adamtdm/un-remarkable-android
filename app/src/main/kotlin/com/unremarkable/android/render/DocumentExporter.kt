package com.unremarkable.android.render

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.unremarkable.android.storage.AnnotationStore
import com.unremarkable.android.ui.components.DrawnStroke
import com.unremarkable.core.cloud.BlobStore
import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.render.RmBinaryParser
import com.unremarkable.core.render.RmPalette
import com.unremarkable.core.render.ToolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object DocumentExporter {

    suspend fun exportAnnotatedPdf(
        context: Context,
        document: DocumentItem,
        totalPages: Int,
        pdfRenderer: PdfDocumentRenderer?,
        blobStore: BlobStore?,
        annotationStore: AnnotationStore?
    ): File = withContext(Dispatchers.Default) {
        val pdfDoc = PdfDocument()

        val safeTitle = document.visibleName
            .filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }
            .trim()
            .ifBlank { "Notebook" }
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outputFile = File(exportDir, "${safeTitle}_annotated.pdf")

        try {
            val pageW = 1404
            val pageH = 1872

            for (pageIdx in 0 until totalPages) {
                val pageNum = pageIdx + 1
                val pageRef = document.pages.getOrNull(pageIdx)
                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
                val page = pdfDoc.startPage(pageInfo)
                val canvas = page.canvas

                // Locate cached .rm page scene if present
                val rmHash = resolveRmBlobHash(document, pageIdx)
                val rmPage = rmHash?.let { blobStore?.getBlob(it) }?.let { RmBinaryParser.parse(it) }

                val pdfIdx = resolvePdfPageIndex(document, pageIdx)
                if (pdfRenderer != null && pdfIdx in 0 until pdfRenderer.pageCount) {
                    val baseBmp = pdfRenderer.renderPage(pdfIdx, pageW, pageH)
                    canvas.drawBitmap(baseBmp, 0f, 0f, null)
                    baseBmp.recycle()
                    if (rmPage != null) {
                        RmPageCanvasRenderer.drawRmOverlay(canvas, rmPage, darkMode = false, targetWidth = pageW, targetHeight = pageH)
                    }
                } else {
                    val notebookBmp = RmPageCanvasRenderer.renderNotebookPage(rmPage, darkMode = false, targetWidth = pageW, targetHeight = pageH)
                    canvas.drawBitmap(notebookBmp, 0f, 0f, null)
                    notebookBmp.recycle()
                }

                // Draw local Android touch/stylus annotations
                val strokes = annotationStore?.loadStrokes(document.id, pageNum).orEmpty()
                drawStrokesToCanvas(canvas, strokes, pageW)

                pdfDoc.finishPage(page)
            }

            FileOutputStream(outputFile).use { out ->
                pdfDoc.writeTo(out)
            }
        } finally {
            pdfDoc.close()
        }

        saveCopyToPublicDownloads(context, outputFile, "${safeTitle}_annotated.pdf", "application/pdf")
        outputFile
    }

    /**
     * Maps a logical page index to its backing PDF page index (`-1` for inserted blank note pages).
     * When `cPages` omits `redir` across the entire document, falls back to 1:1 `pageIdx` mapping.
     */
    fun resolvePdfPageIndex(document: DocumentItem, pageIdx: Int): Int {
        if (document.pages.isEmpty()) return pageIdx
        val hasExplicitRedir = document.pages.any { it.redir >= 0 }
        if (!hasExplicitRedir) return pageIdx
        return document.pages.getOrNull(pageIdx)?.redir ?: -1
    }

    fun resolveRmBlobHash(document: DocumentItem, pageIdx: Int): String? {
        val pageId = document.pages.getOrNull(pageIdx)?.pageId
        if (!pageId.isNullOrBlank()) {
            document.subfiles["${document.id}/$pageId.rm"]?.let { return it }
            document.subfiles.entries.firstOrNull { it.key.endsWith("/$pageId.rm") || it.key == "$pageId.rm" }?.let { return it.value }
        }
        // Fallback if pages array was absent in legacy metadata
        val rmEntries = document.subfiles.entries.filter { it.key.endsWith(".rm", ignoreCase = true) }
        return rmEntries.getOrNull(pageIdx)?.value
    }

    private fun drawStrokesToCanvas(canvas: Canvas, strokes: List<DrawnStroke>, width: Int) {
        if (strokes.isEmpty()) return

        for (stroke in strokes) {
            if (stroke.points.size < 2) continue
            val rgba = RmPalette.getColor(stroke.color.value, darkMode = false)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = rgba.toArgbInt(stroke.opacity)
                strokeWidth = stroke.strokeWidth * (width / 600f).coerceAtLeast(1f)
                style = Paint.Style.STROKE
                strokeCap = if (stroke.tool == ToolType.HIGHLIGHTER) Paint.Cap.SQUARE else Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            val path = android.graphics.Path()
            path.moveTo(stroke.points[0].x, stroke.points[0].y)
            for (i in 1 until stroke.points.size) {
                path.lineTo(stroke.points[i].x, stroke.points[i].y)
            }
            canvas.drawPath(path, paint)
        }
    }

    fun saveCopyToPublicDownloads(context: Context, sourceFile: File, displayName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            }
        } catch (_: Exception) {
            // Non-fatal: Share sheet still works from cacheDir via FileProvider
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
