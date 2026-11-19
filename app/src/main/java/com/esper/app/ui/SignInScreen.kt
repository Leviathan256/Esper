package com.esper.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.esper.app.R
import com.esper.app.core.Passkeys
import com.esper.app.core.ServerClient
import com.esper.app.core.Settings
import com.esper.app.core.UpdateChecker
import com.esper.app.game.GameSession
import com.esper.app.ui.theme.EsperButton
import com.esper.app.ui.theme.EsperCard
import com.esper.app.ui.theme.EsperColors
import com.esper.app.ui.theme.Spec
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

    SignInContent(
        state = SignInState(
            configured = configured,
            keystoreAvailable = Settings.keystoreAvailable,
            busy = busy,
            error = error,
        ),
        onCreateAccount = { attempt(Passkeys::register) },
        onSignIn = { attempt(Passkeys::signIn) },
        onHowLocationIsUsed = {
            UpdateChecker.openUrl(context, "https://github.com/${settings.repoSlug}/blob/main/docs/GAME_DESIGN.md")
        },
        onOpenSettings = onOpenSettings,
        onOpenClaude = onOpenClaude,
    )
}

/** What the sign-in screen shows. */
data class SignInState(
    /** A server address is set. */
    val configured: Boolean,
    /** The sign-in can be kept across restarts. */
    val keystoreAvailable: Boolean,
    /** A passkey ceremony is in progress. */
    val busy: Boolean,
    /** The last attempt's failure. */
    val error: String?,
)

/** The stateless sign-in screen (docs/VISUALS.md §4.6): what a test renders. */
@Composable
fun SignInContent(
    state: SignInState,
    onCreateAccount: () -> Unit,
    onSignIn: () -> Unit,
    onHowLocationIsUsed: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenClaude: () -> Unit,
) {
    val canAttempt = state.configured && !state.busy
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spec.SCREEN_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(Spec.GAP_WIDE_DP.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_esper_mark),
            contentDescription = null,
            tint = EsperColors.ink,
            modifier = Modifier.size(Spec.APP_MARK_DP.dp),
        )
        Text("Esper", style = MaterialTheme.typography.headlineMedium)
        EsperCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Esper is online-only. While the map is open, your precise location is sent " +
                    "to the Esper server so it can place you in the shared world. The server keeps " +
                    "only your latest position, in memory, and never stores it.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onHowLocationIsUsed) { Text("How location is used") }

        if (!state.configured) Warning("Server not configured. Set it in Settings.")
        if (!state.keystoreAvailable) {
            Warning(
                "This device's keystore is unavailable, so the sign-in is kept in memory only. " +
                    "You will need to sign in again each time the app restarts.",
            )
        }
        state.error?.let { Warning(it) }

        EsperButton(
            text = "Create account",
            onClick = onCreateAccount,
            primary = true,
            enabled = canAttempt,
            modifier = Modifier.fillMaxWidth(),
        )
        EsperButton(
            text = if (state.busy) "Waiting for passkey…" else "Sign in with passkey",
            onClick = onSignIn,
            enabled = canAttempt,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spec.GAP_DP.dp)) {
            TextButton(onClick = onOpenSettings) { Text("Settings") }
            TextButton(onClick = onOpenClaude) { Text("Updates & Ask Claude") }
        }
    }
}

/** A warning: a `foe`-outlined card with its sentence in `foe`. */
@Composable
internal fun Warning(text: String) {
    EsperCard(modifier = Modifier.fillMaxWidth(), outline = EsperColors.foe) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = EsperColors.foe)
    }
}
