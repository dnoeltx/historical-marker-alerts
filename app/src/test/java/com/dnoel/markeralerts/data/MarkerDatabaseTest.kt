package com.dnoel.markeralerts.data

import com.dnoel.markeralerts.domain.BoundingBox
import com.dnoel.markeralerts.domain.haversineMeters
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Opens the real prepackaged database from assets.
 *
 * This is the test that proves the two halves agree: the schema `:tools` writes
 * by hand, and the schema Room compiles from [MarkerEntity]. If they drift —
 * a column type, a nullability, an index name, or a stale identity hash — Room
 * refuses to open the file and every test here fails at once.
 *
 * sdk 34 because compileSdk 37 outruns what Robolectric supports.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkerDatabaseTest {

    private lateinit var db: MarkerDatabase
    private lateinit var dao: MarkerDao

    @Before
    fun setUp() {
        db = MarkerDatabase.build(RuntimeEnvironment.getApplication())
        dao = db.markerDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `room opens the prepackaged asset`() = runTest {
        // Any schema or identity-hash mismatch fails here, not subtly later.
        val count = dao.count()
        assertTrue("expected the five-state dataset, got $count rows", count > 7_000)
    }

    @Test
    fun `the alertable subset is a real filter`() = runTest {
        val total = dao.count()
        val alertable = dao.alertableCount()

        assertTrue("nothing is alertable — matching or the asset is broken", alertable > 500)
        assertTrue(
            "alertable ($alertable) should be a small fraction of $total; " +
                "if it is most of them the relevance filter has stopped filtering",
            alertable < total / 3,
        )
    }

    @Test
    fun `bounding box query returns only alertable rows inside the box`() = runTest {
        // Downtown Denver.
        val box = BoundingBox.around(39.7392, -104.9903, 4_800.0)
        val rows = dao.alertableInBoundingBox(box.minLat, box.maxLat, box.minLon, box.maxLon)

        assertTrue("expected some markers in central Denver", rows.isNotEmpty())
        rows.forEach {
            assertTrue("returned a non-alertable row: ${it.name}", it.alertable)
            assertTrue(it.lat in box.minLat..box.maxLat)
            assertTrue(it.lon in box.minLon..box.maxLon)
        }
    }

    @Test
    fun `every alertable row actually has something to say`() = runTest {
        val box = BoundingBox.around(30.2672, -97.7431, 4_800.0) // Austin
        val rows = dao.alertableInBoundingBox(box.minLat, box.maxLat, box.minLon, box.maxLon)

        assertTrue(rows.isNotEmpty())
        rows.forEach {
            assertNotNull("alertable but no blurb: ${it.name}", it.blurb)
            assertTrue("alertable but blank blurb: ${it.name}", it.blurb!!.isNotBlank())
            assertNotNull("alertable but no attribution: ${it.name}", it.wikiUrl)
        }
    }

    @Test
    fun `the bounding box over-selects and the circle refines it`() = runTest {
        val lat = 39.7392
        val lon = -104.9903
        val radius = 4_800.0
        val box = BoundingBox.around(lat, lon, radius)
        val rows = dao.alertableInBoundingBox(box.minLat, box.maxLat, box.minLon, box.maxLon)

        val inCircle = rows.count { haversineMeters(lat, lon, it.lat, it.lon) <= radius }

        // The rectangle's corners reach ~41% further than the circle, so in a
        // dense area it must return strictly more. If these were ever equal,
        // the refine step would be doing nothing and the radius would be a lie.
        assertTrue("box=${rows.size} circle=$inCircle", rows.size > inCircle)
    }

    @Test
    fun `a historic district keeps its contributing points`() = runTest {
        // Helper, Utah: one listing, seven separate buildings. This is the case
        // that broke the original schema when refnum was the primary key.
        val points = dao.byRefnum("79002491")
        assertEquals(7, points.size)
        assertEquals(7, points.map { it.geomId }.distinct().size)
    }
}
