package com.dnoel.markeralerts.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.security.MessageDigest

/**
 * These tests exist because of a specific, expensive mistake.
 *
 * The first harvest cached 1,414 HTTP 429 rejections as if they were data. The
 * rule was "persist anything under 500", which is correct for a 404 and wrong
 * for a rate limit. Every later run replayed the failures and produced an
 * identical, quietly incomplete database — invisible for three releases.
 *
 * The cache is not mocked out here: the point is the disk behaviour.
 */
class HttpCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun keyFor(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".txt"

    /** Writes a cache entry by hand, the way an older build would have. */
    private fun seed(url: String, status: Int, body: String) {
        Files.writeString(temp.root.toPath().resolve(keyFor(url)), "$status\n$body")
    }

    private fun cache() = HttpCache(
        cacheDir = temp.root.toPath(),
        userAgent = "test",
        minIntervalMillis = 0,
        maxAttempts = 1,
    )

    @Test
    fun `a cached 404 is served, because it is a real answer`() {
        val url = "https://example.invalid/missing"
        seed(url, 404, "no such article")

        val response = cache().get(url)

        // "This article does not exist" will still be true tomorrow.
        assertEquals(404, response.status)
        assertFalse(response.ok)
    }

    @Test
    fun `a cached 200 is served`() {
        val url = "https://example.invalid/present"
        seed(url, 200, """{"extract":"hello"}""")

        val response = cache().get(url)

        assertEquals(200, response.status)
        assertTrue(response.ok)
        assertEquals("""{"extract":"hello"}""", response.body)
    }

    @Test
    fun `a cached 429 is never served — this is the whole bug`() {
        val url = "https://example.invalid/throttled"
        seed(url, 429, "You are making too many requests to the API.")

        val http = cache()
        // No network here, so the refetch fails; what matters is that it tried
        // rather than handing back the stored rejection as though it were data.
        val response = http.get(url)

        assertEquals("the stored rejection must be recognised as poison", 1, http.poisoned)
        assertEquals("and must not count as a cache hit", 0, http.hits)
        assertFalse("a 429 must never be reported as a usable response", response.ok)
    }

    @Test
    fun `a poisoned entry is deleted so it cannot be served later`() {
        val url = "https://example.invalid/throttled"
        seed(url, 429, "too many requests")

        cache().get(url)

        assertFalse(
            "the 429 file must be gone, or the next run replays it again",
            Files.exists(temp.root.toPath().resolve(keyFor(url))),
        )
    }

    @Test
    fun `a cached 503 is treated the same as a 429`() {
        val url = "https://example.invalid/down"
        seed(url, 503, "service unavailable")

        val http = cache()
        http.get(url)

        assertEquals(1, http.poisoned)
    }

    @Test
    fun `a failed live fetch is not written to disk`() {
        // example.invalid never resolves, so this exercises the network-error
        // path: status 0, transient, must not be persisted.
        val url = "https://nonexistent.invalid/thing"

        val http = cache()
        val response = http.get(url)

        assertFalse(response.ok)
        assertEquals(1, http.givenUp)
        assertFalse(
            "a dropped connection says nothing about the resource",
            Files.exists(temp.root.toPath().resolve(keyFor(url))),
        )
    }
}
