package com.dnoel.markeralerts.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class GeoTest {

    @Test
    fun `one degree of latitude is about 111 km anywhere`() {
        assertEquals(111_200.0, haversineMeters(39.0, -105.0, 40.0, -105.0), 500.0)
        assertEquals(111_200.0, haversineMeters(29.0, -98.0, 30.0, -98.0), 500.0)
    }

    @Test
    fun `distance to the same point is zero`() {
        assertEquals(0.0, haversineMeters(30.2672, -97.7431, 30.2672, -97.7431), 0.001)
    }

    @Test
    fun `bounding box fully contains the circle it approximates`() {
        val lat = 39.7392
        val lon = -104.9903
        val radius = 4_800.0
        val box = BoundingBox.around(lat, lon, radius)

        // Due north, south, east and west at exactly the radius must all fall
        // inside the box, or the prefilter would silently drop real markers.
        val dLat = radius / 111_320.0
        val dLon = radius / (111_320.0 * cos(Math.toRadians(lat)))

        assertTrue(lat + dLat <= box.maxLat + 1e-9)
        assertTrue(lat - dLat >= box.minLat - 1e-9)
        assertTrue(lon + dLon <= box.maxLon + 1e-9)
        assertTrue(lon - dLon >= box.minLon - 1e-9)
    }

    @Test
    fun `longitude span widens with latitude`() {
        // The bug this guards: using a fixed degrees-per-metre for longitude.
        // In Denver that box would be ~23% too narrow and would miss markers.
        val denver = BoundingBox.around(39.7392, -104.9903, 4_800.0)
        val austin = BoundingBox.around(30.2672, -97.7431, 4_800.0)

        val denverWidth = denver.maxLon - denver.minLon
        val austinWidth = austin.maxLon - austin.minLon
        assertTrue(
            "Denver is further north so its degrees of longitude are narrower, " +
                "meaning it needs a WIDER span in degrees",
            denverWidth > austinWidth,
        )
    }

    @Test
    fun `latitude span does not change with latitude`() {
        val denver = BoundingBox.around(39.7392, -104.9903, 4_800.0)
        val austin = BoundingBox.around(30.2672, -97.7431, 4_800.0)
        assertEquals(
            denver.maxLat - denver.minLat,
            austin.maxLat - austin.minLat,
            1e-9,
        )
    }

    @Test
    fun `bearing points to the cardinal directions`() {
        val lat = 39.0
        val lon = -105.0

        assertEquals(0.0, bearingDegrees(lat, lon, lat + 0.1, lon), 0.5)
        assertEquals(90.0, bearingDegrees(lat, lon, lat, lon + 0.1), 0.5)
        assertEquals(180.0, bearingDegrees(lat, lon, lat - 0.1, lon), 0.5)
        assertEquals(270.0, bearingDegrees(lat, lon, lat, lon - 0.1), 0.5)
    }

    @Test
    fun `bearing is reported in 0 to 360, never negative`() {
        // atan2 hands back negative angles for anything west of north, and a
        // course of -90 would compare wrongly against a compass reading.
        val bearing = bearingDegrees(39.0, -105.0, 39.05, -105.05)
        assertTrue("was $bearing", bearing in 0.0..360.0)
    }

    @Test
    fun `relative bearing wraps around north`() {
        // The case that catches naive subtraction: these are twenty degrees
        // apart, not three hundred and forty, and a road trip heading north
        // sits on this boundary for hours.
        assertEquals(20.0, relativeBearingDegrees(350.0, 10.0), 1e-9)
        assertEquals(-20.0, relativeBearingDegrees(10.0, 350.0), 1e-9)
    }

    @Test
    fun `relative bearing is signed left and right`() {
        assertEquals(0.0, relativeBearingDegrees(90.0, 90.0), 1e-9)
        assertEquals(45.0, relativeBearingDegrees(90.0, 135.0), 1e-9)
        assertEquals(-45.0, relativeBearingDegrees(90.0, 45.0), 1e-9)
    }

    @Test
    fun `something dead ahead is not off route at all`() {
        assertEquals(0.0, crossTrackMeters(4_800.0, 0.0), 1e-9)
    }

    @Test
    fun `something dead behind is not off route either`() {
        // It is straight down the line of travel, just the wrong way. The
        // detector retires receding markers, so this should never reach a
        // driver — but the geometry must not report a phantom detour if it does.
        assertEquals(0.0, crossTrackMeters(4_800.0, 180.0), 1e-9)
    }

    @Test
    fun `something square to the side is the full distance off route`() {
        assertEquals(4_800.0, crossTrackMeters(4_800.0, 90.0), 1e-9)
        assertEquals(4_800.0, crossTrackMeters(4_800.0, -90.0), 1e-9)
    }

    @Test
    fun `off route distance is never negative`() {
        // Left and right are equally far out of the way; the sign belongs to
        // the bearing, not to the detour.
        assertEquals(
            crossTrackMeters(1_000.0, 30.0),
            crossTrackMeters(1_000.0, -30.0),
            1e-9,
        )
    }

    @Test
    fun `a site just off the highway reads as nearly on route`() {
        // Five degrees off the nose at three miles is about 420 m to the side —
        // the courthouse you can see from the road, not a detour.
        val offRoute = crossTrackMeters(4_800.0, 5.0)
        assertTrue("was $offRoute", offRoute < 500.0)
    }
}
