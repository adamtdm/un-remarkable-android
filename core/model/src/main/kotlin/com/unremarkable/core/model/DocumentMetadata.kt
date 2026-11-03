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
    @SerialName("lastOpened") val lastOpened: String = "",
    @SerialName("lastOpenedPage") val lastOpenedPage: Int = 0,
    @SerialName("pinned") val pinned: Boolean = false,
    @SerialName("deleted") val deleted: Boolean = false,
    @SerialName("pageCount") val pageCount: Int = 0,
    @SerialName("fileType") val fileType: String = "",
    @SerialName("formatVersion") val formatVersion: Int = 1,
    @SerialName("tags") val tags: List<String> = emptyList(),
    val pages: List<PageRef> = emptyList()
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
            } catch (_: Exception) {
                DocumentMetadata(visibleName = "Untitled")
            }
        }

        fun parseFromRaw(metadataJson: String?, contentJson: String?): DocumentMetadata {
            var visibleName = ""
            var type = TYPE_DOCUMENT
            var parent = ""
            var lastModified = ""
            var lastOpened = ""
            var lastOpenedPage = 0
            var pinned = false
            var deleted = false

            if (!metadataJson.isNullOrBlank()) {
                try {
                    val element = json.parseToJsonElement(metadataJson).jsonObject
                    visibleName = element["visibleName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    type = element["type"]?.jsonPrimitive?.contentOrNull ?: TYPE_DOCUMENT
                    parent = element["parent"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    lastModified = element["lastModified"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    lastOpened = element["lastOpened"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    lastOpenedPage = element["lastOpenedPage"]?.jsonPrimitive?.intOrNull
                        ?: element["lastOpenedPage"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                        ?: 0
                    pinned = element["pinned"]?.jsonPrimitive?.booleanOrNull ?: false
                    deleted = element["deleted"]?.jsonPrimitive?.booleanOrNull ?: false
                } catch (_: Exception) {}
            }

            var fileType = if (type == TYPE_COLLECTION) "" else "notebook"
            var rawPageCount = 0
            val parsedPages = mutableListOf<PageRef>()
            val tags = mutableListOf<String>()

            if (!contentJson.isNullOrBlank()) {
                try {
                    val element = json.parseToJsonElement(contentJson).jsonObject
                    if (visibleName.isBlank()) {
                        visibleName = element["visibleName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                    if (parent.isBlank()) {
                        parent = element["parent"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                    if (lastModified.isBlank()) {
                        lastModified = element["lastModified"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                    if (lastOpened.isBlank()) {
                        lastOpened = element["lastOpened"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                    val contentLastOpenedPage = element["lastOpenedPage"]?.jsonPrimitive?.intOrNull
                        ?: element["lastOpenedPage"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    if (contentLastOpenedPage != null && lastOpenedPage == 0) {
                        lastOpenedPage = contentLastOpenedPage
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
                    rawPageCount = element["pageCount"]?.jsonPrimitive?.intOrNull ?: 0

                    // Parse firmware 3.x cPages (ordered by idx.value, skipping deleted pages)
                    val cPagesArray = element["cPages"]?.jsonObject?.get("pages")?.jsonArray
                    if (cPagesArray != null && cPagesArray.isNotEmpty()) {
                        val active = mutableListOf<Pair<String, PageRef>>()
                        for ((pos, item) in cPagesArray.withIndex()) {
                            val obj = item as? JsonObject ?: continue
                            val pageId = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                            val delEl = obj["deleted"]
                            val isDel = (delEl as? JsonObject)?.get("value")?.jsonPrimitive?.intOrNull == 1 ||
                                (delEl as? JsonPrimitive)?.intOrNull == 1 ||
                                (delEl as? JsonPrimitive)?.booleanOrNull == true
                            if (isDel) continue

                            val idxVal = (obj["idx"] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
                                ?: (obj["idx"] as? JsonPrimitive)?.contentOrNull
                                ?: String.format("%06d", pos)
                            val redirEl = obj["redir"]
                            val redir = (redirEl as? JsonObject)?.get("value")?.jsonPrimitive?.intOrNull
                                ?: (redirEl as? JsonPrimitive)?.intOrNull
                                ?: -1
                            active.add(idxVal to PageRef(pageId = pageId, redir = redir))
                        }
                        active.sortBy { it.first }
                        parsedPages.addAll(active.map { it.second })
                    } else {
                        val legacyPages = element["pages"]?.jsonArray
                        if (legacyPages != null) {
                            for ((idx, p) in legacyPages.withIndex()) {
                                val pid = (p as? JsonPrimitive)?.contentOrNull
                                if (!pid.isNullOrBlank()) {
                                    parsedPages.add(PageRef(pageId = pid, redir = idx))
                                }
                            }
                        }
                    }

                    val tagsArray = element["tags"]?.jsonArray
                    if (tagsArray != null) {
                        for (item in tagsArray) {
                            when (item) {
                                is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }?.let(tags::add)
                                is JsonObject -> item["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let(tags::add)
                                else -> {}
                            }
                        }
                    }

                    if (lastOpenedPage == 0) {
                        val lastOpenedPageId = element["cPages"]?.jsonObject?.get("lastOpened")?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
                        if (!lastOpenedPageId.isNullOrBlank()) {
                            val foundIdx = parsedPages.indexOfFirst { it.pageId == lastOpenedPageId }
                            if (foundIdx >= 0) {
                                lastOpenedPage = foundIdx
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            val finalPageCount = if (parsedPages.isNotEmpty()) parsedPages.size else rawPageCount

            return DocumentMetadata(
                visibleName = visibleName,
                type = type,
                parent = parent,
                lastModified = lastModified,
                lastOpened = lastOpened,
                lastOpenedPage = lastOpenedPage,
                pinned = pinned,
                deleted = deleted,
                pageCount = finalPageCount,
                fileType = fileType,
                tags = tags,
                pages = parsedPages
            )
        }
    }
}
