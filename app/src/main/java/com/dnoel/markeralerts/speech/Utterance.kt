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
     * The blurbs are Wikipedia extracts under CC BY-SA, which requires
     * attribution. The detail screen carries the article URL; speech gets the
     * spoken equivalent, because a driver never sees the screen.
     */
    private const val ATTRIBUTION = "From Wikipedia."

    /**
     * [distanceMeters] is null when there is no meaningful distance to quote —
     * replaying a marker from the list an hour later, say, where "in about 3
     * miles" would be a lie.
     */
    fun forMarker(marker: MarkerEntity, distanceMeters: Double?): String {
        val blurb = marker.blurb?.trim().orEmpty()
        val lead = if (distanceMeters == null) {
            "${marker.name}."
        } else {
            "${distancePhrase(distanceMeters)}, ${marker.name}."
        }
        return if (blurb.isEmpty()) lead else "$lead $blurb $ATTRIBUTION"
    }

    /**
     * Distances are spoken in half-mile steps. The underlying number is a GPS
     * estimate against a coordinate that may itself be off by a few hundred
     * metres, so "in about 2.5 miles" is honest where "in 2.63 miles" is not.
     */
    fun distancePhrase(distanceMeters: Double): String {
        val miles = distanceMeters / METERS_PER_MILE
        if (miles < 0.5) return "Just ahead"

        val rounded = Math.round(miles * 2.0) / 2.0
        val number = if (rounded == Math.floor(rounded)) {
            rounded.toInt().toString()
        } else {
            "%.1f".format(rounded)
        }
        return "In about $number ${if (rounded == 1.0) "mile" else "miles"}"
    }
}
