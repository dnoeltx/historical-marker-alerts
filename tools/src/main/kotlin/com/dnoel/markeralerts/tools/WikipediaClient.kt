package com.dnoel.markeralerts.tools

import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Finds geotagged Wikipedia articles near a coordinate, and fetches the plain
 * text summary for one.
 *
 * The harvest is centred on markers rather than swept over a grid. Five states
 * is mostly empty desert, and a lattice fine enough to catch a marker in
 * downtown Denver would waste thousands of calls on rangeland. Querying around
 * the markers themselves — and skipping any marker already covered by an
 * earlier query circle — makes the cost scale with marker density instead of
 * land area.
 */
class WikipediaClient(
    private val http: HttpCache,
    private val json: Json,
) {
    /**
     * Counters, because a failed fetch used to be indistinguishable from a
     * marker that genuinely has no article. Both produced a non-alertable row
     * and neither said anything, which is how a harvest that lost half its
     * data reported success.
     */
    var geosearchFailures = 0
        private set

    /** Summaries that could not be fetched at all — network or HTTP failure. */
    var summaryFailures = 0
        private set

    /** Summaries fetched successfully but unusable: disambiguation, no text. */
    var summaryUnusable = 0
        private set

    /** Geotagged articles within [radiusMeters] of the point, nearest first. */
    fun geosearch(lat: Double, lon: Double, radiusMeters: Int): List<GeoResult> {
        val url = buildString {
            append("https://en.wikipedia.org/w/api.php?action=query&list=geosearch")
            append("&gscoord=").append(encode("$lat|$lon"))
            append("&gsradius=").append(radiusMeters)
            append("&gslimit=500&format=json")
        }
        val response = http.get(url)
        if (!response.ok) {
            geosearchFailures++
            return emptyList()
        }
        return runCatching { json.decodeFromString<GeoSearchResponse>(response.body) }
            .getOrNull()?.query?.geosearch.orEmpty()
    }

    /**
     * The REST summary endpoint's `extract` is already plain prose with no
     * wiki markup, which is exactly what a text-to-speech engine needs. Returns
     * null for disambiguation pages and anything without usable text.
     */
    fun summary(title: String): WikiSummary? {
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/" +
            encode(title.replace(' ', '_'))
        val response = http.get(url)
        if (!response.ok) {
            // A match that passed the name test and then lost its blurb to a
            // rate limit is a bug, not an absent article. Counted separately
            // from "fetched but unusable" so the two never get confused again.
            summaryFailures++
            return null
        }

        val summary = runCatching { json.decodeFromString<WikiSummary>(response.body) }.getOrNull()
        if (summary == null || summary.type == "disambiguation" || summary.extract.isNullOrBlank()) {
            summaryUnusable++
            return null
        }
        return summary
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)
}
