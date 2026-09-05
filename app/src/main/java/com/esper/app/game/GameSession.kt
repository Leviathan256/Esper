package com.esper.app.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.esper.engine.character.CharacterState
import com.esper.engine.combat.CombatEngine
import com.esper.engine.encounter.Battle
import com.esper.engine.encounter.Encounter
import com.esper.engine.geometry.GeoPoint

/**
 * Placeholder state holder. Behaviour is filled in by the `android-core-and-map`
 * work package.
 *
 * A singleton rather than a ViewModel, mirroring `core/ClaudeSession`, so an
 * in-flight battle survives navigating away to the map and back — which is also the
 * reconnect story the design doc asks for.
 *
 * The rules that will live here, none of them implemented yet:
 * - **Hysteresis:** recentre [radiusCenter] only once the player has moved further
 *   than the accuracy Android reports, so a stationary player's circle stays put.
 * - **Never yank the avatar:** its cell is never derived from a fix.
 * - **Playable without a location grant:** fall back to the map's current centre and
 *   set [locationDenied], because no player may be required to grant a permission —
 *   or take a walk — in order to keep playing.
 */
object GameSession {

    /** Most recent GPS fix, or null if none has arrived. */
    var lastFix by mutableStateOf<GeoPoint?>(null)

    /** Accuracy Android reported for [lastFix], in metres. */
    var lastAccuracyMetres by mutableStateOf<Float?>(null)

    /** Centre of the movement leash. Moved with hysteresis, never on every fix. */
    var radiusCenter by mutableStateOf<GeoPoint?>(null)

    /** 12 m, the MVP default recorded in docs/GAME_DESIGN.md. */
    var radiusMetres by mutableStateOf(12.0)

    var encounter by mutableStateOf<Encounter?>(null)

    var battle by mutableStateOf<Battle?>(null)

    var engine by mutableStateOf<CombatEngine?>(null)

    var character by mutableStateOf<CharacterState?>(null)

    /** Non-null when content failed to load; surfaced instead of crashing. */
    var contentError by mutableStateOf<String?>(null)

    /** True when the player declined location, so the UI can say the map centre is standing in. */
    var locationDenied by mutableStateOf(false)
}
