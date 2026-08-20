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
}
