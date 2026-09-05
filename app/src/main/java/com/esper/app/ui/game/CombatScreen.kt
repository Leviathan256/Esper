package com.esper.app.ui.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Placeholder. The real screen is built by the `android-battle-ui` work package:
 * a second osmdroid `MapView` centred on the encounter anchor, the hex board drawn
 * as a single overlay, tap-to-move and tap-to-attack, ATB gauges and an event log.
 *
 * [onFinished] is called once the battle ends and the result has been applied.
 */
@Composable
fun CombatScreen(onFinished: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Encounters are not wired up yet.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
