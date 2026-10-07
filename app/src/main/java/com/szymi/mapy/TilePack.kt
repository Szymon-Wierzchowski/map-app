package com.szymi.mapy

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.szymi.mapy.shared.Geo
import com.szymi.mapy.shared.Route
import com.szymi.mapy.shared.TileId
import com.szymi.mapy.shared.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Downloads the map tiles along the route and ships them to the watch as one zip. */
object TilePack {
    val status = MutableStateFlow<String?>(null)

    private val ZOOMS = 14..16
    private const val MAX_TILES = 900

    suspend fun buildAndSend(context: Context, route: Route) {
        try {
            val points = route.points()
            if (points.isEmpty()) {
                status.value = "Najpierw dodaj przystanki."
                return
            }
            val tiles = Geo.corridorTiles(points, ZOOMS)
            if (tiles.size > MAX_TILES) {
                status.value = "Trasa jest za długa na mapy offline (${tiles.size} kafelków, limit $MAX_TILES)."
                return
            }
            val dir = File(context.cacheDir, "tilepack")
            val failed = download(tiles, dir)
            if (failed == tiles.size) {
                status.value = "Nie udało się pobrać map. Sprawdź internet."
                return
            }

            status.value = "Pakuję mapy…"
            val zip = File(context.cacheDir, "tiles.zip")
            withContext(Dispatchers.IO) {
                ZipOutputStream(zip.outputStream().buffered()).use { out ->
                    for (t in tiles) {
                        val f = t.file(dir)
                        if (!f.exists()) continue
                        out.putNextEntry(ZipEntry("${t.z}/${t.x}/${t.y}.png"))
                        f.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }

            status.value = "Wysyłam mapy na zegarek (${zip.length() / 1024} kB)…"
            send(context, zip)
            status.value = "Wysłano, czekam na potwierdzenie z zegarka…" +
                if (failed > 0) " (nie pobrano $failed kafelków)" else ""
        } catch (e: Exception) {
            status.value = "Błąd wysyłania map: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Returns the number of tiles that could not be fetched. */
    private suspend fun download(tiles: List<TileId>, dir: File): Int = coroutineScope {
        val done = AtomicInteger()
        val failed = AtomicInteger()
        // Two connections at a time is the limit OSM's tile servers ask for.
        for (worker in 0 until 2) {
            launch(Dispatchers.IO) {
                for (i in worker until tiles.size step 2) {
                    val t = tiles[i]
                    val f = t.file(dir)
                    if (!f.exists()) {
                        runCatching {
                            val bytes = httpGet(String.format(Locale.US, TILE_URL, t.z, t.x, t.y))
                            f.parentFile?.mkdirs()
                            f.writeBytes(bytes)
                        }.onFailure { failed.incrementAndGet() }
                    }
                    status.value = "Pobieram mapy: ${done.incrementAndGet()}/${tiles.size}"
                }
            }
        }
        failed
    }.get()

    private suspend fun send(context: Context, zip: File) {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            ?: error("zegarek nie jest połączony")
        val client = Wearable.getChannelClient(context)
        val channel = client.openChannel(node.id, Wire.PATH_TILES).await()
        val closed = CompletableDeferred<Unit>()
        val callback = object : ChannelClient.ChannelCallback() {
            override fun onOutputClosed(c: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
                closed.complete(Unit)
            }
        }
        client.registerChannelCallback(channel, callback).await()
        try {
            client.sendFile(channel, Uri.fromFile(zip)).await()
            withTimeout(15 * 60_000L) { closed.await() }
        } finally {
            client.unregisterChannelCallback(callback)
            client.close(channel)
        }
    }

    private fun TileId.file(dir: File) = File(dir, "$z/$x/$y.png")
}
