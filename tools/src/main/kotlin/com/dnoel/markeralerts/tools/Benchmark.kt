package com.dnoel.markeralerts.tools

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Measures what the `(lat, lon)` index is worth on the bounding-box query.
 *
 * The whole proximity design rests on the claim that an indexed rectangle scan
 * is cheap enough to run on every location update. That claim is worth
 * measuring rather than assuming — especially before the national 72,668-row
 * dataset arrives and multiplies the row count tenfold.
 *
 *   ./gradlew :tools:run --args="--benchmark app/src/main/assets/markers.db"
 *
 * Works on a copy so the shipped asset is never modified.
 */
object Benchmark {

    private const val WARMUP = 200
    private const val RUNS = 2_000

    fun run(source: Path) {
        val copy = source.resolveSibling("benchmark-copy.db")
        Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING)

        try {
            DriverManager.getConnection("jdbc:sqlite:${copy.toAbsolutePath()}").use { conn ->
                val rows = conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM markers").use { it.getInt(1) }
                }
                println("rows: $rows")
                println()

                val withIndex = time(conn)
                println("  with (lat, lon) index      ${format(withIndex)}")

                conn.createStatement().use { it.execute("DROP INDEX index_markers_lat_lon") }
                val withoutIndex = time(conn)
                println("  without index (full scan)  ${format(withoutIndex)}")

                println()
                val speedup = withoutIndex.toDouble() / withIndex.coerceAtLeast(1)
                println("  speedup: ${"%.1f".format(speedup)}x")
                println()
                println("  A location update arrives every second or so, so the")
                println("  per-query budget is milliseconds. Both numbers matter:")
                println("  the index has to hold up when the national set makes")
                println("  the scan ten times longer.")
            }
        } finally {
            Files.deleteIfExists(copy)
        }
    }

    /** Nanoseconds per query, sampling random points across the five states. */
    private fun time(conn: Connection): Long {
        val sql = """
            SELECT geomId FROM markers
            WHERE alertable = 1
              AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
        """.trimIndent()

        val random = Random(20260818) // Fixed seed: comparable across runs.
        conn.prepareStatement(sql).use { ps ->
            repeat(WARMUP) { queryOnce(ps, random) }

            val start = System.nanoTime()
            repeat(RUNS) { queryOnce(ps, random) }
            return (System.nanoTime() - start) / RUNS
        }
    }

    private fun queryOnce(ps: java.sql.PreparedStatement, random: Random) {
        // Somewhere in the five-state box, at a ~4.8 km radius.
        val lat = random.nextDouble(29.0, 41.0)
        val lon = random.nextDouble(-114.0, -94.0)
        val dLat = 4_800.0 / 111_320.0
        val dLon = 4_800.0 / (111_320.0 * Math.cos(Math.toRadians(lat)))

        ps.setDouble(1, lat - dLat)
        ps.setDouble(2, lat + dLat)
        ps.setDouble(3, lon - dLon)
        ps.setDouble(4, lon + dLon)
        ps.executeQuery().use { while (it.next()) it.getString(1) }
    }

    private fun format(nanos: Long): String {
        val micros = (nanos / 1_000.0).roundToLong()
        return "${micros}µs  (${"%.3f".format(nanos / 1_000_000.0)} ms)"
    }
}
