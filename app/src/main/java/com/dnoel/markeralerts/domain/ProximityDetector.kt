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
    val radiusMeters: Double = DEFAULT_RADIUS_METERS,
    /**
     * Most alerts allowed from a single position fix.
     *
     * Measured on a replay of Austin to Denver: without this the drive produced
     * 71 alerts whose *median* spacing was zero — they arrived in simultaneous
     * bursts on entering a city. Nobody can listen to eight blurbs at once, so
     * the closest one wins and the rest are dismissed. They remain visible in
     * the trip's list; they just do not speak.
     */
    private val maxAlertsPerFix: Int = 1,
    /**
     * When true, sites already within range at the first fix announce
     * themselves instead of being retired silently.
     *
     * Wrong for driving — it would announce the building you are parked next to
     * — but it is the only way to exercise the app without going anywhere,
     * which otherwise makes every change a 15-mile round trip to verify.
     */
    private val announceAtStart: Boolean = false,
) {
    /** Markers already handled this trip, whether alerted or passed by. */
    private val settled = mutableSetOf<String>()

    /** Last measured distance, for working out which way we are moving. */
    private val lastDistance = mutableMapOf<String, Double>()

    /** Markers seen but silenced — kept so the trip list can still show them. */
    private val suppressed = mutableListOf<MarkerEntity>()

    private var hadFirstFix = false

    /**
     * [candidates] should be the alertable markers inside
     * [BoundingBox.around]`(lat, lon, radiusMeters)` — this refines that
     * rectangle to a true circle and applies the alerting rules.
     */
    fun observe(lat: Double, lon: Double, candidates: List<MarkerEntity>): List<ProximityAlert> {
        val inRange = candidates
            .filter { it.geomId !in settled }
            .map { it to haversineMeters(lat, lon, it.lat, it.lon) }
            .filter { (_, distance) -> distance <= radiusMeters }

        // The very first fix of a trip describes where you already are, not
        // anything you are coming upon. Starting the app in downtown Austin
        // should not announce every listed building around the car; the trip is
        // about what you drive up on next. Everything already in range is
        // silently retired.
        if (!hadFirstFix) {
            hadFirstFix = true
            if (!announceAtStart) {
                inRange.forEach { (marker, _) ->
                    settled += marker.geomId
                    suppressed += marker
                }
                return emptyList()
            }
            // Testing mode announces everything in range at once, nearest
            // first, deliberately ignoring [maxAlertsPerFix]. The per-fix cap
            // exists to stop a driver being buried; someone sitting on a sofa
            // checking that alerts work wants the opposite — as much output as
            // possible from a single fix. SpeechQueue still reads them one at a
            // time and drops whatever goes stale.
            return inRange.sortedBy { it.second }.map { (marker, distance) ->
                settled += marker.geomId
                ProximityAlert(marker, distance)
            }
        }

        val candidateAlerts = mutableListOf<ProximityAlert>()

        for ((marker, distance) in inRange) {
            val previous = lastDistance.put(marker.geomId, distance)

            if (previous == null) {
                // First sighting. Direction is unknowable from one point, so
                // normally we wait for a second fix — except when the marker
                // appears near the edge of the circle, which means we have just
                // driven into range and are therefore approaching. Waiting in
                // that case would burn part of the warning distance.
                if (distance >= radiusMeters * EDGE_FRACTION) {
                    candidateAlerts += ProximityAlert(marker, distance)
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

            candidateAlerts += ProximityAlert(marker, distance)
        }

        // Closest first: if several land at once, the most imminent is the one
        // worth hearing about.
        val ranked = candidateAlerts.sortedBy { it.distanceMeters }
        val spoken = ranked.take(maxAlertsPerFix)
        val silenced = ranked.drop(maxAlertsPerFix)

        spoken.forEach { settled += it.marker.geomId }
        silenced.forEach {
            settled += it.marker.geomId
            suppressed += it.marker
        }
        return spoken
    }

    /** Markers alerted or dismissed so far this trip. */
    fun settledCount(): Int = settled.size

    /** Markers that were in range but never spoken — still worth listing. */
    fun suppressedMarkers(): List<MarkerEntity> = suppressed.toList()

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
