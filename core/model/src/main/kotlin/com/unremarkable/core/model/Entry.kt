package com.unremarkable.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Entry(
    val hash: String,
    val type: String,
    val docId: String,
    val subfiles: Int,
    val size: Long
) {
    fun toLine(): String = "$hash$DELIMITER$type$DELIMITER$docId$DELIMITER$subfiles$DELIMITER$size"

    val isDocument: Boolean
        get() = type == DOC_TYPE || subfiles > 0

    val isFile: Boolean
        get() = type == FILE_TYPE && subfiles == 0

    companion object {
        const val SCHEMA_V3 = "3"
        const val SCHEMA_V4 = "4"
        const val DOC_TYPE = "80000000"
        const val FILE_TYPE = "0"
        const val DELIMITER = ":"

        fun parseEntryLine(line: String): Entry {
            val parts = line.trim().split(DELIMITER)
            require(parts.size == 5) {
                "Invalid entry line (expected 5 fields, got ${parts.size}): '$line'"
            }
            return Entry(
                hash = parts[0],
                type = parts[1],
                docId = parts[2],
                subfiles = parts[3].toInt(),
                size = parts[4].toLong()
            )
        }
    }
}
