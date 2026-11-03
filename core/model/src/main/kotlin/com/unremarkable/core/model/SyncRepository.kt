package com.unremarkable.core.model

interface SyncRepository {
    fun getLocalRootHash(): String
    fun getLocalGeneration(): Long
    fun saveRoot(hash: String, generation: Long)
    fun upsertDocument(doc: DocumentItem)
    fun upsertDocuments(docs: List<DocumentItem>) {
        docs.forEach { upsertDocument(it) }
    }
    fun markDocumentDeleted(id: String)
    fun getAllDocuments(): List<DocumentItem>
    fun getDocumentsInFolder(parentId: String): List<DocumentItem>
    fun getDocument(id: String): DocumentItem?
    fun searchDocuments(query: String): List<DocumentItem>
    fun updateLastOpenedPage(id: String, pageIndex: Int) {
        val existing = getDocument(id) ?: return
        upsertDocument(existing.copy(lastOpenedPage = pageIndex))
    }
    fun getPendingPageSyncDocIds(): Set<String> = emptySet()
    fun markPageSyncPending(id: String) {}
    fun clearPageSyncPending(id: String) {}
}

class InMemorySyncRepository : SyncRepository {
    private var rootHash: String = ""
    private var generation: Long = 0L
    private val docs = mutableMapOf<String, DocumentItem>()
    private val pendingPageSyncIds = mutableSetOf<String>()

    override fun getLocalRootHash(): String = rootHash

    override fun getLocalGeneration(): Long = generation

    override fun saveRoot(hash: String, generation: Long) {
        this.rootHash = hash
        this.generation = generation
    }

    override fun upsertDocument(doc: DocumentItem) {
        docs[doc.id] = doc
    }

    override fun updateLastOpenedPage(id: String, pageIndex: Int) {
        docs[id]?.let {
            docs[id] = it.copy(lastOpenedPage = pageIndex)
            if (!it.localOnly) {
                pendingPageSyncIds.add(id)
            }
        }
    }

    override fun getPendingPageSyncDocIds(): Set<String> = pendingPageSyncIds.toSet()

    override fun markPageSyncPending(id: String) {
        pendingPageSyncIds.add(id)
    }

    override fun clearPageSyncPending(id: String) {
        pendingPageSyncIds.remove(id)
    }

    override fun markDocumentDeleted(id: String) {
        docs[id]?.let {
            docs[id] = it.copy(deleted = true)
        }
    }

    override fun getAllDocuments(): List<DocumentItem> {
        return docs.values.filter { !it.deleted }.sortedByDescending { it.lastModified }
    }

    override fun getDocumentsInFolder(parentId: String): List<DocumentItem> {
        return docs.values.filter { !it.deleted && it.parent == parentId }
    }

    override fun getDocument(id: String): DocumentItem? {
        val doc = docs[id]
        return if (doc?.deleted == false) doc else null
    }

    override fun searchDocuments(query: String): List<DocumentItem> {
        if (query.isBlank()) return emptyList()
        val lower = query.lowercase()
        return docs.values.filter {
            !it.deleted && (it.visibleName.lowercase().contains(lower) || it.tags.any { tag -> tag.lowercase().contains(lower) })
        }
    }
}
