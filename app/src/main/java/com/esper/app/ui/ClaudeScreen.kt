package com.esper.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.esper.app.BuildConfig
import com.esper.app.core.ClaudeSession
import com.esper.app.core.ReleaseInfo
import com.esper.app.core.Settings
import com.esper.app.core.UpdateChecker
import com.esper.app.core.UpdateState
import com.esper.app.core.WorkflowRun
import com.esper.app.ui.theme.EsperButton
import com.esper.app.ui.theme.EsperCard
import com.esper.app.ui.theme.EsperChip
import com.esper.app.ui.theme.EsperColors
import com.esper.app.ui.theme.EsperField
import com.esper.app.ui.theme.Spec
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

    ClaudeContent(
        state = ClaudeState(
            update = ClaudeSession.updateState,
            followNightly = settings.followNightly,
            baseBranch = settings.baseBranch,
            repoSlug = settings.repoSlug,
            hasToken = settings.hasToken,
            prompt = ClaudeSession.prompt,
            sending = ClaudeSession.sending,
            statusMessage = ClaudeSession.statusMessage,
            statusIsError = ClaudeSession.statusIsError,
            context = if (showContext) snapshot.toPrettyText() else null,
            hasCrash = snapshot.lastCrash != null,
            runs = ClaudeSession.runs,
            refreshingRuns = ClaudeSession.refreshingRuns,
        ),
        onPromptChange = { ClaudeSession.prompt = it },
        onSend = { scope.launch { ClaudeSession.send(context, settings) } },
        onToggleContext = { showContext = !showContext },
        onForgetCrash = { ClaudeSession.clearCrash(context) },
        onOpenSettings = onOpenSettings,
        onCheckUpdate = { scope.launch { ClaudeSession.checkForUpdate(settings) } },
        onInstall = { UpdateChecker.installDirectly(context, it) },
        onObtainium = { UpdateChecker.openInObtainium(context, settings, it) },
        onRefreshRuns = { scope.launch { ClaudeSession.refreshRuns(settings) } },
        onOpenRun = { UpdateChecker.openUrl(context, it.htmlUrl) },
    )
}

/** What the Ask Claude screen shows. */
data class ClaudeState(
    val update: UpdateState,
    val followNightly: Boolean,
    val baseBranch: String,
    val repoSlug: String,
    val hasToken: Boolean,
    val prompt: String,
    val sending: Boolean,
    val statusMessage: String?,
    val statusIsError: Boolean,
    /** The context preview's text, or null while it is hidden. Its map line is a zoom, never a position. */
    val context: String?,
    /** A stored crash is in the context. */
    val hasCrash: Boolean,
    val runs: List<WorkflowRun>,
    val refreshingRuns: Boolean,
    val versionName: String = BuildConfig.VERSION_NAME,
)

/**
 * The stateless Ask Claude screen (docs/VISUALS.md §4.6): what a test renders. `esper` is its
 * accent; `Send to Claude` is its one gold button.
 */
