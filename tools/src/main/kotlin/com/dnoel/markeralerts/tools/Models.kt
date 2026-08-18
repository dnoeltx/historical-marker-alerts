package com.dnoel.markeralerts.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A National Register *point*, before any Wikipedia enrichment.
 *
 * Note the two identifiers. [geomId] identifies this physical location and is
 * unique; [refnum] identifies the National Register *listing* and is NOT — a
 * historic district is one listing with many contributing buildings. Reference
 * number 79002491 is the Helper, Utah district and covers seven separate
 * points, each with its own name and coordinates.
 */
data class Marker(
    val geomId: String,
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
)

/** A marker after the matching pass. [wiki] is null when nothing matched. */
data class EnrichedMarker(
    val marker: Marker,
    val wiki: WikiMatch?,
) {
    /**
     * The alert rule. A marker earns an audible interruption only if there is
     * something worth saying about it — and the blurb is what would be said.
     * Most NRHP records are private houses with no article, and alerting on
     * those would make the app unusable noise.
     */
    val alertable: Boolean get() = wiki?.blurb?.isNotBlank() == true
}

data class WikiMatch(
    val title: String,
    val url: String,
    val blurb: String,
    val distanceMeters: Double,
    val similarity: Double,
)

// --- ArcGIS (NPS) wire format ------------------------------------------------

@Serializable
data class ArcGisResponse(
    val features: List<ArcGisFeature> = emptyList(),
    val exceededTransferLimit: Boolean = false,
)

@Serializable
data class ArcGisFeature(
    val attributes: ArcGisAttributes,
    val geometry: ArcGisGeometry? = null,
)

@Serializable
data class ArcGisAttributes(
    @SerialName("GEOM_ID") val geomId: String? = null,
    @SerialName("NRIS_Refnum") val refnum: String? = null,
    @SerialName("RESNAME") val name: String? = null,
    @SerialName("ResType") val resType: String? = null,
    @SerialName("Address") val address: String? = null,
    @SerialName("City") val city: String? = null,
    @SerialName("County") val county: String? = null,
    @SerialName("State") val state: String? = null,
    @SerialName("CertDate") val certDate: String? = null,
)

/** ArcGIS returns x = longitude, y = latitude. Easy to transpose; don't. */
@Serializable
data class ArcGisGeometry(val x: Double, val y: Double)

// --- Wikipedia wire format ---------------------------------------------------

@Serializable
data class GeoSearchResponse(val query: GeoQuery? = null)

@Serializable
data class GeoQuery(val geosearch: List<GeoResult> = emptyList())

@Serializable
data class GeoResult(
    val pageid: Long,
    val title: String,
    val lat: Double,
    val lon: Double,
)

@Serializable
data class WikiSummary(
    val type: String? = null,
    val title: String? = null,
    val extract: String? = null,
    @SerialName("content_urls") val contentUrls: ContentUrls? = null,
)

@Serializable
data class ContentUrls(val desktop: UrlSet? = null)

@Serializable
data class UrlSet(val page: String? = null)
