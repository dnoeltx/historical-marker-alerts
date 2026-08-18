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
 * to the matching threshold would mean re-downloading the entire internet's
 * worth of the same JSON. With one, only the first run is slow and every
 * subsequent run is effectively instant.
 *
 * Failures are cached too — a 404 from Wikipedia is a real answer ("no such
 * article"), not a transient error, and re-asking would be pointless. Only 5xx
 * and network errors are left uncached so they can be retried.
 */
class HttpCache(
    private val cacheDir: Path,
    private val userAgent: String,
    /** Wikimedia asks for a considerate request rate; this throttles live calls only. */
    private val minIntervalMillis: Long = 120,
    /**
     * Entries older than this are refetched. Null disables expiry entirely.
     *
     * Age comes from the cache file's own modification time rather than a
     * timestamp written into the file, so turning this on does not invalidate
     * the ~15 MB of responses already on disk.
     */
    private val maxAge: Duration? = null,
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private var lastRequestAt = 0L

    var hits = 0
        private set
    var misses = 0
        private set

    init {
        Files.createDirectories(cacheDir)
    }

    var expired = 0
        private set

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
            hits++
            val text = Files.readString(path)
            val split = text.indexOf('\n')
            return Response(text.substring(0, split).toInt(), text.substring(split + 1))
        }

        throttle()
        misses++

        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Only persist answers that will still be true tomorrow. A 500 or a
        // dropped connection says nothing about the resource.
        if (response.statusCode() < 500) {
            Files.writeString(path, "${response.statusCode()}\n${response.body()}")
        }
        return Response(response.statusCode(), response.body())
    }

    private fun isExpired(path: Path): Boolean {
        val limit = maxAge ?: return false
        val age = Duration.ofMillis(
            System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis(),
        )
        return (age > limit).also { if (it) expired++ }
    }

    private fun throttle() {
        val elapsed = System.currentTimeMillis() - lastRequestAt
        if (elapsed < minIntervalMillis) Thread.sleep(minIntervalMillis - elapsed)
        lastRequestAt = System.currentTimeMillis()
    }

    private fun keyFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".txt"
    }

    data class Response(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
    }
}
