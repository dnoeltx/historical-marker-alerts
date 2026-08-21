package com.dnoel.markeralerts

import android.app.Application
import com.dnoel.markeralerts.trip.TripNotifications

/**
 * Exists for one reason: notification channels must be registered before the
 * user can ever see them.
 *
 * They used to be created in `TripService.onCreate`, which meant they did not
 * exist until the first trip was started. Until then the app appeared in system
 * notification settings with nothing to configure — so the alert sound, the
 * one setting a user is most likely to want to change, could not be changed
 * before the drive where it matters. Registering a channel is cheap and
 * idempotent, so app startup is the right place.
 */
class MarkerAlertsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TripNotifications.createChannels(this)
    }
}
