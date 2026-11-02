package com.unremarkable.android.storage

import android.content.Context
import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.model.SyncRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets

class PersistentDocumentRepository(context: Context) : SyncRepository {
    private val prefs = context.getSharedPreferences("rm_sync_state", Context.MODE_PRIVATE)
    private val docFile = File(context.filesDir, "library_documents.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val docsMap = mutableMapOf<String, DocumentItem>()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        if (!docFile.exists()) return
        try {
            val text = docFile.readText(StandardCharsets.UTF_8)
            val list = json.decodeFromString<List<DocumentItem>>(text)
            docsMap.clear()
            for (doc in list) {
                docsMap[doc.id] = doc
            }
        } catch (e: Exception) {
            // If parsing fails, preserve current state
        }
    }

    private fun persistToDisk() {
        try {
            val list = docsMap.values.toList()
            val text = json.encodeToString(list)
            docFile.writeText(text, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            // Ignore persistence errors
        }
    }

    override fun getLocalRootHash(): String {
        return prefs.getString("root_hash", "").orEmpty()
    }

    override fun getLocalGeneration(): Long {
        return prefs.getLong("generation", 0L)
    }

    override fun saveRoot(hash: String, generation: Long) {
        prefs.edit()
            .putString("root_hash", hash)
            .putLong("generation", generation)
            .apply()
    }

    override fun upsertDocument(doc: DocumentItem) {
        docsMap[doc.id] = doc
        persistToDisk()
    }

    override fun markDocumentDeleted(id: String) {
        val existing = docsMap[id] ?: return
        docsMap[id] = existing.copy(deleted = true)
        persistToDisk()
    }

    override fun getAllDocuments(): List<DocumentItem> {
        return docsMap.values.filter { !it.deleted }
            .sortedWith(
                compareByDescending<DocumentItem> { it.isFolder }
                    .thenByDescending { it.pinned }
                    .thenByDescending { it.lastModified }
            )
    }

    override fun getDocumentsInFolder(parentId: String): List<DocumentItem> {
        return docsMap.values.filter { !it.deleted && it.parent == parentId }
            .sortedWith(
                compareByDescending<DocumentItem> { it.isFolder }
                    .thenByDescending { it.pinned }
                    .thenByDescending { it.lastModified }
            )
    }

    override fun getDocument(id: String): DocumentItem? {
        val doc = docsMap[id]
        return if (doc?.deleted == false) doc else null
    }

    override fun searchDocuments(query: String): List<DocumentItem> {
        if (query.isBlank()) return emptyList()
        val lower = query.lowercase().trim()
        return docsMap.values.filter {
            !it.deleted && (
                    it.visibleName.lowercase().contains(lower) ||
                            it.tags.any { tag -> tag.lowercase().contains(lower) }
                    )
        }.sortedByDescending { it.lastModified }
    }

    fun clearAll() {
        docsMap.clear()
        if (docFile.exists()) docFile.delete()
        prefs.edit().clear().apply()
    }
}
