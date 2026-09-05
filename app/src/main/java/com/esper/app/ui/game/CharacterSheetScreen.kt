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
 * Placeholder. The real sheet is built by the `android-battle-ui` work package:
 * name, job, level, xp toward the next threshold, derived stats, ability scores,
 * job points per job, unlocked jobs, and the bestiary.
 */
@Composable
fun CharacterSheetScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "The character sheet is not wired up yet.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
