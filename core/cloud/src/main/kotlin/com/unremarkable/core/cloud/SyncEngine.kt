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
    private val remoteWriteMutex = Mutex()
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
                        // The cloud copy (with a server-assigned id) replaces this one on the pull below.
                        // Retire it now so a failure later in this sync can never upload it twice.
                        repository.markDocumentDeleted(localDoc.id)
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
            deleted = metadata.deleted || metadata.parent == "trash",
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
        remoteWriteMutex.withLock {
            if (lastPushedPositionByDocId[docId] == pageIndex && !repository.getPendingPageSyncDocIds().contains(docId)) {
                return@withLock true
            }
            try {
                val nowMs = System.currentTimeMillis().toString()
                val (newDocHash, newHashes) = editRemoteDoc(
                    docId,
                    editMeta = {
                        it["lastOpenedPage"] = JsonPrimitive(pageIndex)
                        it["lastOpened"] = JsonPrimitive(nowMs)
                    },
                    editContent = { withLastOpenedPage(it, pageIndex) }
                )
                repository.clearPageSyncPending(docId)
                repository.getDocument(docId)?.let { doc ->
                    repository.upsertDocument(
                        doc.copy(hash = newDocHash, subfiles = doc.subfiles + newHashes, lastOpenedPage = pageIndex)
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

    /** Moves a cloud document or folder to the reMarkable trash, the same as deleting it on the tablet. */
    suspend fun moveToTrash(docId: String): Result<Unit> = withContext(Dispatchers.IO) {
        remoteWriteMutex.withLock {
            try {
                editRemoteDoc(docId, editMeta = {
                    it["parent"] = JsonPrimitive("trash")
                    it["lastModified"] = JsonPrimitive(System.currentTimeMillis().toString())
                })
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Rewrites a cloud document's .metadata (and optionally .content) JSON, then commits a new root.
     * Returns the new doc index hash and the new hash of each rewritten subfile.
     */
    private suspend fun editRemoteDoc(
        docId: String,
        editMeta: (MutableMap<String, JsonElement>) -> Unit,
        editContent: ((JsonObject) -> JsonObject?)? = null
    ): Pair<String, Map<String, String>> {
        val root = client.getRoot()
        val (rootEntries, rootSchema) = client.getRootIndex(root.hash)
        val docEntry = rootEntries.firstOrNull { it.docId == docId }
            ?: throw IllegalStateException("Document $docId not found in cloud root")
        val (subfiles, docSchemaVer) = IndexParser.parseIndex(getOrFetchBlob(docEntry.hash, "$docId.docSchema"))
        val metaEntry = subfiles.firstOrNull { it.docId.endsWith(".metadata") }
            ?: throw IllegalStateException("Document $docId has no .metadata subfile")
        val contentEntry = subfiles.firstOrNull { it.docId.endsWith(".content") }

        val rewritten = mutableMapOf<String, Entry>()
        suspend fun upload(entry: Entry, json: JsonObject) {
            val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
            val hash = IndexParser.computeRootHash(bytes)
            client.putBlob(hash, bytes, entry.docId)
            blobStore.putBlob(hash, bytes)
            rewritten[entry.docId] = entry.copy(hash = hash, size = bytes.size.toLong())
        }

        // readJson throws on unparseable metadata, so we never upload a blank one that would wipe name/parent
        val meta = readJson(metaEntry).toMutableMap()
        editMeta(meta)
        upload(metaEntry, JsonObject(meta))
        if (contentEntry != null && editContent != null) {
            // .content edits are best-effort; the metadata change is valid on its own
            try {
                editContent(readJson(contentEntry))?.let { upload(contentEntry, it) }
            } catch (_: Exception) {}
        }

        val docEntries = subfiles.map { rewritten[it.docId] ?: it }
        val (docSchemaBytes, newDocHash) = IndexParser.buildDocSchema(docEntries, docSchemaVer, docId)
        client.putBlob(newDocHash, docSchemaBytes, "$docId.docSchema", "text/plain; charset=UTF-8")
        blobStore.putBlob(newDocHash, docSchemaBytes)

        // The root entry's subfile count and total size must reflect the new doc index
        commitRoot(root, rootEntries.map {
            if (it.docId == docId) it.copy(hash = newDocHash, subfiles = docEntries.size, size = docEntries.sumOf { e -> e.size }) else it
        }, rootSchema)
        return newDocHash to rewritten.mapValues { it.value.hash }
    }

    private suspend fun readJson(entry: Entry): JsonObject =
        Json.parseToJsonElement(String(getOrFetchBlob(entry.hash, entry.docId), StandardCharsets.UTF_8)).jsonObject

    /** Sets .content cPages.lastOpened (firmware 3.x) to the page at [pageIndex]; null if there is no such page. */
    private fun withLastOpenedPage(content: JsonObject, pageIndex: Int): JsonObject? {
        val cPages = content["cPages"]?.jsonObject ?: return null
        val pages = cPages["pages"]?.jsonArray ?: return null
        val activePages = mutableListOf<Pair<String, String>>()
        for ((pos, item) in pages.withIndex()) {
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
        val pageId = activePages.getOrNull(pageIndex)?.second ?: return null
        val lastOpened = buildJsonObject {
            put("value", JsonPrimitive(pageId))
            put("timestamp", JsonPrimitive("1:${pageIndex + 1}"))
        }
        return JsonObject(content + ("cPages" to JsonObject(cPages + ("lastOpened" to lastOpened))))
    }

    /** Uploads a new root index and moves the cloud root to it. */
    private suspend fun commitRoot(root: RootInfo, entries: List<Entry>, schema: String) {
        val bytes = IndexParser.formatIndex(entries, schema).toByteArray(StandardCharsets.UTF_8)
        val hash = IndexParser.computeRootHash(bytes)
        client.putBlob(hash, bytes, "root.docSchema", "text/plain; charset=UTF-8")
        blobStore.putBlob(hash, bytes)
        val newGen = client.updateRoot(hash, root.generation, broadcast = true)
        // Only advance the local root if we were already in sync; otherwise the next
        // sync would report "up to date" and never pull changes made on other devices.
        if (repository.getLocalRootHash() == root.hash) repository.saveRoot(hash, newGen)
    }

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

        // The folder's index must use the root's schema (v4: named by sha256 of its own bytes)
        val root = client.getRoot()
        val (entries, schema) = client.getRootIndex(root.hash)
        val (docSchemaBytes, folderDocHash) = IndexParser.buildDocSchema(listOf(metaEntry), schema, folder.id)

        client.putBlob(metaHash, metaBytes, "${folder.id}.metadata")
        client.putBlob(folderDocHash, docSchemaBytes, "${folder.id}.docSchema", "text/plain; charset=UTF-8")
        commitRoot(root, entries.filterNot { it.docId == folder.id } + Entry(
            hash = folderDocHash,
            type = Entry.DOC_TYPE,
            docId = folder.id,
            subfiles = 1,
            size = metaBytes.size.toLong()
        ), schema)
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
