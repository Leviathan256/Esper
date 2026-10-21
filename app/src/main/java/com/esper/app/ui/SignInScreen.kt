package com.esper.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.esper.app.core.Passkeys
import com.esper.app.core.ServerClient
import com.esper.app.core.Settings
import com.esper.app.core.UpdateChecker
import com.esper.app.game.GameSession
import kotlinx.coroutines.launch

/**
 * Shown whenever there is no session token: Esper is online-only, so nothing
 * else is reachable until the player signs in with a passkey. Settings, the
 * updater and Ask Claude stay reachable from here so a broken build or a down
 * server never strands the player.
 */
@Composable
fun SignInScreen(settings: Settings, onOpenSettings: () -> Unit, onOpenClaude: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Re-read on every entry: the player may have just set it in Settings.
    val configured = settings.serverUrl.isNotBlank()

    fun attempt(ceremony: suspend (Context, ServerClient) -> Result<String>) {
        busy = true
        error = null
        scope.launch {
            ceremony(context, ServerClient(settings.serverUrl, null))
                .onSuccess { GameSession.signIn(it) }
                .onFailure { error = it.message ?: "Sign-in failed." }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Esper", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Esper is online-only. While the map is open, your precise location is sent " +
                "to the Esper server so it can place you in the shared world. The server keeps " +
                "only your latest position, in memory, and never stores it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            onClick = {
                UpdateChecker.openUrl(
                    context,
                    "https://github.com/${settings.repoSlug}/blob/main/docs/GAME_DESIGN.md",
                )
            },
        ) {
            Text("How location is used")
        }

        if (!configured) {
            Text(
                text = "Server not configured. Set it in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!Settings.keystoreAvailable) {
            Text(
                text = "This device's keystore is unavailable, so the sign-in is kept in memory only. " +
                    "You will need to sign in again each time the app restarts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = { attempt(Passkeys::register) },
            enabled = configured && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Create account")
        }
        OutlinedButton(
            onClick = { attempt(Passkeys::signIn) },
            enabled = configured && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Waiting for passkey…" else "Sign in with passkey")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenSettings) { Text("Settings") }
            TextButton(onClick = onOpenClaude) { Text("Updates & Ask Claude") }
        }
    }
}
