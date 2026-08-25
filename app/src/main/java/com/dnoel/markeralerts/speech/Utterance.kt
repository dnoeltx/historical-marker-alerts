package com.dnoel.markeralerts.speech

import com.dnoel.markeralerts.data.MarkerEntity

/**
 * Turns a marker into the sentence a driver hears.
 *
 * Pure string work, kept apart from the speech engine so the wording can be
 * tested without an Android device — wording is the part most likely to change,
 * and the part most likely to be wrong in a way only a human notices.
 */
object Utterance {

    private const val METERS_PER_MILE = 1609.344

    /**
     * Inside this, a site is on the road you are already on. GPS and the
     * National Register coordinate together are worth roughly this much
     * uncertainty anyway, so claiming any finer would be inventing precision.
     */
    private const val ON_ROUTE_METERS = 150.0

    /**
     * The blurbs are Wikipedia extracts under CC BY-SA, which requires
     * attribution. The detail screen carries the article URL; speech gets the
     * spoken equivalent, because a driver never sees the screen.
     */
    private const val ATTRIBUTION = "From Wikipedia."

    /**
     * The spoken distance was dropped after the first real drive.
     *
     * Alerts fire on *entry* to the radius, so every one is between 90% and
     * 100% of it — which rounded to half miles meant "In about 3 miles" every
     * single time, ten times in a row. A number that never varies is not
     * information, it is four syllables of throat-clearing in front of the part
     * you actually want. The exact distance is still on the notification and
     * the card, where it costs nothing and can be glanced at.
     *
     * [distancePhrase] survives as a separate function: it is still correct, it
     * is still tested, and a future mode may want to say a distance when it
     * genuinely differs. It just is not part of the standard alert.
     *
     * [offRouteMeters] is the fact that *does* vary — see [offRoutePhrase].
     * When it is null the course was unknown and the sentence is exactly what
     * it was before this existed.
     */
    fun forMarker(marker: MarkerEntity, offRouteMeters: Double? = null): String {
        val blurb = marker.blurb?.trim().orEmpty()
        val lead = if (offRouteMeters == null) {
            "${marker.name}."
        } else {
            "${marker.name}, ${offRoutePhrase(offRouteMeters)}."
        }
        return if (blurb.isEmpty()) lead else "$lead $blurb $ATTRIBUTION"
    }

    /**
     * How far to the side of the line of travel a site sits.
     *
     * This is the one spatial fact worth saying, because it is the one that
     * changes: two sites alerting at the same distance can be the courthouse on
     * the highway you are driving and a cemetery a mile down a farm road.
     *
     * The wording deliberately says "off your route" and never "detour" — the
     * real detour is at least twice this and depends on where the turnoffs are.
     * See `crossTrackMeters` in Geo.kt for what the number honestly means.
     */
    fun offRoutePhrase(offRouteMeters: Double): String {
        val miles = offRouteMeters / METERS_PER_MILE
        if (offRouteMeters < ON_ROUTE_METERS) return "right on your route"
        if (miles < 0.5) return "just off your route"

        val rounded = roundToHalfMiles(miles)
        val amount = when (rounded) {
            0.5 -> "half a mile"
            1.0 -> "a mile"
            else -> "${formatMiles(rounded)} miles"
        }
        return "about $amount off your route"
    }

    /**
     * Distances are spoken in half-mile steps. The underlying number is a GPS
     * estimate against a coordinate that may itself be off by a few hundred
     * metres, so "in about 2.5 miles" is honest where "in 2.63 miles" is not.
     */
    fun distancePhrase(distanceMeters: Double): String {
        val miles = distanceMeters / METERS_PER_MILE
        if (miles < 0.5) return "Just ahead"

        val rounded = roundToHalfMiles(miles)
        return "In about ${formatMiles(rounded)} ${if (rounded == 1.0) "mile" else "miles"}"
    }

    /** The shared rounding rule: both phrases owe the reader the same honesty. */
    private fun roundToHalfMiles(miles: Double): Double = Math.round(miles * 2.0) / 2.0

    private fun formatMiles(rounded: Double): String =
        if (rounded == Math.floor(rounded)) rounded.toInt().toString() else "%.1f".format(rounded)
}
