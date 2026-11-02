package com.unremarkable.android.storage

import androidx.compose.ui.geometry.Offset
import com.unremarkable.android.ui.components.DrawnStroke
import com.unremarkable.core.render.PenColor
import com.unremarkable.core.render.ToolType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AnnotationStoreTest {

    @Test
    fun `test strokes persist to disk and reload accurately`(@TempDir tempDir: File) {
        val store = AnnotationStore(tempDir)
        val docId = "test-doc-123"
        val page = 1

        val initialStrokes = listOf(
            DrawnStroke(
                points = listOf(Offset(10f, 20f), Offset(15f, 25f), Offset(20f, 30f)),
                tool = ToolType.FINELINER,
                color = PenColor.BLACK,
                strokeWidth = 2.0f,
                opacity = 1.0f
            ),
            DrawnStroke(
                points = listOf(Offset(50f, 100f), Offset(60f, 110f)),
                tool = ToolType.HIGHLIGHTER,
                color = PenColor.YELLOW,
                strokeWidth = 12.0f,
                opacity = 0.4f
            )
        )

        store.saveStrokes(docId, page, initialStrokes)

        // Verify across independent instance
        val store2 = AnnotationStore(tempDir)
        val loaded = store2.loadStrokes(docId, page)

        assertEquals(2, loaded.size)
        assertEquals(3, loaded[0].points.size)
        assertEquals(10f, loaded[0].points[0].x)
        assertEquals(20f, loaded[0].points[0].y)
        assertEquals(ToolType.FINELINER, loaded[0].tool)
        assertEquals(PenColor.BLACK, loaded[0].color)

        assertEquals(2, loaded[1].points.size)
        assertEquals(ToolType.HIGHLIGHTER, loaded[1].tool)
        assertEquals(PenColor.YELLOW, loaded[1].color)
        assertEquals(0.4f, loaded[1].opacity)
    }

    @Test
    fun `test clearAll purges persisted stroke files`(@TempDir tempDir: File) {
        val store = AnnotationStore(tempDir)
        store.saveStrokes("doc-1", 1, listOf(
            DrawnStroke(listOf(Offset(1f, 1f)), ToolType.BALLPOINT, PenColor.BLUE, 2f)
        ))
        assertEquals(1, store.loadStrokes("doc-1", 1).size)

        store.clearAll()
        assertEquals(0, store.loadStrokes("doc-1", 1).size)
    }
}
