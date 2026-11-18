package com.esper.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MapStateTest {
    /** The Ask Claude snapshot is public; the map centre is where the player stands. */
    @Test
    fun `the snapshot line carries the zoom and never the position`() {
        MapState.latitude = 12.34567
        MapState.longitude = -98.76543
        MapState.zoom = 18.0
        assertEquals("z18.0", MapState.describe())
    }
}
