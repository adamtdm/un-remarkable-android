package com.unremarkable.android.ui.components

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unremarkable.android.render.DocumentExporter
import com.unremarkable.android.render.PdfDocumentRenderer
import com.unremarkable.android.render.RmPageCanvasRenderer
import com.unremarkable.android.storage.AnnotationStore
import com.unremarkable.android.ui.theme.Zinc900
import com.unremarkable.core.cloud.BlobStore
import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.render.RmBinaryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ThumbnailFilter {
    ALL,
    BOOKMARKED,
    ANNOTATED
}

@Composable
fun PageThumbnailGridDialog(
    document: DocumentItem,
    totalPages: Int,
    currentPage: Int,
    pdfRenderer: PdfDocumentRenderer?,
    epubPages: List<List<String>> = emptyList(),
    blobStore: BlobStore? = null,
    annotationStore: AnnotationStore?,
    onSelectPage: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val docId = document.id
    var selectedFilter by remember { mutableStateOf(ThumbnailFilter.ALL) }
    val bookmarkedPages = remember(docId) {
        try {
            annotationStore?.getBookmarks(docId).orEmpty()
        } catch (_: Exception) {
            emptySet()
        }
    }
    val annotatedPages = remember(docId) {
        try {
            annotationStore?.getAnnotatedPages(docId).orEmpty()
        } catch (_: Exception) {
            emptySet()
        }
    }

    val total = totalPages.coerceAtLeast(1)
    val allPageNumbers = remember(total) { (1..total).toList() }
    val filteredPages = remember(selectedFilter, bookmarkedPages, annotatedPages, allPageNumbers) {
        when (selectedFilter) {
            ThumbnailFilter.ALL -> allPageNumbers
            ThumbnailFilter.BOOKMARKED -> allPageNumbers.filter { it in bookmarkedPages }
            ThumbnailFilter.ANNOTATED -> allPageNumbers.filter { it in annotatedPages }
        }
    }

    // In-memory cache for notebook and EPUB thumbnails scoped to dialog lifetime
    val notebookThumbnailCache = remember(docId) { LruCache<Int, Bitmap>(64) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Page Sorter",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$totalPages total pages • Tap any thumbnail to jump",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedFilter == ThumbnailFilter.ALL,
                        onClick = { selectedFilter = ThumbnailFilter.ALL },
                        label = { Text("All (${allPageNumbers.size})") },
                        leadingIcon = {
                            Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                    FilterChip(
                        selected = selectedFilter == ThumbnailFilter.BOOKMARKED,
                        onClick = { selectedFilter = ThumbnailFilter.BOOKMARKED },
                        label = { Text("Bookmarked (${bookmarkedPages.size})") },
                        leadingIcon = {
                            Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFF59E0B))
                        }
                    )
                    FilterChip(
                        selected = selectedFilter == ThumbnailFilter.ANNOTATED,
                        onClick = { selectedFilter = ThumbnailFilter.ANNOTATED },
                        label = { Text("Annotated (${annotatedPages.size})") },
                        leadingIcon = {
                            Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Grid
                if (filteredPages.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = when (selectedFilter) {
                                ThumbnailFilter.BOOKMARKED -> "No bookmarked pages yet. Tap the bookmark icon while reading."
                                ThumbnailFilter.ANNOTATED -> "No annotated pages yet. Draw or write to add notes."
                                else -> "No pages available"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 130.dp),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(filteredPages, key = { it }) { pageNum ->
                            val isCurrent = pageNum == currentPage
                            val isBookmarked = pageNum in bookmarkedPages
                            val isAnnotated = pageNum in annotatedPages

                            // Async thumbnail loading for PDFs, EPUBs, and .rm notebooks
                            val thumbnailBitmap by produceState<Bitmap?>(initialValue = null, pageNum, pdfRenderer, document.id) {
                                value = withContext(Dispatchers.Default) {
                                    try {
                                        val pageIdx = pageNum - 1
                                        val pdfIdx = DocumentExporter.resolvePdfPageIndex(document, pageIdx)
                                        if (pdfRenderer != null && pdfIdx in 0 until pdfRenderer.pageCount) {
                                            pdfRenderer.renderThumbnail(pdfIdx, 180, 240)
                                        } else if (epubPages.isNotEmpty()) {
                                            notebookThumbnailCache.get(pageNum)?.let { if (!it.isRecycled) return@withContext it }
                                            val lines = epubPages.getOrElse(pageIdx.coerceIn(0, epubPages.lastIndex)) { emptyList() }
                                            val fullBmp = RmPageCanvasRenderer.renderEpubTextPage(lines, pageNum, total, darkMode = false)
                                            val thumbBmp = Bitmap.createScaledBitmap(fullBmp, 180, 240, true)
                                            if (fullBmp !== thumbBmp) fullBmp.recycle()
                                            notebookThumbnailCache.put(pageNum, thumbBmp)
                                            thumbBmp
                                        } else if (blobStore != null) {
                                            notebookThumbnailCache.get(pageNum)?.let { if (!it.isRecycled) return@withContext it }
                                            val rmHash = DocumentExporter.resolveRmBlobHash(document, pageIdx)
                                            val rmPage = rmHash?.let { blobStore.getBlob(it) }?.let { RmBinaryParser.parse(it) }
                                            if (rmPage != null) {
                                                val bmp = RmPageCanvasRenderer.renderNotebookPage(rmPage, false, 240, 320)
                                                notebookThumbnailCache.put(pageNum, bmp)
                                                bmp
                                            } else null
                                        } else null
                                    } catch (e: Exception) {
                                        Log.w("PageThumbnailGridDialog", "Failed to render thumbnail for page $pageNum", e)
                                        null
                                    }
                                }
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectPage(pageNum)
                                        onDismiss()
                                    }
                                    .border(
                                        width = if (isCurrent) 2.5.dp else 1.dp,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    // Thumbnail Container
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(0.72f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.background),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val bmp = thumbnailBitmap
                                        if (bmp != null && !bmp.isRecycled) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = "Page $pageNum",
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            // Ruled placeholder lines
                                            Column(
                                                modifier = Modifier.fillMaxSize().padding(12.dp),
                                                verticalArrangement = Arrangement.SpaceEvenly
                                            ) {
                                                repeat(8) {
                                                    HorizontalDivider(color = Color(0xFFE4E4E7), thickness = 1.dp)
                                                }
                                            }
                                        }

                                        // Badges Overlay
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .align(Alignment.TopEnd)
                                                .padding(6.dp),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isAnnotated) {
                                                Surface(
                                                    color = Zinc900.copy(alpha = 0.75f),
                                                    shape = CircleShape,
                                                    modifier = Modifier.size(20.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Draw,
                                                        contentDescription = null,
                                                        tint = Color.White,
                                                        modifier = Modifier.padding(3.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(4.dp))
                                            }
                                            if (isBookmarked) {
                                                Surface(
                                                    color = Color(0xFFF59E0B),
                                                    shape = CircleShape,
                                                    modifier = Modifier.size(20.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Bookmark,
                                                        contentDescription = null,
                                                        tint = Color.White,
                                                        modifier = Modifier.padding(3.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = "Page $pageNum",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
