package com.unremarkable.core.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RmSvgRendererTest {

    @Test
    fun `renderToSvg generates light mode svg with correct dimensions and styles`() {
        val points = listOf(
            StrokePoint(100f, 200f),
            StrokePoint(150f, 250f),
            StrokePoint(200f, 300f)
        )
        val stroke = Stroke(
            tool = ToolType.FINELINER,
            color = PenColor.BLACK,
            thicknessScale = 2.0f,
            points = points
        )
        val layer = Layer("Layer 1", listOf(stroke))
        val page = Page(width = 1404, height = 1872, layers = listOf(layer))

        val svg = RmSvgRenderer.renderToSvg(page, darkMode = false)
        assertTrue(svg.contains("viewBox=\"0 0 1404 1872\""))
        assertTrue(svg.contains("fill=\"#ffffff\""))
        assertTrue(svg.contains("d=\"M 100.0 200.0 L 150.0 250.0 L 200.0 300.0\""))
        assertTrue(svg.contains("stroke=\"#18181b\"")) // black ink on light
    }

    @Test
    fun `renderToSvg generates dark mode svg with inverted canvas and light ink`() {
        val points = listOf(
            StrokePoint(50f, 50f),
            StrokePoint(100f, 100f)
        )
        val stroke = Stroke(
            tool = ToolType.FINELINER,
            color = PenColor.BLACK,
            thicknessScale = 2.0f,
            points = points
        )
        val layer = Layer("Layer 1", listOf(stroke))
        val page = Page(width = 1404, height = 1872, layers = listOf(layer))

        val svg = RmSvgRenderer.renderToSvg(page, darkMode = true)
        assertTrue(svg.contains("fill=\"#18181b\"")) // dark background
        assertTrue(svg.contains("stroke=\"#f4f4f5\"")) // white ink in dark mode
    }

    @Test
    fun `highlighter inverts blend mode for dark mode`() {
        val points = listOf(StrokePoint(10f, 10f), StrokePoint(20f, 20f))
        val stroke = Stroke(
            tool = ToolType.HIGHLIGHTER,
            color = PenColor.YELLOW,
            thicknessScale = 3.0f,
            points = points
        )
        val page = Page(layers = listOf(Layer("Highlights", listOf(stroke))))

        val lightSvg = RmSvgRenderer.renderToSvg(page, darkMode = false)
        assertTrue(lightSvg.contains("mix-blend-mode: multiply;"))

        val darkSvg = RmSvgRenderer.renderToSvg(page, darkMode = true)
        assertTrue(darkSvg.contains("mix-blend-mode: screen;"))
    }
}
