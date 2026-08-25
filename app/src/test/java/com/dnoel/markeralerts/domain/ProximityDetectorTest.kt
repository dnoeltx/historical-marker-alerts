package com.dnoel.markeralerts.domain

import com.dnoel.markeralerts.data.MarkerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `testing mode announces what is already in range at the start`() {
        val detector = ProximityDetector(radiusMeters = 1000.0, announceAtStart = true)
        val near = marker("near", northOf(39.0, 100.0), -105.0)
        val far = marker("far", northOf(39.0, 800.0), -105.0)

        val alerts = detector.observe(39.0, -105.0, listOf(near, far))

        // The whole point is exercising the app without driving, so the per-fix
        // cap is deliberately not applied here — one alert would be a weak test.
        assertEquals(listOf("near", "far"), alerts.map { it.marker.geomId })
    }

    @Test
    fun `testing mode still ignores anything outside the radius`() {
        val detector = ProximityDetector(radiusMeters = 1000.0, announceAtStart = true)
        val inside = marker("in", northOf(39.0, 500.0), -105.0)
        val outside = marker("out", northOf(39.0, 2_000.0), -105.0)

        val alerts = detector.observe(39.0, -105.0, listOf(inside, outside))

        assertEquals(listOf("in"), alerts.map { it.marker.geomId })
    }

    @Test
    fun `testing mode does not repeat itself on later fixes`() {
        val detector = ProximityDetector(radiusMeters = 1000.0, announceAtStart = true)
        val here = marker("a", northOf(39.0, 100.0), -105.0)

        assertEquals(1, detector.observe(39.0, -105.0, listOf(here)).size)

        // Sitting still on a sofa produces fix after fix from the same spot.
        // Announcing again each time would be unbearable.
        assertTrue(detector.observe(39.0, -105.0, listOf(here)).isEmpty())
        assertTrue(detector.observe(39.0, -105.0, listOf(here)).isEmpty())
    }

    @Test
    fun `a configured radius is what decides range`() {
        val target = marker("a", northOf(39.0, 2_500.0), -105.0)

        // 2.5 km away: inside a 3 km radius, outside a 1 km one.
        val wide = ProximityDetector(radiusMeters = 3_000.0, announceAtStart = true)
        val narrow = ProximityDetector(radiusMeters = 1_000.0, announceAtStart = true)

        assertEquals(1, wide.observe(39.0, -105.0, listOf(target)).size)
        assertTrue(narrow.observe(39.0, -105.0, listOf(target)).isEmpty())
    }

    @Test
    fun `without a course an alert reports no off-route distance`() {
        // Every caller that predates courses, and every fix taken while
        // stopped. Null must mean "unknown", never "zero".
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val target = marker("a", northOf(39.0, 1500.0), -105.0)
        detector.primeAwayFrom(listOf(target))

        val alerts = detector.observe(northOf(39.0, 550.0), -105.0, listOf(target))

        assertEquals(1, alerts.size)
        assertNull(alerts.first().offRouteMeters)
    }

    @Test
    fun `driving straight at a marker reports it as on route`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val target = marker("a", northOf(39.0, 1500.0), -105.0)
        detector.primeAwayFrom(listOf(target))

        // Heading due north, with the marker due north of us.
        val alerts = detector.observe(
            northOf(39.0, 550.0),
            -105.0,
            listOf(target),
            courseDegrees = 0.0,
        )

        assertEquals(1, alerts.size)
        assertEquals(0.0, alerts.first().offRouteMeters!!, 1.0)
    }

    @Test
    fun `a marker square to the side is fully off route`() {
        val detector = ProximityDetector(radiusMeters = 1000.0)
        val target = marker("a", northOf(39.0, 1500.0), -105.0)
        detector.primeAwayFrom(listOf(target))

        // Same geometry, but now driving east: the marker is off the left
        // shoulder rather than up the road, and the same 950 m distance means
        // something completely different to a driver.
        val alerts = detector.observe(
            northOf(39.0, 550.0),
            -105.0,
            listOf(target),
            courseDegrees = 90.0,
        )

        assertEquals(1, alerts.size)
        val alert = alerts.first()
        assertEquals(alert.distanceMeters, alert.offRouteMeters!!, 1.0)
    }

    @Test
    fun `the same marker at the same distance can be on route or not`() {
        // The point of the whole feature, in one assertion: distance is fixed
        // by when alerts fire, so it cannot tell these two apart. Direction can.
        fun offRouteOn(course: Double): Double {
            val detector = ProximityDetector(radiusMeters = 1000.0)
            val target = marker("a", northOf(39.0, 1500.0), -105.0)
            detector.primeAwayFrom(listOf(target))
            return detector.observe(
                northOf(39.0, 550.0),
                -105.0,
                listOf(target),
                courseDegrees = course,
            ).first().offRouteMeters!!
        }

        assertTrue(offRouteOn(90.0) - offRouteOn(0.0) > 900.0)
    }
}
