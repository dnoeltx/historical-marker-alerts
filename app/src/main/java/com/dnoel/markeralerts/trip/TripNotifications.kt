package com.dnoel.markeralerts.trip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dnoel.markeralerts.MainActivity
import com.dnoel.markeralerts.R
import com.dnoel.markeralerts.data.MarkerEntity

/**
 * The two notifications this app posts, and why they are different.
 *
 * The ongoing one is what the system demands in exchange for letting a
 * foreground service keep running with the screen off — it must be quiet and
 * permanent. The alert is the product: it needs to make a sound and push itself
 * in front of whatever is on screen, because the driver is not looking at the
 * phone.
 */
object TripNotifications {

    const val ONGOING_ID = 1
    private const val CHANNEL_ONGOING = "trip"
    private const val CHANNEL_ALERTS = "alerts"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        // IMPORTANCE_LOW: visible in the shade, silent. Anything higher would
        // buzz once per trip start for no reason.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                "Trip in progress",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while Marker Alerts is watching for historical sites."
                setShowBadge(false)
            },
        )

        // IMPORTANCE_HIGH earns a heads-up banner and a sound — the only way an
        // alert is any use at 70 mph.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Marker alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "A historical site is coming up."
            },
        )
    }

    fun ongoing(context: Context, alertCount: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setContentTitle("Trip in progress")
            .setContentText(
                if (alertCount == 0) "Watching for historical sites"
                else "$alertCount site${if (alertCount == 1) "" else "s"} so far",
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .addAction(0, "Stop trip", stopTrip(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun alert(context: Context, marker: MarkerEntity, distanceMeters: Double): Notification {
        val miles = distanceMeters / 1609.344
        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle(marker.name)
            .setContentText("${"%.1f".format(miles)} mi — tap to hear about it")
            // The blurb is long; BigTextStyle is what lets an expanded
            // notification show more than one line of it.
            .setStyle(NotificationCompat.BigTextStyle().bigText(marker.blurb.orEmpty()))
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(speak(context, marker))
            .build()
    }

    /** Distinct request codes keep PendingIntents from overwriting each other. */
    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun stopTrip(context: Context): PendingIntent =
        PendingIntent.getService(
            context,
            1,
            Intent(context, TripService::class.java).setAction(TripService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Tapping an alert opens the app pointed at that marker. In M4 this is what
     * triggers speech; for now it simply brings the detail into view.
     */
    private fun speak(context: Context, marker: MarkerEntity): PendingIntent =
        PendingIntent.getActivity(
            context,
            marker.geomId.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SPEAK_MARKER_ID, marker.geomId),
            PendingIntent.FLAG_IMMUTABLE,
        )
}
