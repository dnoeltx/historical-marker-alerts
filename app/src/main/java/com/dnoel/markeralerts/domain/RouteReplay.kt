package com.dnoel.markeralerts.domain

import com.dnoel.markeralerts.data.MarkerEntity

/** An alert, placed on the drive that produced it. */
data class ReplayEvent(
    val alert: ProximityAlert,
    /** Index of the track point where it fired. */
    val pointIndex: Int,
    /** How far along the drive, in metres. */
    val metersTravelled: Double,
)

/**
 * Drives a [ProximityDetector] along a recorded or generated track and reports
 * every alert it would have produced.
 *
 * This exists because the acceptance test for this app is a 600-mile drive, and
 * that is not a loop anyone can iterate on. Replaying the same route at a desk
 * turns "does the alerting feel right?" into a question answerable in
 * milliseconds — how many alerts fire, how far apart, and whether a historic
 * district produces one useful interruption or seven useless ones.
 *
 * [candidates] is a lambda rather than a DAO so this stays free of Room: tests
 * can hand it a list, and the real app hands it a database query.
 */
class RouteReplay(
    private val detector: ProximityDetector,
    private val candidates: suspend (BoundingBox) -> List<MarkerEntity>,
) {
    suspend fun run(track: List<TrackPoint>): List<ReplayEvent> {
        val events = mutableListOf<ReplayEvent>()
        var travelled = 0.0

        track.forEachIndexed { index, point ->
            if (index > 0) {
                val previous = track[index - 1]
                travelled += haversineMeters(previous.lat, previous.lon, point.lat, point.lon)
            }

            val box = BoundingBox.around(point.lat, point.lon, detector.radiusMeters)
            val nearby = candidates(box)

            detector.observe(point.lat, point.lon, nearby).forEach { alert ->
                events += ReplayEvent(alert, index, travelled)
            }
        }
        return events
    }

    companion object {
        /** Gaps between consecutive alerts, in metres — the "storminess" of a drive. */
        fun gapsMeters(events: List<ReplayEvent>): List<Double> =
            events.zipWithNext().map { (a, b) -> b.metersTravelled - a.metersTravelled }
    }
}
