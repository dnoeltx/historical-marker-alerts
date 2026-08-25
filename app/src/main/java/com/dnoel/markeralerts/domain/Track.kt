package com.dnoel.markeralerts.domain

/**
 * A position on a drive. Deliberately not Android's Location — this is domain data.
 *
 * [courseDegrees] and [speedMetersPerSecond] are nullable because they are not
 * always knowable: a cold-start fix has no course yet, and a replayed track has
 * no speed at all. Everything downstream must read a missing course as "say
 * nothing about direction" rather than substituting a default, which would be
 * confidently wrong instead of silent.
 */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    /** Course over ground, degrees clockwise from north. */
    val courseDegrees: Double? = null,
    val speedMetersPerSecond: Double? = null,
) {
    /**
     * The course, but only when it can be believed.
     *
     * Course over ground is derived from movement, so a stationary phone
     * reports whatever direction its last metre of GPS noise happened to point
     * — it swings wildly and means nothing. Below [MIN_COURSE_SPEED_MPS] the
     * course is discarded rather than trusted, which is what makes an alert in
     * a parking lot fall back to saying nothing about direction.
     *
     * A null speed is trusted: it means a replayed track, whose course was
     * computed geometrically and is exact.
     */
    val trustedCourseDegrees: Double?
        get() = courseDegrees?.takeIf {
            speedMetersPerSecond == null || speedMetersPerSecond >= MIN_COURSE_SPEED_MPS
        }

    companion object {
        /** ~11 mph. Below walking-into-traffic speed, course is noise. */
        const val MIN_COURSE_SPEED_MPS = 5.0
    }
}

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
     * Points every [stepMeters] along the polyline through [waypoints], each
     * stamped with the course it is travelling.
     *
     * Interpolation is linear in lat/lon rather than great-circle. Over a step
     * of a few hundred metres the difference is centimetres, and the harness
     * only needs plausible positions — not navigation-grade ones.
     *
     * The courses are what let the replay harness exercise direction-dependent
     * behaviour at a desk. Without them the only way to test anything that
     * depends on which way the car is pointing would be to drive it.
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
        return withCourses(points)
    }

    /** Total length of a track in metres. */
    fun lengthMeters(points: List<TrackPoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> haversineMeters(a.lat, a.lon, b.lat, b.lon) }

    /**
     * Stamps each point with the bearing toward the next one.
     *
     * The final point inherits the bearing of the leg that arrived at it — a
     * track's last fix has nowhere left to point, and leaving it null would
     * make the end of every replay silently untestable.
     */
    private fun withCourses(points: List<TrackPoint>): List<TrackPoint> {
        if (points.size < 2) return points
        return points.mapIndexed { index, point ->
            val from = if (index < points.lastIndex) point else points[index - 1]
            val to = if (index < points.lastIndex) points[index + 1] else point
            point.copy(courseDegrees = bearingDegrees(from.lat, from.lon, to.lat, to.lon))
        }
    }
}
