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
    private const val KEY_RADIUS_MILES = "radius_miles"
    private const val KEY_ANNOUNCE_AT_START = "announce_at_start"

    private const val METERS_PER_MILE = 1609.344

    /** The choices offered on the trip screen. 3 miles is the shipped default. */
    val RADIUS_CHOICES_MILES = listOf(1.0, 2.0, 3.0, 5.0)

    private val _autoSpeak = MutableStateFlow(false)

    /** When true, markers are read aloud as they alert, with no tap needed. */
    val autoSpeak: StateFlow<Boolean> = _autoSpeak.asStateFlow()

    private val _radiusMiles = MutableStateFlow(3.0)

    /** How far ahead a site announces itself. */
    val radiusMiles: StateFlow<Double> = _radiusMiles.asStateFlow()

    private val _announceAtStart = MutableStateFlow(false)

    /**
     * Normally a trip silently retires everything already within range on the
     * first fix, so you are not told about the building you are parked next to.
     * Turning this on announces them instead, which is the only way to see the
     * app work without driving anywhere.
     */
    val announceAtStart: StateFlow<Boolean> = _announceAtStart.asStateFlow()

    val radiusMeters: Double get() = _radiusMiles.value * METERS_PER_MILE

    /**
     * Auto-speak is off by default. It is the mode this app is really for, but
     * a stranger's first launch should not immediately start talking, and the
     * switch sits on the trip screen rather than buried in a menu.
     */
    fun load(context: Context) {
        val p = prefs(context)
        _autoSpeak.value = p.getBoolean(KEY_AUTO_SPEAK, false)
        _radiusMiles.value = p.getFloat(KEY_RADIUS_MILES, 3.0f).toDouble()
        _announceAtStart.value = p.getBoolean(KEY_ANNOUNCE_AT_START, false)
    }

    fun setAutoSpeak(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTO_SPEAK, enabled) }
        _autoSpeak.value = enabled
    }

    fun setRadiusMiles(context: Context, miles: Double) {
        prefs(context).edit { putFloat(KEY_RADIUS_MILES, miles.toFloat()) }
        _radiusMiles.value = miles
    }

    fun setAnnounceAtStart(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ANNOUNCE_AT_START, enabled) }
        _announceAtStart.value = enabled
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
