package com.dnoel.markeralerts.ui

import com.dnoel.markeralerts.data.MarkerEntity
import java.util.Locale

/**
 * Builds the `geo:` URI that shows a site on a map.
 *
 * **Deliberately not `google.navigation:`.** Firing turn-by-turn makes Google
 * Maps abandon whatever route is running — tap it four hours into a drive to
 * Denver and you have swapped that route for a three-mile detour, with no easy
 * way back while moving. A `geo:` pin changes nothing: the map shows where the
 * site is, and Google Maps puts its own Directions button on the pin, so
 * navigating is one deliberate extra tap rather than an accident.
 *
 * It also lands on the Android Auto display, since Maps is already projecting
 * there, which is where the decision to take the exit actually gets made.
 *
 * Pure string work so the format can be tested without a device.
 */
object MapLink {

    /**
     * `geo:<lat>,<lon>?q=<lat>,<lon>(<label>)`
     *
     * The coordinate appears twice on purpose. The part before `?` positions
     * the map; the `q` parameter is what actually drops a labelled pin. Give
     * only the first and most apps show the right area with nothing marked on
     * it, which for a building on a street corner is useless.
     */
    fun forMarker(marker: MarkerEntity): String {
        // Locale.US: a device set to a locale that formats decimals with commas
        // would otherwise produce "geo:30,2685,-97,7423" — a valid-looking URI
        // pointing nowhere. This is the sort of bug that only appears on
        // somebody else's phone.
        val lat = String.format(Locale.US, "%.6f", marker.lat)
        val lon = String.format(Locale.US, "%.6f", marker.lon)
        return "geo:$lat,$lon?q=$lat,$lon(${label(marker.name)})"
    }

    /**
     * Parentheses would terminate the label early and an ampersand would look
     * like another query parameter, so both are stripped rather than escaped —
     * percent-encoding inside the `q` label is handled inconsistently across
     * map apps, and a site name has no need of either character.
     */
    private fun label(name: String): String =
        name.replace(Regex("[()&]"), "").trim().ifEmpty { "Historical site" }
}
