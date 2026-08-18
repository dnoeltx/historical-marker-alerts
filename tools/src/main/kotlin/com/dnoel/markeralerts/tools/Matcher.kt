package com.dnoel.markeralerts.tools

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides whether a Wikipedia article is actually *about* a given National
 * Register record.
 *
 * Proximity alone is not enough: in a dense downtown there may be twenty
 * geotagged articles within 500 m of a listed building, and picking the nearest
 * would routinely attach the wrong story to the wrong place. Name agreement is
 * what makes the match trustworthy.
 *
 * Everything here is pure and deterministic, so the threshold can be tuned
 * against cached data without touching the network.
 */
object Matcher {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Words that carry no identifying signal. "House" and "Building" are
     * deliberately NOT in this list — "Barr House" vs "Barr Building" are
     * different places and the noun is what separates them.
     */
    private val STOPWORDS = setOf("the", "of", "and", "a", "an", "at", "in", "on", "for")

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Reduces a name to a bag of significant words.
     *
     * The National Register writes names inverted and comma-separated —
     * "Barr, William Braxton, House" — while Wikipedia writes them naturally:
     * "William Braxton Barr House". Comparing token *sets* makes word order
     * irrelevant, so the two forms match without any special-case parsing.
     */
    fun tokens(name: String): Set<String> = name
        .lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() && it !in STOPWORDS }
        .toSet()

    /** "Smith House (Austin, Texas)" -> "Smith House". */
    private fun stripDisambiguator(title: String): String =
        title.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "")

    /**
     * How much of the marker's name the article's title accounts for, in 0..1.
     *
     * Deliberately **directional**, and that asymmetry is the whole point.
     * Extra words in a Wikipedia title are almost always disambiguators, so
     * they are stripped and then ignored — "City National Bank Building" should
     * score a perfect match against "City National Bank Building (Houston)".
     * But words *missing* from the title are lost specificity, and those must
     * cost: "Cedar Fort School" against "Cedar Fort" is a school being matched
     * to the article about the town it sits in. Dividing by the shorter of the
     * two names scored that 1.00; dividing by the marker's own length scores it
     * 0.67 and correctly rejects it.
     */
    fun similarity(markerName: String, articleTitle: String): Double {
        val ta = tokens(markerName)
        val tb = tokens(stripDisambiguator(articleTitle))
        if (ta.isEmpty() || tb.isEmpty()) return 0.0

        // Numbers are identifiers, not adjectives. "Engine No. 20" and "Motor
        // No. 6" share every word that matters and are still different objects
        // sitting in the same museum yard. When both names are numbered and the
        // numbers disagree, that is decisive — no amount of word overlap should
        // outvote it.
        val na = ta.filter { it.all(Char::isDigit) }.toSet()
        val nb = tb.filter { it.all(Char::isDigit) }.toSet()
        if (na.isNotEmpty() && nb.isNotEmpty() && na.intersect(nb).isEmpty()) return 0.0

        val shared = ta.intersect(tb).size.toDouble()
        return shared / ta.size
    }

    /**
     * Picks the best article for [marker] from [candidates], or null if none
     * clears both bars. Ranks by name agreement first and distance second — a
     * strong name match 400 m away beats a weak one next door.
     */
    fun bestMatch(
        marker: Marker,
        candidates: List<GeoResult>,
        maxDistanceMeters: Double,
        minSimilarity: Double,
    ): Pair<GeoResult, Double>? =
        candidates
            .mapNotNull { candidate ->
                val distance = haversineMeters(marker.lat, marker.lon, candidate.lat, candidate.lon)
                if (distance > maxDistanceMeters) return@mapNotNull null
                val score = similarity(marker.name, candidate.title)
                if (score < minSimilarity) return@mapNotNull null
                Triple(candidate, score, distance)
            }
            .maxWithOrNull(
                compareBy<Triple<GeoResult, Double, Double>> { it.second }
                    .thenByDescending { it.third },
            )
            ?.let { it.first to it.second }

    /**
     * Chooses query centres so that every marker falls within [radiusMeters] of
     * one. Markers in a city collapse onto a single centre, which is what keeps
     * the call count proportional to density rather than to marker count.
     */
    fun coveringCentres(markers: List<Marker>, radiusMeters: Double): List<Marker> {
        val centres = mutableListOf<Marker>()
        // Sorting by latitude keeps nearby markers adjacent, so the early-exit
        // below fires almost immediately instead of scanning every centre.
        for (marker in markers.sortedBy { it.lat }) {
            val covered = centres.any { centre ->
                haversineMeters(centre.lat, centre.lon, marker.lat, marker.lon) <= radiusMeters
            }
            if (!covered) centres.add(marker)
        }
        return centres
    }

    /** Trims a summary to something that can be read aloud without droning. */
    fun trimBlurb(extract: String, maxChars: Int = 600): String {
        val clean = extract.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= maxChars) return clean
        // Prefer cutting at a sentence end so speech does not stop mid-thought.
        val cut = clean.take(maxChars)
        val lastStop = max(cut.lastIndexOf(". "), max(cut.lastIndexOf("! "), cut.lastIndexOf("? ")))
        return if (lastStop > maxChars / 2) cut.take(lastStop + 1) else "${cut.trimEnd()}…"
    }
}
