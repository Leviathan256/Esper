package com.esper.engine.geometry

/**
 * The map-facing board: hex cells pinned to real geography.
 *
 * The single seam behind which the grid implementation lives. Swapping this for
 * H3 later would not change anything in `combat`.
 */
class HexBoard(val anchor: GeoPoint, val radiusCells: Int) {
    val plane: LocalTangentPlane = LocalTangentPlane(anchor)

    val cells: Set<HexCoord> = HexGrid.cellsWithinRadius(HexCoord.ORIGIN, radiusCells)

    fun contains(hex: HexCoord): Boolean = TODO("implemented by engine-geometry")

    fun centerGeo(hex: HexCoord): GeoPoint = TODO("implemented by engine-geometry")

    /** 6 points, for one map polygon. */
    fun cornersGeo(hex: HexCoord): List<GeoPoint> = TODO("implemented by engine-geometry")

    /** May be outside [cells] — check [contains] before using it. */
    fun nearestCell(point: GeoPoint): HexCoord = TODO("implemented by engine-geometry")
}
