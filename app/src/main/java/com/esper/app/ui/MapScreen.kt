package com.esper.app.ui

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.esper.app.core.MapState
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * First view on launch: a map.
 *
 * Uses OpenStreetMap live tiles (network) via osmdroid. The current camera is
 * mirrored into [MapState] so the "Ask Claude" screen can report where the user
 * was looking when they filed a request.
 */
@Composable
fun MapScreen(
    onOpenClaude: () -> Unit,
    onOpenPrompts: () -> Unit,
) {
    val context = LocalContext.current

    // Keep the same MapView instance across recompositions.
    val mapView = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(MapState.zoom)
            controller.setCenter(GeoPoint(MapState.latitude, MapState.longitude))
        }
    }

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
            mapView.removeMapListener(listener)
            mapView.onPause()
            mapView.onDetach()
        }
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
        }
    }
}

private fun publish(mapView: MapView) {
    val center = mapView.mapCenter
    MapState.latitude = center.latitude
    MapState.longitude = center.longitude
    MapState.zoom = mapView.zoomLevelDouble
}
