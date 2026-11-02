package com.unremarkable.android.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unremarkable.android.R
import com.unremarkable.android.render.PdfDocumentRenderer
import com.unremarkable.android.render.RmPageCanvasRenderer
import com.unremarkable.android.ui.theme.StatusSuccess
import com.unremarkable.android.ui.theme.StatusWarning
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
    PINNED("★ Pinned")
}

enum class LibrarySort(val label: String) {
    MODIFIED("Modified"),
    NAME("Name (A–Z)"),
    PAGES("Page Count")
}

enum class LibraryViewMode {
    LIST,
    GRID
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
    focusSearchTrigger: Long = 0L,
    onMinimizeSidebar: () -> Unit = {},
    onGoHome: () -> Unit = {},
    onOpenDocument: (DocumentItem) -> Unit,
    onOpenFolder: (String) -> Unit,
    onSyncNow: () -> Unit,
    onQuickCapture: () -> Unit,
    onOpenConverter: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenMeetingRecorder: () -> Unit = {},
    onDeleteDocument: (DocumentItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(DocumentFilter.ALL) }
    var sortMode by remember { mutableStateOf(LibrarySort.MODIFIED) }
    var viewMode by remember { mutableStateOf(LibraryViewMode.LIST) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showCreateMenu by remember { mutableStateOf(false) }
    var documentPendingDelete by remember { mutableStateOf<DocumentItem?>(null) }
    var contextMenuDoc by remember { mutableStateOf<DocumentItem?>(null) }

    val recentDocs = remember(documents, currentFolderId, searchQuery, selectedFilter) {
        if (currentFolderId.isEmpty() && searchQuery.isBlank() && selectedFilter == DocumentFilter.ALL) {
            documents
                .filter { !it.isFolder && it.parent != "trash" }
                .sortedByDescending { it.lastModified }
                .take(4)
        } else {
            emptyList()
        }
    }

    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(focusSearchTrigger) {
        if (focusSearchTrigger > 0L) {
            searchFocusRequester.requestFocus()
        }
    }

    val docsById = remember(documents) { documents.associateBy { it.id } }
    val parentFolderId = remember(docsById, currentFolderId) {
        docsById[currentFolderId]?.parent.orEmpty()
    }

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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                searchQuery = ""
                                selectedFilter = DocumentFilter.ALL
                                onOpenFolder("")
                                onGoHome()
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_ur_logo),
                            contentDescription = "Un-remarkable Logo",
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(7.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Un-remarkable",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.2).sp
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    if (isFoldable) {
                        IconButton(onClick = onMinimizeSidebar) {
                            Icon(Icons.AutoMirrored.Filled.ViewSidebar, contentDescription = "Minimize to Rail")
                        }
                    }
                    IconButton(onClick = onSyncNow, enabled = !isSyncing) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = "Sync Library")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateMenu = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create New Document")
            }
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Container App Style Search Bar (Pill shape with bg-tertiary)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchFocusRequester),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search library...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Container App Filter Tabs (.filter-tabs enclosed pill)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier.padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DocumentFilter.entries.forEach { filter ->
                            val isSelected = selectedFilter == filter
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable { selectedFilter = filter }
                                    .padding(horizontal = 13.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = filter.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Toolbar Status & Controls (Sync Status Dot + Sort + Grid/List Toggle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Live Sync Dot + Status Text
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    val dotColor = when {
                        isSyncing -> StatusWarning
                        syncGeneration > 0L -> StatusSuccess
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    val statusText = when {
                        isSyncing -> syncStatusMessage.ifBlank { "Syncing with cloud..." }
                        syncGeneration > 0L -> "Synced • ${documents.count { !it.isFolder && it.parent != "trash" }} docs"
                        else -> "${documents.count { !it.isFolder }} local items"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Controls: Up to Root + Sort Dropdown + View Mode
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (currentFolderId.isNotEmpty() && searchQuery.isBlank()) {
                        TextButton(
                            onClick = { onOpenFolder(parentFolderId) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Up", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // Sort menu
                    Box {
                        TextButton(
                            onClick = { showSortMenu = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when (sortMode) {
                                    LibrarySort.MODIFIED -> "Date"
                                    LibrarySort.NAME -> "Name"
                                    LibrarySort.PAGES -> "Pages"
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
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

                    // View Mode Toggle (Grid vs List)
                    IconButton(
                        onClick = {
                            viewMode = if (viewMode == LibraryViewMode.LIST) LibraryViewMode.GRID else LibraryViewMode.LIST
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (viewMode == LibraryViewMode.LIST) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                            contentDescription = "Toggle Grid or List View",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Breadcrumb Navigation Trail
            if (breadcrumbs.isNotEmpty() && searchQuery.isBlank() && selectedFilter != DocumentFilter.PINNED) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Home",
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

            val (folders, nonFolders) = remember(filteredDocs) {
                filteredDocs.partition { it.isFolder }
            }

            // Main Document List or Grid with Pull-to-Refresh
            PullToRefreshBox(
                isRefreshing = isSyncing,
                onRefresh = onSyncNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (filteredDocs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
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
                                modifier = Modifier.size(52.dp)
                            )
                            Text(
                                text = if (searchQuery.isNotBlank()) "No matching notes or documents" else "Folder is empty",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Tap + to create a note, record a meeting, or optimize a document",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else if (viewMode == LibraryViewMode.GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (recentDocs.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Column {
                                    SectionHeader(title = "Jump Back In", count = recentDocs.size)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                            .padding(bottom = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        recentDocs.forEach { recent ->
                                            RecentJumpCard(
                                                document = recent,
                                                onClick = { onOpenDocument(recent) },
                                                onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    contextMenuDoc = recent
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (folders.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SectionHeader(title = "Folders", count = folders.size)
                            }
                            items(folders, key = { it.id }) { folder ->
                                FolderGridCard(
                                    folder = folder,
                                    allDocuments = documents,
                                    onClick = { onOpenFolder(folder.id) },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        contextMenuDoc = folder
                                    }
                                )
                            }
                        }

                        if (nonFolders.isNotEmpty()) {
                            if (folders.isNotEmpty() || recentDocs.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    SectionHeader(
                                        title = if (currentFolderId.isEmpty()) "Notebooks & Documents" else "Documents",
                                        count = nonFolders.size
                                    )
                                }
                            }
                            items(nonFolders, key = { it.id }) { doc ->
                                DocumentGridCard(
                                    document = doc,
                                    blobStore = blobStore,
                                    onClick = { onOpenDocument(doc) },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        contextMenuDoc = doc
                                    }
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (recentDocs.isNotEmpty()) {
                            item {
                                Column {
                                    SectionHeader(title = "Jump Back In", count = recentDocs.size)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                            .padding(bottom = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        recentDocs.forEach { recent ->
                                            RecentJumpCard(
                                                document = recent,
                                                onClick = { onOpenDocument(recent) },
                                                onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    contextMenuDoc = recent
                                                }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }
                        }

                        if (folders.isNotEmpty()) {
                            item {
                                SectionHeader(title = "Folders", count = folders.size)
                            }
                            val folderPairs = folders.chunked(2)
                            items(folderPairs) { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    FolderGridCard(
                                        folder = pair[0],
                                        allDocuments = documents,
                                        onClick = { onOpenFolder(pair[0].id) },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            contextMenuDoc = pair[0]
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (pair.size > 1) {
                                        FolderGridCard(
                                            folder = pair[1],
                                            allDocuments = documents,
                                            onClick = { onOpenFolder(pair[1].id) },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                contextMenuDoc = pair[1]
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                            if (nonFolders.isNotEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    SectionHeader(
                                        title = if (currentFolderId.isEmpty()) "Notebooks & Documents" else "Documents",
                                        count = nonFolders.size
                                    )
                                }
                            }
                        }

                        items(nonFolders, key = { it.id }) { doc ->
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { dismissValue ->
                                    if (dismissValue == SwipeToDismissBoxValue.EndToStart || dismissValue == SwipeToDismissBoxValue.StartToEnd) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        documentPendingDelete = doc
                                        false
                                    } else false
                                }
                            )

                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = true,
                                enableDismissFromEndToStart = true,
                                backgroundContent = {
                                    val direction = dismissState.dismissDirection
                                    val alignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.errorContainer)
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = alignment
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete",
                                                tint = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Text(
                                                text = "Delete",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                            ) {
                                DocumentCard(
                                    document = doc,
                                    blobStore = blobStore,
                                    onClick = { onOpenDocument(doc) },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        contextMenuDoc = doc
                                    },
                                    onOptionsClick = {
                                        contextMenuDoc = doc
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (documentPendingDelete != null) {
        val docToDelete = documentPendingDelete!!
        AlertDialog(
            onDismissRequest = { documentPendingDelete = null },
            icon = {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = if (docToDelete.isFolder) "Delete Folder?" else "Delete Document?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${docToDelete.visibleName}\"? This item will be moved to trash.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteDocument(docToDelete)
                        documentPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { documentPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Context Menu Bottom Sheet
    if (contextMenuDoc != null) {
        val doc = contextMenuDoc!!
        ModalBottomSheet(
            onDismissRequest = { contextMenuDoc = null },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (doc.isFolder) Icons.Default.Folder else Icons.Default.EditNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = doc.visibleName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (doc.isFolder) "Folder" else "${maxOf(doc.pages.size, doc.pageCount)} pages • ${formatRelativeDate(doc.lastModified)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                ListItem(
                    headlineContent = { Text(if (doc.isFolder) "Open Folder" else "Open & Read") },
                    leadingContent = {
                        Icon(if (doc.isFolder) Icons.Default.FolderOpen else Icons.Default.AutoStories, contentDescription = null)
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            contextMenuDoc = null
                            if (doc.isFolder) onOpenFolder(doc.id) else onOpenDocument(doc)
                        }
                )

                if (!doc.isFolder) {
                    ListItem(
                        headlineContent = { Text("Optimize for reMarkable") },
                        leadingContent = {
                            Icon(Icons.Default.FlashOn, contentDescription = null)
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                contextMenuDoc = null
                                onOpenConverter()
                            }
                    )
                }

                ListItem(
                    headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingContent = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val target = doc
                            contextMenuDoc = null
                            documentPendingDelete = target
                        }
                )
            }
        }
    }

    // Consolidated Creation Bottom Sheet (matches container app split-dropdown-menu)
    if (showCreateMenu) {
        ModalBottomSheet(
            onDismissRequest = { showCreateMenu = false },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "New Item",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showCreateMenu = false
                            onQuickCapture()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.EditNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Column {
                            Text("New Note (Quick Capture)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Instant note or task synced to reMarkable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showCreateMenu = false
                            onOpenMeetingRecorder()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Column {
                            Text("Record Meeting Notes", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Live transcription & auto-generated notebook", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showCreateMenu = false
                            onOpenConverter()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.FlashOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Column {
                            Text("Optimize for reMarkable", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Convert PDF, CBZ or image to Paper Pro / RM2 format", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                fontSize = 11.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$count ${if (count == 1) "item" else "items"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentJumpCard(
    document: DocumentItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .width(190.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val icon = when {
                        document.fileType == "pdf" -> Icons.Default.PictureAsPdf
                        document.fileType == "epub" -> Icons.Default.Book
                        else -> Icons.Default.EditNote
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.visibleName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatRelativeDate(document.lastModified),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderGridCard(
    folder: DocumentItem,
    allDocuments: List<DocumentItem>,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val childCount = remember(folder.id, allDocuments) {
        allDocuments.count { it.parent == folder.id && it.parent != "trash" }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column {
            // Folder Thumbnail Box (16:10 aspect ratio matching container app .doc-card-folder)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.6f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }

            // Folder Title & Meta Body (.doc-card-body)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = folder.visibleName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = "FOLDER",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = if (childCount == 0) "Empty" else "$childCount ${if (childCount == 1) "item" else "items"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentCard(
    document: DocumentItem,
    blobStore: BlobStore? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onOptionsClick: (() -> Unit)? = null
) {
    var coverThumb by remember(document.id, document.hash) { mutableStateOf<Bitmap?>(null) }

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
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                modifier = Modifier.size(width = 44.dp, height = if (document.isFolder) 40.dp else 52.dp)
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = document.visibleName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (document.pinned) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = StatusWarning,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (document.localOnly) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(
                                text = "LOCAL",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    for (tag in document.tags.take(2)) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = tag.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            if (onOptionsClick != null) {
                IconButton(
                    onClick = onOptionsClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentGridCard(
    document: DocumentItem,
    blobStore: BlobStore? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    var coverThumb by remember(document.id, document.hash) { mutableStateOf<Bitmap?>(null) }

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
                            return@withContext pdf.renderPage(pageIdx, 140, 186)
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
                        targetWidth = 140,
                        targetHeight = 186
                    )
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (document.isFolder) 1.5f else 0.78f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val thumb = coverThumb
                if (thumb != null && !document.isFolder) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = if (document.isFolder) Icons.Default.Folder else Icons.Default.EditNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(if (document.isFolder) 38.dp else 44.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = document.visibleName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (document.isFolder) "Folder" else "${maxOf(document.pages.size, document.pageCount)} pgs • ${formatRelativeDate(document.lastModified)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatRelativeDate(epochMs: Long): String {
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
