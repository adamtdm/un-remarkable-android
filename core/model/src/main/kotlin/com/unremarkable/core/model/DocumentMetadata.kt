package com.unremarkable.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

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

        fun parseFromRaw(metadataJson: String?, contentJson: String?): DocumentMetadata {
            var visibleName = ""
            var type = TYPE_DOCUMENT
            var parent = ""
            var lastModified = ""
            var pinned = false
            var deleted = false

            if (!metadataJson.isNullOrBlank()) {
                try {
                    val element = json.parseToJsonElement(metadataJson).jsonObject
                    visibleName = element["visibleName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    type = element["type"]?.jsonPrimitive?.contentOrNull ?: TYPE_DOCUMENT
                    parent = element["parent"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    lastModified = element["lastModified"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    pinned = element["pinned"]?.jsonPrimitive?.booleanOrNull ?: false
                    deleted = element["deleted"]?.jsonPrimitive?.booleanOrNull ?: false
                } catch (_: Exception) {}
            }

            var fileType = if (type == TYPE_COLLECTION) "" else "notebook"
            var pageCount = 0
            val tags = mutableListOf<String>()

            if (!contentJson.isNullOrBlank()) {
                try {
                    val element = json.parseToJsonElement(contentJson).jsonObject
                    if (visibleName.isBlank()) {
                        val cvn = element["visibleName"]?.jsonPrimitive?.contentOrNull
                        if (!cvn.isNullOrBlank()) visibleName = cvn
                    }
                    if (parent.isBlank()) {
                        val cp = element["parent"]?.jsonPrimitive?.contentOrNull
                        if (!cp.isNullOrBlank()) parent = cp
                    }
                    if (lastModified.isBlank()) {
                        val clm = element["lastModified"]?.jsonPrimitive?.contentOrNull
                        if (!clm.isNullOrBlank()) lastModified = clm
                    }
                    if (!pinned) {
                        pinned = element["pinned"]?.jsonPrimitive?.booleanOrNull ?: false
                    }
                    if (!deleted) {
                        deleted = element["deleted"]?.jsonPrimitive?.booleanOrNull ?: false
                    }
                    if (type == TYPE_DOCUMENT) {
                        val ct = element["type"]?.jsonPrimitive?.contentOrNull
                        if (!ct.isNullOrBlank()) type = ct
                    }
                    val rawFileType = element["fileType"]?.jsonPrimitive?.contentOrNull
                    if (!rawFileType.isNullOrBlank()) {
                        fileType = rawFileType
                    }
                    val rawPageCount = element["pageCount"]?.jsonPrimitive?.intOrNull ?: 0
                    if (rawPageCount > 0) {
                        pageCount = rawPageCount
                    } else {
                        val cPagesObj = element["cPages"]?.jsonObject
                        val cPagesArray = cPagesObj?.get("pages")?.jsonArray
                        if (cPagesArray != null && cPagesArray.isNotEmpty()) {
                            pageCount = cPagesArray.count { pageItem ->
                                val delVal = pageItem.jsonObject["deleted"]
                                val isDel = delVal?.jsonObject?.get("value")?.jsonPrimitive?.intOrNull == 1 ||
                                            delVal?.jsonPrimitive?.intOrNull == 1 ||
                                            delVal?.jsonPrimitive?.booleanOrNull == true
                                !isDel
                            }
                        } else {
                            val pagesArray = element["pages"]?.jsonArray
                            if (pagesArray != null) {
                                pageCount = pagesArray.size
                            }
                        }
                    }
                    val tagsArray = element["tags"]?.jsonArray
                    if (tagsArray != null) {
                        for (item in tagsArray) {
                            if (item is JsonPrimitive) {
                                val name = item.contentOrNull
                                if (!name.isNullOrBlank()) tags.add(name)
                            } else if (item is JsonObject) {
                                val name = item["name"]?.jsonPrimitive?.contentOrNull
                                if (!name.isNullOrBlank()) tags.add(name)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            return DocumentMetadata(
                visibleName = visibleName,
                type = type,
                parent = parent,
                lastModified = lastModified,
                pinned = pinned,
                deleted = deleted,
                pageCount = pageCount,
                fileType = fileType,
                tags = tags
            )
        }
    }
}
