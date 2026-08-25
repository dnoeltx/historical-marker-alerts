package com.dnoel.markeralerts.domain

import com.dnoel.markeralerts.data.MarkerDao
import com.dnoel.markeralerts.data.MarkerDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Replays the actual drive this app was built for, against the actual shipped
 * database, and checks that the result is something a driver would want.
 *
 * These are the assertions that could not be written before M1 and M2 existed:
 * they are about the behaviour of real data on a real route, not about a
 * function in isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteReplayTest {

    private lateinit var db: MarkerDatabase
    private lateinit var dao: MarkerDao

    @Before
    fun setUp() {
        db = MarkerDatabase.build(RuntimeEnvironment.getApplication())
        dao = db.markerDao()
    }

    @After
    fun tearDown() = db.close()

    private fun replay(detector: ProximityDetector) = RouteReplay(detector) { box ->
        dao.alertableInBoundingBox(box.minLat, box.maxLat, box.minLon, box.maxLon)
    }

    /** Austin to Denver, roughly I-35 / US-287 / I-25. */
    private val austinToDenver = listOf(
        TrackPoint(30.2672, -97.7431),   // Austin, TX
        TrackPoint(31.5493, -97.1467),   // Waco
        TrackPoint(32.7555, -97.3308),   // Fort Worth
        TrackPoint(33.9137, -98.4934),   // Wichita Falls
        TrackPoint(35.2220, -101.8313),  // Amarillo
        TrackPoint(36.9034, -104.4392),  // Raton, NM
        TrackPoint(37.1695, -104.5005),  // Trinidad, CO
        TrackPoint(38.2544, -104.6091),  // Pueblo
        TrackPoint(38.8339, -104.8214),  // Colorado Springs
        TrackPoint(39.7392, -104.9903),  // Denver
    )

    @Test
    fun `the drive to Colorado produces a usable number of alerts`() = runTest {
        val track = Track.alongRoute(austinToDenver, stepMeters = 500.0)
        val events = replay(ProximityDetector()).run(track)

        val km = Track.lengthMeters(track) / 1000.0
        val gaps = RouteReplay.gapsMeters(events)

        println("route:  ${"%.0f".format(km)} km over ${track.size} fixes")
        println("alerts: ${events.size}  (one per ${"%.1f".format(km / events.size)} km)")
        println("gaps:   min=${"%.1f".format((gaps.minOrNull() ?: 0.0) / 1000)} km  " +
            "median=${"%.1f".format(median(gaps) / 1000)} km  " +
            "max=${"%.1f".format((gaps.maxOrNull() ?: 0.0) / 1000)} km")
        println("first 8:")
        events.take(8).forEach {
            println("  ${"%6.0f".format(it.metersTravelled / 1000)} km  " +
                "${"%4.0f".format(it.alert.distanceMeters)}m  ${it.alert.marker.name}")
        }

        assertTrue("the drive should surface something", events.isNotEmpty())
        // A ~1,300 km drive that alerts thousands of times is unusable; one that
        // alerts twice is pointless. This is a deliberately wide sanity band.
        assertTrue("suspiciously few alerts: ${events.size}", events.size >= 10)
        assertTrue("alert storm: ${events.size} over ${"%.0f".format(km)} km", events.size <= 400)
    }

    @Test
    fun `no marker is announced twice on one drive`() = runTest {
        val track = Track.alongRoute(austinToDenver, stepMeters = 500.0)
        val events = replay(ProximityDetector()).run(track)

        val ids = events.map { it.alert.marker.geomId }
        assertEquals("a marker was announced more than once", ids.size, ids.distinct().size)
    }

    @Test
    fun `everything announced has something to say`() = runTest {
        val track = Track.alongRoute(austinToDenver, stepMeters = 500.0)
        val events = replay(ProximityDetector()).run(track)

        events.forEach {
            val marker = it.alert.marker
            assertTrue("not alertable: ${marker.name}", marker.alertable)
            assertTrue("blank blurb: ${marker.name}", !marker.blurb.isNullOrBlank())
            assertTrue("no attribution: ${marker.name}", !marker.wikiUrl.isNullOrBlank())
            assertTrue("alerted beyond the radius", it.alert.distanceMeters <= 4_800.0)
        }
    }

    @Test
    fun `driving through a historic district is not an alert storm`() = runTest {
        // Helper, Utah: one listing, seven contributing buildings on one street.
        // This is the clustering case that the alertable filter is supposed to
        // keep tolerable — see how many actually speak up.
        val mainStreet = Track.alongRoute(
            listOf(TrackPoint(39.6600, -110.8560), TrackPoint(39.7100, -110.8540)),
            stepMeters = 100.0,
        )
        val events = replay(ProximityDetector()).run(mainStreet)

        val helper = events.filter { it.alert.marker.city == "Helper" }
        println("Helper, UT: ${helper.size} alerts from a 7-point district")
        helper.forEach { println("  - ${it.alert.marker.name}") }

        assertTrue(
            "a single district should not produce a barrage: ${helper.size}",
            helper.size <= 3,
        )
    }

    @Test
    fun `alerts do not arrive in simultaneous bursts`() = runTest {
        // Before the first-fix and burst rules, this same route produced 71
        // alerts whose median spacing was zero — a wall of speech on leaving
        // Austin, then silence. Spacing is the property that matters, not count.
        val track = Track.alongRoute(austinToDenver, stepMeters = 500.0)
        val events = replay(ProximityDetector()).run(track)
        val gaps = RouteReplay.gapsMeters(events)

        assertTrue("nothing to measure", gaps.isNotEmpty())
        assertTrue(
            "alerts fired at the same instant: min gap ${gaps.minOrNull()}m",
            (gaps.minOrNull() ?: 0.0) > 0.0,
        )
        assertTrue(
            "half the alerts are bunched together: median ${median(gaps)}m",
            median(gaps) >= 400.0,
        )
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    @Test
    fun `every alert on a real route knows how far off route it is`() = runTest {
        val detector = ProximityDetector(radiusMeters = 4_800.0)
        val track = Track.alongRoute(austinToDenver, stepMeters = 500.0)
        val events = replay(detector).run(track)

        assertTrue("no alerts to check", events.isNotEmpty())

        // Track.alongRoute stamps a course on every point, so there is no
        // excuse for an unknown one here. A null would mean the course stopped
        // being threaded through somewhere between the track and the detector.
        assertTrue(
            "some alerts had no off-route distance",
            events.all { it.alert.offRouteMeters != null },
        )
    }

    @Test
    fun `off-route distance never exceeds the alert radius`() = runTest {
        val radius = 4_800.0
        val detector = ProximityDetector(radiusMeters = radius)
        val track = Track.alongRoute(austinToDenver, stepMeters = 500.0)
        val events = replay(detector).run(track)

        // It is the side of a right triangle whose hypotenuse is the distance,
        // and the distance is inside the radius by definition. Anything larger
        // means the trigonometry is wrong, most likely a degrees/radians slip.
        val worst = events.maxOf { it.alert.offRouteMeters!! }
        assertTrue("worst off-route was $worst against a radius of $radius", worst <= radius)
    }

    @Test
    fun `off-route distance actually varies across a drive`() = runTest {
        val detector = ProximityDetector(radiusMeters = 4_800.0)
        val track = Track.alongRoute(austinToDenver, stepMeters = 500.0)
        val events = replay(detector).run(track)

        val offRoute = events.map { it.alert.offRouteMeters!! }
        val onRoute = offRoute.count { it < 150.0 }
        val wellOff = offRoute.count { it > 1_600.0 }

        // This is the assertion the spoken distance would have failed, and the
        // reason it was cut: alerts fire on entry to the radius, so distance is
        // always 90-100% of it and says nothing. A real drive must produce both
        // sites on the road ahead and sites well off to one side, or this
        // feature is repeating the same mistake with different words.
        assertTrue(
            "of ${offRoute.size} alerts, $onRoute were on route and $wellOff were a mile-plus off",
            onRoute > 0 && wellOff > 0,
        )
    }
}
