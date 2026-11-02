package com.unremarkable.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.unremarkable.android.ui.screens.LibraryScreen
import com.unremarkable.android.ui.screens.ReaderScreen
import com.unremarkable.core.model.DocumentItem

const val FOLDABLE_BREAKPOINT_DP = 600

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
    selectedDocument: DocumentItem?,
    onSelectDocument: (DocumentItem?) -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val isFoldableOrExpanded = screenWidthDp >= FOLDABLE_BREAKPOINT_DP

    if (isFoldableOrExpanded) {
        // Dual-Pane Layout for Foldable / Tablet
        Row(modifier = modifier.fillMaxSize()) {
            // Left Pane (Library & Folders)
            Box(
                modifier = Modifier
                    .width(380.dp)
                    .fillMaxHeight()
            ) {
                LibraryScreen(
                    documents = documents,
                    isSyncing = isSyncing,
                    syncGeneration = syncGeneration,
                    currentFolderId = currentFolderId,
                    onOpenDocument = onSelectDocument,
                    onOpenFolder = onOpenFolder,
                    onSyncNow = onSyncNow,
                    onQuickCapture = onQuickCapture,
                    onOpenConverter = onOpenConverter
                )
            }

            VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            // Right Pane (Reader or Details)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (selectedDocument != null) {
                    ReaderScreen(
                        document = selectedDocument,
                        onBack = { onSelectDocument(null) }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoStories,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(56.dp)
                                )
                                Text(
                                    text = "Select a document to read",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Foldable dual-pane active • Tap any notebook or PDF from the library",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Single-Pane Phone Layout
        if (selectedDocument != null) {
            ReaderScreen(
                document = selectedDocument,
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
                modifier = modifier
            )
        }
    }
}
