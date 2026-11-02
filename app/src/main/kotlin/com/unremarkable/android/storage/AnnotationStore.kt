package com.unremarkable.android.storage

import androidx.compose.ui.geometry.Offset
import com.unremarkable.android.ui.components.DrawnStroke
import com.unremarkable.core.render.PenColor
import com.unremarkable.core.render.ToolType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets

@Serializable
data class SerializedStroke(
    val points: List<Float>,
    val tool: String,
    val colorValue: Int,
    val strokeWidth: Float,
    val opacity: Float = 1.0f
)

class AnnotationStore(private val storageDir: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
    }

    fun loadStrokes(docId: String, page: Int): List<DrawnStroke> {
        val safeDocId = docId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val file = File(storageDir, "${safeDocId}_p$page.json")
        if (!file.exists() || file.length() == 0L) return emptyList()

        return try {
            val text = file.readText(StandardCharsets.UTF_8)
            val list = json.decodeFromString<List<SerializedStroke>>(text)
            list.map { s ->
                val pts = ArrayList<Offset>(s.points.size / 2)
                var i = 0
                while (i + 1 < s.points.size) {
                    pts.add(Offset(s.points[i], s.points[i + 1]))
                    i += 2
                }
                val tool = ToolType.entries.find { it.name == s.tool } ?: ToolType.FINELINER
                val color = PenColor.entries.find { it.value == s.colorValue } ?: PenColor.BLACK
                DrawnStroke(
                    points = pts,
                    tool = tool,
                    color = color,
                    strokeWidth = s.strokeWidth,
                    opacity = s.opacity
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveStrokes(docId: String, page: Int, strokes: List<DrawnStroke>) {
        val safeDocId = docId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val file = File(storageDir, "${safeDocId}_p$page.json")

        if (strokes.isEmpty()) {
            if (file.exists()) file.delete()
            return
        }

        try {
            val serialized = strokes.map { s ->
                val flatPoints = ArrayList<Float>(s.points.size * 2)
                for (pt in s.points) {
                    flatPoints.add(pt.x)
                    flatPoints.add(pt.y)
                }
                SerializedStroke(
                    points = flatPoints,
                    tool = s.tool.name,
                    colorValue = s.color.value,
                    strokeWidth = s.strokeWidth,
                    opacity = s.opacity
                )
            }
            val text = json.encodeToString(serialized)
            val tmp = File(storageDir, "${file.name}.tmp")
            tmp.writeText(text, StandardCharsets.UTF_8)
            if (!tmp.renameTo(file)) {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (_: Exception) {
            // Ignore write failures gracefully
        }
    }

    fun clearAll() {
        try {
            storageDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    fun hasAnnotations(docId: String, page: Int): Boolean {
        val safeDocId = docId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val file = File(storageDir, "${safeDocId}_p$page.json")
        return file.exists() && file.length() > 4L
    }

    fun getAnnotatedPages(docId: String): Set<Int> {
        val safeDocId = docId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val prefix = "${safeDocId}_p"
        val files = storageDir.listFiles { _, name -> name.startsWith(prefix) && name.endsWith(".json") } ?: return emptySet()
        return files.mapNotNull { f ->
            val pageStr = f.name.removePrefix(prefix).removeSuffix(".json")
            pageStr.toIntOrNull()
        }.toSet()
    }

    fun getBookmarks(docId: String): Set<Int> {
        val safeDocId = docId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val file = File(storageDir, "${safeDocId}_bookmarks.json")
        if (!file.exists() || file.length() == 0L) return emptySet()
        return try {
            val text = file.readText(StandardCharsets.UTF_8)
            json.decodeFromString<List<Int>>(text).toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun toggleBookmark(docId: String, page: Int): Boolean {
        val safeDocId = docId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val current = getBookmarks(docId).toMutableSet()
        val isNowBookmarked = if (current.contains(page)) {
            current.remove(page)
            false
        } else {
            current.add(page)
            true
        }
        val file = File(storageDir, "${safeDocId}_bookmarks.json")
        if (current.isEmpty()) {
            if (file.exists()) file.delete()
        } else {
            try {
                file.writeText(json.encodeToString(current.toList().sorted()), StandardCharsets.UTF_8)
            } catch (_: Exception) {}
        }
        return isNowBookmarked
    }

    fun isBookmarked(docId: String, page: Int): Boolean {
        return getBookmarks(docId).contains(page)
    }
}
