package com.esper.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.esper.app.core.MapState
import com.esper.app.game.GameSession
import com.esper.app.game.LocationProvider
import com.esper.engine.geometry.GeoPoint
import kotlin.math.roundToInt
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * First view on launch: a map.
 *
 * Uses OpenStreetMap live tiles (network) via osmdroid. The current camera is
 * mirrored into [MapState] so the "Ask Claude" screen can report where the user
 * was looking when they filed a request.
 *
 * Also the home of the core loop's map-facing pieces: the movement radius
 * (leashed to the GPS fix with hysteresis, see [GameSession]), the seeded
 * encounter marker, and the buttons that enter it or open the character sheet.
 */
@Composable
fun MapScreen(
    onOpenClaude: () -> Unit,
    onOpenPrompts: () -> Unit,
    // Wired up by the android-core-and-map work package, which adds the movement
    // radius, the encounter marker and the buttons that call these.
    onOpenEncounter: () -> Unit,
    onOpenCharacterSheet: () -> Unit,
) {
    val context = LocalContext.current

    // Keep the same MapView instance across recompositions.
    val mapView = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(MapState.zoom)
            controller.setCenter(OsmGeoPoint(MapState.latitude, MapState.longitude))
        }
    }

    // The movement leash circle and the encounter marker. Created once per
    // MapView and mutated in place as GameSession state changes, then
    // invalidate()d — never recreated, so a 469-cell board's worth of state
    // never turns into per-frame overlay churn.
    val radiusPolygon = remember(mapView) {
        Polygon(mapView).apply {
            fillColor = 0x220000FF
            strokeColor = 0x800000FF.toInt()
            strokeWidth = 3f
            title = "Movement radius"
        }
    }
    val encounterMarker = remember(mapView) {
        Marker(mapView).apply {
            setVisible(false)
        }
    }
    LaunchedEffect(mapView) {
        if (mapView.overlays.none { it === radiusPolygon }) {
            mapView.overlays.add(radiusPolygon)
        }
        if (mapView.overlays.none { it === encounterMarker }) {
            mapView.overlays.add(encounterMarker)
        }
    }

    // Location permission. Requested once on entry; the game stays playable
    // either way (see GameSession.useFallbackCenter below) because no player may
    // be required to grant a permission in order to keep playing.
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (!granted) {
            GameSession.locationDenied = true
        }
    }
    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Seed a leash centre immediately from the map's current view so the game
    // is playable before any GPS fix (or permission) arrives, then load or
    // create the character.
    LaunchedEffect(Unit) {
        GameSession.useFallbackCenter(GeoPoint(MapState.latitude, MapState.longitude))
        GameSession.ensureCharacter(context)
        if (!permissionGranted) {
            GameSession.locationDenied = true
        }
    }

    // Seed one encounter once a leash centre exists.
    LaunchedEffect(GameSession.radiusCenter) {
        GameSession.ensureEncounter(context)
    }

    val locationProvider = remember(context) { LocationProvider(context) }

    // The map's own lifecycle stays keyed on `mapView` alone: onDetach() tears
    // down real resources and must not fire just because `permissionGranted`
    // flips later in the same screen visit. Starting location lives in the
    // LaunchedEffect below instead, so it is triggered exactly once whichever
    // way permission arrives (already granted on entry, or granted afterwards
    // via the launcher) without this effect ever re-running.
    DisposableEffect(mapView) {
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                publish(mapView)
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                publish(mapView)
                return false
            }
        }
        mapView.addMapListener(listener)
        mapView.onResume()

        onDispose {
            locationProvider.stop()
            mapView.removeMapListener(listener)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Starts location once permission is granted — immediately if it already
    // was on entry, or as soon as the launcher callback flips this to true.
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            GameSession.locationDenied = false
            locationProvider.start { lat, lon, accuracy ->
                GameSession.onLocationFix(lat, lon, accuracy)
            }
        }
    }

    // Keep the leash circle in sync with GameSession's state.
    LaunchedEffect(GameSession.radiusCenter, GameSession.radiusMetres) {
        val center = GameSession.radiusCenter
        radiusPolygon.points = if (center != null) {
            Polygon.pointsAsCircle(OsmGeoPoint(center.lat, center.lon), GameSession.radiusMetres)
        } else {
            emptyList()
        }
        mapView.invalidate()
    }

    // Keep the encounter marker in sync with GameSession's state.
    LaunchedEffect(GameSession.encounter) {
        val encounter = GameSession.encounter
        if (encounter != null) {
            encounterMarker.position = OsmGeoPoint(encounter.anchor.lat, encounter.anchor.lon)
            encounterMarker.title = encounter.monsterIds.joinToString(", ")
            encounterMarker.setVisible(true)
        } else {
            encounterMarker.setVisible(false)
        }
        mapView.invalidate()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            // Visible attribution is required by the ODbL and by the OSM tile
            // usage policy. It belongs over the map itself, not in an about
            // screen, so it stays visible whenever tiles are on screen.
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "First view: local map",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Ask Claude to change this app and it opens a pull request. " +
                    "Merging it publishes a new build for Obtainium.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenClaude) { Text("Ask Claude") }
                OutlinedButton(onClick = onOpenPrompts) { Text("Prompt templates") }
            }

            val locationStatus = if (GameSession.locationDenied) "denied" else "granted"
            Text(
                text = "Location: $locationStatus",
                style = MaterialTheme.typography.bodySmall,
            )
            val encounterStatus = GameSession.encounter?.let { encounter ->
                val names = encounter.monsterIds.joinToString(", ")
                val distance = GameSession.distanceToEncounterMetres()
                if (distance != null) {
                    "Encounter: $names · ${distance.roundToInt()} m away"
                } else {
                    "Encounter: $names"
                }
            } ?: "Encounter: none nearby yet"
            Text(
                text = encounterStatus,
                style = MaterialTheme.typography.bodySmall,
            )
            GameSession.contentError?.let { error ->
                Text(
                    text = "Content error: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        GameSession.beginBattle(context)
                        onOpenEncounter()
                    },
                    enabled = GameSession.encounter != null,
                ) { Text("Enter encounter") }
                OutlinedButton(onClick = onOpenCharacterSheet) { Text("Character") }
            }
        }
    }
}

private fun publish(mapView: MapView) {
    val center = mapView.mapCenter
    MapState.latitude = center.latitude
    MapState.longitude = center.longitude
    MapState.zoom = mapView.zoomLevelDouble
}
