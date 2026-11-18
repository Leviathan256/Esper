package com.esper.app.ui.game

import org.junit.Assert.assertEquals
import org.junit.Test

/** The camera numbers of docs/VISUALS.md §4.3 and §9 step 2. */
class CameraMathTest {
    @Test
    fun `34 dp per metre is zoom 22_34, 21_96 and 21_34 at 0, 40 and 60 degrees`() {
        assertEquals(22.34, CameraMath.zoomFor(34.0, 0.0), 0.005)
        assertEquals(21.96, CameraMath.zoomFor(34.0, 40.0), 0.005)
        assertEquals(21.34, CameraMath.zoomFor(34.0, 60.0), 0.005)
    }
}
