package com.dnoel.markeralerts.tools

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/**
 * A GET client that writes every response to disk and never fetches the same
 * URL twice.
 *
 * This exists because the full run makes a few thousand Wikipedia calls, which
 * takes tens of minutes at a polite request rate. Without a cache, every tweak
 * to the matching threshold would mean re-downloading the same JSON. With one,
 * only the first run is slow and every subsequent run is effectively instant.
 *
 * ## What may and may not be cached
 *
 * A 404 is a real answer — "no such article" — and will still be true tomorrow,
 * so it is stored. A **429 is not an answer at all**; it means we asked too
 * fast. Storing one turns a moment of impatience into a permanent hole in the
 * dataset.
 *
 * This is not hypothetical. The first full harvest cached **1,414 rate-limit
 * rejections** — 45% of every response on disk — because the rule was
 * "anything under 500". Both stages were hit: roughly 426 of 1,206 geosearch
 * centres returned nothing, and about half the article summaries were thrown
 * away. Because the failures were cached, every later run replayed them
 * faithfully and produced an identical, quietly incomplete database. The bug
 * was invisible for three releases and surfaced only as "Arizona has a strangely
 * low match rate".
 *
 * Two rules follow, and they are load-bearing:
 *
 *  - a transient status is **never written** to the cache
 *  - a transient status already **on** disk is ignored and refetched, so a
 *    cache poisoned by an older build heals itself rather than needing a manual
 *    purge that nobody will remember to run
 */
class HttpCache(
    private val cacheDir: Path,
    private val userAgent: String,
    /**
     * Minimum gap between live requests.
     *
     * Was 120 ms — about eight requests a second — which is what earned 1,414
     * rejections. Wikimedia asks for a considerate rate rather than publishing
     * a hard number for anonymous clients, so this is deliberately conservative:
     * a slow harvest that finishes is worth more than a fast one that silently
     * loses half its data, and the cache means the cost is paid once.
     */
    private val minIntervalMillis: Long = 350,
    /**
     * Entries older than this are refetched. Null disables expiry entirely.
     *
     * Age comes from the cache file's own modification time rather than a
     * timestamp written into the file, so turning this on does not invalidate
     * the responses already on disk.
     */
    private val maxAge: Duration? = null,
    /** Attempts per URL before giving up, including the first. */
    private val maxAttempts: Int = 5,
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private var lastRequestAt = 0L

    /**
     * Grows each time the server pushes back and decays on success, so a run
     * that starts hitting limits slows down instead of hammering through the
     * whole backlog at a rate the server has already refused.
     */
    private var backoffPenaltyMillis = 0L

    var hits = 0
        private set
    var misses = 0
        private set
    var expired = 0
        private set

    /** Cached entries discarded because an older build stored a failure. */
    var poisoned = 0
        private set

    /** Requests that exhausted [maxAttempts] and still failed. */
    var givenUp = 0
        private set

    /** Times the server answered 429 or 5xx, across all attempts. */
    var throttled = 0
        private set

    init {
        Files.createDirectories(cacheDir)
    }

    /**
     * Returns status code and body, from disk when available.
     *
     * [bypassCache] forces a live fetch even when a cached copy exists. This is
     * how a data refresh actually refreshes: the source URL never changes, so
     * without it a rerun would serve the same stored response forever and
     * report success while rebuilding a byte-identical database.
     */
    fun get(url: String, bypassCache: Boolean = false): Response {
        val path = cacheDir.resolve(keyFor(url))

        if (!bypassCache && Files.exists(path) && !isExpired(path)) {
            val cached = read(path)
            if (!isTransient(cached.status)) {
                hits++
                return cached
            }
            // Written by a build that treated 429 as an answer. Drop it.
            poisoned++
            Files.deleteIfExists(path)
        }

        misses++
        return fetchWithRetry(url, path)
    }

    private fun fetchWithRetry(url: String, path: Path): Response {
        var last = Response(0, "")

        repeat(maxAttempts) { attempt ->
            throttle()

            val request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build()

            last = runCatching {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                Response(response.statusCode(), response.body(), retryAfter(response))
            }.getOrElse {
                // A dropped connection says nothing about the resource, so it
                // is treated exactly like a 5xx and retried.
                Response(0, it.message.orEmpty())
            }

            if (!isTransient(last.status)) {
                // Success, or a genuine answer such as 404. Both are worth
                // keeping; only the transient ones are not.
                backoffPenaltyMillis = (backoffPenaltyMillis / 2)
                Files.writeString(path, "${last.status}\n${last.body}")
                return last
            }

            throttled++

            // Honour Retry-After when the server sends one; otherwise back off
            // exponentially from one second. The penalty also slows every
            // subsequent request in the run, not just the retries of this one.
            val wait = last.retryAfterSeconds?.times(1_000L)
                ?: (1_000L shl attempt)
            backoffPenaltyMillis = maxOf(backoffPenaltyMillis, minOf(wait, MAX_PENALTY_MILLIS))
            Thread.sleep(wait.coerceAtMost(MAX_SLEEP_MILLIS))
        }

        givenUp++
        return last
    }

    /** 429 means "ask again later"; 5xx and network errors say nothing at all. */
    private fun isTransient(status: Int): Boolean =
        status == 429 || status >= 500 || status == 0

    private fun read(path: Path): Response {
        val text = Files.readString(path)
        val split = text.indexOf('\n')
        return Response(text.substring(0, split).toInt(), text.substring(split + 1))
    }

    private fun retryAfter(response: HttpResponse<String>): Long? =
        response.headers().firstValue("Retry-After").orElse(null)?.toLongOrNull()

    private fun isExpired(path: Path): Boolean {
        val limit = maxAge ?: return false
        val age = Duration.ofMillis(
            System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis(),
        )
        return (age > limit).also { if (it) expired++ }
    }

    private fun throttle() {
        val target = minIntervalMillis + backoffPenaltyMillis
        val elapsed = System.currentTimeMillis() - lastRequestAt
        if (elapsed < target) Thread.sleep(target - elapsed)
        lastRequestAt = System.currentTimeMillis()
    }

    private fun keyFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".txt"
    }

    data class Response(
        val status: Int,
        val body: String,
        val retryAfterSeconds: Long? = null,
    ) {
        val ok: Boolean get() = status in 200..299
    }

    private companion object {
        const val MAX_PENALTY_MILLIS = 5_000L
        const val MAX_SLEEP_MILLIS = 60_000L
    }
}
