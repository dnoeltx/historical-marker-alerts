package com.dnoel.markeralerts.domain

import com.dnoel.markeralerts.data.MarkerEntity

/** A marker that has just earned an alert, with how far away it was. */
data class ProximityAlert(
    val marker: MarkerEntity,
    val distanceMeters: Double,
)

/**
 * Decides which nearby markers deserve to interrupt the driver.
 *
 * Deliberately pure and stateful-in-memory: it takes a position and a list of
 * candidates and returns alerts. No Room, no Android, no clock. That is what
 * lets a 600-mile drive be replayed at a desk in milliseconds instead of
 * driven.
 *
 * One instance per trip — the dedupe state is the trip's memory.
 */
class ProximityDetector(
    private val radiusMeters: Double = DEFAULT_RADIUS_METERS,
) {
    /** Markers already handled this trip, whether alerted or passed by. */
    private val settled = mutableSetOf<String>()

    /** Last measured distance, for working out which way we are moving. */
    private val lastDistance = mutableMapOf<String, Double>()

    /**
     * [candidates] should be the alertable markers inside
     * [BoundingBox.around]`(lat, lon, radiusMeters)` — this refines that
     * rectangle to a true circle and applies the alerting rules.
     */
    fun observe(lat: Double, lon: Double, candidates: List<MarkerEntity>): List<ProximityAlert> {
        val alerts = mutableListOf<ProximityAlert>()

        for (marker in candidates) {
            if (marker.geomId in settled) continue

            val distance = haversineMeters(lat, lon, marker.lat, marker.lon)
            if (distance > radiusMeters) continue

            val previous = lastDistance.put(marker.geomId, distance)

            if (previous == null) {
                // First sighting. Direction is unknowable from one point, so
                // normally we wait for a second fix — except when the marker
                // appears near the edge of the circle, which means we have just
                // driven into range and are therefore approaching. Waiting in
                // that case would burn part of the warning distance.
                if (distance >= radiusMeters * EDGE_FRACTION) {
                    settled += marker.geomId
                    alerts += ProximityAlert(marker, distance)
                }
                continue
            }

            if (distance > previous) {
                // Moving away. Either we already passed it or the app started
                // with it behind us. Either way, saying something now would be
                // pointing at the rear-view mirror.
                settled += marker.geomId
                continue
            }

            settled += marker.geomId
            alerts += ProximityAlert(marker, distance)
        }

        // Closest first: if several land at once, the most imminent is the one
        // worth hearing about first.
        return alerts.sortedBy { it.distanceMeters }
    }

    /** Markers alerted or dismissed so far this trip. */
    fun settledCount(): Int = settled.size

    companion object {
        /** ~3 miles: about 2.5 minutes of warning at 70 mph. */
        const val DEFAULT_RADIUS_METERS = 4_800.0

        /**
         * How close to the radius a first sighting must be to count as
         * "just entered range" rather than "was already here".
         */
        private const val EDGE_FRACTION = 0.9
    }
}
