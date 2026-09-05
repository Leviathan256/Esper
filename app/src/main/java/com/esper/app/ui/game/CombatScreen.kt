package com.esper.app.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.esper.app.game.ContentRepository
import com.esper.app.game.GameSession
import com.esper.engine.combat.BattleResult
import com.esper.engine.combat.CombatAction
import com.esper.engine.combat.CombatEngine
import com.esper.engine.combat.CombatEvent
import com.esper.engine.combat.CombatUnit
import com.esper.engine.combat.SimpleAi
import com.esper.engine.geometry.GeoPoint
import com.esper.engine.geometry.HexCoord
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay

/**
 * The fight: a second osmdroid `MapView` centred on the encounter, a hex grid
 * overlay, tap-to-move / tap-to-attack, ATB gauges and an event log.
 *
 * `:app` computes nothing here — every action goes straight through
 * [CombatEngine.submitAction]; this screen only decides which action a tap or a
 * button press means, and renders what the engine returns.
 *
 * There is no background timer or coroutine tick loop: the ATB model is discrete,
 * so advancing it is just calling [CombatEngine.advanceToNextActor] in a loop
 * after every player action (and once at screen start), letting [SimpleAi] act for
 * every non-player turn until it is the player's turn again or the battle ends.
 *
 * [onFinished] is called once the player dismisses the result panel.
 */
@Composable
fun CombatScreen(onFinished: () -> Unit) {
    val engine = GameSession.engine
    if (engine == null) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "No battle is in progress.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onFinished) { Text("Back to map") }
            }
        }
        return
    }

    // Captured as a stable parameter (not read again from GameSession.engine)
    // so the rest of this screen never has to worry about it changing or being
    // reassigned to null mid-composition.
    CombatBattleScreen(engine = engine, onFinished = onFinished)
}

