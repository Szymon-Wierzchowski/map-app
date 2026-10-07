package com.szymi.mapy.shared

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

data class LatLon(val lat: Double, val lon: Double)

data class TileId(val z: Int, val x: Int, val y: Int)

object Geo {
    const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

    /** Consecutive stops further apart than this are treated as a ride, not a walk. */
    const val TRANSIT_GAP_M = 3_000.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(a))
    }

    // Web Mercator normalised to 0..1 on both axes; multiply by 2^z for tile units.
    fun mercX(lon: Double): Double = (lon + 180.0) / 360.0

    fun mercY(lat: Double): Double {
        val phi = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - ln(tan(phi) + 1.0 / cos(phi)) / PI) / 2.0
    }

    fun latFromMercY(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))

    /**
     * Tiles covering the line plus one tile of margin on each side, per zoom level.
     * Segments longer than [TRANSIT_GAP_M] are rides: only their two ends are covered.
     */
    fun corridorTiles(points: List<LatLon>, zooms: IntRange): List<TileId> {
        val ride = points.zipWithNext { a, b -> distanceM(a.lat, a.lon, b.lat, b.lon) > TRANSIT_GAP_M }
        val result = LinkedHashSet<TileId>()
        for (z in zooms) {
            val n = 1 shl z
            fun add(x: Double, y: Double) {
                val tx = floor(x * n).toInt()
                val ty = floor(y * n).toInt()
                for (dx in -1..1) for (dy in -1..1) {
                    val yy = ty + dy
                    if (yy in 0 until n) result += TileId(z, Math.floorMod(tx + dx, n), yy)
                }
            }
            val merc = points.map { mercX(it.lon) to mercY(it.lat) }
            merc.firstOrNull()?.let { add(it.first, it.second) }
            for (index in 0 until merc.size - 1) {
                val a = merc[index]
                val b = merc[index + 1]
                if (ride[index]) {
                    add(b.first, b.second)
                    continue
                }
                // Sample at most half a tile apart so no tile along the segment is missed.
                val steps = max(1, ceil(max(abs(b.first - a.first), abs(b.second - a.second)) * n * 2).toInt())
                for (i in 1..steps) {
                    val t = i.toDouble() / steps
                    add(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t)
                }
            }
        }
        return result.toList()
    }
}

/** Google encoded polyline, precision 5 (what OSRM returns for geometries=polyline). */
object Polyline {
    fun decode(encoded: String): List<LatLon> {
        val points = ArrayList<LatLon>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            points += LatLon(lat / 1e5, lon / 1e5)
        }
        return points
    }

    fun encode(points: List<LatLon>): String {
        val sb = StringBuilder()
        var prevLat = 0
        var prevLon = 0
        for (p in points) {
            val lat = Math.round(p.lat * 1e5).toInt()
            val lon = Math.round(p.lon * 1e5).toInt()
            encodeValue(lat - prevLat, sb)
            encodeValue(lon - prevLon, sb)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    private fun encodeValue(value: Int, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toChar())
    }
}
