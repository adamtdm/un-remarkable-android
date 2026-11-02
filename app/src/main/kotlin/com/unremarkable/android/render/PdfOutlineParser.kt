package com.unremarkable.android.render

import java.io.File
import java.nio.charset.StandardCharsets

data class TocEntry(
    val title: String,
    val pageNumber: Int,
    val level: Int = 0
)

object PdfOutlineParser {

    /**
     * Extracts outline/bookmarks from PDF file bytes.
     * Falls back to section milestones if PDF outline is not present or unencrypted.
     */
    fun extractToc(pdfFile: File, totalPages: Int): List<TocEntry> {
        val extracted = tryParsePdfOutlines(pdfFile, totalPages)
        if (extracted.isNotEmpty()) {
            return extracted
        }

        // Fallback: Generate clean structural page landmarks
        val landmarks = mutableListOf<TocEntry>()
        landmarks.add(TocEntry("Beginning", 1, 0))
        if (totalPages > 10) {
            var p = 10
            while (p < totalPages) {
                landmarks.add(TocEntry("Page $p", p, 1))
                p += 10
            }
        }
        if (totalPages > 1 && landmarks.none { it.pageNumber == totalPages }) {
            landmarks.add(TocEntry("End (Page $totalPages)", totalPages, 0))
        }
        return landmarks
    }

    private fun tryParsePdfOutlines(pdfFile: File, totalPages: Int): List<TocEntry> {
        if (!pdfFile.exists() || pdfFile.length() < 100) return emptyList()

        return try {
            // Read first 2MB or entire file if smaller
            val bytesToRead = minOf(pdfFile.length(), 2 * 1024 * 1024).toInt()
            val bytes = ByteArray(bytesToRead)
            pdfFile.inputStream().use { it.read(bytes) }
            val content = String(bytes, StandardCharsets.ISO_8859_1)

            val results = mutableListOf<TocEntry>()
            // Match pattern: /Title (Chapter Title) ... /Dest [ N ... ] or /Count
            val titleRegex = Regex("""/Title\s*\(([^)]+)\)""")
            val destRegex = Regex("""/Dest\s*\[\s*(\d+)\s+""")

            val titleMatches = titleRegex.findAll(content).toList()
            for (match in titleMatches) {
                val rawTitle = match.groupValues[1].trim()
                if (rawTitle.isNotBlank() && rawTitle.length < 80) {
                    // Look nearby for destination page number
                    val searchWindowStart = match.range.last
                    val searchWindowEnd = minOf(content.length, searchWindowStart + 300)
                    val window = content.substring(searchWindowStart, searchWindowEnd)
                    val destMatch = destRegex.find(window)
                    val pageNum = destMatch?.groupValues?.get(1)?.toIntOrNull()?.let { it + 1 }?.coerceIn(1, totalPages) ?: 1

                    if (results.none { it.title == rawTitle && it.pageNumber == pageNum }) {
                        results.add(TocEntry(title = rawTitle, pageNumber = pageNum, level = 0))
                    }
                }
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }
}
