package com.unremarkable.core.render

data class StrokePoint(
    val x: Float,
    val y: Float,
    val speed: Float = 0.0f,
    val direction: Float = 0.0f,
    val width: Float = 1.0f,
    val pressure: Float = 0.5f
)

data class Stroke(
    val tool: ToolType,
    val color: PenColor,
    val thicknessScale: Float,
    val points: List<StrokePoint>,
    val explicitColor: Rgba? = null
)

data class Layer(
    val name: String,
    val strokes: List<Stroke>
)

data class HighlightRect(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val color: PenColor = PenColor.HIGHLIGHT,
    val explicitColor: Rgba? = null
)

enum class ParagraphStyle(val code: Int, val lineHeight: Float) {
    BASIC(0, 70f),
    PLAIN(1, 70f),
    HEADING(2, 140f),
    BOLD(3, 70f),
    BULLET(4, 45f),
    BULLET2(5, 45f),
    CHECKBOX(6, 45f),
    CHECKBOX_CHECKED(7, 45f);

    companion object {
        fun fromCode(code: Int): ParagraphStyle = entries.find { it.code == code } ?: PLAIN
    }
}

data class TypedParagraph(
    val text: String,
    val style: ParagraphStyle = ParagraphStyle.PLAIN
)

data class Page(
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val layers: List<Layer> = emptyList(),
    val centerOriginX: Boolean = false,
    val highlights: List<HighlightRect> = emptyList(),
    val typedParagraphs: List<TypedParagraph> = emptyList(),
    val textPosX: Float = -468f,
    val textPosY: Float = 234f,
    val textWidth: Float = 936f
) {
    val hasContent: Boolean
        get() = layers.any { it.strokes.isNotEmpty() } ||
            highlights.isNotEmpty() ||
            typedParagraphs.any { it.text.isNotBlank() }

    companion object {
        const val DEFAULT_WIDTH = 1404
        const val DEFAULT_HEIGHT = 1872
        const val PAPER_PRO_WIDTH = 1620
        const val PAPER_PRO_HEIGHT = 2160
    }
}
