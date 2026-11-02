package com.unremarkable.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unremarkable.android.storage.AnnotationStore
import com.unremarkable.android.ui.screens.LibraryScreen
import com.unremarkable.android.ui.screens.ReaderScreen
import com.unremarkable.core.cloud.BlobStore
import com.unremarkable.core.cloud.RMCloudClient
import com.unremarkable.core.model.DocumentItem

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
    syncGeneration: Long,
    currentFolderId: String,
    onOpenFolder: (String) -> Unit,
    onSyncNow: () -> Unit,
    onQuickCapture: () -> Unit,
    onOpenConverter: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMeetingRecorder: () -> Unit = {},
    selectedDocument: DocumentItem?,
    onSelectDocument: (DocumentItem?) -> Unit,
    blobStore: BlobStore? = null,
    annotationStore: AnnotationStore? = null,
    cloudClient: RMCloudClient? = null,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val isFoldableOrExpanded = screenWidthDp >= FOLDABLE_BREAKPOINT_DP

    var sidebarMode by remember { mutableStateOf(SidebarMode.EXPANDED) }
    var showFavoritesShelf by remember { mutableStateOf(false) }

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
                            syncGeneration = syncGeneration,
                            currentFolderId = currentFolderId,
                            onOpenDocument = onSelectDocument,
                            onOpenFolder = onOpenFolder,
                            onSyncNow = onSyncNow,
                            onQuickCapture = onQuickCapture,
                            onOpenConverter = onOpenConverter,
                            onOpenSettings = onOpenSettings,
                            onOpenMeetingRecorder = onOpenMeetingRecorder,
                            isFoldable = true,
                            onMinimizeSidebar = { sidebarMode = SidebarMode.MINIMIZED_RAIL }
                        )
                    } else if (sidebarMode == SidebarMode.MINIMIZED_RAIL) {
                        // Minimized Icon-Only Navigation Rail
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            NavigationRail(
                                modifier = Modifier.fillMaxHeight(),
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                header = {
                                    IconButton(
                                        onClick = { sidebarMode = SidebarMode.EXPANDED },
                                        modifier = Modifier.padding(top = 8.dp)
                                    ) {
                                        Icon(Icons.Default.MenuOpen, contentDescription = "Expand Sidebar")
                                    }
                                }
                            ) {
                                NavigationRailItem(
                                    selected = selectedDocument == null,
                                    onClick = { onSelectDocument(null) },
                                    icon = { Icon(Icons.Default.Folder, contentDescription = "Library") },
                                    label = { Text("Library", style = MaterialTheme.typography.labelSmall) }
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
                                    onClick = { sidebarMode = SidebarMode.FULLSCREEN },
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
                            sidebarMode = if (sidebarMode == SidebarMode.FULLSCREEN) {
                                SidebarMode.EXPANDED
                            } else {
                                SidebarMode.FULLSCREEN
                            }
                        },
                        onSelectDocument = onSelectDocument,
                        onOpenMeetingRecorder = onOpenMeetingRecorder,
                        onBack = { onSelectDocument(null) }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .padding(40.dp)
                                .widthIn(max = 440.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(36.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = CircleShape,
                                    modifier = Modifier.size(64.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.AutoStories,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Ready to Read & Write",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Foldable dual-pane active. Select any notebook from the left pane, or collapse the menu to single-column rail.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(onClick = { sidebarMode = SidebarMode.MINIMIZED_RAIL }) {
                                        Icon(Icons.Default.ViewSidebar, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Minimize to Rail")
                                    }
                                    Button(onClick = onQuickCapture) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Quick Capture")
                                    }
                                }
                            }
                        }
                    }
                }

                // If in fullscreen on placeholder screen, floating pill to restore sidebar
                if (sidebarMode == SidebarMode.FULLSCREEN && selectedDocument == null) {
                    FloatingActionButton(
                        onClick = { sidebarMode = SidebarMode.EXPANDED },
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
                onBack = { onSelectDocument(null) },
                modifier = modifier
            )
        } else {
            LibraryScreen(
                documents = documents,
                isSyncing = isSyncing,
                syncGeneration = syncGeneration,
                currentFolderId = currentFolderId,
                onOpenDocument = onSelectDocument,
                onOpenFolder = onOpenFolder,
                onSyncNow = onSyncNow,
                onQuickCapture = onQuickCapture,
                onOpenConverter = onOpenConverter,
                onOpenSettings = onOpenSettings,
                onOpenMeetingRecorder = onOpenMeetingRecorder,
                isFoldable = false,
                modifier = modifier
            )
        }
    }
}
