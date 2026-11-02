package com.unremarkable.android.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.unremarkable.android.render.DocumentExporter
import com.unremarkable.android.render.PdfDocumentRenderer
import com.unremarkable.android.render.PdfOutlineParser
import com.unremarkable.android.render.RmPageCanvasRenderer
import com.unremarkable.android.storage.AnnotationStore
import com.unremarkable.android.storage.AppSettings
import com.unremarkable.android.ui.components.*
import com.unremarkable.android.ui.theme.BasaltBgPrimary
import com.unremarkable.android.ui.theme.BasaltBgSecondary
import com.unremarkable.android.ui.theme.BasaltBgTertiary
import com.unremarkable.android.ui.theme.BasaltBorder
import com.unremarkable.android.ui.theme.PaperBgPrimary
import com.unremarkable.android.ui.theme.PaperBorder
import com.unremarkable.android.ui.theme.Zinc800
import com.unremarkable.android.ui.theme.Zinc900
import com.unremarkable.core.cloud.BlobStore
import com.unremarkable.core.cloud.RMCloudClient
import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.render.RmBinaryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

private enum class FitMode { FIT_PAGE, FIT_WIDTH }

private class RenderedSpread(
    val docId: String,
    val leftPage: Int,
    val rightPage: Int?,
    val leftBitmap: Bitmap?,
    val rightBitmap: Bitmap?
)

