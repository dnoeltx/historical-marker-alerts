package com.dnoel.markeralerts.trip

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The handful of settings a trip has, persisted so a choice made in the car
 * survives the app being swapped out at a gas station.
 *
 * SharedPreferences rather than DataStore: two booleans do not justify a
 * coroutine-based store, and this is read synchronously from
 * [TripService.onStartCommand], where a suspending read would mean starting a
 * trip that is briefly configured wrong.
 */
object TripPreferences {

    private const val FILE = "trip_prefs"
    private const val KEY_AUTO_SPEAK = "auto_speak"

    private val _autoSpeak = MutableStateFlow(false)

    /** When true, markers are read aloud as they alert, with no tap needed. */
    val autoSpeak: StateFlow<Boolean> = _autoSpeak.asStateFlow()

    /**
     * Off by default. Auto-speak is the mode this app is really for — a driver
     * should never be picking the phone up — but a stranger's first launch
     * should not immediately start talking. It is one switch away on the trip
     * screen, deliberately visible rather than buried in settings.
     */
    fun load(context: Context) {
        _autoSpeak.value = prefs(context).getBoolean(KEY_AUTO_SPEAK, false)
    }

    fun setAutoSpeak(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTO_SPEAK, enabled) }
        _autoSpeak.value = enabled
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
