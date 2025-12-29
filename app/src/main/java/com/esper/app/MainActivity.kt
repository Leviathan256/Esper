package com.esper.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid expects a user-agent and shared preferences configured.
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE),
        )

        setContent { EsperApp() }
    }
}

private object Routes {
    const val Map = "map"
    const val Prompts = "prompts"
}

@Composable
private fun EsperApp() {
    val navController = rememberNavController()
    EsperScaffold(navController = navController)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EsperScaffold(navController: NavHostController) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Esper") },
                actions = {
                    if (currentRoute != Routes.Prompts) {
                        IconButton(onClick = { navController.navigate(Routes.Prompts) }) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = "Prompts",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Map,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.Map) { MapScreen(onOpenPrompts = { navController.navigate(Routes.Prompts) }) }
            composable(Routes.Prompts) { PromptsScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

/**
 * First view on launch: a map.
 *
 * For now this uses OpenStreetMap live tiles (network) via osmdroid.
 * If you want fully-offline tiles, we can wire a local tile archive provider later.
 */
@Composable
private fun MapScreen(onOpenPrompts: () -> Unit) {
    val context = LocalContext.current

    // Keep the same MapView instance across recompositions.
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(3.5)
            controller.setCenter(GeoPoint(0.0, 0.0))
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

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
                text = "Tap “Prompts” to get Codex/Copilot/ChatGPT instructions for making code changes + merge flow.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onOpenPrompts,
                modifier = Modifier.align(Alignment.End),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text("Open prompts")
            }
        }
    }
}

@Composable
private fun PromptsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val content = withContext(Dispatchers.IO) {
                context.assets.open("codex_prompts.md").bufferedReader().use { it.readText() }
            }
            text = content
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "In-app prompts (Codex / Copilot / ChatGPT)", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Use this text as the prompt when you want an AI assistant to change code, manage branches/PRs, and handle merges safely.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (error != null) {
            Text(text = "Failed to load prompts: $error", color = MaterialTheme.colorScheme.error)
        } else if (text == null) {
            Text(text = "Loading…")
        } else {
            Button(
                onClick = { copyToClipboard(context, "Esper prompts", text ?: "") },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Copy all prompts")
            }

            Text(
                text = text ?: "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Button(
            onClick = onBack,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("Back")
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

