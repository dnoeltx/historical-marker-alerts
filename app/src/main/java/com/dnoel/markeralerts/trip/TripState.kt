package com.dnoel.markeralerts.trip

import com.dnoel.markeralerts.data.MarkerEntity
import com.dnoel.markeralerts.domain.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A marker that spoke up, in the order it happened. */
data class TripAlert(
    val marker: MarkerEntity,
    val distanceMeters: Double,
    val atMillis: Long,
)

/**
 * The trip's live state, shared between the service that produces it and the
 * UI that displays it.
 *
 * A process-wide object rather than an injected singleton because dependency
 * injection has not arrived yet — Hilt is planned but deliberately deferred to
 * the milestone that first needs it. The seams are drawn so that swapping this
 * for an injected repository later touches the service and the ViewModel and
 * nothing else.
 */
object TripState {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _alerts = MutableStateFlow<List<TripAlert>>(emptyList())
    val alerts: StateFlow<List<TripAlert>> = _alerts.asStateFlow()

    private val _lastFix = MutableStateFlow<TrackPoint?>(null)
    val lastFix: StateFlow<TrackPoint?> = _lastFix.asStateFlow()

    private val _fixCount = MutableStateFlow(0)
    val fixCount: StateFlow<Int> = _fixCount.asStateFlow()

    /** Markers that were in range but stayed silent, so the list can still show them. */
    private val _silenced = MutableStateFlow<List<MarkerEntity>>(emptyList())
    val silenced: StateFlow<List<MarkerEntity>> = _silenced.asStateFlow()

    fun startTrip() {
        // A trip is a clean slate: the dedupe memory lives in the detector, and
        // the visible history should agree with it.
        _alerts.value = emptyList()
        _silenced.value = emptyList()
        _lastFix.value = null
        _fixCount.value = 0
        _running.value = true
    }

    fun endTrip() {
        _running.value = false
    }

    fun recordFix(point: TrackPoint) {
        _lastFix.value = point
        _fixCount.value += 1
    }

    fun recordAlert(alert: TripAlert) {
        // Newest first: the list is read at a glance, most often to see what
        // just spoke.
        _alerts.value = listOf(alert) + _alerts.value
    }

    fun recordSilenced(markers: List<MarkerEntity>) {
        _silenced.value = markers
    }
}
