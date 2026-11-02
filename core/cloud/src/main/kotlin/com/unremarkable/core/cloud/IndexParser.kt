package com.unremarkable.core.cloud

import com.unremarkable.core.model.Entry
import java.nio.charset.StandardCharsets

object IndexParser {

    fun parseIndex(rawBytes: ByteArray): Pair<List<Entry>, String> {
        val text = String(rawBytes, StandardCharsets.UTF_8)
        return parseIndex(text)
    }

    fun parseIndex(content: String): Pair<List<Entry>, String> {
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (lines.isEmpty()) {
            throw IllegalArgumentException("Empty index content")
        }

        val schema = lines[0]
        val entries = mutableListOf<Entry>()

        when (schema) {
            Entry.SCHEMA_V4 -> {
                if (lines.size < 2) {
                    throw IllegalArgumentException("Schema v4 index missing summary header line")
                }
                // Line 0 is "4", Line 1 is summary: "0:.:<count>:<size>", entries start at line 2
                for (i in 2 until lines.size) {
                    entries.add(Entry.parseEntryLine(lines[i]))
                }
            }
            Entry.SCHEMA_V3 -> {
                // Line 0 is "3", entries start at line 1
                for (i in 1 until lines.size) {
                    entries.add(Entry.parseEntryLine(lines[i]))
                }
            }
            else -> throw IllegalArgumentException("Unsupported index schema version: $schema")
        }

        return Pair(entries, schema)
    }

    fun formatIndex(entries: List<Entry>, schema: String = Entry.SCHEMA_V4): String {
        val sortedEntries = entries.sortedBy { it.docId }
        val sb = StringBuilder()
        sb.append(schema).append("\n")

        if (schema == Entry.SCHEMA_V4) {
            val totalSize = sortedEntries.sumOf { it.size }
            sb.append("0").append(Entry.DELIMITER)
                .append(".").append(Entry.DELIMITER)
                .append(sortedEntries.size).append(Entry.DELIMITER)
                .append(totalSize).append("\n")
        }

        for (entry in sortedEntries) {
            sb.append(entry.toLine()).append("\n")
        }

        return sb.toString()
    }
}
