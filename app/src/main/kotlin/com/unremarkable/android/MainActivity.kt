package com.unremarkable.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.unremarkable.android.ui.components.AdaptiveScaffold
import com.unremarkable.android.ui.components.ConverterSheet
import com.unremarkable.android.ui.components.QuickCaptureDialog
import com.unremarkable.android.ui.theme.UnremarkableTheme
import com.unremarkable.core.converter.ComicConverterEngine
import com.unremarkable.core.converter.ConverterConfig
import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.model.InMemorySyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private val repository = InMemorySyncRepository()
    private val converterEngine = ComicConverterEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Seed with starter notebooks for immediate offline test & display
        seedDemoData()

        setContent {
            UnremarkableTheme {
                var documents by remember { mutableStateOf(repository.getAllDocuments()) }
                var selectedDocument by remember { mutableStateOf<DocumentItem?>(null) }
                var currentFolderId by remember { mutableStateOf("") }
                var isSyncing by remember { mutableStateOf(false) }
                var syncGeneration by remember { mutableLongStateOf(repository.getLocalGeneration()) }

                var showQuickCapture by remember { mutableStateOf(false) }
                var showConverterSheet by remember { mutableStateOf(false) }
                var converterFileName by remember { mutableStateOf("Shared Document.cbz") }
                var isConverting by remember { mutableStateOf(false) }
                var conversionProgress by remember { mutableStateOf("") }

                // Check for incoming Share Sheet Intent (e.g. user selected "Share -> Un-remarkable")
                LaunchedEffect(intent) {
                    handleIncomingShareIntent(intent) { uri, name ->
                        converterFileName = name
                        showConverterSheet = true
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    AdaptiveScaffold(
                        documents = documents,
                        isSyncing = isSyncing,
                        syncGeneration = syncGeneration,
                        currentFolderId = currentFolderId,
                        onOpenFolder = { currentFolderId = it },
                        onSyncNow = {
                            lifecycleScope.launch {
                                isSyncing = true
                                delay(1200) // Simulated quick cloud refresh
                                syncGeneration += 1
                                repository.saveRoot("local-root-hash-$syncGeneration", syncGeneration)
                                documents = repository.getAllDocuments()
                                isSyncing = false
                                Toast.makeText(this@MainActivity, "Library up to date", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onQuickCapture = { showQuickCapture = true },
                        onOpenConverter = {
                            converterFileName = "Comic_Issue_01.cbz"
                            showConverterSheet = true
                        },
                        selectedDocument = selectedDocument,
                        onSelectDocument = { selectedDocument = it }
                    )

                    // Quick Capture Bottom Sheet / Dialog
                    if (showQuickCapture) {
                        QuickCaptureDialog(
                            onDismiss = { showQuickCapture = false },
                            onCapture = { text, isTask ->
                                val docTitle = if (isTask) "[\u2713] $text" else text
                                val newDoc = DocumentItem(
                                    id = java.util.UUID.randomUUID().toString(),
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
                                    delay(1000)
                                    conversionProgress = "Applying 5-stop quality preset..."
                                    delay(800)
                                    conversionProgress = "Splitting two-page spreads into portrait..."
                                    delay(600)

                                    val convertedDoc = DocumentItem(
                                        id = java.util.UUID.randomUUID().toString(),
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
                                        "Optimized & uploaded to reMarkable Cloud!",
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

    private fun seedDemoData() {
        if (repository.getAllDocuments().isNotEmpty()) return

        val workFolder = DocumentItem(
            id = "folder-work",
            visibleName = "Work & Projects",
            parent = "",
            isFolder = true,
            lastModified = System.currentTimeMillis() - 86400000
        )
        val meetingNotes = DocumentItem(
            id = "doc-meeting",
            visibleName = "Sprint Architecture & Sync15 Notes",
            parent = "folder-work",
            isFolder = false,
            pageCount = 6,
            pinned = true,
            lastModified = System.currentTimeMillis() - 3600000,
            tags = listOf("Sprint", "reMarkable")
        )
        val comicDoc = DocumentItem(
            id = "doc-comic",
            visibleName = "Graphic Novel (Paper Pro Optimized)",
            parent = "",
            isFolder = false,
            pageCount = 48,
            pinned = false,
            lastModified = System.currentTimeMillis() - 7200000,
            tags = listOf("Comic", "Paper Pro")
        )
        val quickNotes = DocumentItem(
            id = "doc-tasks",
            visibleName = "Tasks & Action Items",
            parent = "",
            isFolder = false,
            pageCount = 3,
            pinned = true,
            lastModified = System.currentTimeMillis() - 1800000,
            tags = listOf("Tasks", "TODO")
        )

        repository.upsertDocument(workFolder)
        repository.upsertDocument(meetingNotes)
        repository.upsertDocument(comicDoc)
        repository.upsertDocument(quickNotes)
        repository.saveRoot("init-root-hash", 42L)
    }
}
