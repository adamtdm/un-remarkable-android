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
    GREEN_2(9),
    CYAN(10),
    MAGENTA(11),
    YELLOW_2(12);

    companion object {
        fun fromValue(v: Int): PenColor = entries.find { it.value == v } ?: BLACK
    }
}

data class Rgba(val r: Int, val g: Int, val b: Int, val a: Float = 1.0f) {
    fun toCssRgba(): String = "rgba($r, $g, $b, ${String.format(java.util.Locale.US, "%.3f", a)})"
    fun toHex(): String = String.format("#%02x%02x%02x", r, g, b)
    fun toArgbInt(): Int = ((a * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b
}

object RmPalette {
    val LIGHT_PALETTE = mapOf(
        PenColor.BLACK to Rgba(24, 24, 27),        // Near black #18181b
        PenColor.GRAY to Rgba(140, 140, 145),      // Medium gray #8c8c91
        PenColor.WHITE to Rgba(255, 255, 255),     // White
        PenColor.YELLOW to Rgba(234, 179, 8),      // Amber/Yellow #eab308
        PenColor.GREEN to Rgba(34, 197, 94),       // Emerald green #22c55e
        PenColor.PINK to Rgba(244, 114, 182),      // Pink #f472b6
        PenColor.BLUE to Rgba(37, 99, 235),        // Cobalt blue #2563eb
        PenColor.RED to Rgba(220, 38, 38),         // Crimson red #dc2626
        PenColor.GRAY_OVERLAP to Rgba(115, 115, 120),
        PenColor.GREEN_2 to Rgba(132, 204, 22),    // Lime green #84cc16
        PenColor.CYAN to Rgba(6, 182, 212),        // Cyan #06b6d4
        PenColor.MAGENTA to Rgba(168, 85, 247),    // Purple/Magenta #a855f7
        PenColor.YELLOW_2 to Rgba(250, 204, 21)    // Bright yellow #facc15
    )

    // Perceptually tuned palette for dark mode (#18181b canvas)
    // Preserves hue vibrancy while inverting lightness
    val DARK_PALETTE = mapOf(
        PenColor.BLACK to Rgba(244, 244, 245),     // Zinc 100 #f4f4f5 (near white ink)
        PenColor.GRAY to Rgba(161, 161, 170),      // Zinc 400 #a1a1aa
        PenColor.WHITE to Rgba(39, 39, 42),        // Zinc 800 (dark ink)
        PenColor.YELLOW to Rgba(253, 224, 71),     // Yellow 300 #fde047
        PenColor.GREEN to Rgba(74, 222, 128),      // Green 400 #4ade80
        PenColor.PINK to Rgba(244, 114, 182),      // Pink 400 #f472b6
        PenColor.BLUE to Rgba(96, 165, 250),       // Blue 400 #60a5fa
        PenColor.RED to Rgba(248, 113, 113),       // Red 400 #f87171
        PenColor.GRAY_OVERLAP to Rgba(161, 161, 170),
        PenColor.GREEN_2 to Rgba(163, 230, 53),    // Lime 400 #a3e635
        PenColor.CYAN to Rgba(56, 189, 248),       // Sky 400 #38bdf8
        PenColor.MAGENTA to Rgba(192, 132, 252),   // Purple 400 #c084fc
        PenColor.YELLOW_2 to Rgba(254, 240, 138)   // Yellow 200 #fef08a
    )

    fun getColor(colorValue: Int, darkMode: Boolean = false): Rgba {
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
