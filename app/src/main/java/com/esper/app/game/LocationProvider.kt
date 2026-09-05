package com.esper.app.game

import android.content.Context

/**
 * Placeholder. Filled in by the `android-core-and-map` work package.
 *
 * Will wrap `android.location.LocationManager` (deliberately not Play Services'
 * fused provider, which resolves from Google's Maven and pulls in a dependency
 * this project does not otherwise need). Every call it makes must be guarded by
 * `ContextCompat.checkSelfPermission` *and* wrapped in `try/catch
 * (SecurityException)` — a missed guard is a guaranteed crash the first time
 * permission is denied.
 */
class LocationProvider(private val context: Context) {

    /** Starts listening. [onFix] receives a position and the reported accuracy in metres. */
    fun start(onFix: (latitude: Double, longitude: Double, accuracyMetres: Float) -> Unit) {
        TODO("implemented by android-core-and-map")
    }

    fun stop() {
        TODO("implemented by android-core-and-map")
    }
}
