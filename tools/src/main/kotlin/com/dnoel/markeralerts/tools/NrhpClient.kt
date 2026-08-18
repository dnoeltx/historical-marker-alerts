package com.dnoel.markeralerts.tools

import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Pages every National Register point for the requested states out of the NPS
 * ArcGIS feature service.
 *
 * Verified against the live service on 2026-08-18: 72,668 points nationally,
 * 7,084 across TX/CO/NM/AZ/UT, `maxRecordCount` 2000.
 */
class NrhpClient(
    private val http: HttpCache,
    private val json: Json,
) {
    /**
     * [refresh] bypasses the response cache. This is only ever four requests,
     * so re-fetching costs seconds — cheap insurance against a refresh run that
     * silently rebuilds from months-old cached JSON.
     */
    fun fetchStates(states: List<String>, refresh: Boolean = false): List<Marker> {
        val where = states.joinToString(",") { "'${it.uppercase()}'" }.let { "State IN ($it)" }
        val markers = mutableListOf<Marker>()
        var offset = 0

        while (true) {
            val url = buildUrl(where, offset)
            val response = http.get(url, bypassCache = refresh)
            require(response.ok) { "NPS service returned ${response.status} for offset $offset" }

            val page = json.decodeFromString<ArcGisResponse>(response.body)
            if (page.features.isEmpty()) break

            page.features.mapNotNullTo(markers) { it.toMarker() }
            println("  fetched ${markers.size} markers...")

            // exceededTransferLimit is the service telling us there is another
            // page. Trusting features.size == PAGE_SIZE instead would break on
            // a total that happens to be an exact multiple.
            if (!page.exceededTransferLimit) break
            offset += PAGE_SIZE
        }
        return markers
    }

    private fun buildUrl(where: String, offset: Int): String {
        val params = linkedMapOf(
            "where" to where,
            "outFields" to "GEOM_ID,NRIS_Refnum,RESNAME,ResType,Address,City,County,State,CertDate",
            "returnGeometry" to "true",
            "outSR" to "4326",
            // Pagination is only stable with a deterministic sort, and the sort
            // field must be unique. NRIS_Refnum is not — sorting by it can drop
            // or repeat rows at a page boundary that falls inside a group of
            // contributing buildings sharing one listing.
            "orderByFields" to "GEOM_ID",
            "resultOffset" to offset.toString(),
            "resultRecordCount" to PAGE_SIZE.toString(),
            "f" to "json",
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, StandardCharsets.UTF_8)
        }
        return "$BASE/query?$query"
    }

    private fun ArcGisFeature.toMarker(): Marker? {
        val g = geometry ?: return null
        val geomId = attributes.geomId?.takeIf { it.isNotBlank() } ?: return null
        val refnum = attributes.refnum?.takeIf { it.isNotBlank() } ?: return null
        val name = attributes.name?.takeIf { it.isNotBlank() } ?: return null
        // A handful of records carry a null island or out-of-range coordinate.
        if (g.y !in -90.0..90.0 || g.x !in -180.0..180.0) return null
        if (g.x == 0.0 && g.y == 0.0) return null

        return Marker(
            geomId = geomId,
            refnum = refnum,
            name = name,
            resType = attributes.resType?.takeIf { it.isNotBlank() },
            address = attributes.address?.takeIf { it.isNotBlank() },
            city = attributes.city?.takeIf { it.isNotBlank() },
            county = attributes.county?.takeIf { it.isNotBlank() },
            state = attributes.state.orEmpty(),
            certDate = attributes.certDate?.takeIf { it.isNotBlank() },
            lat = g.y,
            lon = g.x,
        )
    }

    private companion object {
        const val BASE =
            "https://mapservices.nps.gov/arcgis/rest/services/cultural_resources/nrhp_locations/MapServer/0"
        const val PAGE_SIZE = 2000
    }
}
