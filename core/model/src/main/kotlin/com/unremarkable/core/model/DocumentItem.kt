package com.unremarkable.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PageRef(
    val pageId: String,
    val redir: Int = -1
)

@Serializable
data class DocumentItem(
    val id: String,
    val visibleName: String,
    val parent: String = "",
    val isFolder: Boolean = false,
    val pageCount: Int = 0,
    val pinned: Boolean = false,
    val deleted: Boolean = false,
    val lastModified: Long = 0L,
    val tags: List<String> = emptyList(),
    val hash: String = "",
    val fileType: String = "",
    val subfiles: Map<String, String> = emptyMap(),
    val pages: List<PageRef> = emptyList(),
    val localOnly: Boolean = false
)
