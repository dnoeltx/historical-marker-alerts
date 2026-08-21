package com.dnoel.markeralerts.tools

import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Builds the marker database that ships in the APK.
 *
 *   ./gradlew :tools:run --args="--out app/src/main/assets/markers.db"
 *
 * Every HTTP response is cached under tools/scratch/http (gitignored), so the
 * first run is slow and every run after it is fast. Tuning --similarity and
 * re-running costs nothing but CPU.
 *
 * To actually refresh the data — which is a manual step before a release, since
 * the database ships inside the APK:
 *
 *   ./gradlew :tools:run --args="--refresh --max-age-days 90"
 *
 * --refresh re-fetches the National Register (four requests, seconds) because
 * its URL never changes and would otherwise be served from cache forever,
 * rebuilding an identical database while reporting success. --max-age-days
 * additionally ages out Wikipedia responses; leaving it off keeps the
 * expensive harvest cached.
 *
 * Options: --states --out --harvest --radius --similarity --limit --refresh
 *          --max-age-days
 */
fun main(args: Array<String>) {
    val opts = Options.parse(args)

    opts.benchmark?.let {
        Benchmark.run(it)
        return
    }

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Wikimedia's User-Agent policy asks for a real contact address so they can
    // get in touch instead of silently blocking an unidentified client.
    val http = HttpCache(
        cacheDir = Path.of("tools/scratch/http"),
        userAgent = "historical-marker-alerts/0.1 (https://github.com/dnoeltx/historical-marker-alerts; dnoeltx@gmail.com)",
        maxAge = opts.maxAgeDays?.let { Duration.ofDays(it) },
    )

    val nrhp = NrhpClient(http, json)
    val wikipedia = WikipediaClient(http, json)

    println("== 1/4  Fetching National Register records ==")
    println("   states: ${opts.states.joinToString(", ")}")
    if (opts.refresh) println("   --refresh: bypassing cache for the National Register fetch")
    opts.maxAgeDays?.let { println("   cache entries older than $it days will be refetched") }
    var markers = nrhp.fetchStates(opts.states, refresh = opts.refresh)
    if (opts.limit > 0) markers = markers.take(opts.limit)
    println("   ${markers.size} markers with usable coordinates")

    // Validate the primary key BEFORE the expensive matching pass. The first
    // full run spent 7m49s fetching and matching, then died on a duplicate key
    // at the final insert. Checking here costs nothing and fails in seconds.
    val duplicateIds = markers.groupingBy { it.geomId }.eachCount().filterValues { it > 1 }
    if (duplicateIds.isNotEmpty()) {
        System.err.println("geomId is not unique — ${duplicateIds.size} duplicated values, " +
            "e.g. ${duplicateIds.keys.take(3)}. The schema assumes one row per location.")
        exitProcess(1)
    }
    val districts = markers.groupingBy { it.refnum }.eachCount().filterValues { it > 1 }
    println("   ${districts.size} listings contribute more than one point " +
        "(largest: ${districts.values.maxOrNull() ?: 0} points)")

    println()
    println("== 2/4  Planning Wikipedia harvest ==")
    val centres = Matcher.coveringCentres(markers, opts.harvestRadiusMeters)
    println("   ${centres.size} query centres cover ${markers.size} markers " +
        "(${"%.1f".format(markers.size.toDouble() / centres.size)} markers per call)")

    println()
    println("== 3/4  Harvesting and matching ==")
    val articlesByCentre = mutableListOf<GeoResult>()
    centres.forEachIndexed { index, centre ->
        articlesByCentre += wikipedia.geosearch(
            centre.lat, centre.lon, opts.harvestRadiusMeters.toInt(),
        )
        if ((index + 1) % 100 == 0 || index == centres.lastIndex) {
            println("   centre ${index + 1}/${centres.size}  " +
                "articles=${articlesByCentre.size}  " +
                "cache hits=${http.hits} misses=${http.misses}")
        }
    }
    val articles = articlesByCentre.distinctBy { it.pageid }
    println("   ${articles.size} distinct geotagged articles in range")

    val enriched = markers.mapIndexed { index, marker ->
        // Only articles that could plausibly be within reach are worth scoring.
        val nearby = articles.filter {
            Matcher.haversineMeters(marker.lat, marker.lon, it.lat, it.lon) <= opts.matchRadiusMeters
        }
        val best = Matcher.bestMatch(marker, nearby, opts.matchRadiusMeters, opts.minSimilarity)

        val match = best?.let { (article, score) ->
            wikipedia.summary(article.title)?.let { summary ->
                WikiMatch(
                    title = summary.title ?: article.title,
                    url = summary.contentUrls?.desktop?.page
                        ?: "https://en.wikipedia.org/wiki/${article.title.replace(' ', '_')}",
                    blurb = Matcher.trimBlurb(summary.extract.orEmpty()),
                    distanceMeters = Matcher.haversineMeters(
                        marker.lat, marker.lon, article.lat, article.lon,
                    ),
                    similarity = score,
                )
            }
        }
        if ((index + 1) % 500 == 0) println("   matched ${index + 1}/${markers.size}")
        EnrichedMarker(marker, match)
    }

    println()
    println("== 4/4  Writing database ==")
    val identityHash = RoomSchema.readIdentityHash(opts.schema)
    if (identityHash == null) {
        println("   WARNING: no schema at ${opts.schema} — writing an unstamped database.")
        println("   Room will refuse to open it. Build :app first so Room exports its schema.")
    } else {
        println("   stamping room_master_table with identityHash $identityHash")
    }
    DatabaseWriter(opts.output).write(enriched, roomIdentityHash = identityHash)

    report(enriched, opts, http, wikipedia)

    if (enriched.none { it.alertable }) {
        System.err.println("No marker is alertable — matching is broken, not merely strict.")
        exitProcess(1)
    }

    // The harvest must be complete, not merely finished.
    //
    // The first release shipped a database built from a run where 1,414 of
    // 3,157 requests were rate-limit rejections. Roughly a third of the
    // geosearch centres returned nothing and about half the summaries were
    // dropped, and the run said "936 alertable (13.2%)" and exited zero. A
    // partial harvest is worse than a failed one, because it looks like data.
    val failures = wikipedia.geosearchFailures + wikipedia.summaryFailures
    if (failures > 0 || http.givenUp > 0) {
        System.err.println()
        System.err.println("HARVEST INCOMPLETE — refusing to write a database that would look fine.")
        System.err.println("  geosearch centres that failed : ${wikipedia.geosearchFailures}")
        System.err.println("  summaries that failed to fetch: ${wikipedia.summaryFailures}")
        System.err.println("  requests that exhausted retries: ${http.givenUp}")
        System.err.println("  server pushback (429/5xx) events: ${http.throttled}")
        System.err.println()
        System.err.println("Rerun to pick up where it stopped — successful responses are cached,")
        System.err.println("and failures are not, so a rerun only retries what actually failed.")
        exitProcess(1)
    }
}

