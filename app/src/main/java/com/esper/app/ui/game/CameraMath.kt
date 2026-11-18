package com.esper.app.ui.game

import com.esper.app.ui.theme.Spec
import com.esper.engine.geometry.LocalMetres
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.min

/** The camera arithmetic of docs/VISUALS.md §4.3. Pure: no Android, no osmdroid. */
object CameraMath {
    private const val EQUATOR_METRES = 40075016.686
    private const val TILE_DP = 256.0

    /** The map zoom at which a metre is [dpPerMetre] dp, with tiles scaled to the density. */
    fun zoomFor(dpPerMetre: Double, latitude: Double): Double =
        log2(dpPerMetre * EQUATOR_METRES * cos(Math.toRadians(latitude)) / TILE_DP)

    /** The scale at which the leash ring of a [boardRadiusCells] board fits the map. */
    fun wholeBoardScale(mapWidthDp: Double, mapHeightDp: Double, boardRadiusCells: Int): Double =
        (min(mapWidthDp, mapHeightDp) - 2 * Spec.WHOLE_BOARD_SPARE_DP) /
            (2 * (boardRadiusCells + Spec.WHOLE_BOARD_RING_CELLS))

    /** The fight view's scale for an already grown box: 34, less if it does not fit, never below the whole board. */
    fun fightScale(
        boxWidthM: Double,
        boxHeightM: Double,
        mapWidthDp: Double,
        mapHeightDp: Double,
        boardRadiusCells: Int,
    ): Double = minOf(Spec.FIGHT_DP_PER_METRE, mapWidthDp / boxWidthM, mapHeightDp / boxHeightM)
        .coerceAtLeast(wholeBoardScale(mapWidthDp, mapHeightDp, boardRadiusCells))

    data class Frame(val centre: LocalMetres, val dpPerMetre: Double)

    /**
     * The fight frame: the box around [points] (every living unit's cell centre
     * and every cell the acting unit can reach), grown on each side, centred.
     * [points] must not be empty.
     */
    fun fightFrame(
        points: List<LocalMetres>,
        mapWidthDp: Double,
        mapHeightDp: Double,
        boardRadiusCells: Int,
    ): Frame {
        val grow = Spec.FIGHT_BOX_GROW_M
        val west = points.minOf { it.east } - grow
        val east = points.maxOf { it.east } + grow
        val south = points.minOf { it.north } - grow
        val north = points.maxOf { it.north } + grow
        return Frame(
            centre = LocalMetres((west + east) / 2, (south + north) / 2),
            dpPerMetre = fightScale(east - west, north - south, mapWidthDp, mapHeightDp, boardRadiusCells),
        )
    }
}
