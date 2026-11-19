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

    // §4.1 Identity: surfaces and strokes, in dp.

    /** Cut corners: cards. */
    const val CUT_CARD_DP = 8f

    /** Cut corners: buttons, chips and fields. */
    const val CUT_CONTROL_DP = 6f

    /** Cut corners: tags, stamps, stat tiles. */
    const val CUT_TAG_DP = 4f

    /** Hairline stroke. */
    const val HAIRLINE_DP = 1f

    /** The outline of every surface, and the top bar's line. */
    const val OUTLINE_DP = 1.5f

    /** Emphasis stroke: a chosen card. */
    const val EMPHASIS_DP = 2.5f

    /** The solid ink foot under a surface, in place of a shadow. */
    const val FOOT_DP = 3f

    /** A pressed surface moves down this far onto its foot. */
    const val PRESS_DP = 2f

    /** The sun veil: white at this alpha over everything (§4.1 floors, §4.8). */
    const val SUN_VEIL_ALPHA = 0.45f

    /** The mat: paper at this alpha inside the leash ring (§4.2). */
    const val MAT_WASH_ALPHA = 0.5f

    /** The move range: ally at this alpha on the reachable cells (§4.4). */
    const val MOVE_FILL_ALPHA = 0.16f

    // §4.6 Screens and §4.8 touch, in dp.

    /** A screen's padding. */
    const val SCREEN_PADDING_DP = 16f

    /** Gaps between a screen's parts: 8 inside a group, 12 between groups. */
    const val GAP_DP = 8f
    const val GAP_WIDE_DP = 12f

    /** Every button and control is at least this tall. */
    const val TOUCH_MIN_DP = 48f

    /** The one gold button of a screen. */
    const val PRIMARY_BUTTON_DP = 56f

    /** The app mark on sign-in. */
    const val APP_MARK_DP = 72f

    /** A stat tile is at least this tall. */
    const val TILE_MIN_DP = 46f

    /** The XP bar; a job-point bar is 10. */
    const val BAR_DP = 12f
}
