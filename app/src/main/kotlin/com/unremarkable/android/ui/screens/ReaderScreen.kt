package com.unremarkable.android.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unremarkable.android.render.PdfDocumentRenderer
import com.unremarkable.android.storage.AnnotationStore
import com.unremarkable.android.ui.components.DrawnStroke
import com.unremarkable.android.ui.components.DrawingCanvas
import com.unremarkable.android.ui.components.FavoritesShelf
import com.unremarkable.android.ui.theme.Zinc800
import com.unremarkable.android.ui.theme.Zinc900
import com.unremarkable.core.cloud.BlobStore
import com.unremarkable.core.cloud.RMCloudClient
import com.unremarkable.core.model.DocumentItem
import kotlinx.coroutines.delay

@Composable
fun ReaderScreen(
    document: DocumentItem,
    allDocuments: List<DocumentItem> = emptyList(),
    blobStore: BlobStore? = null,
    annotationStore: AnnotationStore? = null,
    cloudClient: RMCloudClient? = null,
    isFoldable: Boolean = false,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onSelectDocument: (DocumentItem) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentPage by remember(document.id) { mutableIntStateOf(1) }
    var totalPages by remember(document.id) { mutableIntStateOf(document.pageCount.coerceAtLeast(1)) }
    var isDarkMode by remember { mutableStateOf(false) }
    var isHudVisible by remember { mutableStateOf(true) }
    var isDrawingMode by remember { mutableStateOf(false) }
    var showFavoritesShelf by remember { mutableStateOf(false) }
    var pageStrokesMap by remember(document.id) { mutableStateOf<Map<Int, List<DrawnStroke>>>(emptyMap()) }
    val currentStrokes = pageStrokesMap[currentPage].orEmpty()

    // Load persisted strokes for current page
    LaunchedEffect(document.id, currentPage) {
        if (annotationStore != null) {
            val loaded = annotationStore.loadStrokes(document.id, currentPage)
            pageStrokesMap = pageStrokesMap + (currentPage to loaded)
        }
    }

    // Auto-hide HUD after 3.5 seconds unless drawing mode is active
    LaunchedEffect(isHudVisible, isDrawingMode) {
        if (isHudVisible && !isDrawingMode) {
            delay(3500)
            isHudVisible = false
        }
    }

    // Detect PDF document payload
    val pdfEntry = document.subfiles.entries.find { it.key.endsWith(".pdf", ignoreCase = true) }
    val pdfHash = pdfEntry?.value ?: if (document.fileType.equals("pdf", ignoreCase = true)) document.hash else null
    val isPdf = !pdfHash.isNullOrBlank()

    var pdfRenderer by remember(document.id, pdfHash) { mutableStateOf<PdfDocumentRenderer?>(null) }
    var isLoadingFile by remember(document.id) { mutableStateOf(false) }
    var currentBitmap by remember(document.id, currentPage) { mutableStateOf<Bitmap?>(null) }

    // Background PDF Loader & Downloader
    LaunchedEffect(document.id, pdfHash) {
        if (isPdf && blobStore != null && pdfHash != null) {
            val blobFile = blobStore.getBlobFile(pdfHash)
            if (!blobFile.exists() || blobFile.length() == 0L) {
                if (cloudClient != null) {
                    isLoadingFile = true
                    try {
                        cloudClient.downloadBlobToFile(pdfHash, blobFile, pdfEntry?.key ?: "${document.id}.pdf")
                    } catch (_: Exception) {}
                    isLoadingFile = false
                }
            }
            if (blobFile.exists() && blobFile.length() > 0L) {
                val renderer = PdfDocumentRenderer.open(blobFile)
                if (renderer != null) {
                    pdfRenderer = renderer
                    totalPages = renderer.pageCount.coerceAtLeast(1)
                }
            }
        }
    }

    // Render PDF page on page change
    LaunchedEffect(currentPage, pdfRenderer) {
        val renderer = pdfRenderer
        if (renderer != null) {
            currentBitmap = renderer.renderPage(currentPage - 1)
        }
    }

    DisposableEffect(pdfRenderer) {
        onDispose {
            pdfRenderer?.close()
        }
    }

    val canvasBackground = if (isDarkMode) Zinc900 else Color.White
    val inkColor = if (isDarkMode) Color(0xFFF4F4F5) else Color(0xFF18181B)

    val darkColorMatrix = remember {
        ColorMatrix(
            floatArrayOf(
                -0.85f, 0f, 0f, 0f, 230f,
                0f, -0.85f, 0f, 0f, 230f,
                0f, 0f, -0.85f, 0f, 230f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(canvasBackground)
            // Two-finger pull down gesture detector for Favorites Shelf
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2) {
                            val yDeltas = event.changes.map { it.position.y - it.previousPosition.y }
                            if (yDeltas.all { it > 18f }) {
                                showFavoritesShelf = true
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // Edge-tap page turn detector
            .pointerInput(isDrawingMode) {
                if (!isDrawingMode) {
                    detectTapGestures { offset ->
                        val screenWidth = size.width
                        val x = offset.x
                        when {
                            x < screenWidth * 0.22f -> {
                                if (currentPage > 1) currentPage--
                            }
                            x > screenWidth * 0.78f -> {
                                if (currentPage < totalPages) currentPage++
                            }
                            else -> {
                                isHudVisible = !isHudVisible
                            }
                        }
                    }
                }
            }
    ) {
        // Document Page Content & Background
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullscreen) 4.dp else 20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = if (isDarkMode) Zinc900 else Color(0xFFFAFAFA),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isDarkMode) Zinc800 else Color(0xFFE4E4E7)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        isLoadingFile -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Text(
                                        text = "Downloading document from reMarkable Cloud...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        isPdf && currentBitmap != null -> {
                            // Real PDF Page Image Rendering with Smart Dark Mode
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Image(
                                    bitmap = currentBitmap!!.asImageBitmap(),
                                    contentDescription = "Page $currentPage",
                                    contentScale = ContentScale.Fit,
                                    colorFilter = if (isDarkMode) ColorFilter.colorMatrix(darkColorMatrix) else null,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Overlay Drawing Canvas directly onto PDF page
                                DrawingCanvas(
                                    isDarkMode = isDarkMode,
                                    isDrawingEnabled = isDrawingMode,
                                    strokes = currentStrokes,
                                    onStrokesChanged = { updatedList ->
                                        pageStrokesMap = pageStrokesMap + (currentPage to updatedList)
                                        annotationStore?.saveStrokes(document.id, currentPage, updatedList)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        else -> {
                            // Authentic reMarkable Notebook Lined Page Template
                            Box(modifier = Modifier.fillMaxSize()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = document.visibleName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = inkColor.copy(alpha = 0.9f)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Ruled Notebook Template
                                    Column(
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                        verticalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        repeat(20) {
                                            HorizontalDivider(
                                                color = if (isDarkMode) Zinc800.copy(alpha = 0.5f) else Color(0xFFE4E4E7).copy(alpha = 0.6f),
                                                thickness = 1.dp
                                            )
                                        }
                                    }

                                    Text(
                                        text = "Page $currentPage of $totalPages • Swipe 2 fingers down for Favorites",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = inkColor.copy(alpha = 0.4f)
                                    )
                                }

                                // Interactive Drawing Canvas on Notebook
                                DrawingCanvas(
                                    isDarkMode = isDarkMode,
                                    isDrawingEnabled = isDrawingMode,
                                    strokes = currentStrokes,
                                    onStrokesChanged = { updatedList ->
                                        pageStrokesMap = pageStrokesMap + (currentPage to updatedList)
                                        annotationStore?.saveStrokes(document.id, currentPage, updatedList)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }

        // Auto-Hiding Top HUD
        AnimatedVisibility(
            visible = isHudVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = Zinc900.copy(alpha = 0.94f),
                contentColor = Color.White,
                shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        if (isFoldable) {
                            IconButton(onClick = onToggleFullscreen) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = "Toggle Fullscreen / Sidebar"
                                )
                            }
                        }
                    }

                    Text(
                        text = document.visibleName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp)
                    )

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Drawing / Inking Toggle
                        IconButton(
                            onClick = { isDrawingMode = !isDrawingMode },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isDrawingMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                contentColor = if (isDrawingMode) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
                            )
                        ) {
                            Icon(Icons.Default.Draw, contentDescription = "Draw on file")
                        }

                        // Favorites Quick Pull
                        IconButton(onClick = { showFavoritesShelf = true }) {
                            Icon(Icons.Default.Star, contentDescription = "Favorites Shelf", tint = Color(0xFFF59E0B))
                        }

                        // Dark Mode Toggle
                        IconButton(onClick = { isDarkMode = !isDarkMode }) {
                            Icon(
                                imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Toggle Dark Mode"
                            )
                        }
                    }
                }
            }
        }

        // Auto-Hiding Bottom HUD (Page Controls)
        AnimatedVisibility(
            visible = isHudVisible && !isDrawingMode,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Surface(
                color = Zinc900.copy(alpha = 0.94f),
                contentColor = Color.White,
                shape = CircleShape,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(
                        onClick = { if (currentPage > 1) currentPage-- },
                        enabled = currentPage > 1
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Page")
                    }

                    Text(
                        text = "$currentPage / $totalPages",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(
                        onClick = { if (currentPage < totalPages) currentPage++ },
                        enabled = currentPage < totalPages
                    ) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next Page")
                    }
                }
            }
        }

        // Two-Finger Pull Down Favorites Shelf
        FavoritesShelf(
            isVisible = showFavoritesShelf,
            documents = allDocuments,
            onSelectDocument = {
                onSelectDocument(it)
                showFavoritesShelf = false
            },
            onDismiss = { showFavoritesShelf = false }
        )
    }
}

