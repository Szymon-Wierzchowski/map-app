package com.szymi.mapy.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
    private val points = listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453))

    @Test
    fun polylineDecodesReferenceExample() {
        val decoded = Polyline.decode(encoded)
        assertEquals(points.size, decoded.size)
        for ((expected, actual) in points.zip(decoded)) {
            assertEquals(expected.lat, actual.lat, 1e-6)
            assertEquals(expected.lon, actual.lon, 1e-6)
        }
    }

    @Test
    fun polylineEncodesReferenceExample() {
        assertEquals(encoded, Polyline.encode(points))
    }

    @Test
    fun mercatorRoundTrips() {
        assertEquals(52.2297, Geo.latFromMercY(Geo.mercY(52.2297)), 1e-9)
        assertEquals(0.5, Geo.mercX(0.0), 0.0)
        assertEquals(0.5, Geo.mercY(0.0), 1e-12)
    }

    @Test
    fun distanceIsRoughlyRight() {
        // Warsaw centre to Kraków centre is about 252 km.
        assertEquals(252_000.0, Geo.distanceM(52.2297, 21.0122, 50.0647, 19.9450), 3_000.0)
    }

    @Test
    fun corridorCoversEveryTileTheLineCrosses() {
        val a = LatLon(52.2297, 21.0122)
        val b = LatLon(52.2400, 21.0400)
        val tiles = Geo.corridorTiles(listOf(a, b), 16..16).toSet()
        val n = 1 shl 16
        for (i in 0..1000) {
            val t = i / 1000.0
            val x = (Geo.mercX(a.lon) + (Geo.mercX(b.lon) - Geo.mercX(a.lon)) * t) * n
            val y = (Geo.mercY(a.lat) + (Geo.mercY(b.lat) - Geo.mercY(a.lat)) * t) * n
            assertTrue(TileId(16, x.toInt(), y.toInt()) in tiles)
        }
        assertTrue(tiles.size < 150)
    }

    @Test
    fun corridorSkipsTheMiddleOfARide() {
        // Tokyo to Kamakura: only the surroundings of both ends, 9 tiles each.
        val tiles = Geo.corridorTiles(listOf(LatLon(35.6737, 139.7405), LatLon(35.3190, 139.5505)), 16..16)
        assertEquals(18, tiles.size)
    }
}
