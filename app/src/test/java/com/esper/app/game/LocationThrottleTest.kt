package com.esper.app.game

import com.esper.engine.geometry.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationThrottleTest {
    private val start = GeoPoint(51.5, -0.12)

    // ~0.00045 degrees of latitude is about 50 m.
    private val near = GeoPoint(51.5002, -0.12)
    private val far = GeoPoint(51.5006, -0.12)

    @Test fun firstFixAlwaysSends() = assertTrue(shouldSendFix(null, 0L, start, 0L))

    @Test fun smallMoveWithin30sIsHeld() = assertFalse(shouldSendFix(start, 1_000L, near, 30_999L))

    @Test fun sendsAfter30s() = assertTrue(shouldSendFix(start, 1_000L, near, 31_000L))

    @Test fun sendsAfter50mEvenWithin30s() = assertTrue(shouldSendFix(start, 1_000L, far, 2_000L))
}
