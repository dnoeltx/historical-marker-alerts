package com.dnoel.markeralerts.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These cases are not hypothetical — every one of them is a real pair that
 * came out of the 40-marker smoke run, including the two false positives that
 * forced the threshold up to 0.8 and added the numeric rule.
 */
class MatcherTest {

    private fun marker(name: String, lat: Double = 39.0, lon: Double = -105.0) = Marker(
        geomId = "{TEST-${name.hashCode()}}",
        refnum = "00000001", name = name, resType = "building", address = null,
        city = null, county = null, state = "COLORADO", certDate = null, lat = lat, lon = lon,
    )

    private fun article(title: String, lat: Double = 39.0, lon: Double = -105.0) =
        GeoResult(pageid = 1, title = title, lat = lat, lon = lon)

    // --- distance ---

    @Test
    fun `haversine matches a known separation`() {
        // One degree of latitude is ~111.2 km anywhere on the globe.
        val meters = Matcher.haversineMeters(39.0, -105.0, 40.0, -105.0)
        assertEquals(111_200.0, meters, 500.0)
    }

    @Test
    fun `haversine is zero for the same point`() {
        assertEquals(0.0, Matcher.haversineMeters(30.2, -97.7, 30.2, -97.7), 0.001)
    }

    // --- tokenizing ---

    @Test
    fun `inverted register names tokenize the same as natural order`() {
        // The whole reason matching works: NRHP writes "Lyman, William and
        // Julia, House" where Wikipedia writes "William and Julia Lyman House".
        assertEquals(
            Matcher.tokens("William and Julia Lyman House"),
            Matcher.tokens("Lyman, William and Julia, House"),
        )
    }

    @Test
    fun `punctuation differences do not matter`() {
        assertEquals(
            Matcher.tokens("Shenandoah-Dives Mill"),
            Matcher.tokens("Shenandoah--Dives Mill"),
        )
    }

    // --- similarity ---

    @Test
    fun `a qualified wikipedia title still fully matches`() {
        // A trailing parenthetical is a disambiguator, not a difference.
        assertEquals(
            1.0,
            Matcher.similarity("City National Bank Building", "City National Bank Building (Houston)"),
            0.001,
        )
    }

    @Test
    fun `an article about the surrounding place is not a match`() {
        // "Cedar Fort" is the town; the marker is a school inside it. Scoring
        // by the shorter name rated this 1.00 and would have read out the
        // town's history when passing the school.
        val score = Matcher.similarity("Cedar Fort School", "Cedar Fort")
        assertTrue("a broader place must lose specificity credit, got $score", score < 0.8)
    }

    @Test
    fun `similarity is directional`() {
        // Marker -> article is not the same question as article -> marker.
        assertTrue(
            Matcher.similarity("Cedar Fort", "Cedar Fort School") >
                Matcher.similarity("Cedar Fort School", "Cedar Fort"),
        )
    }

    @Test
    fun `a typo in the register data costs only one token`() {
        val score = Matcher.similarity(
            "Warenski--Duvall Comerical Building and Apartments",
            "Warenski-Duvall Commercial Building and Apartments",
        )
        assertTrue("expected a strong but imperfect match, got $score", score in 0.75..0.99)
    }

    @Test
    fun `disagreeing numbers veto an otherwise strong match`() {
        // Real false positive: same railroad, same yard, different equipment.
        assertEquals(
            0.0,
            Matcher.similarity(
                "Rio Grande Southern Railroad Engine No. 20",
                "Rio Grande Southern Railroad, Motor No. 6",
            ),
            0.001,
        )
    }

    @Test
    fun `agreeing numbers do not veto`() {
        assertTrue(Matcher.similarity("Engine No. 20", "Engine No. 20 (Colorado)") > 0.9)
    }

    @Test
    fun `a number on only one side does not veto`() {
        // "Ash Fork Maintenance Camp #1" vs "Ash Fork station" must be rejected
        // by the threshold, not by the numeric rule — only one side is numbered.
        val score = Matcher.similarity("Ash Fork Maintenance Camp #1", "Ash Fork station")
        assertTrue("should score poorly but not be vetoed outright, got $score", score in 0.01..0.79)
    }

    // --- selection ---

    @Test
    fun `best match rejects everything below the threshold`() {
        val result = Matcher.bestMatch(
            marker = marker("Ash Fork Maintenance Camp #1"),
            candidates = listOf(article("Ash Fork station")),
            maxDistanceMeters = 500.0,
            minSimilarity = 0.8,
        )
        assertNull(result)
    }

    @Test
    fun `best match rejects an article that is too far away`() {
        val result = Matcher.bestMatch(
            marker = marker("Cedar Fort School", lat = 39.0, lon = -105.0),
            candidates = listOf(article("Cedar Fort School", lat = 39.1, lon = -105.0)),
            maxDistanceMeters = 500.0,
            minSimilarity = 0.8,
        )
        assertNull(result)
    }

    @Test
    fun `name agreement outranks proximity`() {
        val far = article("Cedar Fort School", lat = 39.0021, lon = -105.0)   // ~230m
        val near = article("Cedar Fort", lat = 39.0, lon = -105.0)            // 0m, weaker name
        val result = Matcher.bestMatch(
            marker = marker("Cedar Fort School"),
            candidates = listOf(near, far),
            maxDistanceMeters = 500.0,
            minSimilarity = 0.8,
        )
        assertNotNull(result)
        assertEquals("Cedar Fort School", result!!.first.title)
    }

    // --- harvest planning ---

    @Test
    fun `covering centres collapse a cluster into one query`() {
        val downtown = (0 until 20).map {
            marker("Building $it", lat = 39.0 + it * 0.0001, lon = -105.0)
        }
        val centres = Matcher.coveringCentres(downtown, radiusMeters = 10_000.0)
        assertEquals(1, centres.size)
    }

    @Test
    fun `covering centres keep far apart markers separate`() {
        val spread = listOf(
            marker("A", lat = 39.0, lon = -105.0),
            marker("B", lat = 39.5, lon = -105.0),
            marker("C", lat = 40.0, lon = -105.0),
        )
        val centres = Matcher.coveringCentres(spread, radiusMeters = 10_000.0)
        assertEquals(3, centres.size)
    }

    // --- blurb ---

    @Test
    fun `short blurbs pass through untouched`() {
        val text = "A historic church built in 1890."
        assertEquals(text, Matcher.trimBlurb(text))
    }

    @Test
    fun `long blurbs are cut at a sentence boundary`() {
        val text = "First sentence here. " + "Second sentence padding. ".repeat(40)
        val trimmed = Matcher.trimBlurb(text, maxChars = 100)
        assertTrue("should stop at a sentence end: '$trimmed'", trimmed.endsWith("."))
        assertTrue(trimmed.length <= 100)
    }
}
