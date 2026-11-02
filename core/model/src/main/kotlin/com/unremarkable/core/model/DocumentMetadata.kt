package com.unremarkable.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DocumentMetadata(
    @SerialName("visibleName") val visibleName: String = "",
    @SerialName("type") val type: String = TYPE_DOCUMENT,
    @SerialName("parent") val parent: String = "",
    @SerialName("lastModified") val lastModified: String = "",
    @SerialName("pinned") val pinned: Boolean = false,
    @SerialName("deleted") val deleted: Boolean = false,
    @SerialName("pageCount") val pageCount: Int = 0,
    @SerialName("fileType") val fileType: String = "",
    @SerialName("formatVersion") val formatVersion: Int = 1,
    @SerialName("tags") val tags: List<String> = emptyList()
) {
    val isFolder: Boolean
        get() = type == TYPE_COLLECTION

    companion object {
        const val TYPE_DOCUMENT = "DocumentType"
        const val TYPE_COLLECTION = "CollectionType"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun fromJson(jsonString: String): DocumentMetadata {
            return try {
                json.decodeFromString<DocumentMetadata>(jsonString)
            } catch (e: Exception) {
                DocumentMetadata(visibleName = "Untitled")
            }
        }
    }
}
