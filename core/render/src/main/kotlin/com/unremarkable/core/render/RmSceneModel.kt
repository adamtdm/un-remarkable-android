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
    val points: List<StrokePoint>
)

data class Layer(
    val name: String,
    val strokes: List<Stroke>
)

data class Page(
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val layers: List<Layer> = emptyList()
) {
    companion object {
        const val DEFAULT_WIDTH = 1404
        const val DEFAULT_HEIGHT = 1872
        const val PAPER_PRO_WIDTH = 1620
        const val PAPER_PRO_HEIGHT = 2160
    }
}
