package com.esper.app.game

import android.content.Context
import com.esper.engine.content.ContentCatalog

/**
 * Placeholder. Filled in by the `android-core-and-map` work package.
 *
 * Reads the jobs and monsters shipped as APK assets (the repo's `/content`
 * directory, merged into assets by `app/build.gradle.kts`) and hands the raw text
 * to `ContentLoader.load` — the same loader the engine tests run against, so the
 * two consumers can never disagree about what the data means.
 *
 * Must never throw: it catches load failures, records [lastError], and returns
 * [ContentCatalog.EMPTY]. CI already fails a PR carrying malformed content, so this
 * is defence in depth rather than the primary check.
 */
object ContentRepository {

    /** Set when the last [catalog] call failed. Null when content loaded cleanly. */
    var lastError: String? = null
        private set

    /** Cached after the first successful load. */
    fun catalog(context: Context): ContentCatalog {
        TODO("implemented by android-core-and-map")
    }
}
