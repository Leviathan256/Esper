package com.esper.app.core

import android.content.Context
import android.os.Build
import com.esper.app.BuildConfig
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Where the map is currently looking.
 *
 * Held outside the composable so the "Ask Claude" screen can report it without
 * the map having to stay composed.
 */
object MapState {
    @Volatile var latitude: Double = 0.0
    @Volatile var longitude: Double = 0.0
    @Volatile var zoom: Double = 3.5

    fun describe(): String = String.format(Locale.US, "%.5f, %.5f @ z%.1f", latitude, longitude, zoom)
}

/**
 * A snapshot of everything worth telling Claude about the running app.
 *
 * Deliberately excludes anything secret: no tokens, no account identifiers.
 * The whole thing is serialised into a public workflow input, so assume it
 * ends up visible in the Actions log.
 */
data class AppStateSnapshot(
    val versionName: String,
    val versionCode: Long,
    val channel: String,
    val gitSha: String,
    val applicationId: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val locale: String,
    val capturedAt: String,
    val mapPosition: String,
    val updateStatus: String,
    val lastCrash: String?,
    val recentEvents: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("versionName", versionName)
        put("versionCode", versionCode)
        put("channel", channel)
        put("gitSha", gitSha)
        put("applicationId", applicationId)
        put("device", device)
        put("android", "$androidRelease (API $sdkInt)")
        put("locale", locale)
        put("capturedAt", capturedAt)
        put("mapPosition", mapPosition)
        put("updateStatus", updateStatus)
        put("lastCrash", lastCrash ?: JSONObject.NULL)
        put("recentEvents", org.json.JSONArray(recentEvents))
    }

    /** Human-readable form, shown on screen so the user sees exactly what is sent. */
    fun toPrettyText(): String = buildString {
        appendLine("version:  $versionName ($versionCode, $channel)")
        appendLine("commit:   $gitSha")
        appendLine("device:   $device — Android $androidRelease (API $sdkInt)")
        appendLine("locale:   $locale")
        appendLine("map:      $mapPosition")
        appendLine("updates:  $updateStatus")
        appendLine("captured: $capturedAt")
        if (lastCrash != null) {
            appendLine()
            appendLine("last crash:")
            appendLine(lastCrash)
        }
        if (recentEvents.isNotEmpty()) {
            appendLine()
            appendLine("recent events:")
            recentEvents.forEach { appendLine("  - $it") }
        }
    }.trimEnd()

    companion object {
        fun capture(context: Context, updateStatus: String): AppStateSnapshot {
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return AppStateSnapshot(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                channel = BuildConfig.CHANNEL,
                gitSha = BuildConfig.GIT_SHA,
                applicationId = BuildConfig.APPLICATION_ID,
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidRelease = Build.VERSION.RELEASE ?: "unknown",
                sdkInt = Build.VERSION.SDK_INT,
                locale = Locale.getDefault().toLanguageTag(),
                capturedAt = iso.format(Date()),
                mapPosition = MapState.describe(),
                updateStatus = updateStatus,
                lastCrash = CrashLog.read(context),
                recentEvents = EventLog.snapshot(),
            )
        }
    }
}
