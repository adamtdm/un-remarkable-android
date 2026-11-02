package com.unremarkable.core.render

enum class PenColor(val value: Int) {
    BLACK(0),
    GRAY(1),
    WHITE(2),
    YELLOW(3),
    GREEN(4),
    PINK(5),
    BLUE(6),
    RED(7),
    GRAY_OVERLAP(8),
    HIGHLIGHT(9),
    GREEN_2(10),
    CYAN(11),
    MAGENTA(12),
    YELLOW_2(13);

    companion object {
        fun fromValue(v: Int): PenColor = entries.find { it.value == v } ?: BLACK
    }
}

data class Rgba(val r: Int, val g: Int, val b: Int, val a: Float = 1.0f) {
    fun toCssRgba(): String = "rgba($r, $g, $b, ${String.format(java.util.Locale.US, "%.3f", a)})"
    fun toHex(): String = String.format("#%02x%02x%02x", r, g, b)
    fun toArgbInt(overrideAlpha: Float = a): Int =
        (((overrideAlpha.coerceIn(0f, 1f)) * 255f).toInt() shl 24) or
            ((r and 0xFF) shl 16) or
            ((g and 0xFF) shl 8) or
            (b and 0xFF)
}

object RmPalette {
    val LIGHT_PALETTE = mapOf(
        PenColor.BLACK to Rgba(24, 24, 27),
        PenColor.GRAY to Rgba(140, 140, 145),
        PenColor.WHITE to Rgba(255, 255, 255),
        PenColor.YELLOW to Rgba(234, 179, 8),
        PenColor.GREEN to Rgba(34, 197, 94),
        PenColor.PINK to Rgba(244, 114, 182),
        PenColor.BLUE to Rgba(37, 99, 235),
        PenColor.RED to Rgba(220, 38, 38),
        PenColor.GRAY_OVERLAP to Rgba(115, 115, 120),
        PenColor.HIGHLIGHT to Rgba(250, 204, 21),
        PenColor.GREEN_2 to Rgba(132, 204, 22),
        PenColor.CYAN to Rgba(6, 182, 212),
        PenColor.MAGENTA to Rgba(168, 85, 247),
        PenColor.YELLOW_2 to Rgba(250, 204, 21)
    )

    val DARK_PALETTE = mapOf(
        PenColor.BLACK to Rgba(244, 244, 245),
        PenColor.GRAY to Rgba(161, 161, 170),
        PenColor.WHITE to Rgba(39, 39, 42),
        PenColor.YELLOW to Rgba(253, 224, 71),
        PenColor.GREEN to Rgba(74, 222, 128),
        PenColor.PINK to Rgba(244, 114, 182),
        PenColor.BLUE to Rgba(96, 165, 250),
        PenColor.RED to Rgba(248, 113, 113),
        PenColor.GRAY_OVERLAP to Rgba(161, 161, 170),
        PenColor.HIGHLIGHT to Rgba(253, 224, 71),
        PenColor.GREEN_2 to Rgba(163, 230, 53),
        PenColor.CYAN to Rgba(56, 189, 248),
        PenColor.MAGENTA to Rgba(192, 132, 252),
        PenColor.YELLOW_2 to Rgba(254, 240, 138)
    )

    fun getColor(colorValue: Int, darkMode: Boolean = false, explicitRgba: Rgba? = null): Rgba {
        if (explicitRgba != null) {
            return invertExplicitColor(explicitRgba, darkMode)
        }
        val pen = PenColor.fromValue(colorValue)
        val palette = if (darkMode) DARK_PALETTE else LIGHT_PALETTE
        return palette[pen] ?: palette.getValue(PenColor.BLACK)
    }

    fun invertExplicitColor(rgba: Rgba, darkMode: Boolean = false): Rgba {
        if (!darkMode) return rgba
        val luminance = 0.2126f * rgba.r + 0.7152f * rgba.g + 0.0722f * rgba.b
        return when {
            luminance < 50f -> rgba.copy(r = 244, g = 244, b = 245)
            luminance > 200f -> rgba.copy(r = 39, g = 39, b = 42)
            else -> rgba
        }
    }
}
