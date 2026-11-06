package com.unremarkable.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.unremarkable.android.render.AndroidDocumentOptimizer
import com.unremarkable.android.storage.AndroidTokenStorage
import com.unremarkable.android.storage.AnnotationStore
import com.unremarkable.android.storage.AppSettings
import com.unremarkable.android.storage.PersistentDocumentRepository
import com.unremarkable.android.storage.ThemeMode
import com.unremarkable.android.ui.components.AdaptiveScaffold
import com.unremarkable.android.ui.components.ConverterSheet
import com.unremarkable.android.ui.components.MeetingRecorderDialog
import com.unremarkable.android.ui.components.QuickCaptureDialog
import com.unremarkable.android.ui.screens.OnboardingScreen
import com.unremarkable.android.ui.screens.SettingsScreen
import com.unremarkable.android.ui.theme.UnremarkableTheme
import com.unremarkable.core.cloud.AuthManager
import com.unremarkable.core.cloud.FileBlobStore
import com.unremarkable.core.cloud.RMCloudClient
import com.unremarkable.core.cloud.SyncEngine
import com.unremarkable.core.cloud.SyncResult
import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.model.PageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var tokenStorage: AndroidTokenStorage
    private lateinit var authManager: AuthManager
    private lateinit var appSettings: AppSettings
    private lateinit var repository: PersistentDocumentRepository
    private lateinit var blobStore: FileBlobStore
    private lateinit var annotationStore: AnnotationStore
    private lateinit var cloudClient: RMCloudClient
    private lateinit var syncEngine: SyncEngine
    private var onImmediateSyncAction: (() -> Unit)? = null
    private var onDocumentsRefreshed: (() -> Unit)? = null

    private var pendingShareIntent by mutableStateOf<Intent?>(null)
    private val libraryWriter = Dispatchers.IO.limitedParallelism(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tokenStorage = AndroidTokenStorage(applicationContext)
        authManager = AuthManager(storage = tokenStorage)
        appSettings = AppSettings(applicationContext)
        repository = PersistentDocumentRepository(applicationContext)
        blobStore = FileBlobStore(File(filesDir, "blobs"))
        annotationStore = AnnotationStore(File(filesDir, "annotations"))
        cloudClient = RMCloudClient(auth = authManager)
        syncEngine = SyncEngine(client = cloudClient, blobStore = blobStore, repository = repository)

        // Asynchronously check cached documents and backfill lastOpenedPage without blocking main UI thread
        lifecycleScope.launch(Dispatchers.IO) {
            val existingDocs = repository.getAllDocuments()
            if (existingDocs.any { !it.isFolder && !it.localOnly && it.subfiles.isEmpty() }) {
                repository.saveRoot("", 0L)
            }
            var updatedAny = false
            for (doc in existingDocs) {
                if (!doc.isFolder && doc.lastOpenedPage == 0) {
                    val metaHash = doc.subfiles.entries.firstOrNull { it.key.endsWith(".metadata") }?.value
                    val cachedMeta = metaHash?.let { blobStore.getBlob(it) }
                    if (cachedMeta != null) {
                        val contentHash = doc.subfiles.entries.firstOrNull { it.key.endsWith(".content") }?.value
                        val cachedContent = contentHash?.let { blobStore.getBlob(it) }
                        val meta = com.unremarkable.core.model.DocumentMetadata.parseFromRaw(
                            String(cachedMeta, java.nio.charset.StandardCharsets.UTF_8),
                            cachedContent?.let { String(it, java.nio.charset.StandardCharsets.UTF_8) }
                        )
                        if (meta.lastOpenedPage > 0) {
                            repository.updateLastOpenedPage(doc.id, meta.lastOpenedPage)
                            updatedAny = true
                        }
                    }
                }
            }
            if (updatedAny) {
                withContext(Dispatchers.Main) {
                    onDocumentsRefreshed?.invoke()
                }
            }
        }

        if (savedInstanceState == null) {
            pendingShareIntent = intent
        }

        setContent {
            var themeModeState by remember { mutableStateOf(appSettings.themeMode) }
            val isDarkTheme = when (themeModeState) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }

            UnremarkableTheme(darkTheme = isDarkTheme) {
                var documents by remember { mutableStateOf(repository.getAllDocuments()) }

                DisposableEffect(Unit) {
                    onDocumentsRefreshed = {
                        documents = repository.getAllDocuments()
                    }
                    onDispose {
                        onDocumentsRefreshed = null
                    }
                }
                var selectedDocument by remember { mutableStateOf<DocumentItem?>(null) }
                var currentFolderId by remember { mutableStateOf("") }
                var isSyncing by remember { mutableStateOf(false) }
                var syncStatusMessage by remember { mutableStateOf("") }
                var syncGeneration by remember { mutableLongStateOf(repository.getLocalGeneration()) }

                var showSettings by remember { mutableStateOf(false) }
                var showOnboarding by remember {
                    mutableStateOf(!authManager.isAuthenticated && repository.getAllDocuments().isEmpty())
                }
                var isPairing by remember { mutableStateOf(false) }
                var pairingError by remember { mutableStateOf<String?>(null) }

                var showQuickCapture by remember { mutableStateOf(false) }
                var showMeetingRecorder by remember { mutableStateOf(false) }
                var showConverterSheet by remember { mutableStateOf(false) }
                var converterFileName by remember { mutableStateOf("") }
                var converterUri by remember { mutableStateOf<Uri?>(null) }
                var converterSourceFile by remember { mutableStateOf<File?>(null) }
                var isConverting by remember { mutableStateOf(false) }
                var conversionProgress by remember { mutableStateOf("") }

                val documentPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        converterSourceFile = null
                        converterUri = uri
                        converterFileName = queryFileName(uri) ?: "Selected Document.pdf"
                        showConverterSheet = true
                    }
                }

                fun runSync(silentIfUnauthenticated: Boolean = false) {
                    if (isSyncing) return
                    if (!authManager.isAuthenticated) {
                        if (!silentIfUnauthenticated) {
                            Toast.makeText(
                                this@MainActivity,
                                "Connect your reMarkable account in Settings to sync",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return
                    }
                    lifecycleScope.launch {
                        isSyncing = true
                        syncStatusMessage = "Connecting to reMarkable Cloud..."
                        val result = syncEngine.sync { progress ->
                            syncStatusMessage = progress.message
                        }
                        isSyncing = false
                        syncStatusMessage = ""
                        when (result) {
                            is SyncResult.Success -> {
                                val updated = repository.getAllDocuments()
                                documents = updated
                                syncGeneration = repository.getLocalGeneration()
                                selectedDocument?.let { openDoc ->
                                    selectedDocument = updated.find { it.id == openDoc.id }
                                        ?: updated.find { !it.isFolder && it.visibleName == openDoc.visibleName }
                                }
                                Toast.makeText(
                                    this@MainActivity,
                                    "Synced ${result.syncedDocs} documents",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            is SyncResult.UpToDate -> {
                                documents = repository.getAllDocuments()
                                syncGeneration = repository.getLocalGeneration()
                                if (!silentIfUnauthenticated) {
                                    Toast.makeText(this@MainActivity, "Library is up to date", Toast.LENGTH_SHORT).show()
                                }
                            }
                            is SyncResult.Error -> {
                                documents = repository.getAllDocuments()
                                Toast.makeText(this@MainActivity, "Sync: ${result.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    if (authManager.isAuthenticated && appSettings.autoSyncOnStart) {
                        runSync(silentIfUnauthenticated = true)
                    }
                }

                LaunchedEffect(pendingShareIntent) {
                    val incoming = pendingShareIntent ?: return@LaunchedEffect
                    pendingShareIntent = null
                    handleIncomingShareIntent(incoming) { uri, name ->
                        converterSourceFile = null
                        converterUri = uri
                        converterFileName = name
                        showConverterSheet = true
                    }
                }

                var pageSyncJob by remember { mutableStateOf<Job?>(null) }

                fun handlePageChanged(docId: String, pageIndex: Int, immediate: Boolean = false) {
                    val prevDoc = repository.getDocument(docId)
                    val pageActuallyChanged = prevDoc == null || prevDoc.lastOpenedPage != pageIndex
                    val isPending = repository.getPendingPageSyncDocIds().contains(docId)

                    if (pageActuallyChanged) {
                        // Saving rewrites the whole library file, so keep it off the UI thread.
                        // One writer thread keeps fast page turns in order.
                        lifecycleScope.launch {
                            withContext(libraryWriter) { repository.updateLastOpenedPage(docId, pageIndex) }
                            documents = repository.getAllDocuments()
                        }
                        if (selectedDocument?.id == docId && selectedDocument?.lastOpenedPage != pageIndex) {
                            selectedDocument = selectedDocument?.copy(lastOpenedPage = pageIndex)
                        }
                    }

                    val doc = repository.getDocument(docId) ?: return
                    if (!authManager.isAuthenticated || doc.localOnly) return

                    // Skip network call if page hasn't changed and no sync is pending
                    if (!pageActuallyChanged && !isPending) {
                        return
                    }

                    pageSyncJob?.cancel()
                    pageSyncJob = lifecycleScope.launch {
                        if (!immediate) {
                            delay(2000)
                        }
                        try {
                            val success = syncEngine.updateRemotePosition(docId, pageIndex)
                            if (success) {
                                syncGeneration = repository.getLocalGeneration()
                                documents = repository.getAllDocuments()
                            } else {
                                // Stays queued in pending_page_syncs; retried on the next library sync
                                repository.markPageSyncPending(docId)
                                val reason = syncEngine.lastPositionSyncError ?: "unknown error"
                                android.util.Log.w("UnremarkableSync", "Position push failed for $docId p$pageIndex: $reason")
                                if (immediate) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Couldn't sync reading position: $reason",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.w("UnremarkableSync", "Position push crashed for $docId", e)
                        }
                    }
                }

                DisposableEffect(selectedDocument) {
                    onImmediateSyncAction = {
                        selectedDocument?.let { doc ->
                            handlePageChanged(doc.id, doc.lastOpenedPage, immediate = true)
                        }
                    }
                    onDispose {
                        onImmediateSyncAction = null
                    }
                }

                val easeOutExpo = remember { CubicBezierEasing(0.16f, 1f, 0.3f, 1f) }

                BackHandler(enabled = selectedDocument != null && !showSettings) {
                    selectedDocument?.let { doc ->
                        handlePageChanged(doc.id, doc.lastOpenedPage, immediate = true)
                    }
                    selectedDocument = null
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (showOnboarding) {
                            OnboardingScreen(
                                isPairing = isPairing,
                                errorMessage = pairingError,
                                onPairCode = { code ->
                                    lifecycleScope.launch {
                                        isPairing = true
                                        pairingError = null
                                        val res = authManager.registerDevice(code)
                                        isPairing = false
                                        if (res.isSuccess) {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "reMarkable Connected Successfully!",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            showOnboarding = false
                                            runSync()
                                        } else {
                                            val error = res.exceptionOrNull()
                                            pairingError = when {
                                                error is java.net.SocketTimeoutException ||
                                                    error?.message?.contains("timeout", ignoreCase = true) == true ->
                                                    "Connection timed out. Please check your network and try again."
                                                error is java.net.UnknownHostException ->
                                                    "Cannot reach reMarkable cloud. Verify your internet connection."
                                                error?.message?.contains("Invalid", ignoreCase = true) == true ||
                                                    error?.message?.contains("400") == true ->
                                                    "Invalid or expired one-time code. Generate a fresh code at my.remarkable.com."
                                                else ->
                                                    error?.message ?: "Pairing failed. Please check the code and try again."
                                            }
                                        }
                                    }
                                },
                                onSkip = { showOnboarding = false }
                            )
                        } else {
                            AdaptiveScaffold(
                                documents = documents,
                                isSyncing = isSyncing,
                                syncStatusMessage = syncStatusMessage,
                                syncGeneration = syncGeneration,
                                currentFolderId = currentFolderId,
                                onOpenFolder = { currentFolderId = it },
                                onSyncNow = { runSync() },
                                onQuickCapture = { showQuickCapture = true },
                                onOpenConverter = {
                                    converterUri = null
                                    converterSourceFile = null
                                    converterFileName = ""
                                    showConverterSheet = true
                                },
                                onOpenSettings = { showSettings = true },
                                onOpenMeetingRecorder = { showMeetingRecorder = true },
                                onOptimizeCurrentPdf = { file, name ->
                                    converterUri = null
                                    converterSourceFile = file
                                    converterFileName = name
                                    showConverterSheet = true
                                },
                                selectedDocument = selectedDocument,
                                onSelectDocument = { nextDoc ->
                                    if (nextDoc == null && selectedDocument != null) {
                                        val curr = selectedDocument!!
                                        handlePageChanged(curr.id, curr.lastOpenedPage, immediate = true)
                                    }
                                    selectedDocument = nextDoc
                                },
                                onPageChanged = { docId, pageIndex ->
                                    handlePageChanged(docId, pageIndex, immediate = false)
                                },
                                blobStore = blobStore,
                                annotationStore = annotationStore,
                                appSettings = appSettings,
                                cloudClient = cloudClient,
                                initialIsSidebarMinimized = appSettings.isSidebarMinimized,
                                onSidebarMinimizedChanged = { isMin -> appSettings.isSidebarMinimized = isMin },
                                onDeleteDocument = { doc ->
                                    repository.markDocumentDeleted(doc.id)
                                    documents = repository.getAllDocuments()
                                    if (selectedDocument?.id == doc.id) {
                                        selectedDocument = null
                                    }
                                    if (doc.localOnly) {
                                        Toast.makeText(this@MainActivity, "\"${doc.visibleName}\" deleted", Toast.LENGTH_SHORT).show()
                                    } else lifecycleScope.launch {
                                        // Cloud docs go to the reMarkable trash; undo locally if that fails so nothing silently reappears
                                        val result = syncEngine.moveToTrash(doc.id)
                                        val message = if (result.isSuccess) {
                                            "\"${doc.visibleName}\" moved to trash"
                                        } else {
                                            repository.upsertDocument(doc.copy(deleted = false))
                                            documents = repository.getAllDocuments()
                                            "Couldn't move \"${doc.visibleName}\" to trash: ${result.exceptionOrNull()?.message}"
                                        }
                                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            )

                            AnimatedVisibility(
                                visible = showSettings,
                                enter = slideInHorizontally(tween(240, easing = easeOutExpo)) { it / 8 } + fadeIn(tween(180)),
                                exit = slideOutHorizontally(tween(200, easing = easeOutExpo)) { it / 8 } + fadeOut(tween(140)),
                                label = "SettingsOverlayTransition"
                            ) {
                                BackHandler(enabled = showSettings) {
                                    themeModeState = appSettings.themeMode
                                    showSettings = false
                                }
                                SettingsScreen(
                                    isAuthenticated = authManager.isAuthenticated,
                                    deviceId = authManager.currentTokens.deviceId,
                                    totalCacheSizeMb = blobStore.totalSizeBytes() / (1024f * 1024f),
                                    cachedBlobCount = blobStore.blobCount(),
                                    appSettings = appSettings,
                                    onThemeChanged = { themeModeState = it },
                                    onBack = {
                                        themeModeState = appSettings.themeMode
                                        showSettings = false
                                    },
                                    onPairAccount = {
                                        showSettings = false
                                        showOnboarding = true
                                    },
                                    onUnpairAccount = {
                                        authManager.clearTokens()
                                        repository.clearAll()
                                        blobStore.clear()
                                        annotationStore.clearAll()
                                        documents = emptyList()
                                        selectedDocument = null
                                        syncGeneration = 0L
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Device unpaired and cache cleared",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onForceResync = {
                                        repository.saveRoot("", 0L)
                                        showSettings = false
                                        runSync()
                                    },
                                    onClearCache = {
                                        blobStore.clear()
                                        Toast.makeText(this@MainActivity, "Local cache cleared", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }

                    if (showQuickCapture) {
                        QuickCaptureDialog(
                            onDismiss = { showQuickCapture = false },
                            onCapture = { text, isTask ->
                                lifecycleScope.launch {
                                    val now = System.currentTimeMillis()
                                    val pdfBytes = withContext(Dispatchers.Default) {
                                        AndroidDocumentOptimizer.generateQuickCapturePdf(text, isTask, now)
                                    }
                                    val pdfHash = FileBlobStore.sha256(pdfBytes)
                                    withContext(Dispatchers.IO) {
                                        blobStore.putBlob(pdfHash, pdfBytes)
                                    }
                                    val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty().take(48)
                                    val docTitle = (if (isTask) "[Task] $firstLine" else firstLine).ifBlank { "Quick Note" }
                                    val docId = UUID.randomUUID().toString()
                                    val newDoc = DocumentItem(
                                        id = docId,
                                        visibleName = docTitle,
                                        parent = currentFolderId,
                                        isFolder = false,
                                        fileType = "pdf",
                                        pageCount = 1,
                                        pages = listOf(PageRef("page-0", 0)),
                                        pinned = isTask,
                                        lastModified = now,
                                        hash = pdfHash,
                                        subfiles = mapOf("$docId.pdf" to pdfHash),
                                        tags = if (isTask) listOf("Task") else listOf("Note"),
                                        localOnly = true
                                    )
                                    repository.upsertDocument(newDoc)
                                    documents = repository.getAllDocuments()
                                    selectedDocument = newDoc
                                    Toast.makeText(
                                        this@MainActivity,
                                        if (authManager.isAuthenticated) "Saved & syncing to reMarkable..." else "Saved to library",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    runSync(silentIfUnauthenticated = true)
                                }
                            }
                        )
                    }

                    if (showMeetingRecorder) {
                        MeetingRecorderDialog(
                            repository = repository,
                            blobStore = blobStore,
                            onNotebookCreated = { notebook ->
                                documents = repository.getAllDocuments()
                                currentFolderId = notebook.parent
                                selectedDocument = notebook
                                runSync(silentIfUnauthenticated = true)
                            },
                            onDismiss = { showMeetingRecorder = false }
                        )
                    }

                    if (showConverterSheet) {
                        val hasSource = converterUri != null || converterSourceFile != null
                        ConverterSheet(
                            fileName = converterFileName,
                            hasInputSource = hasSource,
                            defaultProfile = appSettings.defaultDeviceProfile,
                            isConverting = isConverting,
                            progressMessage = conversionProgress,
                            onPickFile = {
                                documentPickerLauncher.launch(
                                    arrayOf(
                                        "application/pdf",
                                        "application/vnd.comicbook+zip",
                                        "application/x-cbz",
                                        "application/zip",
                                        "image/*"
                                    )
                                )
                            },
                            onDismiss = { if (!isConverting) showConverterSheet = false },
                            onStartConversion = { config ->
                                val srcFile = converterSourceFile
                                val srcUri = converterUri
                                if (srcFile == null && srcUri == null) return@ConverterSheet
                                lifecycleScope.launch {
                                    isConverting = true
                                    conversionProgress = "Preparing document..."
                                    try {
                                        val result = if (srcFile != null) {
                                            AndroidDocumentOptimizer.optimizeFile(
                                                inputFile = srcFile,
                                                fileName = converterFileName,
                                                config = config,
                                                onProgress = { p -> conversionProgress = p.message }
                                            )
                                        } else {
                                            AndroidDocumentOptimizer.optimizeUri(
                                                context = this@MainActivity,
                                                uri = srcUri!!,
                                                fileName = converterFileName,
                                                config = config,
                                                onProgress = { p -> conversionProgress = p.message }
                                            )
                                        }
                                        val pdfHash = FileBlobStore.sha256(result.pdfBytes)
                                        withContext(Dispatchers.IO) {
                                            blobStore.putBlob(pdfHash, result.pdfBytes)
                                        }
                                        val baseName = converterFileName
                                            .substringBeforeLast('.')
                                            .ifBlank { "Document" }
                                        val docId = UUID.randomUUID().toString()
                                        val convertedDoc = DocumentItem(
                                            id = docId,
                                            visibleName = "$baseName (${config.targetProfile.id.uppercase()})",
                                            parent = currentFolderId,
                                            isFolder = false,
                                            fileType = "pdf",
                                            pageCount = result.pageCount,
                                            pages = (0 until result.pageCount).map { PageRef("page-$it", it) },
                                            pinned = false,
                                            lastModified = System.currentTimeMillis(),
                                            hash = pdfHash,
                                            subfiles = mapOf("$docId.pdf" to pdfHash),
                                            tags = listOf("Optimized", config.targetProfile.id),
                                            localOnly = true
                                        )
                                        repository.upsertDocument(convertedDoc)
                                        documents = repository.getAllDocuments()
                                        selectedDocument = convertedDoc
                                        isConverting = false
                                        showConverterSheet = false
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Optimized ${result.pageCount} pages for ${config.targetProfile.displayName}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        runSync(silentIfUnauthenticated = true)
                                    } catch (e: Exception) {
                                        isConverting = false
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Optimization failed: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingShareIntent = intent
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingShareIntent(
        intent: Intent?,
        onFileReceived: (Uri, String) -> Unit
    ) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.data
            else -> null
        }
        if (uri != null) {
            val fileName = queryFileName(uri) ?: "Shared Document.pdf"
            onFileReceived(uri, fileName)
        }
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index)
                }
            }
        }
        return name ?: uri.path?.substringAfterLast('/')
    }

    override fun onStop() {
        super.onStop()
        onImmediateSyncAction?.invoke()
    }
}
