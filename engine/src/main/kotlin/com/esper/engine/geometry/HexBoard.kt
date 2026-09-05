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

    fun contains(hex: HexCoord): Boolean = hex in cells

    fun centerGeo(hex: HexCoord): GeoPoint = plane.toGeo(HexMetrics.axialToLocal(hex))

    /** 6 points, for one map polygon. */
    fun cornersGeo(hex: HexCoord): List<GeoPoint> =
        HexMetrics.cornersLocal(hex).map { plane.toGeo(it) }

    /** May be outside [cells] — check [contains] before using it. */
    fun nearestCell(point: GeoPoint): HexCoord =
        HexMetrics.localToNearestAxial(plane.toLocal(point))
}
