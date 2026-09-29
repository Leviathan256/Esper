package com.esper.app.game

import android.content.Context
import android.content.res.AssetManager
import com.esper.engine.content.ContentCatalog
import com.esper.engine.content.ContentLoader
import com.esper.engine.content.ContentValidationException
import java.io.IOException

/**
 * Reads the jobs and monsters shipped as APK assets (the repo's `/content`
 * directory, merged into assets by `app/build.gradle.kts`) and hands the raw text
 * to [ContentLoader.load] — the same loader the engine tests run against, so the
 * two consumers can never disagree about what the data means.
 *
 * Never throws: it catches load failures, records [lastError], and returns
 * [ContentCatalog.EMPTY]. CI already fails a PR carrying malformed content, so
 * this is defence in depth rather than the primary check.
 */
object ContentRepository {

    /** Set when the last [catalog] call failed. Null when content loaded cleanly. */
    var lastError: String? = null
        private set

    @Volatile
    private var cached: ContentCatalog? = null

    /** Cached after the first successful load. */
    fun catalog(context: Context): ContentCatalog {
        cached?.let { return it }

        val assets = context.applicationContext.assets
        return try {
            val jobTexts = readAssetTexts(assets, "jobs")
            val monsterTexts = readAssetTexts(assets, "monsters")
            val loaded = ContentLoader.load(jobTexts, monsterTexts)
            lastError = null
            cached = loaded
            loaded
        } catch (e: ContentValidationException) {
            lastError = e.message
            ContentCatalog.EMPTY
        } catch (e: IOException) {
            lastError = e.message ?: "Failed to read game content."
            ContentCatalog.EMPTY
        }
    }

    private fun readAssetTexts(assets: AssetManager, dir: String): Map<String, String> {
        val names = assets.list(dir) ?: emptyArray()
        return names
            .filter { it.endsWith(".json") }
            .associateWith { name ->
                assets.open("$dir/$name").bufferedReader().use { it.readText() }
            }
    }
}
