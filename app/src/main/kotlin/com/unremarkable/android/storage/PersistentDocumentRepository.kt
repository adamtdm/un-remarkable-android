package com.unremarkable.android.storage

import android.content.Context
import android.content.SharedPreferences
import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.model.SyncRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class PersistentDocumentRepository(
    private val docFile: File,
    private val prefs: SharedPreferences? = null
) : SyncRepository {

    constructor(context: Context) : this(
        docFile = File(context.filesDir, "library_documents.json"),
        prefs = context.getSharedPreferences("rm_sync_state", Context.MODE_PRIVATE)
    )

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val docsMap = mutableMapOf<String, DocumentItem>()

    @Volatile
    private var localRootHash: String = ""
    @Volatile
    private var localGeneration: Long = 0L

    init {
        synchronized(lock) {
            localRootHash = prefs?.getString("root_hash", "").orEmpty()
            localGeneration = prefs?.getLong("generation", 0L) ?: 0L
            loadFromDisk()
        }
    }

    private fun loadFromDisk() {
        if (!docFile.exists() || docFile.length() == 0L) return
        try {
            val text = docFile.readText(StandardCharsets.UTF_8)
            val list = json.decodeFromString<List<DocumentItem>>(text)
            docsMap.clear()
            for (doc in list) {
                docsMap[doc.id] = doc
            }
        } catch (e: Exception) {
            // Preserve in-memory state on parse failure
        }
    }

    private fun persistToDisk() {
        val parent = docFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmpFile = File(parent ?: File("."), "${docFile.name}.tmp")
        try {
            val list = synchronized(lock) { docsMap.values.toList() }
            val text = json.encodeToString(list)
            tmpFile.writeText(text, StandardCharsets.UTF_8)
            try {
                Files.move(
                    tmpFile.toPath(),
                    docFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: Exception) {
                // Fallback if filesystem does not support ATOMIC_MOVE
                Files.move(tmpFile.toPath(), docFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            // Ignore persistence errors
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    override fun getLocalRootHash(): String = synchronized(lock) {
        localRootHash
    }

    override fun getLocalGeneration(): Long = synchronized(lock) {
        localGeneration
    }

    override fun saveRoot(hash: String, generation: Long) {
        synchronized(lock) {
            localRootHash = hash
            localGeneration = generation
            prefs?.edit()
                ?.putString("root_hash", hash)
                ?.putLong("generation", generation)
                ?.apply()
            persistToDisk()
        }
    }

    override fun upsertDocument(doc: DocumentItem) {
        synchronized(lock) {
            docsMap[doc.id] = doc
            persistToDisk()
        }
    }

    override fun markDocumentDeleted(id: String) {
        synchronized(lock) {
            val existing = docsMap[id] ?: return
            docsMap[id] = existing.copy(deleted = true)
            persistToDisk()
        }
    }

    override fun getAllDocuments(): List<DocumentItem> = synchronized(lock) {
        docsMap.values.filter { !it.deleted }
            .sortedWith(
                compareByDescending<DocumentItem> { it.isFolder }
                    .thenByDescending { it.pinned }
                    .thenByDescending { it.lastModified }
            )
    }

    override fun getDocumentsInFolder(parentId: String): List<DocumentItem> = synchronized(lock) {
        docsMap.values.filter { !it.deleted && it.parent == parentId }
            .sortedWith(
                compareByDescending<DocumentItem> { it.isFolder }
                    .thenByDescending { it.pinned }
                    .thenByDescending { it.lastModified }
            )
    }

    override fun getDocument(id: String): DocumentItem? = synchronized(lock) {
        val doc = docsMap[id]
        if (doc?.deleted == false) doc else null
    }

    override fun searchDocuments(query: String): List<DocumentItem> = synchronized(lock) {
        if (query.isBlank()) return emptyList()
        val lower = query.lowercase().trim()
        docsMap.values.filter {
            !it.deleted && (
                it.visibleName.lowercase().contains(lower) ||
                it.tags.any { tag -> tag.lowercase().contains(lower) }
            )
        }.sortedByDescending { it.lastModified }
    }

    fun clearAll() {
        synchronized(lock) {
            docsMap.clear()
            localRootHash = ""
            localGeneration = 0L
            if (docFile.exists()) docFile.delete()
            prefs?.edit()?.clear()?.apply()
        }
    }
}
