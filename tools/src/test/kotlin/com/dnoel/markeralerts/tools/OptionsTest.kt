package com.dnoel.markeralerts.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser has to cope with valueless flags sitting next to valued ones,
 * which is exactly the shape that breaks naive pair-wise argument scanning.
 */
class OptionsTest {

    @Test
    fun `defaults are the five-state production run`() {
        val opts = Options.parse(emptyArray())
        assertEquals(
            listOf("TEXAS", "COLORADO", "NEW MEXICO", "ARIZONA", "UTAH"),
            opts.states,
        )
        assertEquals(0.8, opts.minSimilarity, 0.001)
        assertFalse(opts.refresh)
        assertNull(opts.maxAgeDays)
    }

    @Test
    fun `a valueless flag at the end is still recognised`() {
        assertTrue(Options.parse(arrayOf("--limit", "40", "--refresh")).refresh)
    }

    @Test
    fun `a valueless flag does not swallow the flag after it`() {
        // The bug this guards: --refresh consuming "--limit" as its value,
        // leaving limit unset and refresh set to a nonsense string.
        val opts = Options.parse(arrayOf("--refresh", "--limit", "40"))
        assertTrue(opts.refresh)
        assertEquals(40, opts.limit)
    }

    @Test
    fun `valued flags still parse normally`() {
        val opts = Options.parse(arrayOf("--similarity", "0.9", "--max-age-days", "90"))
        assertEquals(0.9, opts.minSimilarity, 0.001)
        assertEquals(90L, opts.maxAgeDays)
        assertFalse(opts.refresh)
    }
}
