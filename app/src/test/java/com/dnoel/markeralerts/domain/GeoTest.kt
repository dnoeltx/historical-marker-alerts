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
}
