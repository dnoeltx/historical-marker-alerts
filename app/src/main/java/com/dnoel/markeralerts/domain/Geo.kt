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
