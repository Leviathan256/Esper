package com.esper.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.esper.app.core.CrashLog
import com.esper.app.core.EventLog
import com.esper.app.core.Settings
import com.esper.app.ui.ClaudeScreen
import com.esper.app.ui.MapScreen
import com.esper.app.ui.PromptsScreen
import com.esper.app.ui.SettingsScreen
import com.esper.app.ui.game.CharacterSheetScreen
import com.esper.app.ui.game.CombatScreen
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Capture crashes before anything else can throw, so the next launch can
        // hand the trace to Claude.
        CrashLog.install(this)
        EventLog.record("app launched (${BuildConfig.VERSION_NAME})")

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
    const val MAP = "map"
    const val COMBAT = "combat"
    const val SHEET = "sheet"
    const val CLAUDE = "claude"
    const val PROMPTS = "prompts"
    const val SETTINGS = "settings"
}

@Composable
private fun EsperApp() {
    val context = LocalContext.current
    val settings = remember(context) { Settings(context) }
    EsperScaffold(navController = rememberNavController(), settings = settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EsperScaffold(navController: NavHostController, settings: Settings) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val onMap = currentRoute == Routes.MAP
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (currentRoute) {
                            Routes.COMBAT -> "Encounter"
                            Routes.SHEET -> "Character"
                            Routes.CLAUDE -> "Ask Claude"
                            Routes.PROMPTS -> "Prompts"
                            Routes.SETTINGS -> "Settings"
                            else -> "Esper"
                        },
                    )
                },
                navigationIcon = {
                    if (!onMap) {
                        IconButton(
                            onClick = {
                                // Through the dispatcher, not popBackStack(), so a
                                // screen's BackHandler (combat settles its battle)
                                // sees this exactly like the system back gesture.
                                if (backDispatcher != null) {
                                    backDispatcher.onBackPressed()
                                } else {
                                    navController.popBackStack()
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (currentRoute != Routes.SHEET) {
                        IconButton(onClick = { navController.navigate(Routes.SHEET) }) {
                            Icon(Icons.Filled.Person, contentDescription = "Character")
                        }
                    }
                    if (currentRoute != Routes.CLAUDE) {
                        IconButton(onClick = { navController.navigate(Routes.CLAUDE) }) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = "Ask Claude")
                        }
                    }
                    if (currentRoute != Routes.SETTINGS) {
                        IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.MAP,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.MAP) {
                MapScreen(
                    onOpenClaude = { navController.navigate(Routes.CLAUDE) },
                    onOpenPrompts = { navController.navigate(Routes.PROMPTS) },
                    onOpenEncounter = { navController.navigate(Routes.COMBAT) },
                    onOpenCharacterSheet = { navController.navigate(Routes.SHEET) },
                )
            }
            composable(Routes.COMBAT) {
                CombatScreen(onFinished = { navController.popBackStack() })
            }
            composable(Routes.SHEET) {
                CharacterSheetScreen()
            }
            composable(Routes.CLAUDE) {
                ClaudeScreen(
                    settings = settings,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.PROMPTS) {
                PromptsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(settings = settings, onDone = { navController.popBackStack() })
            }
        }
    }
}
