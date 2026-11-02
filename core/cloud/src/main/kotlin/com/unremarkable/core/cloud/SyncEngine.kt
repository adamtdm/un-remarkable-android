package com.unremarkable.core.cloud

import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.model.DocumentMetadata
import com.unremarkable.core.model.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

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
    private val repository: SyncRepository,
    private val maxConcurrency: Int = 6
) {
    suspend fun sync(onProgress: (SyncProgress) -> Unit = {}): SyncResult = withContext(Dispatchers.IO) {
        try {
            // 1. Push any pending local-only PDFs/EPUBs before reading cloud root so new docs are ingested in one pass
            val uploadedLocalIds = mutableSetOf<String>()
            val pendingLocalDocs = repository.getAllDocuments().filter { it.localOnly && !it.isFolder }
            for (localDoc in pendingLocalDocs) {
                val payloadHash = localDoc.subfiles.entries
                    .firstOrNull { it.key.endsWith(".pdf", true) || it.key.endsWith(".epub", true) }
                    ?.value
                    ?: localDoc.hash.takeIf { it.isNotBlank() }
                val bytes = payloadHash?.let { blobStore.getBlob(it) }
                if (bytes != null && bytes.isNotEmpty()) {
                    try {
                        onProgress(SyncProgress("Uploading", 2, 100, "Uploading ${localDoc.visibleName}..."))
                        val ext = if (localDoc.fileType.equals("epub", true)) "epub" else "pdf"
                        val uploadName = if (localDoc.visibleName.endsWith(".$ext", true)) {
                            localDoc.visibleName
                        } else {
                            "${localDoc.visibleName}.$ext"
                        }
                        client.uploadDocument(
                            fileBytes = bytes,
                            filename = uploadName,
                            parentId = localDoc.parent,
                            fileType = ext
                        )
                        uploadedLocalIds.add(localDoc.id)
                    } catch (_: Exception) {
                        // Keep localOnly = true so offline or transient failure never loses user data
                    }
                }
            }

            onProgress(SyncProgress("Connecting", 5, 100, "Checking cloud root generation..."))
            val root = client.getRoot()

            val localHash = repository.getLocalRootHash()
            val localGen = repository.getLocalGeneration()

            if (uploadedLocalIds.isEmpty() && localHash == root.hash && localGen >= root.generation) {
                return@withContext SyncResult.UpToDate
            }

            onProgress(SyncProgress("Root Index", 10, 100, "Downloading root directory manifest..."))
            val rawRoot = getOrFetchBlob(root.hash, "root.docSchema")

            val (rootEntries, _) = IndexParser.parseIndex(rawRoot)
            val docEntries = rootEntries.filter { it.isDocument }
            val totalDocs = docEntries.size
            val remoteDocIds = docEntries.mapTo(mutableSetOf()) { it.docId }
            val existingById = repository.getAllDocuments().associateBy { it.id }

            val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
            val completed = AtomicInteger(0)

            // 2. Sync only changed/new document manifests & metadata concurrently; payloads download on-demand in Reader
            val syncedDocsList = coroutineScope {
                docEntries.map { docEntry ->
                    async {
                        semaphore.withPermit {
                            val prev = existingById[docEntry.docId]
                            val item = if (
                                prev != null &&
                                !prev.localOnly &&
                                prev.hash == docEntry.hash &&
                                (prev.isFolder || prev.subfiles.isNotEmpty())
                            ) {
                                prev
                            } else {
                                fetchDocumentMetadata(docEntry.docId, docEntry.hash)
                            }
                            val done = completed.incrementAndGet()
                            onProgress(
                                SyncProgress(
                                    stage = "Syncing Library",
                                    current = done,
                                    total = totalDocs.coerceAtLeast(1),
                                    message = "Synced $done of $totalDocs items"
                                )
                            )
                            item
                        }
                    }
                }.awaitAll()
            }

            repository.upsertDocuments(syncedDocsList)

            // 3. Detect remote deletions without wiping unpushed localOnly documents
            for (existing in repository.getAllDocuments()) {
                if (existing.id !in remoteDocIds) {
                    if (!existing.localOnly || existing.id in uploadedLocalIds) {
                        repository.markDocumentDeleted(existing.id)
                    }
                }
            }

            repository.saveRoot(root.hash, root.generation)
            onProgress(SyncProgress("Complete", 100, 100, "Library synchronized successfully."))

            SyncResult.Success(syncedDocs = totalDocs, generation = root.generation)
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Unknown sync error", e)
        }
    }

    private suspend fun fetchDocumentMetadata(docId: String, docHash: String): DocumentItem {
        val rawDocIndex = getOrFetchBlob(docHash, "$docId.docSchema")
        val (subfiles, _) = IndexParser.parseIndex(rawDocIndex)
        val metadataEntry = subfiles.find { it.docId.endsWith(".metadata") }
        val contentEntry = subfiles.find { it.docId.endsWith(".content") }

        val metadataJson = metadataEntry?.let {
            String(getOrFetchBlob(it.hash, it.docId), StandardCharsets.UTF_8)
        }
        val contentJson = contentEntry?.let {
            String(getOrFetchBlob(it.hash, it.docId), StandardCharsets.UTF_8)
        }

        val metadata = DocumentMetadata.parseFromRaw(metadataJson, contentJson)
        val subfileMap = subfiles.associate { it.docId to it.hash }

        return DocumentItem(
            id = docId,
            visibleName = metadata.visibleName.ifBlank { docId },
            parent = metadata.parent,
            isFolder = metadata.isFolder,
            pageCount = metadata.pageCount,
            pinned = metadata.pinned,
            deleted = metadata.deleted,
            lastModified = metadata.lastModified.toLongOrNull() ?: System.currentTimeMillis(),
            tags = metadata.tags,
            hash = docHash,
            fileType = metadata.fileType,
            subfiles = subfileMap,
            pages = metadata.pages,
            localOnly = false
        )
    }

    private suspend fun getOrFetchBlob(hash: String, filename: String): ByteArray {
        if (blobStore.hasBlob(hash)) {
            blobStore.getBlob(hash)?.let { return it }
        }
        val fetched = client.getBlob(hash, filename)
        blobStore.putBlob(hash, fetched)
        return fetched
    }
}
