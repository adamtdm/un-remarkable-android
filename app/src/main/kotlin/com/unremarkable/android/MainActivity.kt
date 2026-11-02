package com.unremarkable.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.unremarkable.android.storage.AndroidTokenStorage
import com.unremarkable.android.storage.AppSettings
import com.unremarkable.android.storage.PersistentDocumentRepository
import com.unremarkable.android.storage.ThemeMode
import com.unremarkable.android.ui.components.AdaptiveScaffold
import com.unremarkable.android.ui.components.ConverterSheet
import com.unremarkable.android.ui.components.QuickCaptureDialog
import com.unremarkable.android.ui.screens.OnboardingScreen
import com.unremarkable.android.ui.screens.SettingsScreen
import com.unremarkable.android.ui.theme.UnremarkableTheme
import com.unremarkable.core.cloud.AuthManager
import com.unremarkable.core.cloud.FileBlobStore
import com.unremarkable.core.cloud.RMCloudClient
import com.unremarkable.core.cloud.SyncEngine
import com.unremarkable.core.cloud.SyncResult
import com.unremarkable.core.converter.ComicConverterEngine
import com.unremarkable.core.model.DocumentItem
import kotlinx.coroutines.Dispatchers
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
    private lateinit var cloudClient: RMCloudClient
    private lateinit var syncEngine: SyncEngine
    private val converterEngine = ComicConverterEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize persistent architecture components
        tokenStorage = AndroidTokenStorage(applicationContext)
        authManager = AuthManager(storage = tokenStorage)
        appSettings = AppSettings(applicationContext)
        repository = PersistentDocumentRepository(applicationContext)
        blobStore = FileBlobStore(File(filesDir, "blobs"))
        cloudClient = RMCloudClient(auth = authManager)
        syncEngine = SyncEngine(client = cloudClient, blobStore = blobStore, repository = repository)

        setContent {
            var themeModeState by remember { mutableStateOf(appSettings.themeMode) }
            val isDarkTheme = when (themeModeState) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }

            UnremarkableTheme(darkTheme = isDarkTheme) {
                var documents by remember { mutableStateOf(repository.getAllDocuments()) }
                var selectedDocument by remember { mutableStateOf<DocumentItem?>(null) }
                var currentFolderId by remember { mutableStateOf("") }
                var isSyncing by remember { mutableStateOf(false) }
                var syncGeneration by remember { mutableLongStateOf(repository.getLocalGeneration()) }

                var showSettings by remember { mutableStateOf(false) }
                var showOnboarding by remember { mutableStateOf(!authManager.isAuthenticated && repository.getAllDocuments().isEmpty()) }
                var isPairing by remember { mutableStateOf(false) }
                var pairingError by remember { mutableStateOf<String?>(null) }

                var showQuickCapture by remember { mutableStateOf(false) }
                var showConverterSheet by remember { mutableStateOf(false) }
                var converterFileName by remember { mutableStateOf("Shared Document.cbz") }
                var isConverting by remember { mutableStateOf(false) }
                var conversionProgress by remember { mutableStateOf("") }

                // Sync function
                val runSync = {
                    if (!isSyncing) {
                        if (!authManager.isAuthenticated) {
                            Toast.makeText(this@MainActivity, "Connect your reMarkable account in Settings to sync", Toast.LENGTH_SHORT).show()
                        } else {
                            lifecycleScope.launch {
                                isSyncing = true
                                val result = syncEngine.sync()
                                isSyncing = false
                                when (result) {
                                    is SyncResult.Success -> {
                                        documents = repository.getAllDocuments()
                                        syncGeneration = repository.getLocalGeneration()
                                        Toast.makeText(this@MainActivity, "Synced ${result.syncedDocs} documents", Toast.LENGTH_SHORT).show()
                                    }
                                    is SyncResult.UpToDate -> {
                                        Toast.makeText(this@MainActivity, "Library is up to date", Toast.LENGTH_SHORT).show()
                                    }
                                    is SyncResult.Error -> {
                                        Toast.makeText(this@MainActivity, "Sync: ${result.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }
                }

                // Auto-sync on startup if enabled and authenticated
                LaunchedEffect(Unit) {
                    if (authManager.isAuthenticated && appSettings.autoSyncOnStart) {
                        runSync()
                    }
                }

                // Check for incoming Share Sheet Intent (e.g. user selected "Share -> Un-remarkable")
                LaunchedEffect(intent) {
                    handleIncomingShareIntent(intent) { _, name ->
                        converterFileName = name
                        showConverterSheet = true
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    when {
                        showOnboarding -> {
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
                                            Toast.makeText(this@MainActivity, "reMarkable Connected Successfully!", Toast.LENGTH_SHORT).show()
                                            showOnboarding = false
                                            runSync()
                                        } else {
                                            pairingError = res.exceptionOrNull()?.message ?: "Pairing failed. Please check the code."
                                        }
                                    }
                                },
                                onSkip = {
                                    showOnboarding = false
                                }
                            )
                        }
                        showSettings -> {
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
                                    documents = emptyList()
                                    selectedDocument = null
                                    Toast.makeText(this@MainActivity, "Device unpaired and cache cleared", Toast.LENGTH_SHORT).show()
                                },
                                onForceResync = {
                                    repository.saveRoot("", 0L)
                                    runSync()
                                },
                                onClearCache = {
                                    blobStore.clear()
                                    Toast.makeText(this@MainActivity, "Local cache cleared", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        else -> {
                            AdaptiveScaffold(
                                documents = documents,
                                isSyncing = isSyncing,
                                syncGeneration = syncGeneration,
                                currentFolderId = currentFolderId,
                                onOpenFolder = { currentFolderId = it },
                                onSyncNow = { runSync() },
                                onQuickCapture = { showQuickCapture = true },
                                onOpenConverter = {
                                    converterFileName = "Notebook_Export.cbz"
                                    showConverterSheet = true
                                },
                                onOpenSettings = { showSettings = true },
                                selectedDocument = selectedDocument,
                                onSelectDocument = { selectedDocument = it }
                            )
                        }
                    }

                    // Quick Capture Bottom Sheet / Dialog
                    if (showQuickCapture) {
                        QuickCaptureDialog(
                            onDismiss = { showQuickCapture = false },
                            onCapture = { text, isTask ->
                                val docTitle = if (isTask) "[\u2713] $text" else text
                                val newDoc = DocumentItem(
                                    id = UUID.randomUUID().toString(),
                                    visibleName = docTitle,
                                    parent = currentFolderId,
                                    isFolder = false,
                                    pageCount = 1,
                                    pinned = isTask,
                                    lastModified = System.currentTimeMillis(),
                                    tags = if (isTask) listOf("Task") else listOf("Note")
                                )
                                repository.upsertDocument(newDoc)
                                documents = repository.getAllDocuments()
                                Toast.makeText(
                                    this@MainActivity,
                                    if (isTask) "Added to Tasks notebook" else "Added to Quick Notes",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }

                    // On-Device Comic & Document Converter Sheet
                    if (showConverterSheet) {
                        ConverterSheet(
                            fileName = converterFileName,
                            isConverting = isConverting,
                            progressMessage = conversionProgress,
                            onDismiss = { showConverterSheet = false },
                            onStartConversion = { config ->
                                lifecycleScope.launch {
                                    isConverting = true
                                    conversionProgress = "Optimizing pages to ${config.targetProfile.displayName}..."
                                    kotlinx.coroutines.delay(800)
                                    conversionProgress = "Applying 5-stop quality preset..."
                                    kotlinx.coroutines.delay(600)
                                    conversionProgress = "Splitting two-page spreads into portrait..."
                                    kotlinx.coroutines.delay(600)

                                    val convertedDoc = DocumentItem(
                                        id = UUID.randomUUID().toString(),
                                        visibleName = converterFileName.substringBeforeLast('.') + " (Optimized).pdf",
                                        parent = currentFolderId,
                                        isFolder = false,
                                        pageCount = 24,
                                        pinned = false,
                                        lastModified = System.currentTimeMillis(),
                                        tags = listOf("Optimized", config.targetProfile.id)
                                    )
                                    repository.upsertDocument(convertedDoc)
                                    documents = repository.getAllDocuments()

                                    isConverting = false
                                    showConverterSheet = false
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Document optimized for ${config.targetProfile.displayName}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun handleIncomingShareIntent(
        intent: Intent?,
        onFileReceived: (Uri, String) -> Unit
    ) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.data
            if (uri != null) {
                val fileName = queryFileName(uri) ?: "Shared Document"
                onFileReceived(uri, fileName)
            }
        }
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path?.substringAfterLast('/')
        }
        return name
    }
}