@Composable
fun ClaudeContent(
    state: ClaudeState,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleContext: () -> Unit,
    onForgetCrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onCheckUpdate: () -> Unit,
    onInstall: (ReleaseInfo) -> Unit,
    onObtainium: (ReleaseInfo) -> Unit,
    onRefreshRuns: () -> Unit,
    onOpenRun: (WorkflowRun) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spec.SCREEN_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(Spec.GAP_WIDE_DP.dp),
    ) {
        UpdateBanner(state, onCheckUpdate, onInstall, onObtainium)

        Text("Ask Claude to change this app", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Your request and the app state below are sent to a Claude Code run on " +
                "GitHub Actions. It opens a pull request against ${state.baseBranch}.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!state.hasToken) {
            EsperCard(modifier = Modifier.fillMaxWidth()) {
                Text("No GitHub token yet", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Dispatching a run needs a fine-grained token with " +
                        "Actions: read & write on ${state.repoSlug}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onOpenSettings) { Text("Open settings") }
            }
        }

        EsperField(
            value = state.prompt,
            onValueChange = onPromptChange,
            label = "What should Claude do?",
            placeholder = "e.g. add a button that recentres the map on my location",
            minLines = 4,
            enabled = !state.sending,
            accent = EsperColors.esper,
            modifier = Modifier.fillMaxWidth(),
        )

        // No spinner while sending: the loading gate's is the only thing in the app that loops (§4.6).
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spec.GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EsperButton(
                text = if (state.sending) "Sending…" else "Send to Claude",
                onClick = onSend,
                primary = true,
                enabled = !state.sending,
            )
            EsperButton(if (state.context != null) "Hide context" else "Show context", onClick = onToggleContext)
        }

        state.statusMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.statusIsError) EsperColors.foe else EsperColors.esper,
            )
        }

        state.context?.let { context ->
            EsperCard(modifier = Modifier.fillMaxWidth()) {
                Text("Context sent with your request", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = context,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(EsperColors.paperDim, MaterialTheme.shapes.small)
                        .padding(Spec.GAP_DP.dp),
                )
                if (state.hasCrash) {
                    TextButton(onClick = onForgetCrash) { Text("Forget stored crash") }
                }
            }
        }

        HorizontalDivider(color = EsperColors.line)

        RunList(state, onRefreshRuns, onOpenRun)
    }
}

@Composable
private fun UpdateBanner(
    state: ClaudeState,
    onCheckUpdate: () -> Unit,
    onInstall: (ReleaseInfo) -> Unit,
    onObtainium: (ReleaseInfo) -> Unit,
) {
    val update = state.update
    EsperCard(modifier = Modifier.fillMaxWidth()) {
        when (update) {
            is UpdateState.Available -> {
                Text("Update available", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${update.release.versionName} is published. " +
                        "You're on ${state.versionName}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spec.GAP_DP.dp)) {
                    EsperButton("Install now", onClick = { onInstall(update.release) })
                    EsperButton("Obtainium", onClick = { onObtainium(update.release) })
                }
            }

            is UpdateState.UpToDate -> Text(
                text = "Up to date — ${update.release.versionName} " +
                    "(${if (state.followNightly) "nightly" else "stable"} channel)",
                style = MaterialTheme.typography.bodySmall,
            )

            is UpdateState.Failed -> Text(
                text = "Update check failed: ${update.message}",
                style = MaterialTheme.typography.bodySmall,
                color = EsperColors.foe,
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

        TextButton(onClick = onCheckUpdate, enabled = update !is UpdateState.Checking) {
            Text("Check again")
        }
    }
}

/** A run's status icon; the card shows it beside the status word. */
internal fun runIcon(run: WorkflowRun): ImageVector = when {
    run.isRunning -> Icons.Filled.Schedule
    run.conclusion == "success" -> Icons.Filled.CheckCircle
    else -> Icons.Filled.ErrorOutline
}

@Composable
private fun RunList(state: ClaudeState, onRefreshRuns: () -> Unit, onOpenRun: (WorkflowRun) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Recent Claude runs", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onRefreshRuns, enabled = !state.refreshingRuns) {
            Text(if (state.refreshingRuns) "Refreshing…" else "Refresh")
        }
    }

    if (state.runs.isEmpty()) {
        Text("No runs yet.", style = MaterialTheme.typography.bodySmall)
    } else {
        state.runs.forEach { run ->
            EsperCard(modifier = Modifier.fillMaxWidth()) {
                Text(run.name.ifBlank { "Claude run" }, style = MaterialTheme.typography.bodyMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spec.GAP_DP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EsperChip(run.displayState, icon = runIcon(run))
                    Text(run.createdAt, style = MaterialTheme.typography.bodySmall, color = EsperColors.inkSoft)
                }
                TextButton(onClick = { onOpenRun(run) }) { Text("Open on GitHub") }
            }
        }
    }
}
