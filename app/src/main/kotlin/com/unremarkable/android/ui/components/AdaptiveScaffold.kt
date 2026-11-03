package com.unremarkable.android.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unremarkable.android.R
import com.unremarkable.android.storage.AnnotationStore
import com.unremarkable.android.storage.AppSettings
import com.unremarkable.android.ui.screens.DocumentFilter
import com.unremarkable.android.ui.screens.LibraryScreen
import com.unremarkable.android.ui.screens.LibrarySort
import com.unremarkable.android.ui.screens.LibraryViewMode
import com.unremarkable.android.ui.screens.ReaderScreen
import com.unremarkable.core.cloud.BlobStore
import com.unremarkable.core.cloud.RMCloudClient
import com.unremarkable.core.model.DocumentItem
import java.io.File

const val FOLDABLE_BREAKPOINT_DP = 600

private val EaseOutExpo = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val SIDEBAR_MINIMIZED_WIDTH = 72.dp
private val SIDEBAR_EXPANDED_WIDTH = 248.dp

@Composable
fun AdaptiveScaffold(
    documents: List<DocumentItem>,
    isSyncing: Boolean,
    syncStatusMessage: String = "",
    syncGeneration: Long,
    currentFolderId: String,
    onOpenFolder: (String) -> Unit,
    onSyncNow: () -> Unit,
    onQuickCapture: () -> Unit,
    onOpenConverter: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMeetingRecorder: () -> Unit = {},
    onOptimizeCurrentPdf: ((File, String) -> Unit)? = null,
    selectedDocument: DocumentItem?,
    onSelectDocument: (DocumentItem?) -> Unit,
    blobStore: BlobStore? = null,
    annotationStore: AnnotationStore? = null,
    appSettings: AppSettings? = null,
    cloudClient: RMCloudClient? = null,
    initialIsSidebarMinimized: Boolean = false,
    onSidebarMinimizedChanged: ((Boolean) -> Unit)? = null,
    onDeleteDocument: (DocumentItem) -> Unit = {},
    onPageChanged: ((docId: String, pageIndex: Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isFoldableOrExpanded = configuration.screenWidthDp >= FOLDABLE_BREAKPOINT_DP

    var isSidebarMinimized by remember { mutableStateOf(initialIsSidebarMinimized) }
    var isReaderFullscreen by remember(selectedDocument?.id) { mutableStateOf(false) }
    var focusSearchTrigger by remember { mutableLongStateOf(0L) }
    var showFavoritesShelf by remember { mutableStateOf(false) }

    // Hoisted Library UI & per-folder scroll states so navigating between Grid <-> Reader never resets context
    val gridStates = remember { mutableMapOf<String, LazyGridState>() }
    val listStates = remember { mutableMapOf<String, LazyListState>() }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(DocumentFilter.ALL) }
    var sortMode by remember { mutableStateOf(LibrarySort.MODIFIED) }
    var viewMode by remember {
        mutableStateOf(if (appSettings?.isGridView != false) LibraryViewMode.GRID else LibraryViewMode.LIST)
    }

    fun updateViewMode(mode: LibraryViewMode) {
        viewMode = mode
        appSettings?.isGridView = (mode == LibraryViewMode.GRID)
    }

    fun setMinimized(minimized: Boolean) {
        isSidebarMinimized = minimized
        isReaderFullscreen = false
        onSidebarMinimizedChanged?.invoke(minimized)
    }

    fun goHome() {
        searchQuery = ""
        selectedFilter = DocumentFilter.ALL
        onOpenFolder("")
        onSelectDocument(null)
    }

    val keyModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) {
            if (event.isCtrlPressed || event.isMetaPressed) {
                when (event.key) {
                    Key.F -> {
                        onSelectDocument(null)
                        focusSearchTrigger = System.currentTimeMillis()
                        true
                    }
                    Key.N -> {
                        onQuickCapture()
                        true
                    }
                    Key.R -> {
                        onSyncNow()
                        true
                    }
                    else -> false
                }
            } else if (event.key == Key.Escape) {
                if (selectedDocument != null) {
                    onSelectDocument(null)
                    true
                } else false
            } else false
        } else false
    }

    if (isFoldableOrExpanded) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .then(keyModifier)
        ) {
            AnimatedVisibility(
                visible = !(selectedDocument != null && isReaderFullscreen),
                enter = expandHorizontally(tween(240, easing = EaseOutExpo)) + fadeIn(tween(180)),
                exit = shrinkHorizontally(tween(200, easing = EaseOutExpo)) + fadeOut(tween(140)),
                label = "FoldableSideMenuVisibility"
            ) {
                Row(modifier = Modifier.fillMaxHeight()) {
                    FoldableSideMenu(
                        isMinimized = isSidebarMinimized,
                        isSyncing = isSyncing,
                        documents = documents,
                        currentFolderId = currentFolderId,
                        selectedDocument = selectedDocument,
                        selectedFilter = selectedFilter,
                        searchQuery = searchQuery,
                        onToggleMinimized = { setMinimized(!isSidebarMinimized) },
                        onGoHome = ::goHome,
                        onBackToLibrary = {
                            if (selectedDocument != null) {
                                onSelectDocument(null)
                            } else {
                                goHome()
                            }
                        },
                        onSearchClick = {
                            if (selectedDocument != null) {
                                onSelectDocument(null)
                            }
                            focusSearchTrigger = System.currentTimeMillis()
                        },
                        onPinnedClick = {
                            if (selectedDocument != null) {
                                showFavoritesShelf = true
                            } else {
                                selectedFilter = if (selectedFilter == DocumentFilter.PINNED) {
                                    DocumentFilter.ALL
                                } else {
                                    DocumentFilter.PINNED
                                }
                            }
                        },
                        onOpenMeetingRecorder = onOpenMeetingRecorder,
                        onQuickCapture = onQuickCapture,
                        onOpenConverter = onOpenConverter,
                        onSyncNow = onSyncNow,
                        onOpenSettings = onOpenSettings,
                        onEnterFullscreen = { isReaderFullscreen = true },
                        onSelectDocument = { doc -> onSelectDocument(doc) }
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                AnimatedContent(
                    targetState = selectedDocument,
                    contentKey = { it != null },
                    transitionSpec = {
                        if (targetState != null) {
                            (fadeIn(tween(220, easing = EaseOutExpo)) +
                                scaleIn(initialScale = 0.97f, animationSpec = tween(240, easing = EaseOutExpo)))
                                .togetherWith(fadeOut(tween(140)))
                        } else {
                            (fadeIn(tween(200, easing = EaseOutExpo)) +
                                scaleIn(initialScale = 0.985f, animationSpec = tween(220, easing = EaseOutExpo)))
                                .togetherWith(
                                    fadeOut(tween(150)) +
                                        scaleOut(targetScale = 0.97f, animationSpec = tween(180, easing = EaseOutExpo))
                                )
                        }
                    },
                    label = "FoldableStageTransition",
                    modifier = Modifier.fillMaxSize()
                ) { targetDoc ->
                    if (targetDoc != null) {
                        ReaderScreen(
                            document = targetDoc,
                            allDocuments = documents,
                            blobStore = blobStore,
                            annotationStore = annotationStore,
                            appSettings = appSettings,
                            cloudClient = cloudClient,
                            isFoldable = true,
                            isFullscreen = isReaderFullscreen,
                            isBackEnabled = selectedDocument != null,
                            onToggleFullscreen = { isReaderFullscreen = !isReaderFullscreen },
                            onSelectDocument = { onSelectDocument(it) },
                            onOpenMeetingRecorder = onOpenMeetingRecorder,
                            onOptimizeCurrentPdf = onOptimizeCurrentPdf,
                            onPageChanged = onPageChanged,
                            onBack = { onSelectDocument(null) }
                        )
                    } else {
                        LibraryScreen(
                            documents = documents,
                            isSyncing = isSyncing,
                            syncStatusMessage = syncStatusMessage,
                            syncGeneration = syncGeneration,
                            currentFolderId = currentFolderId,
                            blobStore = blobStore,
                            isFoldable = true,
                            hasSideRail = true,
                            isBackEnabled = selectedDocument == null,
                            focusSearchTrigger = focusSearchTrigger,
                            gridStates = gridStates,
                            listStates = listStates,
                            externalSearchQuery = searchQuery,
                            onExternalSearchQueryChange = { searchQuery = it },
                            externalFilter = selectedFilter,
                            onExternalFilterChange = { selectedFilter = it },
                            externalSortMode = sortMode,
                            onExternalSortModeChange = { sortMode = it },
                            externalViewMode = viewMode,
                            onExternalViewModeChange = ::updateViewMode,
                            onMinimizeSidebar = { setMinimized(!isSidebarMinimized) },
                            onGoHome = ::goHome,
                            onOpenDocument = { onSelectDocument(it) },
                            onOpenFolder = onOpenFolder,
                            onSyncNow = onSyncNow,
                            onQuickCapture = onQuickCapture,
                            onOpenConverter = onOpenConverter,
                            onOpenSettings = onOpenSettings,
                            onOpenMeetingRecorder = onOpenMeetingRecorder,
                            onDeleteDocument = onDeleteDocument,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                FavoritesShelf(
                    isVisible = showFavoritesShelf,
                    documents = documents,
                    onSelectDocument = {
                        onSelectDocument(it)
                        showFavoritesShelf = false
                    },
                    onDismiss = { showFavoritesShelf = false }
                )
            }
        }
    } else {
        // Phone Single-Pane Layout with Directional Slide + Fade Transitions
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .then(keyModifier)
        ) {
            AnimatedContent(
                targetState = selectedDocument,
                contentKey = { it != null },
                transitionSpec = {
                    if (targetState != null) {
                        (slideInHorizontally(tween(260, easing = EaseOutExpo)) { it / 7 } +
                            fadeIn(tween(200)))
                            .togetherWith(
                                slideOutHorizontally(tween(240, easing = EaseOutExpo)) { -it / 14 } +
                                    fadeOut(tween(150))
                            )
                    } else {
                        (slideInHorizontally(tween(240, easing = EaseOutExpo)) { -it / 14 } +
                            fadeIn(tween(180)))
                            .togetherWith(
                                slideOutHorizontally(tween(240, easing = EaseOutExpo)) { it / 7 } +
                                    fadeOut(tween(150))
                            )
                    }
                },
                label = "PhoneStageTransition",
                modifier = Modifier.fillMaxSize()
            ) { targetDoc ->
                if (targetDoc != null) {
                    ReaderScreen(
                        document = targetDoc,
                        allDocuments = documents,
                        blobStore = blobStore,
                        annotationStore = annotationStore,
                        appSettings = appSettings,
                        cloudClient = cloudClient,
                        isFoldable = false,
                        isFullscreen = false,
                        isBackEnabled = selectedDocument != null,
                        onSelectDocument = { onSelectDocument(it) },
                        onOpenMeetingRecorder = onOpenMeetingRecorder,
                        onOptimizeCurrentPdf = onOptimizeCurrentPdf,
                        onPageChanged = onPageChanged,
                        onBack = { onSelectDocument(null) }
                    )
                } else {
                    LibraryScreen(
                        documents = documents,
                        isSyncing = isSyncing,
                        syncStatusMessage = syncStatusMessage,
                        syncGeneration = syncGeneration,
                        currentFolderId = currentFolderId,
                        blobStore = blobStore,
                        isFoldable = false,
                        hasSideRail = false,
                        isBackEnabled = selectedDocument == null,
                        focusSearchTrigger = focusSearchTrigger,
                        gridStates = gridStates,
                        listStates = listStates,
                        externalSearchQuery = searchQuery,
                        onExternalSearchQueryChange = { searchQuery = it },
                        externalFilter = selectedFilter,
                        onExternalFilterChange = { selectedFilter = it },
                        externalSortMode = sortMode,
                        onExternalSortModeChange = { sortMode = it },
                        externalViewMode = viewMode,
                        onExternalViewModeChange = ::updateViewMode,
                        onGoHome = ::goHome,
                        onOpenDocument = { onSelectDocument(it) },
                        onOpenFolder = onOpenFolder,
                        onSyncNow = onSyncNow,
                        onQuickCapture = onQuickCapture,
                        onOpenConverter = onOpenConverter,
                        onOpenSettings = onOpenSettings,
                        onOpenMeetingRecorder = onOpenMeetingRecorder,
                        onDeleteDocument = onDeleteDocument,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun FoldableSideMenu(
    isMinimized: Boolean,
    isSyncing: Boolean,
    documents: List<DocumentItem>,
    currentFolderId: String,
    selectedDocument: DocumentItem?,
    selectedFilter: DocumentFilter,
    searchQuery: String,
    onToggleMinimized: () -> Unit,
    onGoHome: () -> Unit,
    onBackToLibrary: () -> Unit,
    onSearchClick: () -> Unit,
    onPinnedClick: () -> Unit,
    onOpenMeetingRecorder: () -> Unit,
    onQuickCapture: () -> Unit,
    onOpenConverter: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onSelectDocument: (DocumentItem) -> Unit
) {
    val sidebarWidth by animateDpAsState(
        targetValue = if (isMinimized) SIDEBAR_MINIMIZED_WIDTH else SIDEBAR_EXPANDED_WIDTH,
        animationSpec = tween(durationMillis = 240, easing = EaseOutExpo),
        label = "FoldableSidebarWidth"
    )
    val expandAlpha by animateFloatAsState(
        targetValue = if (isMinimized) 0f else 1f,
        animationSpec = tween(durationMillis = 190, easing = EaseOutExpo),
        label = "FoldableSidebarLabelAlpha"
    )

    val siblingDocs = remember(documents, currentFolderId, selectedDocument?.parent) {
        val activeParent = selectedDocument?.parent ?: currentFolderId
        documents
            .filter { !it.isFolder && !it.deleted && it.parent != "trash" && it.parent == activeParent }
            .sortedByDescending { it.lastModified }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .width(sidebarWidth)
            .fillMaxHeight()
            .clipToBounds()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 1. Brand Home Button (Logo always locked at leading 48dp center)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onGoHome),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier.size(width = 48.dp, height = 44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_ur_logo),
                        contentDescription = "Un-remarkable Home",
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                Text(
                    text = "Un-remarkable",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 52.dp)
                        .wrapContentWidth(align = Alignment.Start, unbounded = true)
                        .width(168.dp)
                        .graphicsLayer { alpha = expandAlpha }
                )
            }

            // 2. Collapse / Expand Side Menu Toggle (stays at exact same X/Y in both modes)
            SideMenuItem(
                icon = if (isMinimized) Icons.AutoMirrored.Filled.ViewSidebar else Icons.AutoMirrored.Filled.MenuOpen,
                label = if (isMinimized) "Expand Menu" else "Minimize Menu",
                selected = false,
                expandAlpha = expandAlpha,
                onClick = onToggleMinimized
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // 3. Core Workspace Navigation
            val isLibraryActive = selectedDocument == null &&
                selectedFilter != DocumentFilter.PINNED &&
                searchQuery.isBlank()

            SideMenuItem(
                icon = if (selectedDocument != null) Icons.Default.GridView else Icons.Default.Folder,
                label = if (selectedDocument != null) "Back to Grid" else "Library",
                selected = isLibraryActive,
                expandAlpha = expandAlpha,
                onClick = onBackToLibrary
            )

            SideMenuItem(
                icon = Icons.Default.Search,
                label = "Search",
                selected = selectedDocument == null && searchQuery.isNotBlank(),
                expandAlpha = expandAlpha,
                onClick = onSearchClick
            )

            SideMenuItem(
                icon = Icons.Default.Star,
                label = "Pinned",
                selected = selectedDocument == null && selectedFilter == DocumentFilter.PINNED,
                expandAlpha = expandAlpha,
                onClick = onPinnedClick
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // 4. Creation & Capture Actions
            SideMenuItem(
                icon = Icons.Default.Mic,
                label = "Record Meeting",
                selected = false,
                expandAlpha = expandAlpha,
                onClick = onOpenMeetingRecorder
            )

            SideMenuItem(
                icon = Icons.Default.EditNote,
                label = "Quick Note",
                selected = false,
                expandAlpha = expandAlpha,
                onClick = onQuickCapture
            )

            SideMenuItem(
                icon = Icons.Default.FlashOn,
                label = "Optimize File",
                selected = false,
                expandAlpha = expandAlpha,
                onClick = onOpenConverter
            )

            // 5. Contextual Sibling File Switcher when reading a document with expanded menu
            if (selectedDocument != null && !isMinimized && siblingDocs.size > 1) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    text = "IN THIS FOLDER",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                        fontSize = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                        .wrapContentWidth(align = Alignment.Start, unbounded = true)
                        .width(196.dp)
                        .graphicsLayer { alpha = expandAlpha }
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(siblingDocs, key = { it.id }) { doc ->
                        val isCurrent = doc.id == selectedDocument.id
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                                )
                                .clickable {
                                    if (!isCurrent) onSelectDocument(doc)
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = doc.visibleName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.graphicsLayer { alpha = expandAlpha }
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // 6. Bottom Utility Controls
            if (selectedDocument != null) {
                SideMenuItem(
                    icon = Icons.Default.Fullscreen,
                    label = "Fullscreen Reader",
                    selected = false,
                    expandAlpha = expandAlpha,
                    onClick = onEnterFullscreen
                )
            }

            // Sync Item (shows spinner when actively syncing)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !isSyncing, onClick = onSyncNow),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier.size(width = 48.dp, height = 42.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Sync Library",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Text(
                    text = if (isSyncing) "Syncing..." else "Sync Cloud",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 52.dp)
                        .wrapContentWidth(align = Alignment.Start, unbounded = true)
                        .width(164.dp)
                        .graphicsLayer { alpha = expandAlpha }
                )
            }

            SideMenuItem(
                icon = Icons.Default.Settings,
                label = "Settings",
                selected = false,
                expandAlpha = expandAlpha,
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun SideMenuItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    expandAlpha: Float,
    onClick: () -> Unit
) {
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier.size(width = 48.dp, height = 42.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = if (selected) MaterialTheme.colorScheme.onSurface else contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 52.dp)
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .width(164.dp)
                .graphicsLayer { alpha = expandAlpha }
        )
    }
}
