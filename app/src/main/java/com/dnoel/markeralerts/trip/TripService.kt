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
import com.dnoel.markeralerts.data.MarkerEntity
import com.dnoel.markeralerts.domain.BoundingBox
import com.dnoel.markeralerts.domain.ProximityDetector
import com.dnoel.markeralerts.speech.Speech
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

    // Built in onStartCommand, once preferences are loaded: the radius and the
    // announce-at-start behaviour are both fixed for the length of a trip.
    private lateinit var detector: ProximityDetector
    private lateinit var locationSource: LocationSource

    override fun onCreate() {
        super.onCreate()
        // Channels are registered by MarkerAlertsApp at process start, so they
        // exist in system settings before a trip is ever run.
        locationSource = FusedLocationSource(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }

        // Silence stops the current blurb and empties the queue but leaves the
        // trip running — you still want to be told about the next site.
        if (intent?.action == ACTION_SILENCE) {
            Speech.clear()
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

        // Read synchronously: a trip that starts with the wrong auto-speak
        // setting and corrects itself a moment later is worse than a brief
        // disk read on a background-capable thread.
        TripPreferences.load(this)

        detector = ProximityDetector(
            radiusMeters = TripPreferences.radiusMeters,
            announceAtStart = TripPreferences.announceAtStart.value,
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

    private fun announce(marker: MarkerEntity, distanceMeters: Double) {
        TripState.recordAlert(
            TripAlert(marker, distanceMeters, System.currentTimeMillis()),
        )
        getSystemService(NotificationManager::class.java).notify(
            // A stable per-marker id so an alert never replaces a different one.
            marker.geomId.hashCode(),
            TripNotifications.alert(this, marker, distanceMeters),
        )

        // With auto-speak on the driver never touches the phone, which is the
        // point of the app. With it off the notification still fires and
        // tapping it reads the same sentence.
        if (TripPreferences.autoSpeak.value) {
            Speech.speak(this, marker)
        }
    }

    private fun refreshOngoing() {
        getSystemService(NotificationManager::class.java).notify(
            TripNotifications.ONGOING_ID,
            TripNotifications.ongoing(this, TripState.alerts.value.size),
        )
    }

    private fun stop() {
        // Nothing queued should outlive the trip that queued it.
        Speech.clear()
        TripState.endTrip()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Speech.clear()
        TripState.endTrip()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.dnoel.markeralerts.STOP_TRIP"
        const val ACTION_SILENCE = "com.dnoel.markeralerts.SILENCE"

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