@Composable
fun ReaderScreen(
    document: DocumentItem,
    allDocuments: List<DocumentItem> = emptyList(),
    blobStore: BlobStore? = null,
    annotationStore: AnnotationStore? = null,
    appSettings: AppSettings? = null,
    cloudClient: RMCloudClient? = null,
    isFoldable: Boolean = false,
    isFullscreen: Boolean = false,
    isBackEnabled: Boolean = true,
    onToggleFullscreen: () -> Unit = {},
    onSelectDocument: (DocumentItem) -> Unit = {},
    onOpenMeetingRecorder: () -> Unit = {},
    onOptimizeCurrentPdf: ((File, String) -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var currentPage by remember(document.id) { mutableIntStateOf(1) }
    var totalPages by remember(document.id) {
        mutableIntStateOf(maxOf(document.pages.size, document.pageCount, 1))
    }

    val isAppDark = MaterialTheme.colorScheme.background == BasaltBgPrimary
    val isComicTag = remember(document) {
        document.tags.any { it.equals("Comic", ignoreCase = true) || it.equals("Optimized", ignoreCase = true) } ||
            document.visibleName.endsWith(".cbz", ignoreCase = true) ||
            document.visibleName.contains("comic", ignoreCase = true)
    }

    // Per-file persisted inversion preference (null = automatic based on app theme & artwork detection)
    var userInvertOverride by remember(document.id) {
        mutableStateOf(appSettings?.getDocumentInvertPreference(document.id))
    }
    var autoDetectedArtwork by remember(document.id) { mutableStateOf(isComicTag) }
    val effectiveInvert = userInvertOverride ?: (isAppDark && !autoDetectedArtwork)

    fun toggleInvertForDocument() {
        val next = !effectiveInvert
        userInvertOverride = next
        appSettings?.setDocumentInvertPreference(document.id, next)
        val msg = if (next) "Page inversion on (saved for this file)" else "Original colors kept (saved for this file)"
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    var fitMode by remember { mutableStateOf(FitMode.FIT_PAGE) }
    var isHudVisible by remember { mutableStateOf(true) }
    var isDrawingMode by remember { mutableStateOf(false) }
    var isDualPageMode by remember { mutableStateOf(false) }

    // Pinch-to-Zoom & Pan state (reset atomically with renderedSpread so old page never jumps before swap)
    var zoomScale by remember(document.id) { mutableFloatStateOf(1f) }
    var panOffset by remember(document.id) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    fun resetZoom() {
        zoomScale = 1f
        panOffset = Offset.Zero
    }

    fun clampPan(offset: Offset, scale: Float): Offset {
        if (scale <= 1.01f || viewportSize.width == 0 || viewportSize.height == 0) return Offset.Zero
        val maxX = (viewportSize.width * (scale - 1f)) / 2f + 48f
        val maxY = (viewportSize.height * (scale - 1f)) / 2f + 48f
        return Offset(
            offset.x.coerceIn(-maxX, maxX),
            offset.y.coerceIn(-maxY, maxY)
        )
    }

    // System Back gesture: exit drawing -> reset zoom -> back to library
    BackHandler(enabled = isBackEnabled) {
        when {
            isDrawingMode -> isDrawingMode = false
            zoomScale > 1.05f -> resetZoom()
            else -> onBack()
        }
    }

    // Dialog & Menu States
    var showThumbnailGrid by remember { mutableStateOf(false) }
    var showPageJumpDialog by remember { mutableStateOf(false) }
    var showTocDialog by remember { mutableStateOf(false) }
    var showSleepScreenStudio by remember { mutableStateOf(false) }
    var showFavoritesShelf by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    var isCurrentPageBookmarked by remember(document.id, currentPage) {
        mutableStateOf(annotationStore?.isBookmarked(document.id, currentPage) ?: false)
    }

    val leftPage = remember(currentPage, isDualPageMode) {
        if (!isDualPageMode) currentPage else if (currentPage % 2 == 1) currentPage else (currentPage - 1).coerceAtLeast(1)
    }
    val rightPage = remember(leftPage, isDualPageMode, totalPages) {
        if (!isDualPageMode) null else (leftPage + 1).takeIf { it <= totalPages }
    }

    // Local stylus/touch annotation strokes
    var pageStrokesMap by remember(document.id) { mutableStateOf<Map<Int, List<DrawnStroke>>>(emptyMap()) }
    LaunchedEffect(document.id, leftPage, rightPage) {
        if (annotationStore != null) {
            val updated = pageStrokesMap.toMutableMap()
            updated[leftPage] = annotationStore.loadStrokes(document.id, leftPage)
            if (rightPage != null) {
                updated[rightPage] = annotationStore.loadStrokes(document.id, rightPage)
            }
            pageStrokesMap = updated
        }
    }

    // Auto-hide HUD after 4.5s while reading cleanly
    LaunchedEffect(isHudVisible, isDrawingMode) {
        if (isHudVisible && !isDrawingMode) {
            delay(4500)
            isHudVisible = false
        }
    }

    // Payload detection: PDF, EPUB, or native .rm Notebook
    val pdfEntry = remember(document.id, document.subfiles) {
        document.subfiles.entries.find { it.key.endsWith(".pdf", ignoreCase = true) }
    }
    val pdfHash = pdfEntry?.value ?: if (document.fileType.equals("pdf", ignoreCase = true)) document.hash.takeIf { it.isNotBlank() } else null
    val epubEntry = remember(document.id, document.subfiles, pdfHash) {
        if (pdfHash == null) document.subfiles.entries.find { it.key.endsWith(".epub", ignoreCase = true) } else null
    }
    val epubHash = epubEntry?.value

    var pdfRenderer by remember(document.id, pdfHash) { mutableStateOf<PdfDocumentRenderer?>(null) }
    var epubPages by remember(document.id, epubHash) { mutableStateOf<List<List<String>>>(emptyList()) }
    var isLoadingFile by remember(document.id) { mutableStateOf(false) }
    var loadError by remember(document.id) { mutableStateOf<String?>(null) }
    var retryToken by remember { mutableIntStateOf(0) }

    var renderedSpread by remember(document.id) {
        mutableStateOf(RenderedSpread(document.id, 1, null, null, null))
    }
    val displayedLeftPage = renderedSpread.leftPage
    val displayedRightPage = renderedSpread.rightPage
    val leftBitmap = renderedSpread.leftBitmap
    val rightBitmap = renderedSpread.rightBitmap
    var isPageRendering by remember(document.id) { mutableStateOf(false) }

    // Download & open base PDF or EPUB container on-demand
    LaunchedEffect(document.id, pdfHash, epubHash, retryToken) {
        loadError = null
        if (blobStore == null) return@LaunchedEffect

        if (!pdfHash.isNullOrBlank()) {
            val blobFile = blobStore.getBlobFile(pdfHash)
            if (!blobFile.exists() || blobFile.length() == 0L) {
                if (cloudClient != null) {
                    isLoadingFile = true
                    try {
                        cloudClient.downloadBlobToFile(pdfHash, blobFile, pdfEntry?.key ?: "${document.id}.pdf")
                    } catch (e: Exception) {
                        loadError = e.message ?: "Unable to download PDF from reMarkable Cloud"
                    } finally {
                        isLoadingFile = false
                    }
                } else {
                    loadError = "Connect to reMarkable Cloud to download this PDF"
                }
            }
            if (blobFile.exists() && blobFile.length() > 0L) {
                val opened = PdfDocumentRenderer.open(blobFile)
                if (opened != null) {
                    pdfRenderer = opened
                    totalPages = if (document.pages.isNotEmpty()) {
                        document.pages.size
                    } else {
                        opened.pageCount.coerceAtLeast(1)
                    }
                } else {
                    loadError = "PDF file could not be opened"
                }
            }
        } else if (!epubHash.isNullOrBlank()) {
            val blobFile = blobStore.getBlobFile(epubHash)
            if (!blobFile.exists() || blobFile.length() == 0L) {
                if (cloudClient != null) {
                    isLoadingFile = true
                    try {
                        cloudClient.downloadBlobToFile(epubHash, blobFile, epubEntry?.key ?: "${document.id}.epub")
                    } catch (e: Exception) {
                        loadError = e.message ?: "Unable to download EPUB from reMarkable Cloud"
                    } finally {
                        isLoadingFile = false
                    }
                }
            }
            if (blobFile.exists() && blobFile.length() > 0L) {
                val extracted = withContext(Dispatchers.Default) {
                    RmPageCanvasRenderer.extractEpubPages(blobFile)
                }
                epubPages = extracted
                if (extracted.isNotEmpty() && document.pages.isEmpty()) {
                    totalPages = extracted.size
                }
            }
        } else {
            val rmCount = document.subfiles.keys.count { it.endsWith(".rm", ignoreCase = true) }
            totalPages = maxOf(document.pages.size, document.pageCount, rmCount, 1)
        }
    }

    // Helper that downloads any missing .rm page blob and composites PDF + .rm ink/highlights/text
    suspend fun loadCompositePage(pageNum: Int): Bitmap? = withContext(Dispatchers.IO) {
        val pageIdx = (pageNum - 1).coerceAtLeast(0)
        val pageRef = document.pages.getOrNull(pageIdx)
        val rmHash = DocumentExporter.resolveRmBlobHash(document, pageIdx)

        // Download .rm stroke/text blob on-demand if missing locally
        var rmBytes: ByteArray? = null
        if (!rmHash.isNullOrBlank() && blobStore != null) {
            if (!blobStore.hasBlob(rmHash) && cloudClient != null) {
                try {
                    val rmKey = document.subfiles.entries.firstOrNull { it.value == rmHash }?.key ?: "${document.id}.rm"
                    val fetched = cloudClient.getBlob(rmHash, rmKey)
                    blobStore.putBlob(rmHash, fetched)
                    rmBytes = fetched
                } catch (_: Exception) {}
            } else {
                rmBytes = blobStore.getBlob(rmHash)
            }
        }

        withContext(Dispatchers.Default) {
            val rmPage = rmBytes?.let { RmBinaryParser.parse(it) }
            val renderer = pdfRenderer
            val rawPdfIdx = DocumentExporter.resolvePdfPageIndex(document, pageIdx)
            val hasPdfBacking = renderer != null && rawPdfIdx in 0 until renderer.pageCount

            when {
                hasPdfBacking -> {
                    val rawPdfBmp = renderer!!.renderPage(rawPdfIdx, 1404, 1872)
                    val pageIsArtwork = isComicTag || isArtworkOrComicBitmap(rawPdfBmp)
                    if (pageIsArtwork && !autoDetectedArtwork) {
                        withContext(Dispatchers.Main) { autoDetectedArtwork = true }
                    }
                    val shouldInvertColors = userInvertOverride ?: (isAppDark && !pageIsArtwork)
                    val outBmp = if (shouldInvertColors || rmPage != null) {
                        val mutable = Bitmap.createBitmap(rawPdfBmp.width, rawPdfBmp.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(mutable)
                        val pdfPaint = if (shouldInvertColors) {
                            Paint().apply {
                                colorFilter = ColorMatrixColorFilter(
                                    ColorMatrix(
                                        floatArrayOf(
                                            -0.85f, 0f, 0f, 0f, 232f,
                                            0f, -0.85f, 0f, 0f, 232f,
                                            0f, 0f, -0.85f, 0f, 235f,
                                            0f, 0f, 0f, 1f, 0f
                                        )
                                    )
                                )
                            }
                        } else null
                        canvas.drawBitmap(rawPdfBmp, 0f, 0f, pdfPaint)
                        rawPdfBmp.recycle()
                        if (rmPage != null) {
                            RmPageCanvasRenderer.drawRmOverlay(canvas, rmPage, shouldInvertColors, mutable.width, mutable.height)
                        }
                        mutable
                    } else {
                        rawPdfBmp
                    }
                    outBmp
                }

                epubPages.isNotEmpty() -> {
                    val lines = epubPages.getOrElse(pageIdx.coerceIn(0, epubPages.lastIndex)) { emptyList() }
                    val bmp = RmPageCanvasRenderer.renderEpubTextPage(lines, pageNum, totalPages, effectiveInvert)
                    if (rmPage != null) {
                        RmPageCanvasRenderer.drawRmOverlay(Canvas(bmp), rmPage, effectiveInvert, bmp.width, bmp.height)
                    }
                    bmp
                }

                else -> {
                    RmPageCanvasRenderer.renderNotebookPage(rmPage, effectiveInvert, 1404, 1872)
                }
            }
        }
    }

    // Render active page(s) and prefetch adjacent .rm blobs in background
    LaunchedEffect(document.id, leftPage, rightPage, pdfRenderer, epubPages, isLoadingFile, isAppDark, userInvertOverride, retryToken) {
        if (isLoadingFile) return@LaunchedEffect
        isPageRendering = true
        val nextLeft = loadCompositePage(leftPage)
        val nextRight = rightPage?.let { loadCompositePage(it) }
        val pageChanged = renderedSpread.docId != document.id ||
            renderedSpread.leftPage != leftPage ||
            renderedSpread.rightPage != rightPage
        if (pageChanged) {
            zoomScale = 1f
            panOffset = Offset.Zero
        }
        renderedSpread = RenderedSpread(
            docId = document.id,
            leftPage = leftPage,
            rightPage = rightPage,
            leftBitmap = nextLeft,
            rightBitmap = nextRight
        )
        isPageRendering = false

        // Background prefetch adjacent .rm blobs (matching Docker preloadAdjacentPages)
        if (blobStore != null && cloudClient != null) {
            launch(Dispatchers.IO) {
                for (adj in listOf(currentPage + 1, currentPage + 2, currentPage - 1)) {
                    if (adj in 1..totalPages) {
                        val h = DocumentExporter.resolveRmBlobHash(document, adj - 1)
                        if (!h.isNullOrBlank() && !blobStore.hasBlob(h)) {
                            val k = document.subfiles.entries.firstOrNull { it.value == h }?.key ?: ""
                            runCatching { blobStore.putBlob(h, cloudClient.getBlob(h, k)) }
                        }
                    }
                }
            }
        }
    }

    val tocEntries = remember(pdfRenderer?.sourceFile, totalPages) {
        val f = pdfRenderer?.sourceFile
        if (f != null && f.exists()) PdfOutlineParser.extractToc(f, totalPages)
        else PdfOutlineParser.extractToc(File(""), totalPages)
    }

    val activeRenderer = pdfRenderer
    DisposableEffect(activeRenderer) {
        onDispose { activeRenderer?.close() }
    }

    val isDarkView = isAppDark || effectiveInvert
    val canvasBackground = when {
        isAppDark -> Color.Black
        effectiveInvert -> BasaltBgPrimary
        else -> PaperBgPrimary
    }
    val swipeThresholdPx = with(density) { 64.dp.toPx() }
    val pageStep = if (isDualPageMode) 2 else 1

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(canvasBackground)
            .onSizeChanged { viewportSize = it }
            // Pinch-to-zoom, focal pan when zoomed, and horizontal swipe page turn at 1x
            .pointerInput(document.id, isDrawingMode, fitMode, isDualPageMode, totalPages) {
                if (isDrawingMode) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var wasMultiTouch = false
                    var totalSwipeX = 0f
                    var totalSwipeY = 0f

                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            wasMultiTouch = true
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                val oldScale = zoomScale
                                val newScale = (oldScale * zoomChange).coerceIn(1f, 4.5f)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val focal = if (centroid != Offset.Unspecified) centroid - center else Offset.Zero
                                val scaleRatio = newScale / oldScale
                                val rawOffset = (panOffset - focal) * scaleRatio + focal + panChange
                                zoomScale = newScale
                                panOffset = clampPan(rawOffset, newScale)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (pressed.size == 1 && !wasMultiTouch) {
                            val pan = event.calculatePan()
                            if (zoomScale > 1.04f) {
                                if (pan != Offset.Zero) {
                                    panOffset = clampPan(panOffset + pan, zoomScale)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } else if (fitMode == FitMode.FIT_PAGE) {
                                totalSwipeX += pan.x
                                totalSwipeY += pan.y
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (zoomScale < 1.03f) {
                        resetZoom()
                    }
                    if (!wasMultiTouch && zoomScale <= 1.04f && fitMode == FitMode.FIT_PAGE) {
                        if (abs(totalSwipeX) > swipeThresholdPx && abs(totalSwipeX) > abs(totalSwipeY) * 1.35f) {
                            if (totalSwipeX < 0 && currentPage < totalPages) {
                                currentPage = (currentPage + pageStep).coerceAtMost(totalPages)
                            } else if (totalSwipeX > 0 && currentPage > 1) {
                                currentPage = (currentPage - pageStep).coerceAtLeast(1)
                            }
                        }
                    }
                }
            }
            // Double-tap 2.25x zoom toggle + edge-tap page turns (disabled while zoomed)
            .pointerInput(document.id, isDrawingMode, isDualPageMode, totalPages) {
                if (isDrawingMode) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (zoomScale > 1.08f) {
                            resetZoom()
                        } else {
                            val target = 2.25f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val focal = tapOffset - center
                            zoomScale = target
                            panOffset = clampPan(-focal * (target - 1f), target)
                        }
                    },
                    onTap = { tapOffset ->
                        if (zoomScale > 1.05f) {
                            isHudVisible = !isHudVisible
                        } else {
                            val x = tapOffset.x
                            when {
                                x < size.width * 0.22f -> {
                                    if (currentPage > 1) currentPage = (currentPage - pageStep).coerceAtLeast(1)
                                }
                                x > size.width * 0.78f -> {
                                    if (currentPage < totalPages) currentPage = (currentPage + pageStep).coerceAtMost(totalPages)
                                }
                                else -> isHudVisible = !isHudVisible
                            }
                        }
                    }
                )
            }
    ) {
        // Main Viewport
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullscreen || isDarkView) 0.dp else 8.dp)
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = panOffset.x
                    translationY = panOffset.y
                }
        ) {
            when {
                isLoadingFile -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(38.dp))
                            Text(
                                "Downloading from reMarkable Cloud...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                loadError != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 4.dp,
                            modifier = Modifier.padding(24.dp).widthIn(max = 420.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                                Text("Could not load document", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(loadError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = { retryToken++ }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Retry Download")
                                }
                            }
                        }
                    }
                }

                isDualPageMode && displayedRightPage != null -> {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReaderPageCard(
                            documentId = document.id,
                            bitmap = leftBitmap,
                            pageNum = displayedLeftPage,
                            isPageInverted = effectiveInvert,
                            isStageDark = isDarkView,
                            fitWidth = false,
                            isDrawingMode = isDrawingMode,
                            strokes = pageStrokesMap[displayedLeftPage].orEmpty(),
                            onStrokesChanged = { updated ->
                                pageStrokesMap = pageStrokesMap + (displayedLeftPage to updated)
                                annotationStore?.saveStrokes(document.id, displayedLeftPage, updated)
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .fillMaxHeight(0.96f)
                                .background(if (isDarkView) BasaltBgTertiary else Color(0xFFD4D4D8))
                        )
                        ReaderPageCard(
                            documentId = document.id,
                            bitmap = rightBitmap,
                            pageNum = displayedRightPage,
                            isPageInverted = effectiveInvert,
                            isStageDark = isDarkView,
                            fitWidth = false,
                            isDrawingMode = isDrawingMode,
                            strokes = pageStrokesMap[displayedRightPage].orEmpty(),
                            onStrokesChanged = { updated ->
                                pageStrokesMap = pageStrokesMap + (displayedRightPage to updated)
                                annotationStore?.saveStrokes(document.id, displayedRightPage, updated)
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }

                else -> {
                    ReaderPageCard(
                        documentId = document.id,
                        bitmap = leftBitmap,
                        pageNum = displayedLeftPage,
                        isPageInverted = effectiveInvert,
                        isStageDark = isDarkView,
                        fitWidth = fitMode == FitMode.FIT_WIDTH,
                        isDrawingMode = isDrawingMode,
                        strokes = pageStrokesMap[displayedLeftPage].orEmpty(),
                        onStrokesChanged = { updated ->
                            pageStrokesMap = pageStrokesMap + (displayedLeftPage to updated)
                            annotationStore?.saveStrokes(document.id, displayedLeftPage, updated)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
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
                modifier = Modifier.fillMaxWidth().statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
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
                                    contentDescription = "Toggle Fullscreen"
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp)
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
                                tint = if (isCurrentPageBookmarked) Color(0xFFF59E0B) else Color.White.copy(alpha = 0.75f)
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Fit Width / Fit Page Toggle (matches Docker btnFitToggle)
                        IconButton(
                            onClick = {
                                fitMode = if (fitMode == FitMode.FIT_PAGE) FitMode.FIT_WIDTH else FitMode.FIT_PAGE
                                resetZoom()
                            }
                        ) {
                            Icon(
                                imageVector = if (fitMode == FitMode.FIT_PAGE) Icons.Default.FitScreen else Icons.Default.SwapVert,
                                contentDescription = if (fitMode == FitMode.FIT_PAGE) "Switch to Fit Width" else "Switch to Fit Page"
                            )
                        }

                        // Per-File Page Inversion Toggle (matches Docker btnPageDarkToggle)
                        IconButton(
                            onClick = { toggleInvertForDocument() },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (effectiveInvert) Color.White.copy(alpha = 0.14f) else Color.Transparent,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = if (effectiveInvert) Icons.Default.InvertColors else Icons.Default.InvertColorsOff,
                                contentDescription = if (effectiveInvert) "Disable Page Inversion" else "Invert Page Colors"
                            )
                        }

                        // Stylus / Touch Drawing Toggle
                        IconButton(
                            onClick = {
                                isDrawingMode = !isDrawingMode
                                if (isDrawingMode) resetZoom()
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isDrawingMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                contentColor = if (isDrawingMode) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
                            )
                        ) {
                            Icon(Icons.Default.Draw, contentDescription = "Annotate")
                        }

                        // Page Thumbnail Grid
                        IconButton(onClick = { showThumbnailGrid = true }) {
                            Icon(Icons.Default.GridView, contentDescription = "Page Grid")
                        }

                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More Options")
                            }

                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Invert Page Colors: ${if (effectiveInvert) "On" else "Off"}") },
                                    leadingIcon = {
                                        Icon(
                                            if (effectiveInvert) Icons.Default.InvertColors else Icons.Default.InvertColorsOff,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        toggleInvertForDocument()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (fitMode == FitMode.FIT_PAGE) "Fit Width" else "Fit Page") },
                                    leadingIcon = { Icon(Icons.Default.FitScreen, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        fitMode = if (fitMode == FitMode.FIT_PAGE) FitMode.FIT_WIDTH else FitMode.FIT_PAGE
                                        resetZoom()
                                    }
                                )
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
                                        resetZoom()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Pinned Favorites") },
                                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showFavoritesShelf = true
                                    }
                                )
                                if (pdfRenderer?.sourceFile != null && onOptimizeCurrentPdf != null) {
                                    DropdownMenuItem(
                                        text = { Text("Optimize for reMarkable") },
                                        leadingIcon = { Icon(Icons.Default.FlashOn, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            pdfRenderer?.sourceFile?.let { f ->
                                                onOptimizeCurrentPdf(f, document.visibleName)
                                            }
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Record Meeting Notes") },
                                    leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenMeetingRecorder()
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
                                            try {
                                                val file = DocumentExporter.exportAnnotatedPdf(
                                                    context = context,
                                                    document = document,
                                                    totalPages = totalPages,
                                                    pdfRenderer = pdfRenderer,
                                                    blobStore = blobStore,
                                                    annotationStore = annotationStore
                                                )
                                                DocumentExporter.shareExportedFile(context, file, "application/pdf", "${document.visibleName} (Annotated)")
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Navigation & Zoom Reset Pill (matches Docker reader-nav + page jump)
        AnimatedVisibility(
            visible = (isHudVisible || zoomScale > 1.08f) && !isDrawingMode,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Zinc900.copy(alpha = 0.94f),
                    contentColor = Color.White,
                    shape = CircleShape,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { if (currentPage > 1) currentPage = (currentPage - pageStep).coerceAtLeast(1) },
                            enabled = currentPage > 1
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Page")
                        }

                        TextButton(onClick = { showPageJumpDialog = true }) {
                            if (isPageRendering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = if (isDualPageMode && rightPage != null) "pp. $leftPage-$rightPage / $totalPages" else "Page $currentPage of $totalPages",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        IconButton(
                            onClick = { if (currentPage < totalPages) currentPage = (currentPage + pageStep).coerceAtMost(totalPages) },
                            enabled = currentPage < totalPages
                        ) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Next Page")
                        }
                    }
                }

                if (zoomScale > 1.08f) {
                    FilledTonalButton(
                        onClick = { resetZoom() },
                        shape = CircleShape
                    ) {
                        Text("${(zoomScale * 100).toInt()}% ↺", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        FavoritesShelf(
            isVisible = showFavoritesShelf,
            documents = allDocuments,
            onSelectDocument = {
                onSelectDocument(it)
                showFavoritesShelf = false
            },
            onDismiss = { showFavoritesShelf = false }
        )

        if (showPageJumpDialog) {
            var pageInput by remember(currentPage) { mutableStateOf(currentPage.toString()) }
            AlertDialog(
                onDismissRequest = { showPageJumpDialog = false },
                title = { Text("Jump to Page") },
                text = {
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { pageInput = it.filter(Char::isDigit) },
                        label = { Text("Page (1 – $totalPages)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            pageInput.toIntOrNull()?.let { target ->
                                currentPage = target.coerceIn(1, totalPages)
                            }
                            showPageJumpDialog = false
                        }
                    ) { Text("Go") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showPageJumpDialog = false
                            showThumbnailGrid = true
                        }
                    ) { Text("Browse Grid") }
                }
            )
        }

        if (showThumbnailGrid) {
            PageThumbnailGridDialog(
                document = document,
                totalPages = totalPages,
                currentPage = currentPage,
                pdfRenderer = pdfRenderer,
                blobStore = blobStore,
                annotationStore = annotationStore,
                onSelectPage = { currentPage = it },
                onDismiss = { showThumbnailGrid = false }
            )
        }

        if (showTocDialog) {
            PdfTocDialog(
                tocEntries = tocEntries,
                currentPage = currentPage,
                onSelectPage = { currentPage = it },
                onDismiss = { showTocDialog = false }
            )
        }

        if (showSleepScreenStudio) {
            SleepScreenStudioDialog(
                sourceBitmap = leftBitmap,
                documentTitle = document.visibleName,
                onDismiss = { showSleepScreenStudio = false }
            )
        }
    }
}

@Composable
private fun ReaderPageCard(
    documentId: String,
    bitmap: Bitmap?,
    pageNum: Int,
    isPageInverted: Boolean,
    isStageDark: Boolean,
    fitWidth: Boolean,
    isDrawingMode: Boolean,
    strokes: List<DrawnStroke>,
    onStrokesChanged: (List<DrawnStroke>) -> Unit,
    modifier: Modifier = Modifier
) {
    // Synchronously instantiate a fresh ScrollState(0) on the exact composition frame that `pageNum`
    // (derived from `renderedSpread.leftPage`) commits with the new bitmap — preventing both:
    //  1) jumping the old page back to 0 while the next page is still rendering, and
    //  2) a 1-frame post-draw flash from an asynchronous LaunchedEffect scrollTo(0).
    val scrollState = remember(documentId, pageNum, fitWidth) { ScrollState(initial = 0) }

    // Outer container is strictly transparent so the dark stage (Color.Black in Dark Mode) always
    // shows cleanly behind letterbox/pillarbox regions without any white bars around the file.
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                color = if (isStageDark) Color.White else MaterialTheme.colorScheme.primary
            )
        } else {
            val pageRatio = (bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)).coerceIn(0.2f, 5f)
            val pageBorderModifier = if (!isStageDark && !isPageInverted) {
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, PaperBorder, RoundedCornerShape(6.dp))
            } else {
                Modifier
            }

            if (fitWidth && !isDrawingMode) {
                Box(
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(pageRatio)
                            .then(pageBorderModifier)
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Page $pageNum",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )
                        DrawingCanvas(
                            isDarkMode = isPageInverted,
                            isDrawingEnabled = false,
                            strokes = strokes,
                            onStrokesChanged = onStrokesChanged,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                }
            } else {
                val containerRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f
                val fittedPageModifier = if (containerRatio > pageRatio) {
                    Modifier.fillMaxHeight().aspectRatio(pageRatio, matchHeightConstraintsFirst = true)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(pageRatio, matchHeightConstraintsFirst = false)
                }

                Box(
                    modifier = fittedPageModifier.then(pageBorderModifier)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Page $pageNum",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                    DrawingCanvas(
                        isDarkMode = isPageInverted,
                        isDrawingEnabled = isDrawingMode,
                        strokes = strokes,
                        onStrokesChanged = onStrokesChanged,
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
        }
    }
}

/**
 * Fast interior pixel sampling matching container app's `pdf_render.py` comic/artwork detector:
 * preserves authentic colors on graphic novels, comics, and image-heavy pages instead of inverting them.
 */
internal fun isArtworkOrComicBitmap(bmp: Bitmap): Boolean {
    val w = bmp.width
    val h = bmp.height
    if (w < 16 || h < 16) return false

    val cols = 24
    val rows = 32
    val xStart = (w * 0.06f).toInt()
    val xEnd = (w * 0.94f).toInt().coerceAtLeast(xStart + 1)
    val yStart = (h * 0.06f).toInt()
    val yEnd = (h * 0.94f).toInt().coerceAtLeast(yStart + 1)

    var total = 0
    var whitePaper = 0
    var colorful = 0

    for (ry in 0 until rows) {
        val y = yStart + ((yEnd - yStart) * ry) / rows
        for (cx in 0 until cols) {
            val x = xStart + ((xEnd - xStart) * cx) / cols
            val px = bmp.getPixel(x, y)
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            total++
            if (r >= 232 && g >= 232 && b >= 232) {
                whitePaper++
            }
            val spread = maxOf(r, g, b) - minOf(r, g, b)
            if (spread >= 38) {
                colorful++
            }
        }
    }

    if (total == 0) return false
    val whiteFraction = whitePaper.toFloat() / total
    val colorFraction = colorful.toFloat() / total
    return whiteFraction < 0.55f || colorFraction > 0.18f
}
