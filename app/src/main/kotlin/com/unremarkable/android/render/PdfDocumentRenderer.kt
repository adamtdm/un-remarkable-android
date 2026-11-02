package com.unremarkable.android.render

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PdfDocumentRenderer private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val sourceFile: File? = null
) : AutoCloseable {

    val pageCount: Int
        get() = renderer.pageCount

    suspend fun renderPage(pageIndex: Int, targetWidth: Int = 1404, targetHeight: Int = 1872): Bitmap = withContext(Dispatchers.Default) {
        val safeIndex = pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
        val page = renderer.openPage(safeIndex)
        try {
            // Scale maintaining aspect ratio
            val srcW = page.width
            val srcH = page.height
            val scale = minOf(targetWidth.toFloat() / srcW, targetHeight.toFloat() / srcH)
            val renderW = (srcW * scale).toInt().coerceAtLeast(1)
            val renderH = (srcH * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
            // Initialize with white paper background
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } finally {
            page.close()
        }
    }

    suspend fun renderThumbnail(pageIndex: Int, targetWidth: Int = 220, targetHeight: Int = 300): Bitmap = withContext(Dispatchers.Default) {
        val safeIndex = pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
        val page = renderer.openPage(safeIndex)
        try {
            val srcW = page.width
            val srcH = page.height
            val scale = minOf(targetWidth.toFloat() / srcW, targetHeight.toFloat() / srcH)
            val renderW = (srcW * scale).toInt().coerceAtLeast(1)
            val renderH = (srcH * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.RGB_565)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } finally {
            page.close()
        }
    }

    override fun close() {
        try {
            renderer.close()
        } catch (_: Exception) {}
        try {
            pfd.close()
        } catch (_: Exception) {}
    }

    companion object {
        fun open(file: File): PdfDocumentRenderer? {
            if (!file.exists() || file.length() == 0L) return null
            return try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                PdfDocumentRenderer(pfd, renderer, file)
            } catch (_: Exception) {
                null
            }
        }
    }
}
