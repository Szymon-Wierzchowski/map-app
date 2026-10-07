package com.szymi.mapy.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.szymi.mapy.shared.Route
import com.szymi.mapy.shared.StopStatus
import com.szymi.mapy.shared.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await

data class Fix(val lat: Double, val lon: Double, val accuracyM: Float)

/** Process-wide state shared by the activity, the tracking service and the sync service. */
object WatchState {
    val route = MutableStateFlow(Route())
    val fix = MutableStateFlow<Fix?>(null)
    val tracking = MutableStateFlow(false)
    val powerSave = MutableStateFlow(false)
    val tilesVersion = MutableStateFlow(0)

    private const val PREFS = "settings"
    private const val KEY_POWER_SAVE = "powerSave"

    fun init(context: Context) {
        powerSave.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_POWER_SAVE, false)
    }

    fun setPowerSave(context: Context, value: Boolean) {
        powerSave.value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_POWER_SAVE, value).apply()
    }

    fun setRouteJson(json: String?) {
        if (json != null) runCatching { route.value = Route.fromJson(json) }
    }

    /** Reads the route the phone last published; later changes arrive through [WearSyncService]. */
    suspend fun loadRoute(context: Context) {
        val items = Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*${Wire.PATH_ROUTE}")).await()
        try {
            items.firstOrNull()?.let { setRouteJson(DataMapItem.fromDataItem(it).dataMap.getString(Wire.KEY_JSON)) }
        } finally {
            items.release()
        }
    }

    // The watch applies its own changes at once and tells the phone, which stays the
    // source of truth and publishes the (re-routed) result back.

    fun setStatus(context: Context, id: String, status: StopStatus) {
        route.update { it.withStatus(id, status) }
        send(context, Wire.cmdStatus(id, status))
    }

    fun remove(context: Context, id: String) {
        route.update { it.without(id) }
        send(context, Wire.cmdRemove(id))
    }

    /** Returns false when there is no position yet. */
    fun addHere(context: Context): Boolean {
        val here = fix.value ?: return false
        send(context, Wire.cmdAdd(here.lat, here.lon))
        return true
    }

    private fun send(context: Context, command: String) {
        val app = context.applicationContext
        Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
            for (node in nodes) {
                Wearable.getMessageClient(app).sendMessage(node.id, Wire.PATH_CMD, command.toByteArray())
            }
        }
    }
}
