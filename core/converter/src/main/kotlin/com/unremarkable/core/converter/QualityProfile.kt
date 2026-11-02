package com.unremarkable.core.converter

import com.unremarkable.core.model.DeviceProfile

data class QualityProfile(
    val stop: Int,
    val name: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val jpegQuality: Float,
    val description: String
)

object QualityProfiles {
    val STOPS = mapOf(
        1 to QualityProfile(
            stop = 1,
            name = "Smallest",
            maxWidth = 1200,
            maxHeight = 1600,
            jpegQuality = 0.65f,
            description = "Fastest page turns, ultra-low memory (~75% reduction)"
        ),
        2 to QualityProfile(
            stop = 2,
            name = "Compact",
            maxWidth = 1440,
            maxHeight = 1920,
            jpegQuality = 0.72f,
            description = "Snappy paging with great legibility (~65% reduction)"
        ),
        3 to QualityProfile(
            stop = 3,
            name = "Balanced",
            maxWidth = 1620,
            maxHeight = 2160,
            jpegQuality = 0.82f,
            description = "1:1 native reMarkable pixel match (device screen target) - Recommended"
        ),
        4 to QualityProfile(
            stop = 4,
            name = "Sharp",
            maxWidth = 1920,
            maxHeight = 2560,
            jpegQuality = 0.88f,
            description = "Crisp fine text and detailed line art (~40% reduction)"
        ),
        5 to QualityProfile(
            stop = 5,
            name = "Best",
            maxWidth = 2250,
            maxHeight = 3000,
            jpegQuality = 0.92f,
            description = "Maximum preservation of master scans (~10% reduction)"
        )
    )

    fun getProfile(stop: Int): QualityProfile =
        STOPS[stop.coerceIn(1, 5)] ?: STOPS.getValue(3)
}

data class ConverterConfig(
    val targetProfile: DeviceProfile = DeviceProfile.PAPER_PRO,
    val qualityStop: Int = 3,
    val splitSpreads: Boolean = true,
    val mangaOrder: Boolean = false
)