@Composable
private fun CombatBattleScreen(engine: CombatEngine, onFinished: () -> Unit) {
    val context = LocalContext.current
    val board = engine.board

    var unitsSnapshot by remember { mutableStateOf(engine.units) }
    var currentActorId by remember { mutableStateOf(engine.currentActor()?.id) }
    var legalMoves by remember { mutableStateOf<Set<HexCoord>>(emptySet()) }
    var attackable by remember { mutableStateOf<List<CombatUnit>>(emptyList()) }
    var result by remember { mutableStateOf(engine.result()) }
    val eventLog = remember { mutableStateListOf<String>() }

    fun nameOf(unitId: String): String = engine.unit(unitId)?.displayName ?: unitId

    fun describeEvent(event: CombatEvent): String = when (event) {
        is CombatEvent.TurnReady -> "${nameOf(event.unitId)} is ready to act."
        is CombatEvent.Moved -> "${nameOf(event.unitId)} moved."
        is CombatEvent.Attacked -> {
            val attacker = nameOf(event.attackerId)
            val target = nameOf(event.targetId)
            when {
                event.roll.fumble -> "$attacker fumbled against $target."
                !event.roll.hit -> "$attacker missed $target."
                event.roll.critical ->
                    "$attacker landed a critical hit on $target for ${event.damage} " +
                        "(HP ${event.targetHpAfter})."
                else -> "$attacker hit $target for ${event.damage} (HP ${event.targetHpAfter})."
            }
        }
        is CombatEvent.Defeated -> "${nameOf(event.unitId)} was defeated."
        is CombatEvent.ActionRejected -> "${nameOf(event.unitId)} could not act: ${event.reason}"
        is CombatEvent.BattleEnded -> "Battle ended: ${event.result}."
    }

    // Pulls a fresh snapshot from the engine. Called after every state-changing
    // engine call so the UI (and the overlay, via the LaunchedEffect below) stays
    // in sync with what the engine actually decided.
    fun refresh() {
        unitsSnapshot = engine.units
        val actor = engine.currentActor()
        currentActorId = actor?.id
        result = engine.result()
        val activePlayerActor = actor?.takeIf { it.playerControlled && result == null }
        legalMoves = activePlayerActor?.let { engine.legalMoves(it.id) } ?: emptySet()
        attackable = activePlayerActor?.let { engine.attackableTargets(it.id) } ?: emptyList()
    }

    // Ticks the ATB gauge and lets SimpleAi act for every non-player turn, in a
    // synchronous loop — never a timer — stopping as soon as it is the player's
    // turn or the battle is over.
    fun runAiLoop() {
        while (!engine.isOver()) {
            val actor = engine.advanceToNextActor() ?: break
            if (actor.playerControlled) break
            val events = engine.submitAction(SimpleAi.chooseAction(engine, actor))
            events.forEach { eventLog.add(describeEvent(it)) }
        }
        refresh()
    }

    fun submitPlayerAction(action: CombatAction) {
        val events = engine.submitAction(action)
        events.forEach { eventLog.add(describeEvent(it)) }
        runAiLoop()
    }

    // Resolves the very first actor once, at screen start.
    LaunchedEffect(Unit) {
        runAiLoop()
    }

    val mapView = remember(engine) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(20.0)
            controller.setCenter(OsmGeoPoint(board.anchor.lat, board.anchor.lon))
            // MAPNIK caps out at zoom 19; a blurry over-zoomed base map under the
            // hex overlay past that is a documented accepted cost, not a bug.
            maxZoomLevel = 21.0
        }
    }

    val hexOverlay = remember(mapView) { HexGridOverlay(board) }

    val mapEventsOverlay = remember(mapView) {
        MapEventsOverlay(
            object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: OsmGeoPoint): Boolean {
                    if (result != null) return false
                    val actor = unitsSnapshot.find { it.id == currentActorId }
                    if (actor == null || !actor.playerControlled) return false

                    val hex = board.nearestCell(GeoPoint(p.latitude, p.longitude))
                    val target = attackable.firstOrNull { it.position == hex }
                    return when {
                        target != null -> {
                            submitPlayerAction(CombatAction.Attack(target.id))
                            true
                        }
                        hex in legalMoves -> {
                            submitPlayerAction(CombatAction.Move(hex))
                            true
                        }
                        else -> false
                    }
                }

                override fun longPressHelper(p: OsmGeoPoint): Boolean = false
            },
        )
    }

    LaunchedEffect(mapView) {
        if (mapView.overlays.none { it === mapEventsOverlay }) {
            mapView.overlays.add(mapEventsOverlay)
        }
        if (mapView.overlays.none { it === hexOverlay }) {
            mapView.overlays.add(hexOverlay)
        }
    }

    // Same lifecycle handling as MapScreen's working DisposableEffect: two live
    // MapViews across two screens is the main integration risk here, so this
    // copies the pattern rather than inventing a new one.
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Keeps the overlay's highlighted sets in sync with combat state, then asks
    // the MapView to redraw — the overlay has no way to invalidate itself.
    LaunchedEffect(unitsSnapshot, currentActorId, legalMoves, attackable) {
        hexOverlay.legalMoveCells = legalMoves
        hexOverlay.attackableCells = attackable.map { it.position }.toSet()
        hexOverlay.currentActorCell = unitsSnapshot.find { it.id == currentActorId }?.position
        hexOverlay.playerUnitCells = unitsSnapshot
            .filter { it.playerControlled && it.alive }
            .map { it.position }
            .toSet()
        hexOverlay.enemyUnitCells = unitsSnapshot
            .filter { !it.playerControlled && it.alive }
            .map { it.position }
            .toSet()
        mapView.invalidate()
    }

    val isPlayerTurn = result == null &&
        unitsSnapshot.find { it.id == currentActorId }?.playerControlled == true

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            // Visible attribution is required wherever OSM tiles are drawn.
            Text(
                text = "© OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Black,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.White.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Combatants", style = MaterialTheme.typography.titleMedium)
            unitsSnapshot.forEach { unit ->
                val marker = buildString {
                    if (unit.id == currentActorId) append(" ▶")
                    if (!unit.alive) append(" (down)")
                }
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = unit.displayName + marker,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "HP ${unit.currentHp}/${unit.stats.maxHp}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    LinearProgressIndicator(
                        progress = (unit.atbGauge / CombatEngine.GAUGE_MAX).coerceIn(0.0, 1.0).toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Text("Log", style = MaterialTheme.typography.titleMedium)
            eventLog.takeLast(8).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }

            Button(onClick = { submitPlayerAction(CombatAction.Wait) }, enabled = isPlayerTurn) {
                Text("Wait")
            }

            result?.let { finalResult ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (finalResult == BattleResult.VICTORY) "Victory!" else "Defeat",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (finalResult == BattleResult.VICTORY) {
                            val catalog = ContentRepository.catalog(context)
                            val defeated = unitsSnapshot
                                .filter { !it.playerControlled && !it.alive }
                                .mapNotNull { catalog.monster(it.sourceId) }
                            val xpGained = defeated.sumOf { it.xpReward }
                            val jobPointsGained = defeated.sumOf { it.jobPointsReward }
                            Text(
                                text = "XP gained: $xpGained · Job points gained: $jobPointsGained",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Defeated: " + defeated.joinToString(", ") { it.displayName },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Button(
                            onClick = {
                                GameSession.endBattle(finalResult, context)
                                onFinished()
                            },
                        ) { Text("Back to map") }
                    }
                }
            }
        }
    }
}
