package com.dnoel.markeralerts.domain

/** A position on a drive. Deliberately not Android's Location — this is domain data. */
data class TrackPoint(val lat: Double, val lon: Double)

/**
 * Builds a dense sequence of positions from a handful of waypoints.
 *
 * A GPS fix arrives roughly once a second, so a six-hour drive is ~20,000
 * points. Rather than record and ship such a file, the route is described by
 * its corners and filled in here at whatever spacing a test needs. That keeps
 * the harness deterministic and the repository free of large binary tracks.
 */
object Track {

    /**
     * Points every [stepMeters] along the polyline through [waypoints].
     *
     * Interpolation is linear in lat/lon rather than great-circle. Over a step
     * of a few hundred metres the difference is centimetres, and the harness
     * only needs plausible positions — not navigation-grade ones.
     */
    fun alongRoute(waypoints: List<TrackPoint>, stepMeters: Double): List<TrackPoint> {
        require(stepMeters > 0) { "stepMeters must be positive" }
        if (waypoints.size < 2) return waypoints

        val points = mutableListOf(waypoints.first())

        for (i in 0 until waypoints.lastIndex) {
            val from = waypoints[i]
            val to = waypoints[i + 1]
            val legMeters = haversineMeters(from.lat, from.lon, to.lat, to.lon)
            val steps = (legMeters / stepMeters).toInt().coerceAtLeast(1)

            for (step in 1..steps) {
                val fraction = step.toDouble() / steps
                points += TrackPoint(
                    lat = from.lat + (to.lat - from.lat) * fraction,
                    lon = from.lon + (to.lon - from.lon) * fraction,
                )
            }
        }
        return points
    }

    /** Total length of a track in metres. */
    fun lengthMeters(points: List<TrackPoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> haversineMeters(a.lat, a.lon, b.lat, b.lon) }
}
