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

    /**
     * Consumes the trip's first fix somewhere nothing is in range.
     *
     * The first fix describes where the driver already is, so it retires
     * everything around them. Tests about *approaching* a marker have to get
     * past it first — which is also what really happens: you start the app, then
     * you drive somewhere.
     */
    private fun ProximityDetector.primeAwayFrom(markers: List<MarkerEntity>) {
        observe(0.0, 0.0, markers)
    }

    @Test
    fun `the first fix announces nothing`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val here = marker("a", northOf(39.0, 100.0), -105.0)

        // Starting the app in a town centre must not read out the whole town.
        assertTrue(detector.observe(39.0, -105.0, listOf(here)).isEmpty())
        assertEquals(listOf("a"), detector.suppressedMarkers().map { it.geomId })
    }

    @Test
    fun `a marker entering at the edge alerts immediately`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val target = marker("a", northOf(39.0, 1500.0), -105.0)
        detector.primeAwayFrom(listOf(target))

        // Now 950 m away: just crossed into range, so no need to wait.
        val alerts = detector.observe(northOf(39.0, 550.0), -105.0, listOf(target))

        assertEquals(1, alerts.size)
        assertEquals("a", alerts.single().marker.geomId)
    }

    @Test
    fun `a marker first seen well inside waits for a second fix`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val target = marker("a", northOf(39.0, 300.0), -105.0)
        detector.primeAwayFrom(listOf(target))

        // One data point cannot distinguish approach from departure.
        assertTrue(detector.observe(39.0, -105.0, listOf(target)).isEmpty())

        // Second fix is closer, so we are approaching.
        assertEquals(1, detector.observe(northOf(39.0, 100.0), -105.0, listOf(target)).size)
    }

    @Test
    fun `a receding marker never alerts`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val target = marker("a", northOf(39.0, 300.0), -105.0)
        detector.primeAwayFrom(listOf(target))

        detector.observe(39.0, -105.0, listOf(target))
        val alerts = detector.observe(northOf(39.0, -100.0), -105.0, listOf(target))

        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `a marker alerts at most once per trip`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val target = marker("a", northOf(39.0, 1500.0), -105.0)
        detector.primeAwayFrom(listOf(target))

        assertEquals(1, detector.observe(northOf(39.0, 550.0), -105.0, listOf(target)).size)
        assertEquals(0, detector.observe(northOf(39.0, 700.0), -105.0, listOf(target)).size)
        assertEquals(0, detector.observe(northOf(39.0, 800.0), -105.0, listOf(target)).size)
    }

    @Test
    fun `markers outside the radius are ignored entirely`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val target = marker("a", northOf(39.0, 5_000.0), -105.0)

        assertTrue(detector.observe(39.0, -105.0, listOf(target)).isEmpty())
        assertEquals(0, detector.settledCount())
        assertTrue(detector.suppressedMarkers().isEmpty())
    }

    @Test
    fun `when several arrive at once only the closest speaks`() {
        // The Austin problem: driving into a city puts a dozen listed buildings
        // in range within one fix. Nobody can hear eight blurbs at once.
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val far = marker("far", northOf(39.0, 2_000.0), -105.0)
        val near = marker("near", northOf(39.0, 1_960.0), -105.0)
        detector.primeAwayFrom(listOf(far, near))

        val alerts = detector.observe(northOf(39.0, 1_050.0), -105.0, listOf(far, near))

        assertEquals(listOf("near"), alerts.map { it.marker.geomId })
        assertTrue("the silenced one must still be listed",
            detector.suppressedMarkers().any { it.geomId == "far" })
    }

    @Test
    fun `the burst limit is configurable`() {
        val detector = ProximityDetector(radiusMeters = 1000.0, maxAlertsPerFix = 2)
        val a = marker("a", northOf(39.0, 2_000.0), -105.0)
        val b = marker("b", northOf(39.0, 1_960.0), -105.0)
        val c = marker("c", northOf(39.0, 1_980.0), -105.0)
        detector.primeAwayFrom(listOf(a, b, c))

        val alerts = detector.observe(northOf(39.0, 1_050.0), -105.0, listOf(a, b, c))

        assertEquals(listOf("b", "c"), alerts.map { it.marker.geomId })
    }

    @Test
    fun `a marker behind you at trip start is retired silently`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val behind = marker("a", 39.0, -105.0)

        detector.observe(northOf(39.0, 300.0), -105.0, listOf(behind))
        val alerts = detector.observe(northOf(39.0, 600.0), -105.0, listOf(behind))

        assertTrue(alerts.isEmpty())
        assertEquals(1, detector.settledCount())
    }
}
