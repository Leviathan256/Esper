package com.esper.app.game

import com.esper.app.BuildConfig
import com.esper.engine.encounter.Hazard
import com.esper.engine.encounter.OverpassHazards
import com.esper.engine.geometry.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches roads, railways, water and private land around a point from the
 * public Overpass API (OpenStreetMap data). The engine builds the query and
 * parses the reply; this only moves bytes.
 *
 * One request per encounter seeding, never prefetched, so it stays well inside
 * Overpass's fair-use limits. Uses [HttpURLConnection] like `core/GitHubClient`
 * rather than adding an HTTP library.
 */
object HazardSource {

    // shortcut: public community instance; move to a self-hosted Overpass alongside the tile source before scale.
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"

    suspend fun around(center: GeoPoint, radiusMetres: Double): List<Hazard> = withContext(Dispatchers.IO) {
        val body = "data=" + URLEncoder.encode(OverpassHazards.query(center, radiusMetres), "UTF-8")
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            // Overpass asks clients to identify themselves; matches osmdroid's agent.
            setRequestProperty("User-Agent", BuildConfig.APPLICATION_ID)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Overpass returned HTTP $code")
            OverpassHazards.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
