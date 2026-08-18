package com.dnoel.markeralerts.domain

import com.dnoel.markeralerts.data.MarkerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityDetectorTest {

    private fun marker(id: String, lat: Double, lon: Double) = MarkerEntity(
        geomId = id, refnum = "12345678", name = "Marker $id", resType = "building",
        address = null, city = null, county = null, state = "COLORADO", certDate = null,
        lat = lat, lon = lon, alertable = true, wikiTitle = "T", wikiUrl = "U", blurb = "B",
    )

    /** Metres north of a base latitude, as a latitude. */
    private fun northOf(lat: Double, meters: Double) = lat + meters / 111_320.0

    @Test
    fun `a marker appearing at the edge alerts immediately`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val target = marker("a", northOf(39.0, 950.0), -105.0)

        val alerts = detector.observe(39.0, -105.0, listOf(target))

        assertEquals(1, alerts.size)
        assertEquals("a", alerts.single().marker.geomId)
    }

    @Test
    fun `a marker first seen well inside waits for a second fix`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val target = marker("a", northOf(39.0, 300.0), -105.0)

        // Only one point of data: we cannot tell approach from departure.
        assertTrue(detector.observe(39.0, -105.0, listOf(target)).isEmpty())

        // Second fix is closer, so we are approaching — alert now.
        val alerts = detector.observe(northOf(39.0, 100.0), -105.0, listOf(target))
        assertEquals(1, alerts.size)
    }

    @Test
    fun `a receding marker never alerts`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val target = marker("a", northOf(39.0, 300.0), -105.0)

        detector.observe(39.0, -105.0, listOf(target))
        // Moving south, away from it.
        val alerts = detector.observe(northOf(39.0, -100.0), -105.0, listOf(target))

        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `a marker alerts at most once per trip`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val target = marker("a", northOf(39.0, 950.0), -105.0)

        assertEquals(1, detector.observe(39.0, -105.0, listOf(target)).size)
        assertEquals(0, detector.observe(northOf(39.0, 100.0), -105.0, listOf(target)).size)
        assertEquals(0, detector.observe(northOf(39.0, 200.0), -105.0, listOf(target)).size)
    }

    @Test
    fun `markers outside the radius are ignored`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val target = marker("a", northOf(39.0, 5_000.0), -105.0)

        assertTrue(detector.observe(39.0, -105.0, listOf(target)).isEmpty())
        assertEquals(0, detector.settledCount())
    }

    @Test
    fun `simultaneous alerts come out closest first`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val far = marker("far", northOf(39.0, 950.0), -105.0)
        val near = marker("near", northOf(39.0, 910.0), -105.0)

        val alerts = detector.observe(39.0, -105.0, listOf(far, near))

        assertEquals(listOf("near", "far"), alerts.map { it.marker.geomId })
    }

    @Test
    fun `a marker behind you at trip start is dismissed not announced`() {
        // The app is opened mid-drive with a marker already 300 m back.
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val behind = marker("a", 39.0, -105.0)

        detector.observe(northOf(39.0, 300.0), -105.0, listOf(behind))
        val alerts = detector.observe(northOf(39.0, 600.0), -105.0, listOf(behind))

        assertTrue(alerts.isEmpty())
        assertEquals(1, detector.settledCount())
    }
}
