package com.esper.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.esper.app.BuildConfig

/** Result of comparing the installed build against what CI has published. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val release: ReleaseInfo) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Failed(val message: String) : UpdateState

    /** One-line form used in the app-state snapshot sent to Claude. */
    fun summary(): String = when (this) {
        Idle -> "not checked"
        Checking -> "checking"
        is UpToDate -> "up to date (${release.versionName})"
        is Available -> "update available: ${release.versionName} (installed ${BuildConfig.VERSION_NAME})"
        is Failed -> "check failed: $message"
    }
}

object UpdateChecker {
    /**
     * Compares the installed versionCode against the published one.
     *
     * CI derives versionCode from the commit timestamp for every channel, so a
     * plain numeric comparison is valid even when switching between nightly and
     * stable. When the release predates the JSON manifest there is no
     * versionCode to compare, so we fall back to the commit sha and treat
     * "different sha" as "newer" — the worst case is offering a reinstall.
     */
    suspend fun check(settings: Settings): UpdateState {
        val client = GitHubClient(settings.githubToken, settings.repoOwner, settings.repoName)
        return client.latestRelease(nightly = settings.followNightly).fold(
            onSuccess = { release ->
                val published = release.versionCode
                val newer = when {
                    published != null -> published > BuildConfig.VERSION_CODE.toLong()
                    release.gitSha != null -> release.gitSha != BuildConfig.GIT_SHA
                    else -> false
                }
                EventLog.record(
                    "update check: installed=${BuildConfig.VERSION_CODE} published=${published ?: "?"}",
                )
                if (newer) UpdateState.Available(release) else UpdateState.UpToDate(release)
            },
            onFailure = { error ->
                EventLog.record("update check failed: ${error.message}")
                UpdateState.Failed(error.message ?: error.toString())
            },
        )
    }

    /**
     * Opens the app's page in Obtainium, falling back to the release page in a
     * browser when Obtainium isn't installed.
     *
     * Obtainium is what normally performs the install; this just saves waiting
     * for its next background poll. Requires the `obtainium` scheme to be
     * declared in `<queries>` for [Intent.resolveActivity] to see it on API 30+.
     */
    fun openInObtainium(context: Context, settings: Settings, release: ReleaseInfo?) {
        val repoUrl = "https://github.com/${settings.repoOwner}/${settings.repoName}"
        val obtainium = Intent(Intent.ACTION_VIEW, Uri.parse("obtainium://add/$repoUrl"))
        val intent = if (obtainium.resolveActivity(context.packageManager) != null) {
            obtainium
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(release?.htmlUrl ?: "$repoUrl/releases"))
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Downloads the APK straight from the release — the quickest route to installed. */
    fun installDirectly(context: Context, release: ReleaseInfo) {
        openUrl(context, release.apkUrl ?: release.htmlUrl)
    }

    fun openUrl(context: Context, url: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
