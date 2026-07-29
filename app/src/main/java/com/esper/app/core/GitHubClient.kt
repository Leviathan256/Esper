package com.esper.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A workflow run as shown in the app's run list. */
data class WorkflowRun(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val createdAt: String,
    val htmlUrl: String,
) {
    /** Single word suitable for a status chip. */
    val displayState: String
        get() = when {
            status != "completed" -> status.replace('_', ' ')
            else -> conclusion ?: "completed"
        }

    val isRunning: Boolean get() = status != "completed"
}

/** The build currently published on GitHub Releases. */
data class ReleaseInfo(
    val tag: String,
    val versionName: String,
    val versionCode: Long?,
    val gitSha: String?,
    val apkUrl: String?,
    val htmlUrl: String,
)

/**
 * Minimal GitHub REST client.
 *
 * Uses [HttpURLConnection] rather than pulling in an HTTP library — the app
 * makes three kinds of request and none of them justify the dependency.
 */
class GitHubClient(
    private val token: String,
    private val owner: String,
    private val repo: String,
) {
    /**
     * Kicks off the Claude workflow. Requires a token with `actions: write`.
     *
     * Returns the moment GitHub accepts the dispatch (HTTP 204); the run itself
     * takes a few seconds to appear, which is why the caller polls afterwards.
     */
    suspend fun dispatchClaude(
        workflowFile: String,
        ref: String,
        prompt: String,
        appContext: String,
    ): Result<Unit> = request(
        method = "POST",
        path = "/repos/$owner/$repo/actions/workflows/$workflowFile/dispatches",
        body = JSONObject().apply {
            put("ref", ref)
            put(
                "inputs",
                JSONObject().apply {
                    put("prompt", prompt)
                    put("app_context", appContext)
                    put("base_branch", ref)
                },
            )
        },
    ).map { }

    suspend fun recentRuns(workflowFile: String, limit: Int = 5): Result<List<WorkflowRun>> =
        request(
            method = "GET",
            path = "/repos/$owner/$repo/actions/workflows/$workflowFile/runs?per_page=$limit",
        ).mapCatching { body ->
            val runs = JSONObject(body.orEmpty()).optJSONArray("workflow_runs") ?: JSONArray()
            (0 until runs.length()).map { i ->
                val run = runs.getJSONObject(i)
                WorkflowRun(
                    id = run.optLong("id"),
                    name = run.optString("display_title").ifBlank { run.optString("name") },
                    status = run.optString("status", "unknown"),
                    conclusion = run.optString("conclusion").takeIf { it.isNotBlank() && it != "null" },
                    createdAt = run.optString("created_at"),
                    htmlUrl = run.optString("html_url"),
                )
            }
        }

    /**
     * Reads the published release for the given channel.
     *
     * Prefers the `esper-release.json` asset that CI uploads, since that
     * carries the exact versionCode. Falls back to the tag name when the asset
     * is missing (e.g. a release published before this feature existed).
     */
    suspend fun latestRelease(nightly: Boolean): Result<ReleaseInfo> {
        val path =
            if (nightly) "/repos/$owner/$repo/releases/tags/nightly"
            else "/repos/$owner/$repo/releases/latest"

        return request("GET", path).mapCatching { body ->
            val release = JSONObject(body.orEmpty())
            val tag = release.optString("tag_name")
            val assets = release.optJSONArray("assets") ?: JSONArray()

            var apkUrl: String? = null
            var manifestUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                when {
                    asset.optString("name").endsWith(".apk") ->
                        apkUrl = asset.optString("browser_download_url")
                    asset.optString("name") == "esper-release.json" ->
                        manifestUrl = asset.optString("browser_download_url")
                }
            }

            val manifest = manifestUrl?.let { url ->
                runCatching { JSONObject(fetchText(url)) }.getOrNull()
            }

            ReleaseInfo(
                tag = tag,
                versionName = manifest?.optString("versionName")?.takeIf { it.isNotBlank() }
                    ?: tag.removePrefix("v"),
                versionCode = manifest?.optLong("versionCode")?.takeIf { it > 0 },
                gitSha = manifest?.optString("gitSha")?.takeIf { it.isNotBlank() },
                apkUrl = apkUrl,
                htmlUrl = release.optString("html_url"),
            )
        }
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
    ): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(API_BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "Esper-Android")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }

            try {
                if (body != null) {
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                }

                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    throw IOException(explain(code, path, detail))
                }
                // 204 No Content is the success case for a dispatch.
                if (code == 204) null
                else connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Esper-Android")
        }
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Turns GitHub's status codes into something worth showing on a phone. */
    private fun explain(code: Int, path: String, detail: String?): String = when (code) {
        401 -> "GitHub rejected the token (401). Check it hasn't expired."
        403 -> if (detail?.contains("rate limit", ignoreCase = true) == true) {
            "GitHub rate limit hit (403). Add a token in Settings to raise it."
        } else {
            "Token lacks permission (403). It needs Actions: read & write on $owner/$repo."
        }
        404 -> "Not found (404): $path. Check the repo name, and that the " +
            "workflow file exists on the default branch."
        422 -> "GitHub rejected the request (422). Usually means the branch " +
            "doesn't exist or the workflow has no workflow_dispatch trigger."
        else -> "GitHub returned $code for $path${detail?.let { ": ${it.take(300)}" }.orEmpty()}"
    }

    companion object {
        private const val API_BASE = "https://api.github.com"
    }
}
