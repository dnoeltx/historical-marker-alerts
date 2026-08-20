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
     */
    fun forMarker(marker: MarkerEntity): String {
        val blurb = marker.blurb?.trim().orEmpty()
        val lead = "${marker.name}."
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
