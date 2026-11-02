package com.unremarkable.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class DeviceProfile(
    val id: String,
    val displayName: String,
    val width: Int,
    val height: Int,
    val ppi: Int,
    val isColor: Boolean
) {
    PAPER_PRO(
        id = "rmpro",
        displayName = "reMarkable Paper Pro",
        width = 2160,
        height = 1620,
        ppi = 229,
        isColor = true
    ),
    PAPER_PRO_MOVE(
        id = "rmpro_move",
        displayName = "reMarkable Paper Pro (Move / Landscape)",
        width = 1620,
        height = 2160,
        ppi = 229,
        isColor = true
    ),
    RM2(
        id = "rm2",
        displayName = "reMarkable 2",
        width = 1872,
        height = 1404,
        ppi = 226,
        isColor = false
    ),
    RM1(
        id = "rm1",
        displayName = "reMarkable 1",
        width = 1872,
        height = 1404,
        ppi = 226,
        isColor = false
    );

    companion object {
        fun fromId(id: String): DeviceProfile =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: PAPER_PRO
    }
}
