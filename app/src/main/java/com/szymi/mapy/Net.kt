package com.szymi.mapy

import com.szymi.mapy.shared.Geo
import com.szymi.mapy.shared.LatLon
import com.szymi.mapy.shared.Polyline
import com.szymi.mapy.shared.Route
import com.szymi.mapy.shared.Stop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.BoundingBox
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

// The OSM-hosted services below require an identifying User-Agent.
const val USER_AGENT = "MapyPrzystanki/1.0 (Android; personal use)"

// Single place to swap the tile server, e.g. for a self-hosted or commercial one.
const val TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png"

fun httpGet(url: String): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.setRequestProperty("User-Agent", USER_AGENT)
    connection.connectTimeout = 10_000
    connection.readTimeout = 20_000
    try {
        if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
        return connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
}

object Router {
    /**
     * Walking route through [stops] in order, or null when the service can't be reached.
     * Stops further apart than [Geo.TRANSIT_GAP_M] are assumed to be a train or bus ride:
     * they are joined by a straight line and left out of the walking distance.
     */
    suspend fun route(stops: List<Stop>): Route? = withContext(Dispatchers.IO) {
        if (stops.size < 2) return@withContext null
        val chains = mutableListOf(mutableListOf(stops[0]))
        for ((a, b) in stops.zipWithNext()) {
            if (Geo.distanceM(a.lat, a.lon, b.lat, b.lon) > Geo.TRANSIT_GAP_M) chains += mutableListOf(b)
            else chains.last() += b
        }
        val points = ArrayList<LatLon>()
        var distance = 0.0
        for (chain in chains) {
            if (chain.size == 1) {
                points += LatLon(chain[0].lat, chain[0].lon)
                continue
            }
            val coords = chain.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }
            val url = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/$coords" +
                "?overview=full&geometries=polyline"
            val best = runCatching {
                JSONObject(String(httpGet(url))).getJSONArray("routes").getJSONObject(0)
            }.getOrNull() ?: return@withContext null
            points += Polyline.decode(best.getString("geometry"))
            distance += best.getDouble("distance")
        }
        Route(geometry = Polyline.encode(points), distanceM = distance, routed = true)
    }
}

data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

object Geocoder {
    /** Nominatim search; [near] only biases the ranking towards what the map shows. */
    suspend fun search(query: String, near: BoundingBox?): List<Place> = withContext(Dispatchers.IO) {
        var url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&accept-language=pl" +
            "&q=" + URLEncoder.encode(query, "UTF-8")
        if (near != null) {
            url += String.format(
                Locale.US, "&viewbox=%.5f,%.5f,%.5f,%.5f",
                near.lonWest, near.latNorth, near.lonEast, near.latSouth,
            )
        }
        val array = JSONArray(String(httpGet(url)))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val display = o.getString("display_name")
            Place(
                name = o.optString("name").ifBlank { display.substringBefore(",") },
                detail = display,
                lat = o.getString("lat").toDouble(),
                lon = o.getString("lon").toDouble(),
            )
        }
    }
}

fun Route.points(): List<LatLon> = Polyline.decode(geometry)
