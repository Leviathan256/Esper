package com.esper.engine.encounter

import com.esper.engine.geometry.GeoPoint

/**
 * The seam for "never seed a player onto a road, railway, water, or private
 * property".
 *
 * Real geodata is not wired up yet, so the MVP ships [AlwaysSafe]. That is a
 * genuine gap against the Combat safety rules and a pre-launch blocker, recorded
 * as one in docs/GAME_DESIGN.md — not a resolved item.
 */
fun interface SafeLocationChecker {
    fun isSafe(point: GeoPoint): Boolean
}

/** The trivial MVP default: everywhere is safe. Replace before real players arrive. */
object AlwaysSafe : SafeLocationChecker {
    override fun isSafe(point: GeoPoint): Boolean = true
}
