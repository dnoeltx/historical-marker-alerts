package com.dnoel.markeralerts.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface MarkerDao {

    @Query("SELECT COUNT(*) FROM markers")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM markers WHERE alertable = 1")
    suspend fun alertableCount(): Int

    /**
     * The hot path: everything alertable inside a lat/lon rectangle.
     *
     * A rectangle is not a circle, so this deliberately over-selects at the
     * corners — the caller refines with Haversine. That split is the point.
     * SQL does the cheap indexed work that removes 99.9% of the rows, and
     * Kotlin does the expensive trigonometry on the handful that survive.
     * Doing the distance maths in SQL instead would force a full table scan on
     * every location update, because no index can help an expression.
     */
    @Query(
        """
        SELECT * FROM markers
        WHERE alertable = 1
          AND lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        """,
    )
    suspend fun alertableInBoundingBox(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
    ): List<MarkerEntity>

    @Query("SELECT * FROM markers WHERE geomId = :geomId")
    suspend fun byId(geomId: String): MarkerEntity?

    /** Contributing points of one listing — a historic district's buildings. */
    @Query("SELECT * FROM markers WHERE refnum = :refnum ORDER BY name")
    suspend fun byRefnum(refnum: String): List<MarkerEntity>
}
