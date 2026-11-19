package com.esper.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateSnapshotTest {
    /** Ask Claude's context preview (docs/VISUALS.md §5): its map line holds a zoom and no position. */
    @Test
    fun `the context preview's map line holds a zoom and no position`() {
        MapState.latitude = 12.34567
        MapState.longitude = -98.76543
        MapState.zoom = 18.0
        val preview = AppStateSnapshot(
            versionName = "1.0", versionCode = 1, channel = "nightly", gitSha = "abc1234",
            applicationId = "com.esper.app", device = "Test Phone", androidRelease = "15", sdkInt = 35,
            locale = "en-GB", capturedAt = "2026-11-19T00:00:00Z", mapZoom = MapState.describe(),
            updateStatus = "up to date", lastCrash = null, recentEvents = listOf("map opened"),
        ).toPrettyText()

        assertTrue(preview, preview.lines().contains("map zoom: z18.0"))
        assertFalse(preview, "12.3" in preview || "98.7" in preview)
    }
}
