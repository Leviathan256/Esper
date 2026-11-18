package com.esper.app.ui.game

import com.esper.engine.geometry.HexCoord
import com.esper.engine.geometry.HexGrid
import com.esper.engine.geometry.HexMetrics
import com.esper.engine.geometry.LocalMetres
import org.junit.Assert.assertEquals
import org.junit.Test

/** The camera numbers of docs/VISUALS.md §4.3 and §9 step 2, on the reference phone's 412 by 500 dp map. */
class CameraMathTest {
    private val width = 412.0
    private val height = 500.0
    private val radius = 12

    @Test
    fun `34 dp per metre is zoom 22_34, 21_96 and 21_34 at 0, 40 and 60 degrees`() {
        assertEquals(22.34, CameraMath.zoomFor(34.0, 0.0), 0.005)
        assertEquals(21.96, CameraMath.zoomFor(34.0, 40.0), 0.005)
        assertEquals(21.34, CameraMath.zoomFor(34.0, 60.0), 0.005)
    }

    @Test
    fun `the whole board is 16_03 dp per metre on a 412 dp map`() {
        assertEquals(16.03, CameraMath.wholeBoardScale(width, height, radius), 0.005)
    }

    @Test
    fun `a 10 by 10 m box gets the full 34`() {
        assertEquals(34.0, CameraMath.fightScale(10.0, 10.0, width, height, radius), 0.005)
    }

    @Test
    fun `a 20 m wide box gets 20_6`() {
        assertEquals(20.6, CameraMath.fightScale(20.0, 10.0, width, height, radius), 0.005)
    }

    @Test
    fun `a 30 m wide box gets the whole-board 16_03, never less`() {
        assertEquals(16.03, CameraMath.fightScale(30.0, 10.0, width, height, radius), 0.005)
    }

    @Test
    fun `a 10 m wide, 20 m tall box gets 25`() {
        assertEquals(25.0, CameraMath.fightScale(10.0, 20.0, width, height, radius), 0.005)
    }

    @Test
    fun `a fresh fight, monsters 4 cells out and Move 4, gets the full 34 centred on the player`() {
        val reach = HexGrid.cellsWithinRadius(HexCoord.ORIGIN, 4)
        val points = (reach + HexCoord(4, 0)).map { HexMetrics.axialToLocal(it) }

        val frame = CameraMath.fightFrame(points, width, height, radius)

        assertEquals(34.0, frame.dpPerMetre, 0.005)
        assertEquals(0.0, frame.centre.east, 1e-9)
        assertEquals(0.0, frame.centre.north, 1e-9)
    }

    @Test
    fun `the frame centres on the box around its points`() {
        val frame = CameraMath.fightFrame(listOf(LocalMetres(2.0, -1.0), LocalMetres(6.0, 3.0)), width, height, radius)

        assertEquals(4.0, frame.centre.east, 1e-9)
        assertEquals(1.0, frame.centre.north, 1e-9)
    }
}
