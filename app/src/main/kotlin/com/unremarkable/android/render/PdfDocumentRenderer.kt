package com.unremarkable.android.render

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PdfDocumentRenderer private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val sourceFile: File? = null
) : AutoCloseable {

    private val renderMutex = Mutex()
    private val thumbnailCache = LruCache<Int, Bitmap>(64)

    @Volatile
    private var isClosed = false

    val pageCount: Int
        get() = if (isClosed) 0 else renderer.pageCount

    suspend fun renderPage(pageIndex: Int, targetWidth: Int = 1404, targetHeight: Int = 1872): Bitmap = withContext(Dispatchers.Default) {
        renderMutex.withLock {
            if (isClosed) {
                return@withLock Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.WHITE)
                }
            }
            val count = renderer.pageCount
            val safeIndex = pageIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
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
    }

    suspend fun renderThumbnail(pageIndex: Int, targetWidth: Int = 220, targetHeight: Int = 300): Bitmap? = withContext(Dispatchers.Default) {
        if (isClosed) return@withContext null
        val count = renderer.pageCount
        if (count <= 0) return@withContext null
        val safeIndex = pageIndex.coerceIn(0, count - 1)

        // Check thumbnail cache first (lock-free)
        thumbnailCache.get(safeIndex)?.let { cached ->
            if (!cached.isRecycled) return@withContext cached
        }

        renderMutex.withLock {
            if (isClosed) return@withLock null

            // Double check cache under lock
            thumbnailCache.get(safeIndex)?.let { cached ->
                if (!cached.isRecycled) return@withLock cached
            }

            try {
                val page = renderer.openPage(safeIndex)
                try {
                    val srcW = page.width
                    val srcH = page.height
                    val scale = minOf(targetWidth.toFloat() / srcW, targetHeight.toFloat() / srcH)
                    val renderW = (srcW * scale).toInt().coerceAtLeast(1)
                    val renderH = (srcH * scale).toInt().coerceAtLeast(1)

                    // NOTE: PdfRenderer strictly requires Bitmap.Config.ARGB_8888!
                    // RGB_565 throws IllegalArgumentException: Unsupported pixel format.
                    val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    thumbnailCache.put(safeIndex, bitmap)
                    bitmap
                } finally {
                    page.close()
                }
            } catch (e: Exception) {
                Log.w("PdfDocumentRenderer", "Failed to render thumbnail for page $safeIndex", e)
                null
            }
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        thumbnailCache.evictAll()
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