private fun report(
    enriched: List<EnrichedMarker>,
    opts: Options,
    http: HttpCache,
    wikipedia: WikipediaClient,
) {
    val alertable = enriched.count { it.alertable }
    val pct = 100.0 * alertable / enriched.size

    println()
    println("---------------- RESULT ----------------")
    println(" markers            ${enriched.size}")
    println(" alertable          $alertable  (${"%.1f".format(pct)}%)")
    println(" silent             ${enriched.size - alertable}")
    println(" output             ${opts.output}")
    println(" http cache         ${http.hits} hits / ${http.misses} fetched" +
        if (http.expired > 0) " / ${http.expired} expired" else "")
    if (http.poisoned > 0) {
        println(" discarded          ${http.poisoned} cached failures from an older build")
    }
    println(" harvest health     ${wikipedia.geosearchFailures} centre failures, " +
        "${wikipedia.summaryFailures} summary failures, " +
        "${wikipedia.summaryUnusable} unusable summaries, " +
        "${http.throttled} server pushbacks")
    println()
    println(" by state:")
    enriched.groupBy { it.marker.state }.toSortedMap().forEach { (state, rows) ->
        val n = rows.count { it.alertable }
        println("   ${state.padEnd(12)} ${rows.size.toString().padStart(5)} total  " +
            "${n.toString().padStart(5)} alertable")
    }
    println()
    println(" sample of what will be spoken:")
    enriched.filter { it.alertable }.take(3).forEach { row ->
        val wiki = row.wiki ?: return@forEach
        println("   - ${row.marker.name}  ->  ${wiki.title}")
        println("     ${wiki.blurb.take(140)}...")
    }

    // The weakest accepted matches are where false positives live: a short
    // Wikipedia title sharing a word or two with a marker can clear the bar
    // without being the same place at all. Printing them makes the threshold a
    // decision based on evidence rather than a guess.
    println()
    println(" weakest accepted matches (check these before trusting --similarity=${opts.minSimilarity}):")
    enriched.mapNotNull { row -> row.wiki?.let { it to row.marker } }
        .sortedBy { it.first.similarity }
        .take(8)
        .forEach { (wiki, marker) ->
            println("   ${"%.2f".format(wiki.similarity)}  ${"%4.0f".format(wiki.distanceMeters)}m  " +
                "${marker.name}  ->  ${wiki.title}")
        }
    println("----------------------------------------")
}

internal data class Options(
    val states: List<String>,
    val output: Path,
    val harvestRadiusMeters: Double,
    val matchRadiusMeters: Double,
    val minSimilarity: Double,
    val limit: Int,
    val refresh: Boolean,
    val maxAgeDays: Long?,
    val schema: Path,
    val benchmark: Path?,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            val map = mutableMapOf<String, String>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                if (!arg.startsWith("--")) { i++; continue }
                val next = args.getOrNull(i + 1)
                if (next == null || next.startsWith("--")) {
                    // A valueless flag such as --refresh.
                    map[arg.removePrefix("--")] = "true"
                    i++
                } else {
                    map[arg.removePrefix("--")] = next
                    i += 2
                }
            }
            return Options(
                states = (map["states"] ?: "TEXAS,COLORADO,NEW MEXICO,ARIZONA,UTAH").split(","),
                output = Path.of(map["out"] ?: "app/src/main/assets/markers.db"),
                harvestRadiusMeters = map["harvest"]?.toDouble() ?: 10_000.0,
                // 500 m allows for the National Register's own coordinate
                // accuracy, which its metadata gives as roughly +/- 12 m at
                // best but is far looser for batch-derived points.
                matchRadiusMeters = map["radius"]?.toDouble() ?: 500.0,
                // 0.8, not 0.6. Measured on a 40-marker sample: every match at
                // 0.80 and above was correct, including one that survived a
                // typo in the NPS data, while both false positives sat at 0.67
                // and 0.71. A wrong blurb read aloud is worse than silence.
                minSimilarity = map["similarity"]?.toDouble() ?: 0.8,
                limit = map["limit"]?.toInt() ?: 0,
                refresh = map["refresh"].toBoolean(),
                // Wikipedia summaries drift slowly and re-harvesting five
                // states costs ~8 minutes, so nothing expires unless asked.
                maxAgeDays = map["max-age-days"]?.toLong(),
                schema = Path.of(
                    map["schema"]
                        ?: "app/schemas/com.dnoel.markeralerts.data.MarkerDatabase/1.json",
                ),
                benchmark = map["benchmark"]?.let(Path::of),
            )
        }
    }
}
