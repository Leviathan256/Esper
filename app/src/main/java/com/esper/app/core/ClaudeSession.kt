package com.esper.app.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.esper.app.BuildConfig
import kotlinx.coroutines.delay

/**
 * App-scoped state for the "Ask Claude" screen.
 *
 * Kept in a singleton rather than a ViewModel so an in-flight dispatch survives
 * navigating away to the map and back, which is the common case while waiting
 * for a run to start.
 */
object ClaudeSession {
    var prompt by mutableStateOf("")
    var sending by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var statusIsError by mutableStateOf(false)
        private set
    var runs by mutableStateOf<List<WorkflowRun>>(emptyList())
        private set
    var refreshingRuns by mutableStateOf(false)
        private set
    var updateState by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** The snapshot shown in the preview and sent with the next dispatch. */
    fun snapshot(context: Context): AppStateSnapshot =
        AppStateSnapshot.capture(context, updateState.summary())

    suspend fun send(context: Context, settings: Settings): Boolean {
        val text = prompt.trim()
        if (text.isEmpty()) {
            setStatus("Write something for Claude to do first.", isError = true)
            return false
        }
        if (!settings.hasToken) {
            setStatus("Add a GitHub token in Settings before dispatching.", isError = true)
            return false
        }

        sending = true
        setStatus("Dispatching…", isError = false)

        val snapshot = snapshot(context)
        val client = GitHubClient(settings.githubToken, settings.repoOwner, settings.repoName)
        val result = client.dispatchClaude(
            workflowFile = BuildConfig.CLAUDE_WORKFLOW_FILE,
            ref = settings.baseBranch,
            prompt = text,
            appContext = snapshot.toJson().toString(),
        )
        sending = false

        return result.fold(
            onSuccess = {
                EventLog.record("dispatched claude run")
                setStatus("Sent. Claude is working — the run appears below shortly.", isError = false)
                prompt = ""
                // The run takes a moment to register, so give GitHub a beat
                // before the first poll rather than showing a stale list.
                delay(3_000)
                refreshRuns(settings)
                true
            },
            onFailure = { error ->
                setStatus(error.message ?: "Dispatch failed.", isError = true)
                false
            },
        )
    }

    suspend fun refreshRuns(settings: Settings) {
        if (settings.repoOwner.isBlank() || settings.repoName.isBlank()) return
        refreshingRuns = true
        val client = GitHubClient(settings.githubToken, settings.repoOwner, settings.repoName)
        client.recentRuns(BuildConfig.CLAUDE_WORKFLOW_FILE).fold(
            onSuccess = { runs = it },
            onFailure = { error -> setStatus(error.message ?: "Could not list runs.", isError = true) },
        )
        refreshingRuns = false
    }

    suspend fun checkForUpdate(settings: Settings) {
        updateState = UpdateState.Checking
        updateState = UpdateChecker.check(settings)
    }

    fun clearCrash(context: Context) {
        CrashLog.clear(context)
        EventLog.record("cleared stored crash trace")
    }

    private fun setStatus(message: String, isError: Boolean) {
        statusMessage = message
        statusIsError = isError
    }
}
