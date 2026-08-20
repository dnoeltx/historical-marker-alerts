package com.dnoel.markeralerts.trip

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dnoel.markeralerts.data.MarkerDatabase
import com.dnoel.markeralerts.domain.BoundingBox
import com.dnoel.markeralerts.domain.ProximityDetector
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Watches position for the length of a trip and speaks up about what is coming.
 *
 * This is a foreground service started while the app is visible, which is what
 * lets it keep receiving location with the screen off while holding only
 * "While using the app" permission. It is the whole reason v1 never has to ask
 * for "Allow all the time".
 *
 * The service owns the [ProximityDetector] because the detector's memory *is*
 * the trip: which markers have already spoken, and which were passed by.
 */
class TripService : LifecycleService() {

    private val detector = ProximityDetector()
    private lateinit var locationSource: LocationSource

    override fun onCreate() {
        super.onCreate()
        TripNotifications.createChannels(this)
        locationSource = FusedLocationSource(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }

        // startForeground must happen within a few seconds of the start request
        // or the system kills the process with a ForegroundServiceDidNotStart
        // exception — so it goes first, before any database or GPS work.
        ServiceCompat.startForeground(
            this,
            TripNotifications.ONGOING_ID,
            TripNotifications.ongoing(this, alertCount = 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )

        TripState.startTrip()
        watchPosition()

        // START_NOT_STICKY: if the system kills us mid-drive, silently resuming
        // location tracking later without the user asking would be a surprise.
        return START_NOT_STICKY
    }

    private fun watchPosition() {
        val dao = MarkerDatabase.build(applicationContext).markerDao()

        lifecycleScope.launch {
            locationSource.positions().collectLatest { point ->
                TripState.recordFix(point)

                val box = BoundingBox.around(point.lat, point.lon, detector.radiusMeters)
                val nearby = dao.alertableInBoundingBox(
                    box.minLat, box.maxLat, box.minLon, box.maxLon,
                )

                val alerts = detector.observe(point.lat, point.lon, nearby)
                alerts.forEach { announce(it.marker, it.distanceMeters) }
                TripState.recordSilenced(detector.suppressedMarkers())

                if (alerts.isNotEmpty()) refreshOngoing()
            }
        }
    }

    private fun announce(
        marker: com.dnoel.markeralerts.data.MarkerEntity,
        distanceMeters: Double,
    ) {
        TripState.recordAlert(
            TripAlert(marker, distanceMeters, System.currentTimeMillis()),
        )
        getSystemService(NotificationManager::class.java).notify(
            // A stable per-marker id so an alert never replaces a different one.
            marker.geomId.hashCode(),
            TripNotifications.alert(this, marker, distanceMeters),
        )
    }

    private fun refreshOngoing() {
        getSystemService(NotificationManager::class.java).notify(
            TripNotifications.ONGOING_ID,
            TripNotifications.ongoing(this, TripState.alerts.value.size),
        )
    }

    private fun stop() {
        TripState.endTrip()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        TripState.endTrip()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.dnoel.markeralerts.STOP_TRIP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TripService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TripService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
