package com.szymi.mapy

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.szymi.mapy.shared.Wire

class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Wire.PATH_CMD -> RouteStore.applyCommand(String(event.data))
            Wire.PATH_TILES_OK ->
                TilePack.status.value = "Mapy offline są na zegarku (${String(event.data)} kafelków)."
        }
    }
}
