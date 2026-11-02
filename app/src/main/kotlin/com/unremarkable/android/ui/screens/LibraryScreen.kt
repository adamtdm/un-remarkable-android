package com.unremarkable.android.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unremarkable.android.R
import com.unremarkable.android.render.PdfDocumentRenderer
import com.unremarkable.android.render.RmPageCanvasRenderer
import com.unremarkable.core.cloud.BlobStore
import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.render.RmBinaryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DocumentFilter(val label: String) {
    ALL("All"),
    NOTEBOOKS("Notebooks"),
    PDFS("PDF & EPUB"),
    COMICS("Optimized"),
    PINNED("Pinned ★")
}

enum class LibrarySort(val label: String) {
    MODIFIED("Last Modified"),
    NAME("Name (A–Z)"),
    PAGES("Page Count")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    documents: List<DocumentItem>,
    isSyncing: Boolean = false,
    syncStatusMessage: String = "",
    syncGeneration: Long = 0L,
    currentFolderId: String = "",
    blobStore: BlobStore? = null,
    isFoldable: Boolean = false,
    onMinimizeSidebar: () -> Unit = {},
    onOpenDocument: (DocumentItem) -> Unit,
    onOpenFolder: (String) -> Unit,
    onSyncNow: () -> Unit,
    onQuickCapture: () -> Unit,
    onOpenConverter: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenMeetingRecorder: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(DocumentFilter.ALL) }
    var sortMode by remember { mutableStateOf(LibrarySort.MODIFIED) }
    var showSortMenu by remember { mutableStateOf(false) }

    val docsById = remember(documents) { documents.associateBy { it.id } }
    val parentFolderId = remember(docsById, currentFolderId) {
        docsById[currentFolderId]?.parent.orEmpty()
    }

    // Build folder breadcrumb chain safely (guards against cyclic parent links)
    val breadcrumbs = remember(docsById, currentFolderId) {
        val trail = mutableListOf<DocumentItem>()
        val visited = HashSet<String>()
        var cursor = docsById[currentFolderId]
        while (cursor != null && visited.add(cursor.id)) {
            trail.add(cursor)
            cursor = if (cursor.parent.isNotEmpty()) docsById[cursor.parent] else null
        }
        trail.asReversed()
    }

    BackHandler(enabled = searchQuery.isNotEmpty() || currentFolderId.isNotEmpty()) {
        if (searchQuery.isNotEmpty()) {
            searchQuery = ""
        } else {
            onOpenFolder(parentFolderId)
        }
    }

    val filteredDocs = remember(documents, searchQuery, currentFolderId, selectedFilter, sortMode) {
        // Hide trashed items
        val activeDocs = documents.filter { it.parent != "trash" }

        val baseList = when {
            selectedFilter == DocumentFilter.PINNED -> {
                if (searchQuery.isBlank()) {
                    activeDocs.filter { it.pinned }
                } else {
                    activeDocs.filter {
                        it.pinned && (
                            it.visibleName.contains(searchQuery, ignoreCase = true) ||
                                it.tags.any { tag -> tag.contains(searchQuery, ignoreCase = true) }
                            )
                    }
                }
            }
            searchQuery.isBlank() -> activeDocs.filter { it.parent == currentFolderId }
            else -> activeDocs.filter {
                it.visibleName.contains(searchQuery, ignoreCase = true) ||
                    it.tags.any { tag -> tag.contains(searchQuery, ignoreCase = true) }
            }
        }

        val filtered = when (selectedFilter) {
            DocumentFilter.ALL -> baseList
            DocumentFilter.PINNED -> baseList
            DocumentFilter.NOTEBOOKS -> baseList.filter {
                !it.isFolder && (it.fileType.isBlank() || it.fileType == "notebook" || it.tags.contains("Note") || it.tags.contains("Task"))
            }
            DocumentFilter.PDFS -> baseList.filter {
                !it.isFolder && (
                    it.fileType == "pdf" ||
                        it.fileType == "epub" ||
                        it.visibleName.endsWith(".pdf", ignoreCase = true) ||
                        it.visibleName.endsWith(".epub", ignoreCase = true)
                    )
            }
            DocumentFilter.COMICS -> baseList.filter {
                !it.isFolder && (it.tags.contains("Comic") || it.tags.contains("Optimized"))
            }
        }

        val comparator = compareByDescending<DocumentItem> { it.isFolder }
            .thenByDescending { it.pinned }
            .let { base ->
                when (sortMode) {
                    LibrarySort.MODIFIED -> base.thenByDescending { it.lastModified }
                    LibrarySort.NAME -> base.thenBy { it.visibleName.lowercase(Locale.US) }
                    LibrarySort.PAGES -> base.thenByDescending { maxOf(it.pages.size, it.pageCount) }
                }
            }
        filtered.sortedWith(comparator)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_ur_logo),
                            contentDescription = "Un-remarkable Logo",
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Un-remarkable",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    if (isFoldable) {
                        IconButton(onClick = onMinimizeSidebar) {
                            Icon(Icons.Default.ViewSidebar, contentDescription = "Minimize to Rail")
                        }
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort Library")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            LibrarySort.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    leadingIcon = {
                                        if (sortMode == mode) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        sortMode = mode
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = onSyncNow, enabled = !isSyncing) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = "Sync Library")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(
                    onClick = onOpenMeetingRecorder,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Record Meeting")
                }
                SmallFloatingActionButton(
                    onClick = onOpenConverter,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.FlashOn, contentDescription = "Optimize Document")
                }
                FloatingActionButton(
                    onClick = onQuickCapture,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Quick Capture")
                }
            }
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search notes, tags, and documents...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(DocumentFilter.entries) { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter.label, style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }

            // Live Sync Status
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusText = when {
                    isSyncing -> syncStatusMessage.ifBlank { "Syncing with cloud..." }
                    syncGeneration > 0L -> "Cloud Gen $syncGeneration • ${documents.count { !it.isFolder && it.parent != "trash" }} docs"
                    else -> "${documents.count { !it.isFolder }} local items"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (currentFolderId.isNotEmpty() && searchQuery.isBlank()) {
                    TextButton(
                        onClick = { onOpenFolder(parentFolderId) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Up", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Folder Breadcrumb Trail (matches Docker web app)
            if (breadcrumbs.isNotEmpty() && searchQuery.isBlank() && selectedFilter != DocumentFilter.PINNED) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "My Files",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenFolder("") }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    breadcrumbs.forEachIndexed { idx, folder ->
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val isLast = idx == breadcrumbs.lastIndex
                        Text(
                            text = folder.visibleName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = !isLast) { onOpenFolder(folder.id) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (filteredDocs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = if (searchQuery.isNotBlank()) "No matching notes or documents" else "Folder is empty",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Tap + to capture notes, ⚡ to optimize a PDF/comic, or sync your reMarkable account in Settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredDocs, key = { it.id }) { doc ->
                        DocumentCard(
                            document = doc,
                            blobStore = blobStore,
                            onClick = {
                                if (doc.isFolder) {
                                    onOpenFolder(doc.id)
                                } else {
                                    onOpenDocument(doc)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DocumentCard(
    document: DocumentItem,
    blobStore: BlobStore? = null,
    onClick: () -> Unit
) {
    var coverThumb by remember(document.id, document.hash) { mutableStateOf<Bitmap?>(null) }

    // Render cached cover thumbnail without triggering network calls
    LaunchedEffect(document.id, document.hash, blobStore) {
        if (document.isFolder || blobStore == null) return@LaunchedEffect
        coverThumb = withContext(Dispatchers.IO) {
            try {
                val pdfEntry = document.subfiles.entries.firstOrNull { it.key.endsWith(".pdf", ignoreCase = true) }
                if (pdfEntry != null && blobStore.hasBlob(pdfEntry.value)) {
                    val file = blobStore.getBlobFile(pdfEntry.value)
                    PdfDocumentRenderer.open(file)?.use { pdf ->
                        val pageIdx = document.pages.firstOrNull()?.redir?.takeIf { it >= 0 } ?: 0
                        if (pageIdx in 0 until pdf.pageCount) {
                            return@withContext pdf.renderPage(pageIdx, 96, 128)
                        }
                    }
                }
                val firstPageId = document.pages.firstOrNull()?.pageId
                val rmHash = if (firstPageId != null) {
                    document.subfiles.entries.firstOrNull {
                        it.key.endsWith("$firstPageId.rm") || it.key == "$firstPageId.rm"
                    }?.value
                } else {
                    document.subfiles.entries.firstOrNull { it.key.endsWith(".rm") }?.value
                }
                if (rmHash != null && blobStore.hasBlob(rmHash)) {
                    val bytes = blobStore.getBlob(rmHash) ?: return@withContext null
                    val scene = RmBinaryParser.parse(bytes) ?: return@withContext null
                    return@withContext RmPageCanvasRenderer.renderNotebookPage(
                        rmPage = scene,
                        darkMode = false,
                        targetWidth = 96,
                        targetHeight = 128
                    )
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                color = if (document.isFolder) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                border = if (!document.isFolder) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)) else null,
                modifier = Modifier.size(width = 46.dp, height = if (document.isFolder) 46.dp else 58.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val thumb = coverThumb
                    if (thumb != null && !document.isFolder) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val icon = when {
                            document.isFolder -> Icons.Default.Folder
                            document.fileType == "pdf" -> Icons.Default.PictureAsPdf
                            document.fileType == "epub" -> Icons.Default.Book
                            else -> Icons.Default.EditNote
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (document.isFolder) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = document.visibleName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (document.pinned) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!document.isFolder) {
                        val pages = maxOf(document.pages.size, document.pageCount)
                        if (pages > 0) {
                            Text(
                                text = "$pages ${if (pages == 1) "pg" else "pgs"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (document.lastModified > 0L) {
                        Text(
                            text = formatRelativeDate(document.lastModified),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                    if (document.localOnly) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = CircleShape
                        ) {
                            Text(
                                text = "Pending Sync",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp)
                            )
                        }
                    }
                    for (tag in document.tags.take(2)) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape
                        ) {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

private fun formatRelativeDate(epochMs: Long): String {
    // Normalize seconds vs milliseconds just in case
    val ms = if (epochMs in 1..99_999_999_999L) epochMs * 1000L else epochMs
    val diffMs = System.currentTimeMillis() - ms
    val mins = diffMs / 60_000L
    val hours = mins / 60L
    val days = hours / 24L
    return when {
        diffMs < 0 -> SimpleDateFormat("MMM d", Locale.US).format(Date(ms))
        mins < 1 -> "Just now"
        mins < 60 -> "${mins}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> SimpleDateFormat("MMM d", Locale.US).format(Date(ms))
    }
}
