package com.dnoel.markeralerts.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

/** Great-circle distance in metres. */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val METERS_PER_DEGREE_LAT = 111_320.0

/**
 * A lat/lon rectangle that fully contains a circle of [radiusMeters] around a
 * point — the prefilter an index can actually use.
 *
 * The rectangle is always larger than the circle, most at its corners, so the
 * caller must still measure real distance afterwards. Over-selecting is
 * harmless; under-selecting would silently drop markers.
 */
data class BoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
) {
    companion object {
        fun around(lat: Double, lon: Double, radiusMeters: Double): BoundingBox {
            val dLat = radiusMeters / METERS_PER_DEGREE_LAT

            // Lines of longitude converge toward the poles, so a degree of
            // longitude is only cos(latitude) as wide as a degree of latitude.
            // Forgetting this makes the box too narrow everywhere except the
            // equator — in Colorado it would be ~23% too narrow and would drop
            // markers that are genuinely in range.
            val cosLat = cos(Math.toRadians(lat))
            val dLon = if (abs(cosLat) < 1e-9) {
                180.0 // At the poles every longitude is within reach.
            } else {
                radiusMeters / (METERS_PER_DEGREE_LAT * abs(cosLat))
            }

            return BoundingBox(
                minLat = lat - dLat,
                maxLat = lat + dLat,
                minLon = lon - dLon,
                maxLon = lon + dLon,
            )
        }
    }
}

/**
 * Initial great-circle bearing from one point to another, in degrees clockwise
 * from true north — 0 is north, 90 is east.
 *
 * "Initial" is a real caveat over long distances, where a great-circle path
 * turns as it goes, but every point this is asked about is inside the alert
 * radius of a few miles. At that range the drift is far below the precision of
 * the coordinates being compared.
 */
fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)

    val y = sin(dLon) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)

    // atan2 returns -180..180; drivers and compasses both think in 0..360.
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/**
 * Signed angle from a course to a target bearing, normalised to -180..180.
 * Negative lies to the left of travel, positive to the right.
 *
 * The normalisation is the entire point of this function. Heading 350 with a
 * target at 10 is twenty degrees off the nose, not three hundred and forty, and
 * subtracting the raw numbers gets that wrong in exactly the situation — due
 * north — that a road trip spends a lot of time in.
 */
fun relativeBearingDegrees(courseDegrees: Double, targetDegrees: Double): Double =
    (targetDegrees - courseDegrees + 540.0) % 360.0 - 180.0

/**
 * How far a target lies to the side of the current course: the perpendicular
 * distance from the line of travel, assuming travel continues straight.
 *
 * **This is not a road detour.** The real detour is at least twice this and
 * depends entirely on where the turnoffs are — a site 200 m off the line of
 * travel is still a ten-minute round trip if the next exit is five miles on.
 * What it honestly answers is "is this basically on my way, or is it not",
 * which is the question a driver is actually asking, and nothing more. The
 * wording it feeds ("off your route") is chosen to claim no more than that.
 */
fun crossTrackMeters(distanceMeters: Double, relativeBearingDegrees: Double): Double =
    abs(distanceMeters * sin(Math.toRadians(relativeBearingDegrees)))
