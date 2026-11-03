package com.unremarkable.core.cloud

import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.model.DocumentMetadata
import com.unremarkable.core.model.Entry
import com.unremarkable.core.model.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
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
    private val maxConcurrency: Int = 3
) {
    private val positionSyncMutex = Mutex()
    private val lastPushedPositionByDocId = ConcurrentHashMap<String, Int>()
    suspend fun sync(onProgress: (SyncProgress) -> Unit = {}): SyncResult = withContext(Dispatchers.IO) {
        try {
            // 0. Push any pending reading position updates
            val pendingPageDocIds = repository.getPendingPageSyncDocIds()
            for (docId in pendingPageDocIds) {
                val doc = repository.getDocument(docId)
                if (doc != null && !doc.localOnly) {
                    try {
                        updateRemotePosition(docId, doc.lastOpenedPage)
                    } catch (_: Exception) {}
                }
            }

            // 1. Push any pending local-only folders first, then PDFs/EPUBs before reading cloud root so new docs are ingested in one pass
            val uploadedLocalIds = mutableSetOf<String>()
            val allLocalItems = repository.getAllDocuments()
            val pendingLocalFolders = allLocalItems.filter { it.localOnly && it.isFolder }
            for (localFolder in pendingLocalFolders) {
                try {
                    onProgress(SyncProgress("Uploading", 1, 100, "Creating folder ${localFolder.visibleName}..."))
                    createRemoteFolder(localFolder)
                    uploadedLocalIds.add(localFolder.id)
                } catch (_: Exception) {
                    // Keep localOnly = true so transient failure never loses folder structure
                }
            }

            val pendingLocalDocs = allLocalItems.filter { it.localOnly && !it.isFolder }
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
                                if (prev.lastOpenedPage == 0 && !prev.isFolder) {
                                    val metaHash = prev.subfiles.entries.firstOrNull { it.key.endsWith(".metadata") }?.value
                                    val cachedMeta = metaHash?.let { blobStore.getBlob(it) }
                                    if (cachedMeta != null) {
                                        val contentHash = prev.subfiles.entries.firstOrNull { it.key.endsWith(".content") }?.value
                                        val cachedContent = contentHash?.let { blobStore.getBlob(it) }
                                        val meta = DocumentMetadata.parseFromRaw(
                                            String(cachedMeta, StandardCharsets.UTF_8),
                                            cachedContent?.let { String(it, StandardCharsets.UTF_8) }
                                        )
                                        if (meta.lastOpenedPage > 0) {
                                            prev.copy(lastOpenedPage = meta.lastOpenedPage)
                                        } else {
                                            prev
                                        }
                                    } else {
                                        prev
                                    }
                                } else {
                                    prev
                                }
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
                            if (!item.localOnly && !item.isFolder) {
                                lastPushedPositionByDocId[item.id] = item.lastOpenedPage
                            }
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
            localOnly = false,
            lastOpenedPage = metadata.lastOpenedPage
        )
    }

    suspend fun updateRemotePosition(docId: String, pageIndex: Int): Boolean = withContext(Dispatchers.IO) {
        positionSyncMutex.withLock {
            if (lastPushedPositionByDocId[docId] == pageIndex && !repository.getPendingPageSyncDocIds().contains(docId)) {
                return@withLock true
            }
            try {
                val root = client.getRoot()
            val (rootEntries, rootSchema) = client.getRootIndex(root.hash)

            val docEntry = rootEntries.firstOrNull { it.docId == docId }
                ?: throw IllegalStateException("Document $docId not found in cloud root")

            val rawDocIndex = getOrFetchBlob(docEntry.hash, "$docId.docSchema")
            val (subfiles, docSchemaVer) = IndexParser.parseIndex(rawDocIndex)

            val metaEntry = subfiles.firstOrNull { it.docId.endsWith(".metadata") }
                ?: throw IllegalStateException("Document $docId has no .metadata subfile")
            val contentEntry = subfiles.firstOrNull { it.docId.endsWith(".content") }

            val nowMs = System.currentTimeMillis().toString()

            // 1. Update .metadata (never upload a blank metadata if parsing fails: that would wipe name/parent)
            val rawMeta = getOrFetchBlob(metaEntry.hash, metaEntry.docId)
            val metaElement = Json.parseToJsonElement(String(rawMeta, StandardCharsets.UTF_8)).jsonObject
            val metaMap = metaElement.toMutableMap()
            metaMap["lastOpenedPage"] = JsonPrimitive(pageIndex)
            metaMap["lastOpened"] = JsonPrimitive(nowMs)
            val newMetaBytes = JsonObject(metaMap).toString().toByteArray(StandardCharsets.UTF_8)
            val newMetaHash = IndexParser.computeRootHash(newMetaBytes)

            client.putBlob(newMetaHash, newMetaBytes, metaEntry.docId)
            blobStore.putBlob(newMetaHash, newMetaBytes)

            // 2. Update .content if present (cPages.lastOpened for firmware 3.x)
            var newContentHash: String? = null
            var newContentBytes: ByteArray? = null
            if (contentEntry != null) {
                try {
                    val rawContent = getOrFetchBlob(contentEntry.hash, contentEntry.docId)
                    val contentElement = Json.parseToJsonElement(String(rawContent, StandardCharsets.UTF_8)).jsonObject
                    val contentMap = contentElement.toMutableMap()

                    var pageId = ""
                    val cPagesElement = contentElement["cPages"]?.jsonObject
                    if (cPagesElement != null) {
                        val pagesArray = cPagesElement["pages"]?.jsonArray
                        if (pagesArray != null) {
                            val activePages = mutableListOf<Pair<String, String>>()
                            for ((pos, item) in pagesArray.withIndex()) {
                                val obj = item as? JsonObject ?: continue
                                val pid = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                                val delEl = obj["deleted"]
                                val isDel = (delEl as? JsonObject)?.get("value")?.jsonPrimitive?.intOrNull == 1 ||
                                    (delEl as? JsonPrimitive)?.intOrNull == 1 ||
                                    (delEl as? JsonPrimitive)?.booleanOrNull == true
                                if (isDel) continue
                                val idxVal = (obj["idx"] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
                                    ?: (obj["idx"] as? JsonPrimitive)?.contentOrNull
                                    ?: String.format("%06d", pos)
                                activePages.add(idxVal to pid)
                            }
                            activePages.sortBy { it.first }
                            if (pageIndex in activePages.indices) {
                                pageId = activePages[pageIndex].second
                            }
                        }
                    } else {
                        val legacyPages = contentElement["pages"]?.jsonArray
                        if (legacyPages != null && pageIndex in 0 until legacyPages.size) {
                            pageId = (legacyPages[pageIndex] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        }
                    }

                    if (cPagesElement != null && pageId.isNotEmpty()) {
                        val mutableCPages = cPagesElement.toMutableMap()
                        val lastOpenedObj = buildJsonObject {
                            put("value", JsonPrimitive(pageId))
                            put("timestamp", JsonPrimitive("1:${pageIndex + 1}"))
                        }
                        mutableCPages["lastOpened"] = lastOpenedObj
                        contentMap["cPages"] = JsonObject(mutableCPages)

                        val updatedContentStr = JsonObject(contentMap).toString()
                        val bytes = updatedContentStr.toByteArray(StandardCharsets.UTF_8)
                        val hash = IndexParser.computeRootHash(bytes)
                        client.putBlob(hash, bytes, contentEntry.docId)
                        blobStore.putBlob(hash, bytes)
                        newContentHash = hash
                        newContentBytes = bytes
                    }
                } catch (_: Exception) {}
            }

            // 3. Update doc index
            val updatedDocEntries = subfiles.map { entry ->
                when (entry.docId) {
                    metaEntry.docId -> Entry(
                        hash = newMetaHash,
                        type = entry.type,
                        docId = entry.docId,
                        subfiles = entry.subfiles,
                        size = newMetaBytes.size.toLong()
                    )
                    contentEntry?.docId -> if (newContentHash != null && newContentBytes != null) {
                        Entry(
                            hash = newContentHash,
                            type = entry.type,
                            docId = entry.docId,
                            subfiles = entry.subfiles,
                            size = newContentBytes.size.toLong()
                        )
                    } else entry
                    else -> entry
                }
            }

            // Schema v4 doc hashes are sha256 of the serialized .docSchema (header carries the doc UUID);
            // the old schema-3 hash-of-hashes produced a blob whose name didn't match its contents.
            val (docSchemaBytes, newDocHash) = IndexParser.buildDocSchema(updatedDocEntries, docSchemaVer, docId)
            client.putBlob(newDocHash, docSchemaBytes, "$docId.docSchema", "text/plain; charset=UTF-8")
            blobStore.putBlob(newDocHash, docSchemaBytes)

            // 4. Update root index (subfile count and total size must reflect the new doc index)
            val updatedRootEntries = rootEntries.map { entry ->
                if (entry.docId == docId) {
                    Entry(
                        hash = newDocHash,
                        type = entry.type,
                        docId = entry.docId,
                        subfiles = updatedDocEntries.size,
                        size = updatedDocEntries.sumOf { it.size }
                    )
                } else entry
            }

            val newRootBytes = IndexParser.formatIndex(updatedRootEntries, rootSchema, ".").toByteArray(StandardCharsets.UTF_8)
            val newRootHash = IndexParser.computeRootHash(newRootBytes)
            client.putBlob(newRootHash, newRootBytes, "root.docSchema", "text/plain; charset=UTF-8")
            blobStore.putBlob(newRootHash, newRootBytes)

            val newGen = client.updateRoot(newRootHash, root.generation, broadcast = true)
            repository.saveRoot(newRootHash, newGen)
            repository.clearPageSyncPending(docId)

            val existingDoc = repository.getDocument(docId)
            if (existingDoc != null) {
                val updatedSubfiles = existingDoc.subfiles.toMutableMap()
                updatedSubfiles[metaEntry.docId] = newMetaHash
                if (contentEntry != null && newContentHash != null) {
                    updatedSubfiles[contentEntry.docId] = newContentHash
                }
                repository.upsertDocument(
                    existingDoc.copy(
                        hash = newDocHash,
                        subfiles = updatedSubfiles,
                        lastOpenedPage = pageIndex
                    )
                )
            }
                lastPushedPositionByDocId[docId] = pageIndex
                lastPositionSyncError = null
                true
            } catch (e: Exception) {
                lastPositionSyncError = "${e::class.simpleName}: ${e.message}"
                false
            }
        }
    }

    /** Human-readable reason for the most recent failed [updateRemotePosition] call, or null. */
    @Volatile
    var lastPositionSyncError: String? = null
        private set

    private suspend fun createRemoteFolder(folder: DocumentItem) {
        val nowMs = folder.lastModified.takeIf { it > 0L }?.toString() ?: System.currentTimeMillis().toString()
        val metaJson = buildJsonObject {
            put("createdTime", JsonPrimitive(nowMs))
            put("lastModified", JsonPrimitive(nowMs))
            put("new", JsonPrimitive(true))
            put("parent", JsonPrimitive(folder.parent))
            put("pinned", JsonPrimitive(folder.pinned))
            put("source", JsonPrimitive(""))
            put("type", JsonPrimitive("CollectionType"))
            put("visibleName", JsonPrimitive(folder.visibleName))
        }.toString()
        val metaBytes = metaJson.toByteArray(StandardCharsets.UTF_8)
        val metaHash = IndexParser.computeRootHash(metaBytes)

        val metaEntry = Entry(
            hash = metaHash,
            type = Entry.FILE_TYPE,
            docId = "${folder.id}.metadata",
            subfiles = 0,
            size = metaBytes.size.toLong()
        )
        val folderDocHash = IndexParser.computeDocIndexHash(listOf(metaEntry))
        val docSchemaBytes = IndexParser.formatIndex(listOf(metaEntry), Entry.SCHEMA_V3).toByteArray(StandardCharsets.UTF_8)

        client.putBlob(metaHash, metaBytes, "${folder.id}.metadata")
        client.putBlob(folderDocHash, docSchemaBytes, "${folder.id}.docSchema", "text/plain; charset=UTF-8")

        val root = client.getRoot()
        val (entries, schema) = client.getRootIndex(root.hash)
        val updatedEntries = entries.filterNot { it.docId == folder.id } + Entry(
            hash = folderDocHash,
            type = Entry.DOC_TYPE,
            docId = folder.id,
            subfiles = 1,
            size = metaBytes.size.toLong()
        )
        val newRootBytes = IndexParser.formatIndex(updatedEntries, schema).toByteArray(StandardCharsets.UTF_8)
        val newRootHash = IndexParser.computeRootHash(newRootBytes)
        client.putBlob(newRootHash, newRootBytes, "root.docSchema", "text/plain; charset=UTF-8")
        client.updateRoot(newRootHash, root.generation, broadcast = true)
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
