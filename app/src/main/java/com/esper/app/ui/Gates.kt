package com.esper.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.esper.app.ui.theme.EsperButton
import com.esper.app.ui.theme.EsperColors
import com.esper.app.ui.theme.Spec

/** The gate while the character loads: the system spinner in `ink`, the only thing in the app that loops (§4.6). */
@Composable
fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = EsperColors.ink)
    }
}

/** The "Can't load your character" gate (docs/VISUALS.md §4.6): what a test renders. `Retry` is its one gold button. */
@Composable
fun LoadErrorContent(
    error: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenClaude: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spec.SCREEN_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(Spec.GAP_WIDE_DP.dp),
    ) {
        Text("Can't load your character", style = MaterialTheme.typography.titleLarge)
        Warning(error)
        EsperButton("Retry", onClick = onRetry, primary = true)
        Row(horizontalArrangement = Arrangement.spacedBy(Spec.GAP_DP.dp)) {
            TextButton(onClick = onOpenSettings) { Text("Settings") }
            TextButton(onClick = onOpenClaude) { Text("Updates & Ask Claude") }
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }
    }
}
