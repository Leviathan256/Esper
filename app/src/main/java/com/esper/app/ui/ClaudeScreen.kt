package com.esper.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.esper.app.BuildConfig
import com.esper.app.core.ClaudeSession
import com.esper.app.core.Settings
import com.esper.app.core.UpdateChecker
import com.esper.app.core.UpdateState
import kotlinx.coroutines.launch

/**
 * Sends a prompt plus the app's current state to a cloud Claude Code run.
 *
 * The dispatch lands in `.github/workflows/claude-dispatch.yml`, which runs
 * Claude against this repo and opens a pull request.
 */
@Composable
fun ClaudeScreen(
    settings: Settings,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var showContext by remember { mutableStateOf(false) }

    val snapshot = remember(ClaudeSession.updateState, showContext) {
        ClaudeSession.snapshot(context)
    }

    LaunchedEffect(Unit) {
        if (ClaudeSession.updateState is UpdateState.Idle) {
            ClaudeSession.checkForUpdate(settings)
        }
        if (settings.hasToken && ClaudeSession.runs.isEmpty()) {
            ClaudeSession.refreshRuns(settings)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UpdateBanner(settings = settings)

        Text("Ask Claude to change this app", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Your request and the app state below are sent to a Claude Code run on " +
                "GitHub Actions. It opens a pull request against ${settings.baseBranch}.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!settings.hasToken) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "No GitHub token yet",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Dispatching a run needs a fine-grained token with " +
                            "Actions: read & write on ${settings.repoSlug}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onOpenSettings) { Text("Open settings") }
                }
            }
        }

        OutlinedTextField(
            value = ClaudeSession.prompt,
            onValueChange = { ClaudeSession.prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What should Claude do?") },
            placeholder = { Text("e.g. add a button that recentres the map on my location") },
            minLines = 4,
            enabled = !ClaudeSession.sending,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { scope.launch { ClaudeSession.send(context, settings) } },
                enabled = !ClaudeSession.sending,
            ) {
                Text(if (ClaudeSession.sending) "Sending…" else "Send to Claude")
            }
            OutlinedButton(onClick = { showContext = !showContext }) {
                Text(if (showContext) "Hide context" else "Show context")
            }
            if (ClaudeSession.sending) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
            }
        }

        ClaudeSession.statusMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (ClaudeSession.statusIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        if (showContext) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Context sent with your request", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = snapshot.toPrettyText(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (snapshot.lastCrash != null) {
                        TextButton(onClick = { ClaudeSession.clearCrash(context) }) {
                            Text("Forget stored crash")
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        RunList(settings = settings)
    }
}

@Composable
private fun UpdateBanner(settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = ClaudeSession.updateState

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                is UpdateState.Available -> {
                    Text("Update available", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${state.release.versionName} is published. " +
                            "You're on ${BuildConfig.VERSION_NAME}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { UpdateChecker.installDirectly(context, state.release) }) {
                            Text("Install now")
                        }
                        OutlinedButton(
                            onClick = { UpdateChecker.openInObtainium(context, settings, state.release) },
                        ) {
                            Text("Obtainium")
                        }
                    }
                }

                is UpdateState.UpToDate -> Text(
                    text = "Up to date — ${state.release.versionName} " +
                        "(${if (settings.followNightly) "nightly" else "stable"} channel)",
                    style = MaterialTheme.typography.bodySmall,
                )

                is UpdateState.Failed -> Text(
                    text = "Update check failed: ${state.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                UpdateState.Checking -> Text(
                    text = "Checking for updates…",
                    style = MaterialTheme.typography.bodySmall,
                )

                UpdateState.Idle -> Text(
                    text = "Updates not checked yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            TextButton(
                onClick = { scope.launch { ClaudeSession.checkForUpdate(settings) } },
                enabled = state !is UpdateState.Checking,
            ) {
                Text("Check again")
            }
        }
    }
}

@Composable
private fun RunList(settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Recent Claude runs", style = MaterialTheme.typography.titleMedium)
        TextButton(
            onClick = { scope.launch { ClaudeSession.refreshRuns(settings) } },
            enabled = !ClaudeSession.refreshingRuns,
        ) {
            Text(if (ClaudeSession.refreshingRuns) "Refreshing…" else "Refresh")
        }
    }

    if (ClaudeSession.runs.isEmpty()) {
        Text(
            text = "No runs yet.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        ClaudeSession.runs.forEach { run ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = run.name.ifBlank { "Claude run" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${run.displayState} · ${run.createdAt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (run.conclusion == "failure") {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    TextButton(onClick = { UpdateChecker.openUrl(context, run.htmlUrl) }) {
                        Text("Open on GitHub")
                    }
                }
            }
        }
    }
}
