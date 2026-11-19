package com.esper.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.esper.app.BuildConfig
import com.esper.app.core.Settings
import com.esper.app.core.UpdateChecker
import com.esper.app.game.GameSession
import com.esper.app.ui.theme.EsperButton
import com.esper.app.ui.theme.EsperCard
import com.esper.app.ui.theme.EsperColors
import com.esper.app.ui.theme.EsperField
import com.esper.app.ui.theme.Spec
import kotlinx.coroutines.launch

/**
 * Esper server address and sign-out, repo target, release channel, and the
 * GitHub token used to dispatch runs.
 *
 * Nothing here is sent to Claude — the tokens in particular are deliberately
 * excluded from the app-state snapshot.
 */
@Composable
fun SettingsScreen(settings: Settings, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember {
        mutableStateOf(
            SettingsState(
                serverUrl = settings.serverUrl,
                owner = settings.repoOwner,
                repo = settings.repoName,
                branch = settings.baseBranch,
                token = settings.githubToken,
                nightly = settings.followNightly,
            ),
        )
    }

    SettingsContent(
        state = state.copy(signedIn = GameSession.signedIn, keystoreAvailable = Settings.keystoreAvailable),
        onChange = { state = it.copy(saved = false) },
        onRevealToken = { state = state.copy(revealToken = !state.revealToken) },
        onSave = {
            settings.serverUrl = state.serverUrl
            settings.repoOwner = state.owner
            settings.repoName = state.repo
            settings.baseBranch = state.branch.ifBlank { "main" }
            settings.githubToken = state.token
            settings.followNightly = state.nightly
            state = state.copy(saved = true)
        },
        onSignOut = { scope.launch { GameSession.signOut() } },
        onCreateToken = {
            UpdateChecker.openUrl(context, "https://github.com/settings/personal-access-tokens/new")
        },
        onDone = onDone,
    )
}

/** What the settings screen shows: the fields as typed, not yet saved. */
data class SettingsState(
    val serverUrl: String,
    val owner: String,
    val repo: String,
    val branch: String,
    val token: String,
    val nightly: Boolean,
    val revealToken: Boolean = false,
    /** Everything shown has been saved. */
    val saved: Boolean = false,
    val signedIn: Boolean = false,
    val keystoreAvailable: Boolean = true,
    val build: String = "Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
        "${BuildConfig.CHANNEL} · ${BuildConfig.GIT_SHA.take(7)}",
)

/** The stateless settings screen (docs/VISUALS.md §4.6): what a test renders. Save is its one gold button. */
@Composable
fun SettingsContent(
    state: SettingsState,
    onChange: (SettingsState) -> Unit,
    onRevealToken: () -> Unit,
    onSave: () -> Unit,
    onSignOut: () -> Unit,
    onCreateToken: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spec.SCREEN_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(Spec.GAP_WIDE_DP.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        EsperField(
            value = state.serverUrl,
            onValueChange = { onChange(state.copy(serverUrl = it)) },
            label = "Esper server (https://…)",
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.signedIn) EsperButton("Sign out", onClick = onSignOut)

        EsperField(
            value = state.owner,
            onValueChange = { onChange(state.copy(owner = it)) },
            label = "Repo owner",
            modifier = Modifier.fillMaxWidth(),
        )
        EsperField(
            value = state.repo,
            onValueChange = { onChange(state.copy(repo = it)) },
            label = "Repo name",
            modifier = Modifier.fillMaxWidth(),
        )
        EsperField(
            value = state.branch,
            onValueChange = { onChange(state.copy(branch = it)) },
            label = "Base branch",
            modifier = Modifier.fillMaxWidth(),
        )

        EsperField(
            value = state.token,
            onValueChange = { onChange(state.copy(token = it)) },
            label = "GitHub token",
            visualTransformation = if (state.revealToken) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spec.GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRevealToken) { Text(if (state.revealToken) "Hide" else "Reveal") }
            TextButton(onClick = onCreateToken) { Text("Create token") }
        }

        EsperCard(modifier = Modifier.fillMaxWidth()) {
            Text("Token scope", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Use a fine-grained token limited to this one repository, with " +
                    "Actions: read & write. That is enough to start a Claude run and read " +
                    "its status, and nothing else.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!state.keystoreAvailable) {
                Text(
                    text = "Warning: this device's keystore is unavailable, so the token " +
                        "is stored unencrypted. Consider leaving it blank.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EsperColors.foe,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = Spec.GAP_WIDE_DP.dp)) {
                Text("Follow nightly builds", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Track the rolling nightly prerelease instead of tagged stable releases.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EsperColors.inkSoft,
                )
            }
            Switch(checked = state.nightly, onCheckedChange = { onChange(state.copy(nightly = it)) })
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spec.GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EsperButton("Save", onClick = onSave, primary = true)
            TextButton(onClick = onDone) { Text("Done") }
            if (state.saved) Text("Saved", style = MaterialTheme.typography.bodySmall)
        }

        Text(state.build, style = MaterialTheme.typography.labelSmall, color = EsperColors.inkSoft)
    }
}
