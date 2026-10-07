package com.szymi.mapy.shared

import org.json.JSONArray
import org.json.JSONObject

enum class StopStatus { PENDING, DONE, SKIPPED }

data class Stop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val status: StopStatus = StopStatus.PENDING,
)

/**
 * The whole route as it travels between phone and watch.
 * [geometry] is an encoded polyline through every stop that is not skipped.
 */
data class Route(
    val stops: List<Stop> = emptyList(),
    val geometry: String = "",
    val distanceM: Double = 0.0,
    val routed: Boolean = false,
) {
    val nextStop: Stop? get() = stops.firstOrNull { it.status == StopStatus.PENDING }
    val activeStops: List<Stop> get() = stops.filter { it.status != StopStatus.SKIPPED }

    /** Straight segments between stops; used until (or instead of) a routed line. */
    fun straight(): Route {
        val points = activeStops.map { LatLon(it.lat, it.lon) }
        return copy(
            geometry = Polyline.encode(points),
            distanceM = points.zipWithNext { a, b -> Geo.distanceM(a.lat, a.lon, b.lat, b.lon) }
                .filter { it <= Geo.TRANSIT_GAP_M }.sum(),
            routed = false,
        )
    }

    fun withStatus(id: String, status: StopStatus) =
        copy(stops = stops.map { if (it.id == id) it.copy(status = status) else it })

    fun without(id: String) = copy(stops = stops.filter { it.id != id })

    fun toJson(): String {
        val array = JSONArray()
        for (s in stops) {
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("lat", s.lat)
                    .put("lon", s.lon)
                    .put("status", s.status.name)
            )
        }
        return JSONObject()
            .put("stops", array)
            .put("geometry", geometry)
            .put("distanceM", distanceM)
            .put("routed", routed)
            .toString()
    }

    companion object {
        fun fromJson(json: String): Route {
            val o = JSONObject(json)
            val array = o.getJSONArray("stops")
            val stops = (0 until array.length()).map { i ->
                val s = array.getJSONObject(i)
                Stop(
                    id = s.getString("id"),
                    name = s.getString("name"),
                    lat = s.getDouble("lat"),
                    lon = s.getDouble("lon"),
                    status = StopStatus.valueOf(s.getString("status")),
                )
            }
            return Route(stops, o.optString("geometry"), o.optDouble("distanceM", 0.0), o.optBoolean("routed"))
        }
    }
}

/** Wear Data Layer paths and the watch -> phone command format. */
object Wire {
    const val PATH_ROUTE = "/route"
    const val PATH_CMD = "/cmd"
    const val PATH_TILES = "/tiles"
    const val PATH_TILES_OK = "/tiles_ok"
    const val KEY_JSON = "json"

    fun cmdAdd(lat: Double, lon: Double): String =
        JSONObject().put("t", "add").put("lat", lat).put("lon", lon).toString()

    fun cmdRemove(id: String): String =
        JSONObject().put("t", "remove").put("id", id).toString()

    fun cmdStatus(id: String, status: StopStatus): String =
        JSONObject().put("t", "status").put("id", id).put("s", status.name).toString()
}
