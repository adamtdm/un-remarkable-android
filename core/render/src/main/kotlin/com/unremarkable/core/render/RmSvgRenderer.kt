package com.unremarkable.core.render

import java.util.Locale

object RmSvgRenderer {

    fun renderToSvg(page: Page, darkMode: Boolean = false): String {
        val sb = StringBuilder()
        val bgFill = if (darkMode) "#18181b" else "#ffffff"
        val minX = if (page.centerOriginX) -(page.width / 2) else 0

        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="$minX 0 ${page.width} ${page.height}" width="100%" height="100%">""").append("\n")
        sb.append("""  <rect x="$minX" y="0" width="${page.width}" height="${page.height}" fill="$bgFill" />""").append("\n")

        for ((lIndex, layer) in page.layers.withIndex()) {
            sb.append("""  <g id="layer_${lIndex}_${layer.name.filter { it.isLetterOrDigit() }}">""").append("\n")

            for (stroke in layer.strokes) {
                if (stroke.points.isEmpty() || stroke.tool == ToolType.ERASER) continue

                val colorRgba = RmPalette.getColor(stroke.color.value, darkMode, stroke.explicitColor)
                val avgPressure = stroke.points.map { it.pressure }.average().toFloat()
                val style = WritingTools.computeStyle(stroke.tool, stroke.thicknessScale, avgPressure)

                val strokeColorHex = colorRgba.toHex()
                val opacityStr = String.format(Locale.US, "%.2f", style.opacity)
                val strokeWidthStr = String.format(Locale.US, "%.1f", style.strokeWidth)

                sb.append("    <path d=\"")
                val first = stroke.points.first()
                sb.append("M ").append(String.format(Locale.US, "%.1f", first.x))
                    .append(" ").append(String.format(Locale.US, "%.1f", first.y))

                for (i in 1 until stroke.points.size) {
                    val pt = stroke.points[i]
                    sb.append(" L ").append(String.format(Locale.US, "%.1f", pt.x))
                        .append(" ").append(String.format(Locale.US, "%.1f", pt.y))
                }

                sb.append("\" fill=\"none\"")
                    .append(" stroke=\"").append(strokeColorHex).append("\"")
                    .append(" stroke-width=\"").append(strokeWidthStr).append("\"")
                    .append(" stroke-linecap=\"").append(style.strokeCap).append("\"")
                    .append(" stroke-linejoin=\"round\"")

                if (style.opacity < 1.0f) {
                    sb.append(" opacity=\"").append(opacityStr).append("\"")
                }
                if (style.blendMode != "normal") {
                    val blend = if (darkMode && style.blendMode == "multiply") "screen" else style.blendMode
                    sb.append(" style=\"mix-blend-mode: ").append(blend).append(";\"")
                }

                sb.append(" />\n")
            }

            sb.append("  </g>\n")
        }

        sb.append("</svg>\n")
        return sb.toString()
    }
}
