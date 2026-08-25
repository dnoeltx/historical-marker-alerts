package com.dnoel.markeralerts.speech

import com.dnoel.markeralerts.data.MarkerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wording a driver hears, which no compiler can check. */
class UtteranceTest {

    private fun marker(
        name: String = "Paramount Theatre",
        blurb: String? = "A 1915 theatre on Congress Avenue.",
    ) = MarkerEntity(
        geomId = "1",
        refnum = "76002022",
        name = name,
        resType = "Building",
        address = null,
        city = "Austin",
        county = "Travis",
        state = "TEXAS",
        certDate = null,
        lat = 30.2685,
        lon = -97.7423,
        alertable = true,
        wikiTitle = "Paramount Theatre (Austin, Texas)",
        wikiUrl = "https://en.wikipedia.org/wiki/Paramount_Theatre_(Austin,_Texas)",
        blurb = blurb,
    )

    @Test
    fun `the sentence leads with the name, not a distance`() {
        val text = Utterance.forMarker(marker())

        // Alerts fire on entry to the radius, so a spoken distance was always
        // "about 3 miles" — ten times in a row on the first real drive. A
        // number that never varies is not information.
        assertTrue(text, text.startsWith("Paramount Theatre."))
        assertFalse(text, text.contains("miles"))
    }

    @Test
    fun `Wikipedia is credited aloud`() {
        val text = Utterance.forMarker(marker())

        // The blurbs are CC BY-SA. A driver never sees the screen, so the
        // attribution has to be in the audio or it does not exist.
        assertTrue(text, text.endsWith("From Wikipedia."))
    }

    @Test
    fun `a marker with no blurb is not credited to Wikipedia`() {
        val text = Utterance.forMarker(marker(blurb = null))

        assertEquals("Paramount Theatre.", text)
        assertFalse(text.contains("Wikipedia"))
    }

    @Test
    fun `a blank blurb is treated as no blurb`() {
        val text = Utterance.forMarker(marker(blurb = "   "))

        assertEquals("Paramount Theatre.", text)
    }

    @Test
    fun `distances round to half miles`() {
        assertEquals("In about 3 miles", Utterance.distancePhrase(4_800.0))
        assertEquals("In about 2.5 miles", Utterance.distancePhrase(4_100.0))
        assertEquals("In about 2 miles", Utterance.distancePhrase(3_200.0))
    }

    @Test
    fun `one mile is singular`() {
        assertEquals("In about 1 mile", Utterance.distancePhrase(1_609.0))
    }

    @Test
    fun `anything under half a mile is just ahead`() {
        // "In about 0.5 miles" read out at 70 mph would already be wrong by the
        // time the sentence finished.
        assertEquals("Just ahead", Utterance.distancePhrase(700.0))
        assertEquals("Just ahead", Utterance.distancePhrase(0.0))
    }

    @Test
    fun `no known course leaves the sentence exactly as it was`() {
        // The guard on the whole feature. A cold-start fix, a phone sitting in
        // a driveway, or a replay without courses must produce the sentence
        // that shipped in v1 — not a direction invented from noise.
        assertEquals(
            Utterance.forMarker(marker()),
            Utterance.forMarker(marker(), offRouteMeters = null),
        )
        assertFalse(Utterance.forMarker(marker()).contains("route"))
    }

    @Test
    fun `a site on the road you are already on says so`() {
        val text = Utterance.forMarker(marker(), offRouteMeters = 40.0)

        assertTrue(text, text.startsWith("Paramount Theatre, right on your route."))
    }

    @Test
    fun `a short way off the line of travel is just off your route`() {
        // Between the on-route threshold and half a mile there is no number
        // worth saying — "just off" is the whole of the useful information.
        assertEquals("just off your route", Utterance.offRoutePhrase(400.0))
    }

    @Test
    fun `further out is spoken in half miles, in words`() {
        // "zero point five miles" is what a TTS engine does with 0.5, and it is
        // four syllables worse than "half a mile".
        assertEquals("about half a mile off your route", Utterance.offRoutePhrase(850.0))
        assertEquals("about a mile off your route", Utterance.offRoutePhrase(1_609.0))
        assertEquals("about 1.5 miles off your route", Utterance.offRoutePhrase(2_400.0))
        assertEquals("about 2 miles off your route", Utterance.offRoutePhrase(3_200.0))
    }

    @Test
    fun `the direction comes before the blurb, not after it`() {
        // A driver decides whether to care in the first second. Burying the
        // one useful fact behind a 300-character Wikipedia extract wastes it.
        val text = Utterance.forMarker(marker(), offRouteMeters = 40.0)

        assertTrue(text, text.indexOf("your route") < text.indexOf("A 1915 theatre"))
    }

    @Test
    fun `a marker with no blurb still gets its direction`() {
        assertEquals(
            "Paramount Theatre, right on your route.",
            Utterance.forMarker(marker(blurb = null), offRouteMeters = 10.0),
        )
    }

    @Test
    fun `Wikipedia is still credited when a direction is present`() {
        val text = Utterance.forMarker(marker(), offRouteMeters = 2_400.0)

        assertTrue(text, text.endsWith("From Wikipedia."))
    }
}
