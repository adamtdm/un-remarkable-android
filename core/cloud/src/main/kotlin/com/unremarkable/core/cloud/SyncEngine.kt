package com.unremarkable.core.cloud

import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.model.DocumentMetadata
import com.unremarkable.core.model.Entry
import com.unremarkable.core.model.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

sealed class SyncResult {
    data class Success(val syncedDocs: Int, val generation: Long) : SyncResult()
    data object UpToDate : SyncResult()
    data class Error(val message: String, val cause: Throwable? = null) : SyncResult()
}

data class SyncProgress(
    val stage: String,
    val current: Int,
    val total: Int,
    val message: String = ""
)

class SyncEngine(
    private val client: RMCloudClient,
    private val blobStore: BlobStore,
    private val repository: SyncRepository
) {
    suspend fun sync(onProgress: (SyncProgress) -> Unit = {}): SyncResult = withContext(Dispatchers.IO) {
        try {
            onProgress(SyncProgress("Connecting", 0, 100, "Checking cloud root generation..."))
            val root = client.getRoot()

            val localHash = repository.getLocalRootHash()
            val localGen = repository.getLocalGeneration()

            if (localHash == root.hash && localGen >= root.generation) {
                return@withContext SyncResult.UpToDate
            }

            onProgress(SyncProgress("Root Index", 5, 100, "Downloading root directory manifest..."))
            val rawRoot = client.getBlob(root.hash, "root.docSchema")
            blobStore.putBlob(root.hash, rawRoot)

            val (rootEntries, _) = IndexParser.parseIndex(rawRoot)
            val docEntries = rootEntries.filter { it.isDocument }

            val totalDocs = docEntries.size
            val remoteDocIds = mutableSetOf<String>()

            for ((index, docEntry) in docEntries.withIndex()) {
                remoteDocIds.add(docEntry.docId)
                val progressPct = 10 + ((index + 1) * 85 / (totalDocs.coerceAtLeast(1)))
                onProgress(
                    SyncProgress(
                        stage = "Syncing Documents",
                        current = index + 1,
                        total = totalDocs,
                        message = "Processing document ${index + 1} of $totalDocs"
                    )
                )

                // 1. Fetch document index if missing
                val rawDocIndex: ByteArray = if (blobStore.hasBlob(docEntry.hash)) {
                    blobStore.getBlob(docEntry.hash) ?: client.getBlob(docEntry.hash, "${docEntry.docId}.docSchema")
                } else {
                    val fetched = client.getBlob(docEntry.hash, "${docEntry.docId}.docSchema")
                    blobStore.putBlob(docEntry.hash, fetched)
                    fetched
                }

                // 2. Parse subfiles to locate .content metadata
                val (subfiles, _) = IndexParser.parseIndex(rawDocIndex)
                val contentEntry = subfiles.find { it.docId.endsWith(".content") }

                var metadata = DocumentMetadata(visibleName = "Untitled", type = DocumentMetadata.TYPE_DOCUMENT)
                if (contentEntry != null) {
                    val rawContent: ByteArray = if (blobStore.hasBlob(contentEntry.hash)) {
                        blobStore.getBlob(contentEntry.hash) ?: client.getBlob(contentEntry.hash, contentEntry.docId)
                    } else {
                        val fetched = client.getBlob(contentEntry.hash, contentEntry.docId)
                        blobStore.putBlob(contentEntry.hash, fetched)
                        fetched
                    }
                    val jsonStr = String(rawContent, StandardCharsets.UTF_8)
                    metadata = DocumentMetadata.fromJson(jsonStr)
                }

                val docItem = DocumentItem(
                    id = docEntry.docId,
                    visibleName = metadata.visibleName.ifBlank { "Untitled" },
                    parent = metadata.parent,
                    isFolder = metadata.isFolder,
                    pageCount = metadata.pageCount,
                    pinned = metadata.pinned,
                    deleted = metadata.deleted,
                    lastModified = metadata.lastModified.toLongOrNull() ?: System.currentTimeMillis(),
                    tags = metadata.tags,
                    hash = docEntry.hash,
                    fileType = metadata.fileType
                )

                repository.upsertDocument(docItem)
            }

            // 3. Detect remote deletions
            val existingDocs = repository.getAllDocuments()
            for (existing in existingDocs) {
                if (existing.id !in remoteDocIds) {
                    repository.markDocumentDeleted(existing.id)
                }
            }

            // 4. Save state
            repository.saveRoot(root.hash, root.generation)
            onProgress(SyncProgress("Complete", 100, 100, "Library synchronized successfully."))

            SyncResult.Success(syncedDocs = totalDocs, generation = root.generation)
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Unknown sync error", e)
        }
    }
}
