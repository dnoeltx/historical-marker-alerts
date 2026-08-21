package com.dnoel.markeralerts.ui

import com.dnoel.markeralerts.data.MarkerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MapLinkTest {

    private fun marker(
        name: String = "Paramount Theatre",
        lat: Double = 30.2685,
        lon: Double = -97.7423,
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
        lat = lat,
        lon = lon,
        alertable = true,
        wikiTitle = null,
        wikiUrl = null,
        blurb = null,
    )

    @Test
    fun `the uri positions the map and drops a labelled pin`() {
        assertEquals(
            "geo:30.268500,-97.742300?q=30.268500,-97.742300(Paramount Theatre)",
            MapLink.forMarker(marker()),
        )
    }

    @Test
    fun `it is never a navigation intent`() {
        val uri = MapLink.forMarker(marker())

        // google.navigation: would make Google Maps abandon an active route —
        // four hours into a drive to Denver, swapped for a three-mile detour.
        assertTrue(uri, uri.startsWith("geo:"))
        assertFalse(uri, uri.contains("navigation"))
    }

    @Test
    fun `a comma-decimal locale does not corrupt the coordinates`() {
        val original = Locale.getDefault()
        try {
            // Germany formats decimals with commas. Left to the default locale
            // this produces "geo:30,268500,-97,742300", which looks like a valid
            // URI and points nowhere — a bug that only ever appears on somebody
            // else's phone.
            Locale.setDefault(Locale.GERMANY)
            assertEquals(
                "geo:30.268500,-97.742300?q=30.268500,-97.742300(Paramount Theatre)",
                MapLink.forMarker(marker()),
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `parentheses in a name would end the label early, so they go`() {
        val uri = MapLink.forMarker(marker(name = "Old Mill (Ruins)"))

        assertTrue(uri, uri.endsWith("(Old Mill Ruins)"))
    }

    @Test
    fun `an ampersand would look like another query parameter`() {
        val uri = MapLink.forMarker(marker(name = "Smith & Sons Warehouse"))

        assertTrue(uri, uri.endsWith("(Smith  Sons Warehouse)"))
        assertFalse(uri.contains("&"))
    }

    @Test
    fun `a nameless site still gets a usable label`() {
        val uri = MapLink.forMarker(marker(name = "()"))

        assertTrue(uri, uri.endsWith("(Historical site)"))
    }

    @Test
    fun `southern and eastern hemispheres keep their signs`() {
        val uri = MapLink.forMarker(marker(lat = -33.8688, lon = 151.2093))

        assertEquals(
            "geo:-33.868800,151.209300?q=-33.868800,151.209300(Paramount Theatre)",
            uri,
        )
    }
}
