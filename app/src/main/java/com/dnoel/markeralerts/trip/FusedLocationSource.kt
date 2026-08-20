package com.dnoel.markeralerts.trip

import android.annotation.SuppressLint
import android.content.Context
import com.dnoel.markeralerts.domain.TrackPoint
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Real position fixes from Google Play services.
 *
 * The caller is responsible for holding location permission — this class is
 * only ever constructed after the permission flow has succeeded, which is why
 * the lint check is suppressed rather than the call being wrapped in a
 * permission test that could only fail.
 */
class FusedLocationSource(
    context: Context,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) : LocationSource {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override fun positions(): Flow<TrackPoint> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            // At 70 mph the car covers ~31 m per second, so a fix that arrives
            // early is still useful; one that arrives late costs warning time.
            .setMinUpdateIntervalMillis(intervalMillis / 2)
            // Below this, consecutive fixes say nothing new and only cost power.
            .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(TrackPoint(it.latitude, it.longitude)) }
            }
        }

        client.requestLocationUpdates(request, callback, null)
        awaitClose { client.removeLocationUpdates(callback) }
    }

    companion object {
        /** Once every few seconds is plenty when alerts fire ~4.8 km out. */
        const val DEFAULT_INTERVAL_MILLIS = 4_000L
        const val MIN_DISTANCE_METERS = 50f
    }
}
