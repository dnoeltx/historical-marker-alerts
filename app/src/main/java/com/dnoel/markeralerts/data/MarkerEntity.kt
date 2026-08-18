package com.dnoel.markeralerts.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One National Register point.
 *
 * This must match the schema written by the `:tools` module byte for byte —
 * column names, nullability, and index names alike. Room validates the
 * prepackaged database against the schema it compiled and refuses to open a
 * mismatch, so a stray nullable here surfaces as a runtime crash on first
 * launch rather than a compile error.
 *
 * Room derives index names as `index_<table>_<columns>`, which is why the
 * hand-written DDL in DatabaseWriter uses exactly `index_markers_lat_lon` and
 * `index_markers_refnum`.
 */
@Entity(
    tableName = "markers",
    indices = [
        // The bounding-box prefilter. This is the index the whole proximity
        // approach rests on, and the one that has to carry 72,668 rows later.
        Index(value = ["lat", "lon"]),
        // Non-unique: contributing buildings of one historic district share a
        // reference number.
        Index(value = ["refnum"]),
    ],
)
data class MarkerEntity(
    /** Location GUID from the NPS service — unique per physical point. */
    @PrimaryKey val geomId: String,
    /** National Register listing number. NOT unique; a district has many points. */
    val refnum: String,
    val name: String,
    val resType: String?,
    val address: String?,
    val city: String?,
    val county: String?,
    val state: String,
    val certDate: String?,
    val lat: Double,
    val lon: Double,
    /** True only when a Wikipedia article was matched, i.e. there is something to say. */
    val alertable: Boolean,
    val wikiTitle: String?,
    val wikiUrl: String?,
    val blurb: String?,
)
