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

    if (isFoldableOrExpanded) {
        // Dual-Pane Layout for Foldable / Tablet
        Row(modifier = modifier.fillMaxSize()) {
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
                                    selected = selectedDocument == null && currentFolderId.isEmpty(),
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

            // Right Pane (Reader Canvas or Placeholder)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (selectedDocument != null) {
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
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier
                                .padding(24.dp)
                                .widthIn(max = 380.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = CircleShape,
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.AutoStories,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Ready to Read & Write",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "Foldable dual-pane active. Select any document from the left pane, or collapse the menu to a single-column rail.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Button(
                                        onClick = onQuickCapture,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("New Note (Quick Capture)", maxLines = 1, softWrap = false)
                                    }
                                    OutlinedButton(
                                        onClick = { setSidebarModeState(SidebarMode.MINIMIZED_RAIL) },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ViewSidebar, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Collapse to Rail", maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                }

                // If in fullscreen on placeholder screen, floating pill to restore sidebar
                if (sidebarMode == SidebarMode.FULLSCREEN && selectedDocument == null) {
                    FloatingActionButton(
                        onClick = { setSidebarModeState(SidebarMode.EXPANDED) },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = "Restore Menu")
                    }
                }

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
    } else {
        // Single-Pane Phone Layout
        if (selectedDocument != null) {
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
                modifier = modifier
            )
        } else {
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
                isFoldable = false,
                onGoHome = {
                    onOpenFolder("")
                    onSelectDocument(null)
                },
                modifier = modifier
            )
        }
    }
}
