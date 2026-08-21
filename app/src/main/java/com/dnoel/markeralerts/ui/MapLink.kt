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
 * ## Known limitation, measured rather than assumed
 *
 * With turn-by-turn already running, Google Maps answers a `geo:` pin with
 * **"Exit navigation?"** before it will show the place. Verified on a Galaxy
 * S24 mid-route. A bare `geo:lat,lon?z=15` map view avoids the prompt, but Maps
 * then ignores the intent entirely and nothing happens — so there is no third
 * behaviour to reach for.
 *
 * The obvious fix — add the site as a stop, and let Maps say "adds 7 minutes" —
 * is not available to a third-party app:
 *
 *  - the standard Maps intents (`geo:`, `google.navigation:`) have no waypoint
 *    parameter at all
 *  - the `waypoints` parameter that does exist belongs to **Android Automotive
 *    OS** (Google built into the car), not Android Auto phone projection, and
 *    even there it starts a new trip rather than amending a running one
 *  - nothing exposes the current navigation destination, so the app could not
 *    reconstruct "existing route plus this stop" even if an intent accepted it
 *
 * Maps' own "Add stop" button is in-app UI, not an API. The real fix is showing
 * the site on a map inside this app, which is the v2 map screen.
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
