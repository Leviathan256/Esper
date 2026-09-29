package com.esper.engine.encounter

import com.esper.engine.geometry.GeoPoint

/**
 * A fight waiting to happen, pinned to real geography.
 *
 * The board is anchored where the encounter was seeded and does not move, so a
 * player who walks away mid-fight is not dragged. Whether the leash *should*
 * follow is still an open design question.
 */
data class Encounter(
    val id: String,
    val anchor: GeoPoint,
    val monsterIds: List<String>,
    val boardRadiusCells: Int = DEFAULT_BOARD_RADIUS_CELLS,
) {
    companion object {
        /** 12 m radius, i.e. a 469-cell board (`3n(n+1)+1` at n = 12). */
        const val DEFAULT_BOARD_RADIUS_CELLS: Int = 12
    }
}
