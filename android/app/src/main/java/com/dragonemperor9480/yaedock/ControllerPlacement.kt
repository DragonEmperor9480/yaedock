package com.dragonemperor9480.yaedock

/** A part of the phone's game controller that can be moved and resized on its own. */
enum class ControllerPiece(val label: String) {
    LEFT_STICK("Left stick"),
    RIGHT_STICK("Right stick"),
    DPAD("D-pad"),
    FACE("A B X Y"),
    LT("LT"),
    LB("LB"),
    RB("RB"),
    RT("RT"),
    VIEW("View"),
    MENU("Menu"),
    GUIDE("Guide"),
}

/**
 * Where a [ControllerPiece] sits: its middle, as fractions of the screen's
 * width and height (0 to 1), so a layout fits any phone, and how big it is
 * next to its usual size.
 */
data class Placement(val x: Float, val y: Float, val scale: Float = 1f) {
    /** The same, kept on the screen and within the sizes allowed. */
    fun tidy(): Placement = Placement(
        x.coerceIn(0f, 1f),
        y.coerceIn(0f, 1f),
        scale.coerceIn(MIN_SCALE, MAX_SCALE),
    )

    companion object {
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 1.8f
        const val SCALE_STEP = 0.1f
    }
}

/** Where each piece sits in [layout], or in the user's own arrangement. */
fun ControllerSettings.placements(): Map<ControllerPiece, Placement> = when (layout) {
    ControllerLayout.CUSTOM -> ControllerPlacements.XBOX + custom
    ControllerLayout.XBOX -> ControllerPlacements.XBOX
    ControllerLayout.SYMMETRIC -> ControllerPlacements.SYMMETRIC
}

object ControllerPlacements {
    /** The pieces where the two fixed layouts had them, on a usual phone held sideways. */
    val XBOX: Map<ControllerPiece, Placement> = mapOf(
        ControllerPiece.LT to Placement(0.047f, 0.06f),
        ControllerPiece.LB to Placement(0.145f, 0.06f),
        ControllerPiece.RB to Placement(0.855f, 0.06f),
        ControllerPiece.RT to Placement(0.953f, 0.06f),
        ControllerPiece.LEFT_STICK to Placement(0.12f, 0.35f),
        ControllerPiece.DPAD to Placement(0.29f, 0.76f),
        ControllerPiece.FACE to Placement(0.854f, 0.37f),
        ControllerPiece.RIGHT_STICK to Placement(0.736f, 0.80f),
        ControllerPiece.VIEW to Placement(0.467f, 0.40f),
        ControllerPiece.MENU to Placement(0.533f, 0.40f),
        ControllerPiece.GUIDE to Placement(0.5f, 0.56f),
    )

    val SYMMETRIC: Map<ControllerPiece, Placement> = XBOX + mapOf(
        ControllerPiece.DPAD to Placement(0.122f, 0.357f),
        ControllerPiece.LEFT_STICK to Placement(0.291f, 0.767f),
    )

    /** [placements] as text to store: `PIECE:x,y,scale` joined by `;`. */
    fun encode(placements: Map<ControllerPiece, Placement>): String =
        placements.entries.joinToString(";") { (piece, at) -> "${piece.name}:${at.x},${at.y},${at.scale}" }

    /** What [encode] wrote; parts it can't read are left out, so their default is used. */
    fun decode(text: String?): Map<ControllerPiece, Placement> {
        if (text.isNullOrBlank()) return emptyMap()
        return text.split(';').mapNotNull { entry ->
            val (name, numbers) = entry.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val piece = ControllerPiece.entries.find { it.name == name } ?: return@mapNotNull null
            val values = numbers.split(',').map { it.toFloatOrNull() ?: return@mapNotNull null }
            if (values.size != 3 || values.any { it.isNaN() }) return@mapNotNull null
            piece to Placement(values[0], values[1], values[2]).tidy()
        }.toMap()
    }
}
