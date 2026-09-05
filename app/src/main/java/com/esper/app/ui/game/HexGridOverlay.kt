package com.esper.app.ui.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import com.esper.engine.geometry.HexBoard
import com.esper.engine.geometry.HexCoord
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * Draws [board]'s cells on the map as a single overlay.
 *
 * A 12 m-radius board is 469 hexes (`3n(n+1)+1` at n = 12); one [Overlay] building
 * one [Path] per draw pass is cheap, where 469 individual `Polygon` overlays would
 * not be. Highlighted sets (legal moves, attackable targets, the current actor,
 * living units) are exposed as mutable vars — the composable mutates them and
 * must call `mapView.invalidate()` afterwards, since this overlay has no way to
 * request a redraw on its own.
 */
class HexGridOverlay(private val board: HexBoard) : Overlay() {

    /** Cells the current actor could legally move to this turn. */
    var legalMoveCells: Set<HexCoord> = emptySet()

    /** Cells holding a living enemy the current actor could attack this turn. */
    var attackableCells: Set<HexCoord> = emptySet()

    /** The cell the unit whose turn it is stands on, if any. */
    var currentActorCell: HexCoord? = null

    /** Cells holding a living player-controlled unit. */
    var playerUnitCells: Set<HexCoord> = emptySet()

    /** Cells holding a living enemy unit. */
    var enemyUnitCells: Set<HexCoord> = emptySet()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(160, 255, 255, 255)
        strokeWidth = 2f
    }
    private val legalMovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(90, 64, 196, 255)
    }
    private val attackablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(110, 255, 64, 64)
    }
    private val playerUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(140, 64, 220, 120)
    }
    private val enemyUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(140, 200, 64, 200)
    }
    private val actorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 255, 215, 0)
        strokeWidth = 5f
    }

    /** Reused across every corner of every cell so drawing 469 hexes allocates nothing. */
    private val reusablePoint = Point()

    override fun draw(canvas: Canvas, projection: Projection) {
        val gridPath = Path()
        for (cell in board.cells) {
            appendCellOutline(gridPath, cell, projection)
        }
        canvas.drawPath(gridPath, gridPaint)

        drawFilledCells(canvas, projection, playerUnitCells, playerUnitPaint)
        drawFilledCells(canvas, projection, enemyUnitCells, enemyUnitPaint)
        drawFilledCells(canvas, projection, legalMoveCells, legalMovePaint)
        drawFilledCells(canvas, projection, attackableCells, attackablePaint)

        currentActorCell?.let { cell ->
            val actorPath = Path()
            appendCellOutline(actorPath, cell, projection)
            canvas.drawPath(actorPath, actorPaint)
        }
    }

    private fun drawFilledCells(
        canvas: Canvas,
        projection: Projection,
        cells: Set<HexCoord>,
        paint: Paint,
    ) {
        if (cells.isEmpty()) return
        val path = Path()
        for (cell in cells) {
            appendCellOutline(path, cell, projection)
        }
        canvas.drawPath(path, paint)
    }

    private fun appendCellOutline(path: Path, cell: HexCoord, projection: Projection) {
        val corners = board.cornersGeo(cell)
        corners.forEachIndexed { index, geo ->
            val screenPoint = projection.toPixels(OsmGeoPoint(geo.lat, geo.lon), reusablePoint)
            if (index == 0) {
                path.moveTo(screenPoint.x.toFloat(), screenPoint.y.toFloat())
            } else {
                path.lineTo(screenPoint.x.toFloat(), screenPoint.y.toFloat())
            }
        }
        path.close()
    }
}
