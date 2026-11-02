package com.unremarkable.android.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unremarkable.android.R
import com.unremarkable.core.model.DocumentItem

enum class DocumentFilter(val label: String) {
    ALL("All"),
    NOTEBOOKS("Notebooks"),
    PDFS("PDFs"),
    COMICS("Comics"),
    PINNED("Pinned ★")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    documents: List<DocumentItem>,
    isSyncing: Boolean = false,
    syncGeneration: Long = 0L,
    currentFolderId: String = "",
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

    val filteredDocs = remember(documents, searchQuery, currentFolderId, selectedFilter) {
        val baseList = if (selectedFilter == DocumentFilter.PINNED) {
            // Pinned shows all starred documents across the entire library, matching reMarkable OS
            if (searchQuery.isBlank()) {
                documents.filter { it.pinned }
            } else {
                documents.filter {
                    it.pinned && (
                        it.visibleName.contains(searchQuery, ignoreCase = true) ||
                        it.tags.any { tag -> tag.contains(searchQuery, ignoreCase = true) }
                    )
                }
            }
        } else if (searchQuery.isBlank()) {
            documents.filter { it.parent == currentFolderId }
        } else {
            documents.filter {
                it.visibleName.contains(searchQuery, ignoreCase = true) ||
                        it.tags.any { tag -> tag.contains(searchQuery, ignoreCase = true) }
            }
        }

        val filtered = when (selectedFilter) {
            DocumentFilter.ALL -> baseList
            DocumentFilter.PINNED -> baseList
            DocumentFilter.NOTEBOOKS -> baseList.filter { !it.isFolder && (it.fileType.isBlank() || it.fileType == "notebook" || it.tags.contains("Note") || it.tags.contains("Task")) }
            DocumentFilter.PDFS -> baseList.filter { !it.isFolder && (it.fileType == "pdf" || it.visibleName.endsWith(".pdf", ignoreCase = true)) }
            DocumentFilter.COMICS -> baseList.filter { !it.isFolder && (it.tags.contains("Comic") || it.tags.contains("Optimized")) }
        }

        // Folders first, then pinned, then by date
        filtered.sortedWith(
            compareByDescending<DocumentItem> { it.isFolder }
                .thenByDescending { it.pinned }
                .thenByDescending { it.lastModified }
        )
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
                    IconButton(onClick = onOpenMeetingRecorder) {
                        Icon(Icons.Default.Mic, contentDescription = "Record Meeting")
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
            // Search Bar
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

            // Category Filter Chips
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

            // Sync Status Pill & Breadcrumb
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isSyncing) "Syncing with cloud..." else "Cloud Generation: $syncGeneration",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (currentFolderId.isNotEmpty()) {
                    TextButton(onClick = { onOpenFolder("") }) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Up to Root", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Document List
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
                            text = "Tap + to capture notes, or sync your reMarkable account in Settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredDocs, key = { it.id }) { doc ->
                        DocumentCard(
                            document = doc,
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
    onClick: () -> Unit
) {
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
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                color = if (document.isFolder) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (document.isFolder) Icons.Default.Folder else Icons.Default.Article,
                        contentDescription = null,
                        tint = if (document.isFolder) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
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
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!document.isFolder) {
                        Text(
                            text = "${document.pageCount} ${if (document.pageCount == 1) "page" else "pages"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    for (tag in document.tags.take(3)) {
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
