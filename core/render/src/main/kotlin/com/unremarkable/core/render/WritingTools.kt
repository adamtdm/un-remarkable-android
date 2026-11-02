package com.unremarkable.core.render

enum class ToolType(val value: Int, val toolName: String) {
    BALLPOINT(2, "Ballpoint"),
    MARKER(3, "Marker"),
    FINELINER(4, "Fineliner"),
    HIGHLIGHTER(5, "Highlighter"),
    ERASER(6, "Eraser"),
    PENCIL(7, "Pencil"),
    CALLIGRAPHY(8, "Calligraphy"),
    PAINTBRUSH(12, "Paintbrush");

    companion object {
        fun fromValue(v: Int): ToolType = entries.find { it.value == v } ?: FINELINER
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
        return when (toolType) {
            ToolType.FINELINER -> StrokeStyle(
                strokeWidth = (baseThickness * 1.8f).coerceAtLeast(0.5f),
                opacity = 1.0f,
                strokeCap = "round"
            )
            ToolType.BALLPOINT -> StrokeStyle(
                strokeWidth = (baseThickness * (0.8f + pressure * 0.8f)).coerceAtLeast(0.5f),
                opacity = 0.95f,
                strokeCap = "round"
            )
            ToolType.MARKER -> StrokeStyle(
                strokeWidth = (baseThickness * 2.2f).coerceAtLeast(1.0f),
                opacity = 1.0f,
                strokeCap = "round"
            )
            ToolType.HIGHLIGHTER -> StrokeStyle(
                strokeWidth = (baseThickness * 6.0f).coerceAtLeast(12.0f),
                opacity = 0.35f,
                strokeCap = "square",
                blendMode = "multiply"
            )
            ToolType.PENCIL -> StrokeStyle(
                strokeWidth = (baseThickness * (0.6f + pressure * 0.6f)).coerceAtLeast(0.5f),
                opacity = (0.3f + pressure * 0.5f).coerceIn(0.2f, 0.9f),
                strokeCap = "round"
            )
            ToolType.CALLIGRAPHY -> StrokeStyle(
                strokeWidth = (baseThickness * (0.5f + pressure * 2.0f)).coerceAtLeast(0.5f),
                opacity = 1.0f,
                strokeCap = "round"
            )
            ToolType.ERASER -> StrokeStyle(
                strokeWidth = baseThickness * 4.0f,
                opacity = 0.0f
            )
            ToolType.PAINTBRUSH -> StrokeStyle(
                strokeWidth = (baseThickness * (1.0f + pressure * 1.5f)).coerceAtLeast(1.0f),
                opacity = 0.85f,
                strokeCap = "round"
            )
        }
    }
}
