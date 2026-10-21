package com.esper.app.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.esper.app.BuildConfig

/**
 * App settings, with the GitHub token and the Esper session token kept in
 * keystore-backed encrypted prefs.
 *
 * The GitHub token is only ever used for api.github.com requests against the
 * configured repo, the session token only for the Esper server. Neither is ever
 * included in the app-state snapshot sent to Claude.
 */
class Settings(context: Context) {
    private val appContext = context.applicationContext

    private val securePrefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "esper_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        // Some devices have a broken/reset keystore. Rather than crash on
        // launch, fall back to plain prefs in a separate file and tell the
        // user, so they can decide whether to store a token at all.
        Log.w(TAG, "Encrypted prefs unavailable, falling back to plaintext", t)
        keystoreAvailable = false
        appContext.getSharedPreferences("esper_secure_fallback", Context.MODE_PRIVATE)
    }

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("esper_settings", Context.MODE_PRIVATE)

    var githubToken: String
        get() = securePrefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) {
            securePrefs.edit().putString(KEY_TOKEN, value.trim()).apply()
        }

    var repoOwner: String
        get() = prefs.getString(KEY_OWNER, null) ?: BuildConfig.REPO_OWNER
        set(value) {
            prefs.edit().putString(KEY_OWNER, value.trim()).apply()
        }

    var repoName: String
        get() = prefs.getString(KEY_REPO, null) ?: BuildConfig.REPO_NAME
        set(value) {
            prefs.edit().putString(KEY_REPO, value.trim()).apply()
        }

    var baseBranch: String
        get() = prefs.getString(KEY_BRANCH, null) ?: "main"
        set(value) {
            prefs.edit().putString(KEY_BRANCH, value.trim()).apply()
        }

    /** Esper server bearer token (docs/SERVER.md §6); null when signed out. */
    var sessionToken: String?
        get() = if (!keystoreAvailable) memorySession else securePrefs.getString(KEY_SESSION, null)?.takeIf { it.isNotBlank() }
        set(value) {
            // No keystore: keep the 90-day bearer in memory only, never in plaintext prefs.
            if (!keystoreAvailable) {
                memorySession = value
                return
            }
            securePrefs.edit().apply { if (value == null) remove(KEY_SESSION) else putString(KEY_SESSION, value) }.apply()
        }

    /** Base URL of the Esper server, e.g. https://esper-x.duckdns.org. Blank = not configured. */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, null) ?: BuildConfig.SERVER_URL
        set(value) {
            val trimmed = value.trim().trimEnd('/')
            // A token must never be sent to a different host than the one that issued it.
            if (trimmed != serverUrl) sessionToken = null
            prefs.edit().putString(KEY_SERVER, trimmed).apply()
        }

    /** Track the nightly prerelease rather than the latest stable tag. */
    var followNightly: Boolean
        get() = prefs.getBoolean(KEY_NIGHTLY, BuildConfig.CHANNEL != "stable")
        set(value) {
            prefs.edit().putBoolean(KEY_NIGHTLY, value).apply()
        }

    val hasToken: Boolean get() = githubToken.isNotBlank()

    val repoSlug: String get() = "$repoOwner/$repoName"

    companion object {
        private const val TAG = "EsperSettings"
        private const val KEY_TOKEN = "github_token"
        private const val KEY_OWNER = "repo_owner"
        private const val KEY_REPO = "repo_name"
        private const val KEY_BRANCH = "base_branch"
        private const val KEY_NIGHTLY = "follow_nightly"
        private const val KEY_SESSION = "session_token"
        private const val KEY_SERVER = "server_url"

        /** False when the device keystore failed and we fell back to plain prefs. */
        @Volatile
        var keystoreAvailable: Boolean = true
            private set

        // Process-wide, so an Activity recreate does not sign the player out.
        private var memorySession: String? = null
    }
}
