package com.esper.server

import com.esper.server.auth.Ceremonies
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CeremoniesTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    @Test
    fun `a ceremony is single use`() {
        val store = Ceremonies<String>()
        val id = store.put("req", now)
        assertEquals("req", store.take(id, now))
        assertNull(store.take(id, now))
    }

    @Test
    fun `a ceremony expires after five minutes`() {
        val store = Ceremonies<String>()
        val id = store.put("req", now)
        assertNull(store.take(id, now.plus(Ceremonies.TTL)))
    }

    @Test
    fun `purge drops expired ceremonies only`() {
        val store = Ceremonies<String>()
        val old = store.put("old", now)
        val fresh = store.put("fresh", now.plusSeconds(200))
        store.purge(now.plusSeconds(301))
        assertNull(store.take(old, now))
        assertEquals("fresh", store.take(fresh, now.plusSeconds(301)))
    }

    @Test
    fun `unknown id and distinct ids`() {
        val store = Ceremonies<String>()
        assertNull(store.take("nope", now))
        assertNotEquals(store.put("a", now), store.put("b", now))
    }
}
