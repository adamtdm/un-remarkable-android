package com.unremarkable.android.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unremarkable.android.render.DocumentExporter
import com.unremarkable.android.render.PdfDocumentRenderer
import com.unremarkable.android.render.PdfOutlineParser
import com.unremarkable.android.render.TocEntry
import com.unremarkable.android.storage.AnnotationStore
import com.unremarkable.android.ui.components.*
import com.unremarkable.android.ui.theme.Zinc800
import com.unremarkable.android.ui.theme.Zinc900
import com.unremarkable.core.cloud.BlobStore
import com.unremarkable.core.cloud.RMCloudClient
import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.render.PenColor
import com.unremarkable.core.render.ToolType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

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
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentPage by remember(document.id) { mutableIntStateOf(1) }
    var totalPages by remember(document.id) { mutableIntStateOf(document.pageCount.coerceAtLeast(1)) }
    var isDarkMode by remember { mutableStateOf(false) }
    var isHudVisible by remember { mutableStateOf(true) }
    var isDrawingMode by remember { mutableStateOf(false) }
    var isDualPageMode by remember(isFoldable) { mutableStateOf(isFoldable) }
    var isFlexMode by remember { mutableStateOf(false) }

    // Dialog & Sheet States
    var showThumbnailGrid by remember { mutableStateOf(false) }
    var showTocDialog by remember { mutableStateOf(false) }
    var showSleepScreenStudio by remember { mutableStateOf(false) }
    var showFavoritesShelf by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var isExportingPdf by remember { mutableStateOf(false) }

    // Bookmarking state for current page
    var isCurrentPageBookmarked by remember(document.id, currentPage) {
        mutableStateOf(annotationStore?.isBookmarked(document.id, currentPage) ?: false)
    }

    // Dual-page pair indices
    val leftPage = remember(currentPage, isDualPageMode) {
        if (!isDualPageMode) currentPage else if (currentPage % 2 == 1) currentPage else currentPage - 1
    }
    val rightPage = remember(leftPage, isDualPageMode, totalPages) {
        if (!isDualPageMode) null else (leftPage + 1).takeIf { it <= totalPages }
    }

    // Strokes management per page
    var pageStrokesMap by remember(document.id) { mutableStateOf<Map<Int, List<DrawnStroke>>>(emptyMap()) }
    val leftStrokes = pageStrokesMap[leftPage].orEmpty()
    val rightStrokes = rightPage?.let { pageStrokesMap[it].orEmpty() } ?: emptyList()

    // Inking tool & color state for Flex Mode & HUD
    var selectedTool by remember { mutableStateOf(ToolType.FINELINER) }
    var selectedColor by remember { mutableStateOf(PenColor.BLACK) }

    // Load persisted strokes for active pages
    LaunchedEffect(document.id, leftPage, rightPage) {
        if (annotationStore != null) {
            val loadedLeft = annotationStore.loadStrokes(document.id, leftPage)
            val updated = pageStrokesMap.toMutableMap()
            updated[leftPage] = loadedLeft
            if (rightPage != null) {
                updated[rightPage] = annotationStore.loadStrokes(document.id, rightPage)
            }
            pageStrokesMap = updated
        }
    }

    // Auto-hide HUD after 4.5 seconds unless drawing mode or flex mode is active
    LaunchedEffect(isHudVisible, isDrawingMode, isFlexMode) {
        if (isHudVisible && !isDrawingMode && !isFlexMode) {
            delay(4500)
            isHudVisible = false
        }
    }

    // Detect PDF document payload
    val pdfEntry = document.subfiles.entries.find { it.key.endsWith(".pdf", ignoreCase = true) }
    val pdfHash = pdfEntry?.value ?: if (document.fileType.equals("pdf", ignoreCase = true)) document.hash else null
    val isPdf = !pdfHash.isNullOrBlank()

    var pdfRenderer by remember(document.id, pdfHash) { mutableStateOf<PdfDocumentRenderer?>(null) }
    var isLoadingFile by remember(document.id) { mutableStateOf(false) }
    var currentBitmap by remember(document.id, leftPage) { mutableStateOf<Bitmap?>(null) }
    var rightBitmap by remember(document.id, rightPage) { mutableStateOf<Bitmap?>(null) }

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

    // Render left & right PDF pages on page change
    LaunchedEffect(leftPage, rightPage, pdfRenderer) {
        val renderer = pdfRenderer
        if (renderer != null) {
            currentBitmap = renderer.renderPage(leftPage - 1)
            rightBitmap = rightPage?.let { renderer.renderPage(it - 1) }
        }
    }

    // Extract table of contents
    val tocEntries = remember(pdfRenderer?.sourceFile, totalPages) {
        val f = pdfRenderer?.sourceFile
        if (f != null && f.exists()) {
            PdfOutlineParser.extractToc(f, totalPages)
        } else {
            PdfOutlineParser.extractToc(File(""), totalPages)
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
            .pointerInput(isDrawingMode, isDualPageMode, totalPages) {
                if (!isDrawingMode) {
                    detectTapGestures { offset ->
                        val screenWidth = size.width
                        val x = offset.x
                        val pageStep = if (isDualPageMode) 2 else 1
                        when {
                            x < screenWidth * 0.22f -> {
                                if (currentPage > 1) {
                                    currentPage = (currentPage - pageStep).coerceAtLeast(1)
                                }
                            }
                            x > screenWidth * 0.78f -> {
                                if (currentPage < totalPages) {
                                    currentPage = (currentPage + pageStep).coerceAtMost(totalPages)
                                }
                            }
                            else -> {
                                isHudVisible = !isHudVisible
                            }
                        }
                    }
                }
            }
    ) {
        // Document Page Content (Single Page, Dual-Page 2-Up, or Tabletop Flex Mode)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullscreen) 4.dp else 16.dp)
        ) {
            // Main Canvas Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (isFlexMode) 0.58f else 1f)
            ) {
                when {
                    isLoadingFile -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                                Text("Downloading document from reMarkable Cloud...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    isDualPageMode && rightPage != null -> {
                        // Dual-Page Spread (2-Up View) for Unfolded Foldable Screen
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left Page
                            Surface(
                                color = if (isDarkMode) Zinc900 else Color(0xFFFAFAFA),
                                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 4.dp, bottomEnd = 4.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isDarkMode) Zinc800 else Color(0xFFE4E4E7)),
                                shadowElevation = 4.dp,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    if (isPdf && currentBitmap != null) {
                                        Image(
                                            bitmap = currentBitmap!!.asImageBitmap(),
                                            contentDescription = "Page $leftPage",
                                            contentScale = ContentScale.Fit,
                                            colorFilter = if (isDarkMode) ColorFilter.colorMatrix(darkColorMatrix) else null,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        NotebookTemplatePage(title = document.visibleName, pageNum = leftPage, totalPages = totalPages, inkColor = inkColor, isDarkMode = isDarkMode)
                                    }
                                    DrawingCanvas(
                                        isDarkMode = isDarkMode,
                                        isDrawingEnabled = isDrawingMode,
                                        strokes = leftStrokes,
                                        selectedTool = selectedTool,
                                        selectedColor = selectedColor,
                                        onStrokesChanged = { updated ->
                                            pageStrokesMap = pageStrokesMap + (leftPage to updated)
                                            annotationStore?.saveStrokes(document.id, leftPage, updated)
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            // Center Book Crease / Spine
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight(0.96f)
                                    .background(if (isDarkMode) Zinc800 else Color(0xFFD4D4D8), shape = RoundedCornerShape(2.dp))
                            )

                            // Right Page
                            Surface(
                                color = if (isDarkMode) Zinc900 else Color(0xFFFAFAFA),
                                shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp, topStart = 4.dp, bottomStart = 4.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isDarkMode) Zinc800 else Color(0xFFE4E4E7)),
                                shadowElevation = 4.dp,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    if (isPdf && rightBitmap != null) {
                                        Image(
                                            bitmap = rightBitmap!!.asImageBitmap(),
                                            contentDescription = "Page $rightPage",
                                            contentScale = ContentScale.Fit,
                                            colorFilter = if (isDarkMode) ColorFilter.colorMatrix(darkColorMatrix) else null,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        NotebookTemplatePage(title = document.visibleName, pageNum = rightPage, totalPages = totalPages, inkColor = inkColor, isDarkMode = isDarkMode)
                                    }
                                    DrawingCanvas(
                                        isDarkMode = isDarkMode,
                                        isDrawingEnabled = isDrawingMode,
                                        strokes = rightStrokes,
                                        selectedTool = selectedTool,
                                        selectedColor = selectedColor,
                                        onStrokesChanged = { updated ->
                                            pageStrokesMap = pageStrokesMap + (rightPage to updated)
                                            annotationStore?.saveStrokes(document.id, rightPage, updated)
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        // Single Page Layout (Phone & Normal Mode)
                        Surface(
                            color = if (isDarkMode) Zinc900 else Color(0xFFFAFAFA),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isDarkMode) Zinc800 else Color(0xFFE4E4E7)),
                            shadowElevation = 4.dp,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (isPdf && currentBitmap != null) {
                                    Image(
                                        bitmap = currentBitmap!!.asImageBitmap(),
                                        contentDescription = "Page $currentPage",
                                        contentScale = ContentScale.Fit,
                                        colorFilter = if (isDarkMode) ColorFilter.colorMatrix(darkColorMatrix) else null,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    NotebookTemplatePage(title = document.visibleName, pageNum = currentPage, totalPages = totalPages, inkColor = inkColor, isDarkMode = isDarkMode)
                                }
                                DrawingCanvas(
                                    isDarkMode = isDarkMode,
                                    isDrawingEnabled = isDrawingMode,
                                    strokes = leftStrokes,
                                    selectedTool = selectedTool,
                                    selectedColor = selectedColor,
                                    onStrokesChanged = { updated ->
                                        pageStrokesMap = pageStrokesMap + (currentPage to updated)
                                        annotationStore?.saveStrokes(document.id, currentPage, updated)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }

            // Tabletop Flex Mode Control Console (Lower Half)
            if (isFlexMode) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().weight(0.42f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Scrubber Slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isDualPageMode && rightPage != null) "Pages $leftPage-$rightPage of $totalPages" else "Page $currentPage of $totalPages",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Tabletop Flex Mode Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = currentPage.toFloat(),
                                onValueChange = { currentPage = it.toInt().coerceIn(1, totalPages) },
                                valueRange = 1f..totalPages.toFloat(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Inking Palette Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = selectedTool == ToolType.FINELINER,
                                    onClick = { selectedTool = ToolType.FINELINER; isDrawingMode = true },
                                    label = { Text("Pen") }
                                )
                                FilterChip(
                                    selected = selectedTool == ToolType.HIGHLIGHTER,
                                    onClick = { selectedTool = ToolType.HIGHLIGHTER; isDrawingMode = true },
                                    label = { Text("Highlighter") }
                                )
                                FilterChip(
                                    selected = selectedTool == ToolType.ERASER,
                                    onClick = { selectedTool = ToolType.ERASER; isDrawingMode = true },
                                    label = { Text("Eraser") }
                                )
                            }

                            // Color swatches
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val colors = listOf(PenColor.BLACK, PenColor.BLUE, PenColor.RED, PenColor.YELLOW)
                                colors.forEach { c ->
                                    val swatchColor = when (c) {
                                        PenColor.BLACK -> Color(0xFF18181B)
                                        PenColor.BLUE -> Color(0xFF2563EB)
                                        PenColor.RED -> Color(0xFFDC2626)
                                        PenColor.YELLOW -> Color(0xFFFBBF24)
                                        else -> Color.DarkGray
                                    }
                                    Surface(
                                        shape = CircleShape,
                                        color = swatchColor,
                                        border = if (selectedColor == c) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        IconButton(onClick = { selectedColor = c; isDrawingMode = true }) {}
                                    }
                                }
                            }
                        }

                        // Quick Navigation Action Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = { showThumbnailGrid = true }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Page Grid")
                            }
                            OutlinedButton(onClick = { showTocDialog = true }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Contents")
                            }
                            Button(onClick = { isFlexMode = false }, modifier = Modifier.weight(1f)) {
                                Text("Full Canvas")
                            }
                        }
                    }
                }
            }
        }

        // Auto-Hiding Top HUD
        AnimatedVisibility(
            visible = isHudVisible && !isFlexMode,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = Zinc900.copy(alpha = 0.94f),
                contentColor = Color.White,
                shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                modifier = Modifier.fillMaxWidth().statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
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
                                    contentDescription = "Toggle Sidebar"
                                )
                            }
                        }
                    }

                    // Title & Bookmark Ribbon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = document.visibleName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        IconButton(
                            onClick = {
                                if (annotationStore != null) {
                                    isCurrentPageBookmarked = annotationStore.toggleBookmark(document.id, currentPage)
                                    val msg = if (isCurrentPageBookmarked) "Page $currentPage bookmarked" else "Bookmark removed"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isCurrentPageBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = "Bookmark Page",
                                tint = if (isCurrentPageBookmarked) Color(0xFFF59E0B) else Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // Action Icons
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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

                        // Page Sorter / Thumbnail Grid
                        IconButton(onClick = { showThumbnailGrid = true }) {
                            Icon(Icons.Default.GridView, contentDescription = "Page Grid")
                        }

                        // Tabletop Flex Mode Toggle
                        IconButton(
                            onClick = { isFlexMode = !isFlexMode },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isFlexMode) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                contentColor = if (isFlexMode) MaterialTheme.colorScheme.onSecondaryContainer else Color.White
                            )
                        ) {
                            Icon(Icons.Default.Laptop, contentDescription = "Flex Mode")
                        }

                        // Overflow Menu for TOC, Dual-Page, Sleep Screen, Export PDF
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More Options")
                            }

                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Table of Contents") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showTocDialog = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text(if (isDualPageMode) "Single Page View" else "Dual-Page Spread (2-Up)") },
                                    leadingIcon = { Icon(Icons.Default.AutoStories, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        isDualPageMode = !isDualPageMode
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Sleep Screen Studio") },
                                    leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showSleepScreenStudio = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Export Annotated PDF") },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        coroutineScope.launch {
                                            isExportingPdf = true
                                            try {
                                                val file = DocumentExporter.exportAnnotatedPdf(
                                                    context = context,
                                                    docId = document.id,
                                                    docTitle = document.visibleName,
                                                    totalPages = totalPages,
                                                    pdfRenderer = pdfRenderer,
                                                    annotationStore = annotationStore
                                                )
                                                DocumentExporter.shareExportedFile(context, file, "application/pdf", "${document.visibleName} (Annotated)")
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
                                            } finally {
                                                isExportingPdf = false
                                            }
                                        }
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text(if (isDarkMode) "Light Mode" else "Dark Invert Mode") },
                                    leadingIcon = { Icon(if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        isDarkMode = !isDarkMode
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Auto-Hiding Bottom HUD (Page Turn Controls)
        AnimatedVisibility(
            visible = isHudVisible && !isDrawingMode && !isFlexMode,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)
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
                    val step = if (isDualPageMode) 2 else 1
                    IconButton(
                        onClick = { if (currentPage > 1) currentPage = (currentPage - step).coerceAtLeast(1) },
                        enabled = currentPage > 1
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Page")
                    }

                    TextButton(onClick = { showThumbnailGrid = true }) {
                        Text(
                            text = if (isDualPageMode && rightPage != null) "pp. $leftPage-$rightPage / $totalPages" else "$currentPage / $totalPages",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    IconButton(
                        onClick = { if (currentPage < totalPages) currentPage = (currentPage + step).coerceAtMost(totalPages) },
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

        // Page Sorter Grid Modal
        if (showThumbnailGrid) {
            PageThumbnailGridDialog(
                docId = document.id,
                totalPages = totalPages,
                currentPage = currentPage,
                pdfRenderer = pdfRenderer,
                annotationStore = annotationStore,
                onSelectPage = { selectedPage ->
                    currentPage = selectedPage
                },
                onDismiss = { showThumbnailGrid = false }
            )
        }

        // Table of Contents Modal
        if (showTocDialog) {
            PdfTocDialog(
                tocEntries = tocEntries,
                currentPage = currentPage,
                onSelectPage = { selectedPage ->
                    currentPage = selectedPage
                },
                onDismiss = { showTocDialog = false }
            )
        }

        // Sleep Screen Studio Modal
        if (showSleepScreenStudio) {
            SleepScreenStudioDialog(
                sourceBitmap = currentBitmap,
                documentTitle = document.visibleName,
                onDismiss = { showSleepScreenStudio = false }
            )
        }
    }
}

@Composable
private fun NotebookTemplatePage(
    title: String,
    pageNum: Int,
    totalPages: Int,
    inkColor: Color,
    isDarkMode: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
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
                text = "Page $pageNum of $totalPages",
                style = MaterialTheme.typography.labelSmall,
                color = inkColor.copy(alpha = 0.4f)
            )
        }
    }
}
