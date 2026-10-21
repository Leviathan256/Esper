package com.esper.server

import com.esper.engine.content.ContentCatalog
import com.esper.engine.content.ContentLoader
import java.io.File

/**
 * Loads the same `content/` the APK ships, through the same [ContentLoader]
 * (mirrors engine's ShippedContentTest). A [com.esper.engine.content.ContentValidationException]
 * fails startup.
 */
object Content {
    fun load(dir: String): ContentCatalog {
        fun texts(sub: String): Map<String, String> {
            val d = File(dir, sub)
            val files = d.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?: error("content directory '${d.absolutePath}' is missing")
            return files.associate { it.name to it.readText() }
        }
        return ContentLoader.load(texts("jobs"), texts("monsters"))
    }
}
