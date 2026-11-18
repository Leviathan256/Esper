package com.esper.app.ui.theme

/**
 * Every number of the design (docs/VISUALS.md §4), in one place. Where this
 * object holds a number it is the source and the document comes second.
 */
object Spec {
    // §4.3 Camera. Rules are in dp per metre, not zoom levels: with tiles
    // scaled to the density one tile pixel is one dp, and pixels per metre at
    // a zoom level change with latitude.

    /** How an Encounter opens: a 1 m cell is about 5.4 mm. */
    const val FIGHT_DP_PER_METRE = 34.0

    /** The closest a pinch goes in an Encounter. */
    const val PINCH_MAX_DP_PER_METRE = 40.0

    /** The fight frame's box is grown by this on each side. */
    const val FIGHT_BOX_GROW_M = 1.0

    /** The whole board fits the leash ring, this many cells past the board's radius... */
    const val WHOLE_BOARD_RING_CELLS = 0.6

    /** ...with this much to spare on each side of the map's shorter edge. */
    const val WHOLE_BOARD_SPARE_DP = 4.0

    /** The camera eases to a new frame over this long. */
    const val CAMERA_EASE_MS = 300L
}
