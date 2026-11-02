package com.unremarkable.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unremarkable.android.R
import com.unremarkable.android.storage.AnnotationStore
import com.unremarkable.android.ui.screens.LibraryScreen
import com.unremarkable.android.ui.screens.ReaderScreen
import com.unremarkable.core.cloud.BlobStore
import com.unremarkable.core.cloud.RMCloudClient
import com.unremarkable.core.model.DocumentItem
import java.io.File

const val FOLDABLE_BREAKPOINT_DP = 600

enum class SidebarMode {
    EXPANDED,
    MINIMIZED_RAIL,
    FULLSCREEN
}

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
    cloudClient: RMCloudClient? = null,
    initialIsSidebarMinimized: Boolean = false,
    onSidebarMinimizedChanged: ((Boolean) -> Unit)? = null,
    onDeleteDocument: (DocumentItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val isFoldableOrExpanded = screenWidthDp >= FOLDABLE_BREAKPOINT_DP

    var sidebarMode by remember {
        mutableStateOf(if (initialIsSidebarMinimized) SidebarMode.MINIMIZED_RAIL else SidebarMode.EXPANDED)
    }
    var focusSearchTrigger by remember { mutableLongStateOf(0L) }
    var showFavoritesShelf by remember { mutableStateOf(false) }

    fun setSidebarModeState(mode: SidebarMode) {
        sidebarMode = mode
        onSidebarMinimizedChanged?.invoke(mode == SidebarMode.MINIMIZED_RAIL)
    }

    val sidebarWidth by animateDpAsState(
        targetValue = when (sidebarMode) {
            SidebarMode.EXPANDED -> 340.dp
            SidebarMode.MINIMIZED_RAIL -> 76.dp
            SidebarMode.FULLSCREEN -> 0.dp
        },
        animationSpec = spring(stiffness = 300f),
        label = "SidebarWidth"
    )

    val keyModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) {
            if (event.isCtrlPressed || event.isMetaPressed) {
                when (event.key) {
                    Key.F -> {
                        setSidebarModeState(SidebarMode.EXPANDED)
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

    if (isFoldableOrExpanded && selectedDocument != null) {
        // Dual-Pane Layout when reading/writing a document on Foldable / Tablet
        Row(modifier = modifier.fillMaxSize().then(keyModifier)) {
            // Sidebar Region
            if (sidebarMode != SidebarMode.FULLSCREEN) {
                Box(
                    modifier = Modifier
                        .width(sidebarWidth)
                        .fillMaxHeight()
                ) {
                    if (sidebarMode == SidebarMode.EXPANDED) {
                        LibraryScreen(
                            documents = documents,
                            isSyncing = isSyncing,
                            syncStatusMessage = syncStatusMessage,
                            syncGeneration = syncGeneration,
                            currentFolderId = currentFolderId,
                            blobStore = blobStore,
                            onOpenDocument = onSelectDocument,
                            onOpenFolder = onOpenFolder,
                            onSyncNow = onSyncNow,
                            onQuickCapture = onQuickCapture,
                            onOpenConverter = onOpenConverter,
                            onOpenSettings = onOpenSettings,
                            onOpenMeetingRecorder = onOpenMeetingRecorder,
                            onDeleteDocument = onDeleteDocument,
                            isFoldable = true,
                            focusSearchTrigger = focusSearchTrigger,
                            onMinimizeSidebar = { setSidebarModeState(SidebarMode.MINIMIZED_RAIL) },
                            onGoHome = {
                                onOpenFolder("")
                                onSelectDocument(null)
                            }
                        )
                    } else if (sidebarMode == SidebarMode.MINIMIZED_RAIL) {
                        // Minimized Icon-Only Navigation Rail
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            NavigationRail(
                                modifier = Modifier.fillMaxHeight(),
                                containerColor = MaterialTheme.colorScheme.surface,
                                header = {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
                                    ) {
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_ur_logo),
                                            contentDescription = "Un-remarkable Home",
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(9.dp))
                                                .clickable {
                                                    onOpenFolder("")
                                                    onSelectDocument(null)
                                                }
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        IconButton(
                                            onClick = { setSidebarModeState(SidebarMode.EXPANDED) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.MenuOpen,
                                                contentDescription = "Expand Sidebar",
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            ) {
                                NavigationRailItem(
                                    selected = currentFolderId.isEmpty(),
                                    onClick = {
                                        onOpenFolder("")
                                        onSelectDocument(null)
                                    },
                                    icon = { Icon(Icons.Default.Folder, contentDescription = "Library") },
                                    label = { Text("Library", style = MaterialTheme.typography.labelSmall) }
                                )
                                NavigationRailItem(
                                    selected = false,
                                    onClick = {
                                        setSidebarModeState(SidebarMode.EXPANDED)
                                        focusSearchTrigger = System.currentTimeMillis()
                                    },
                                    icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                                    label = { Text("Search", style = MaterialTheme.typography.labelSmall) }
                                )
                                NavigationRailItem(
                                    selected = false,
                                    onClick = onOpenMeetingRecorder,
                                    icon = { Icon(Icons.Default.Mic, contentDescription = "Meeting") },
                                    label = { Text("Meeting", style = MaterialTheme.typography.labelSmall) }
                                )
                                NavigationRailItem(
                                    selected = false,
                                    onClick = { showFavoritesShelf = true },
                                    icon = { Icon(Icons.Default.Star, contentDescription = "Favorites") },
                                    label = { Text("Favs", style = MaterialTheme.typography.labelSmall) }
                                )
                                NavigationRailItem(
                                    selected = false,
                                    onClick = onQuickCapture,
                                    icon = { Icon(Icons.Default.Add, contentDescription = "Quick Capture") },
                                    label = { Text("Capture", style = MaterialTheme.typography.labelSmall) }
                                )
                                NavigationRailItem(
                                    selected = false,
                                    onClick = onOpenConverter,
                                    icon = { Icon(Icons.Default.FlashOn, contentDescription = "Optimizer") },
                                    label = { Text("Convert", style = MaterialTheme.typography.labelSmall) }
                                )
                                NavigationRailItem(
                                    selected = false,
                                    onClick = onOpenSettings,
                                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                    label = { Text("Settings", style = MaterialTheme.typography.labelSmall) }
                                )

                                Spacer(modifier = Modifier.weight(1f))

                                IconButton(
                                    onClick = { setSidebarModeState(SidebarMode.FULLSCREEN) },
                                    modifier = Modifier.padding(bottom = 12.dp)
                                ) {
                                    Icon(Icons.Default.Fullscreen, contentDescription = "Hide Sidebar")
                                }
                            }
                        }
                    }
                }

                VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            }

            // Right Pane (Reader Canvas)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                ReaderScreen(
                    document = selectedDocument,
                    allDocuments = documents,
                    blobStore = blobStore,
                    annotationStore = annotationStore,
                    cloudClient = cloudClient,
                    isFoldable = true,
                    isFullscreen = sidebarMode == SidebarMode.FULLSCREEN,
                    onToggleFullscreen = {
                        setSidebarModeState(
                            if (sidebarMode == SidebarMode.FULLSCREEN) {
                                SidebarMode.EXPANDED
                            } else {
                                SidebarMode.FULLSCREEN
                            }
                        )
                    },
                    onSelectDocument = onSelectDocument,
                    onOpenMeetingRecorder = onOpenMeetingRecorder,
                    onOptimizeCurrentPdf = onOptimizeCurrentPdf,
                    onBack = { onSelectDocument(null) }
                )

                // Global Favorites Shelf overlay
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
    } else if (selectedDocument != null) {
        // Single-Pane Phone Layout (Reader Screen)
        ReaderScreen(
            document = selectedDocument,
            allDocuments = documents,
            blobStore = blobStore,
            annotationStore = annotationStore,
            cloudClient = cloudClient,
            isFoldable = false,
            isFullscreen = false,
            onSelectDocument = onSelectDocument,
            onOpenMeetingRecorder = onOpenMeetingRecorder,
            onOptimizeCurrentPdf = onOptimizeCurrentPdf,
            onBack = { onSelectDocument(null) },
            modifier = modifier.then(keyModifier)
        )
    } else {
        // Fullscreen Library (both for Foldables and Phones when browsing the library)
        // Fills the whole page so folder and document grids take full screen width!
        LibraryScreen(
            documents = documents,
            isSyncing = isSyncing,
            syncStatusMessage = syncStatusMessage,
            syncGeneration = syncGeneration,
            currentFolderId = currentFolderId,
            blobStore = blobStore,
            onOpenDocument = onSelectDocument,
            onOpenFolder = onOpenFolder,
            onSyncNow = onSyncNow,
            onQuickCapture = onQuickCapture,
            onOpenConverter = onOpenConverter,
            onOpenSettings = onOpenSettings,
            onOpenMeetingRecorder = onOpenMeetingRecorder,
            onDeleteDocument = onDeleteDocument,
            isFoldable = isFoldableOrExpanded,
            focusSearchTrigger = focusSearchTrigger,
            onGoHome = {
                onOpenFolder("")
                onSelectDocument(null)
            },
            modifier = modifier.fillMaxSize().then(keyModifier)
        )
    }
}
