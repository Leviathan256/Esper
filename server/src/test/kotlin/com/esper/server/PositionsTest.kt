package com.esper.server

import com.esper.server.location.LocationBody
import com.esper.server.location.Positions
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PositionsTest {
    @Test
    fun `validate rejects out of range and NaN`() {
        assertTrue(Positions.validate(51.5, -0.12, 10.0))
        assertTrue(Positions.validate(-90.0, 180.0, 0.0))
        assertFalse(Positions.validate(90.1, 0.0, 5.0))
        assertFalse(Positions.validate(0.0, -180.5, 5.0))
        assertFalse(Positions.validate(0.0, 0.0, -1.0))
        assertFalse(Positions.validate(0.0, 0.0, 10_001.0))
        assertFalse(Positions.validate(Double.NaN, 0.0, 5.0))
        assertFalse(Positions.validate(0.0, Double.NaN, 5.0))
        assertFalse(Positions.validate(0.0, 0.0, Double.NaN))
    }

    @Test
    fun `purge drops a fix older than ten minutes and keeps a fresh one`() {
        val positions = Positions()
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val stale = UUID.randomUUID()
        val fresh = UUID.randomUUID()
        positions.record(stale, LocationBody(1.0, 2.0, 3.0), now.minusSeconds(601))
        positions.record(fresh, LocationBody(1.0, 2.0, 3.0), now.minusSeconds(599))
        positions.purge(now)
        assertNull(positions.latest(stale))
        assertNotNull(positions.latest(fresh))
    }

    @Test
    fun `remove (logout) drops the fix`() {
        val positions = Positions()
        val id = UUID.randomUUID()
        positions.record(id, LocationBody(1.0, 2.0, 3.0))
        positions.remove(id)
        assertNull(positions.latest(id))
    }
}
