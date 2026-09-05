package com.esper.app.game

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Wraps `android.location.LocationManager`.
 *
 * Deliberately not Play Services' `FusedLocationProviderClient`: that resolves
 * from the blocked Google Maven and adds a Play-Services dependency this project
 * does not otherwise need. Framework GPS is sufficient given the design's own
 * leash-circle tolerance for GPS error.
 *
 * Every call is guarded by [ContextCompat.checkSelfPermission] *and* wrapped in
 * `try/catch (SecurityException)` — belt and suspenders; a missed guard is a
 * guaranteed crash the first time permission is denied (e.g. revoked between the
 * check and the call, or an OEM policy stricter than the manifest declares).
 */
class LocationProvider(private val context: Context) {

    private val locationManager: LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    private var activeListener: LocationListener? = null

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    /** Starts listening. [onFix] receives a position and the reported accuracy in metres. */
    fun start(onFix: (latitude: Double, longitude: Double, accuracyMetres: Float) -> Unit) {
        val manager = locationManager ?: return
        if (!hasPermission()) return

        // Never register twice: a second start() would orphan the first listener,
        // which stop() could then no longer remove.
        stop()

        // Explicit object, not a SAM lambda: onStatusChanged/onProviderEnabled/
        // onProviderDisabled only became `default` methods in API 30, and minSdk
        // is 26 — a SAM-converted listener leaves them abstract and ART throws
        // AbstractMethodError the first time the framework dispatches one.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onFix(location.latitude, location.longitude, location.accuracy)
            }

            @Deprecated("Required by LocationListener below API 30")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        activeListener = listener

        try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MILLIS,
                    MIN_DISTANCE_METRES,
                    listener,
                )
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MILLIS,
                    MIN_DISTANCE_METRES,
                    listener,
                )
            }
            // Emit whatever is already known immediately, rather than waiting for
            // the first fresh fix, so the leash circle appears without delay.
            val lastKnown = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastKnown?.let { onFix(it.latitude, it.longitude, it.accuracy) }
        } catch (_: SecurityException) {
            // Permission was revoked between the check above and this call, or the
            // OEM enforces a stricter policy than the manifest declares. Fail
            // quietly — the caller simply never receives a fix.
        }
    }

    fun stop() {
        val manager = locationManager ?: return
        val listener = activeListener ?: return
        try {
            manager.removeUpdates(listener)
        } catch (_: SecurityException) {
            // Same defensive reasoning as start().
        }
        activeListener = null
    }

    companion object {
        private const val MIN_TIME_MILLIS = 2000L
        private const val MIN_DISTANCE_METRES = 1f
    }
}
