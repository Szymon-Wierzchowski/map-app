package com.szymi.mapy.wear

import android.net.Uri
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.szymi.mapy.shared.Wire
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.zip.ZipFile

/** Receives route updates and offline map packs from the phone. */
class WearSyncService : WearableListenerService() {
    private val incoming get() = File(cacheDir, "tiles_incoming.zip")

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == Wire.PATH_ROUTE) {
                WatchState.setRouteJson(DataMapItem.fromDataItem(event.dataItem).dataMap.getString(Wire.KEY_JSON))
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != Wire.PATH_TILES) return
        incoming.delete()
        Wearable.getChannelClient(this).receiveFile(channel, Uri.fromFile(incoming), false)
    }

    override fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
        if (channel.path != Wire.PATH_TILES) return
        // A cut-off transfer has no zip directory, so it fails here and the old tiles stay.
        val count = runCatching { unpack(incoming) }.getOrDefault(0)
        incoming.delete()
        if (count > 0) {
            WatchState.tilesVersion.update { it + 1 }
            Wearable.getMessageClient(this)
                .sendMessage(channel.nodeId, Wire.PATH_TILES_OK, count.toString().toByteArray())
        }
    }

    private fun unpack(zip: File): Int {
        val fresh = File(filesDir, "tiles_new").apply { deleteRecursively() }
        var count = 0
        ZipFile(zip).use { archive ->
            for (entry in archive.entries()) {
                if (!TILE_NAME.matches(entry.name)) continue
                val target = File(fresh, entry.name)
                target.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                count++
            }
        }
        if (count > 0) {
            val current = File(filesDir, TILES_DIR)
            current.deleteRecursively()
            fresh.renameTo(current)
        }
        return count
    }

    companion object {
        const val TILES_DIR = "tiles"
        private val TILE_NAME = Regex("""\d+/\d+/\d+\.png""")
    }
}
