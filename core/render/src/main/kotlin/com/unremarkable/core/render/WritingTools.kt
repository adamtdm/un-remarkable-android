package com.unremarkable.core.render

enum class ToolType(val value: Int, val toolName: String) {
    PAINTBRUSH(12, "Paintbrush"),
    PENCIL(14, "Pencil"),
    BALLPOINT(2, "Ballpoint"),
    MARKER(3, "Marker"),
    FINELINER(4, "Fineliner"),
    HIGHLIGHTER(5, "Highlighter"),
    ERASER(6, "Eraser"),
    MECHANICAL_PENCIL(7, "Mechanical Pencil"),
    CALLIGRAPHY(21, "Calligraphy"),
    SHADER(23, "Shader");

    companion object {
        fun fromValue(v: Int): ToolType = when (v) {
            0, 12 -> PAINTBRUSH
            1, 14 -> PENCIL
            2, 15 -> BALLPOINT
            3, 16 -> MARKER
            4, 17 -> FINELINER
            5, 18 -> HIGHLIGHTER
            6, 8 -> ERASER
            7, 13 -> MECHANICAL_PENCIL
            21 -> CALLIGRAPHY
            23 -> SHADER
            else -> BALLPOINT
        }
    }
}

data class StrokeStyle(
    val strokeWidth: Float,
    val opacity: Float,
    val strokeCap: String = "round",
    val blendMode: String = "normal"
)

object WritingTools {
    fun computeStyle(toolType: ToolType, baseThickness: Float, pressure: Float = 0.5f): StrokeStyle {
        val normP = (if (pressure > 1f) pressure / 255f else pressure).coerceIn(0f, 1f)
        val w = baseThickness.coerceAtLeast(0.5f)
        return when (toolType) {
            ToolType.FINELINER -> StrokeStyle(
                strokeWidth = (w * 1.8f).coerceAtLeast(0.5f),
                opacity = 1.0f,
                strokeCap = "round"
            )
            ToolType.BALLPOINT -> StrokeStyle(
                strokeWidth = (w * 1.5f * (0.6f + 0.8f * normP)).coerceAtLeast(0.5f),
                opacity = (0.7f + 0.3f * normP).coerceIn(0.6f, 1.0f),
                strokeCap = "round"
            )
            ToolType.MARKER -> StrokeStyle(
                strokeWidth = (w * 3.2f).coerceAtLeast(1.0f),
                opacity = 1.0f,
                strokeCap = "round"
            )
            ToolType.HIGHLIGHTER -> StrokeStyle(
                strokeWidth = 28.0f,
                opacity = 0.40f,
                strokeCap = "square",
                blendMode = "multiply"
            )
            ToolType.SHADER -> StrokeStyle(
                strokeWidth = (w * 6.0f).coerceAtLeast(8.0f),
                opacity = 0.35f,
                strokeCap = "round",
                blendMode = "multiply"
            )
            ToolType.PENCIL -> StrokeStyle(
                strokeWidth = (w * 1.6f * (0.7f + 0.6f * normP)).coerceAtLeast(0.6f),
                opacity = (0.4f + 0.5f * normP).coerceIn(0.25f, 0.95f),
                strokeCap = "round"
            )
            ToolType.MECHANICAL_PENCIL -> StrokeStyle(
                strokeWidth = (w * 1.2f).coerceAtLeast(0.5f),
                opacity = 0.85f,
                strokeCap = "round"
            )
            ToolType.CALLIGRAPHY -> StrokeStyle(
                strokeWidth = (w * 3.5f * (0.4f + 0.6f * normP)).coerceAtLeast(1.0f),
                opacity = 1.0f,
                strokeCap = "butt"
            )
            ToolType.ERASER -> StrokeStyle(
                strokeWidth = w * 4.0f,
                opacity = 0.0f
            )
            ToolType.PAINTBRUSH -> StrokeStyle(
                strokeWidth = (w * 4.0f * (0.3f + 1.4f * normP)).coerceAtLeast(1.0f),
                opacity = 0.9f,
                strokeCap = "round"
            )
        }
    }
}
