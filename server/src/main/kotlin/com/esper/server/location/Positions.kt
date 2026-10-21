package com.esper.server.location

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

/** What `POST /v1/location` and the socket `loc` message carry. */
@Serializable
data class LocationBody(val lat: Double, val lon: Double, val accuracyM: Double)

/** [receivedAt] is server time; client time is never accepted. */
data class Fix(val lat: Double, val lon: Double, val accuracyM: Double, val receivedAt: Instant)

/**
 * The latest fix per account, IN MEMORY ONLY: overwritten by the next fix,
 * dropped after [TTL] without one, on logout and on restart. Never written to
 * Postgres or logs. This is the player-facing rule in docs/GAME_DESIGN.md
 * ("Why the app asks for location"); change it there first.
 *
 * Step 3 reads [latest] for the ~300 m query; step 7's speed check compares the
 * previous fix in [record].
 */
class Positions {
    private val fixes = ConcurrentHashMap<UUID, Fix>()

    fun record(accountId: UUID, body: LocationBody, now: Instant = Instant.now()) {
        fixes[accountId] = Fix(body.lat, body.lon, body.accuracyM, now)
    }

    fun latest(accountId: UUID): Fix? = fixes[accountId]

    fun remove(accountId: UUID) {
        fixes.remove(accountId)
    }

    fun purge(now: Instant = Instant.now()) {
        val cutoff = now.minus(TTL)
        fixes.entries.removeIf { it.value.receivedAt.isBefore(cutoff) }
    }

    companion object {
        val TTL: Duration = Duration.ofMinutes(10)

        /** Range checks reject NaN too: every comparison with NaN is false. */
        fun validate(lat: Double, lon: Double, accuracyM: Double): Boolean =
            lat in -90.0..90.0 && lon in -180.0..180.0 && accuracyM in 0.0..10_000.0
    }
}
