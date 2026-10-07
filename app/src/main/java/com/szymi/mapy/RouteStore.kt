package com.szymi.mapy

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.szymi.mapy.shared.Route
import com.szymi.mapy.shared.Stop
import com.szymi.mapy.shared.StopStatus
import com.szymi.mapy.shared.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class NamedRoute(val id: String, val name: String, val route: Route)

/**
 * The phone owns the routes. One of them is active: it is the one being edited
 * and the one the watch shows. Every change is saved, pushed to the watch and,
 * when the line itself changed, re-routed in the background.
 */
object RouteStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val io = Dispatchers.IO.limitedParallelism(1)

    private val _route = MutableStateFlow(Route())
    /** The active route. */
    val route: StateFlow<Route> = _route

    private val _routes = MutableStateFlow<List<NamedRoute>>(emptyList())
    val routes: StateFlow<List<NamedRoute>> = _routes

    private val _activeId = MutableStateFlow("")
    val activeId: StateFlow<String> = _activeId

    private lateinit var app: Context
    private var rerouteJob: Job? = null
    private val file get() = File(app.filesDir, "routes.json")

    fun init(context: Context) {
        app = context.applicationContext
        runCatching { load(file.readText()) }
        if (_routes.value.isEmpty()) {
            val first = NamedRoute(newId(), "Trasa 1", Route())
            _routes.value = listOf(first)
            _activeId.value = first.id
        }
        activate()
    }

    // --- routes ---

    fun selectRoute(id: String) {
        if (id == _activeId.value || _routes.value.none { it.id == id }) return
        storeActive()
        _activeId.value = id
        activate()
    }

    fun newRoute(name: String) {
        storeActive()
        val created = NamedRoute(newId(), name, Route())
        _routes.update { it + created }
        _activeId.value = created.id
        activate()
    }

    fun renameActiveRoute(name: String) {
        _routes.update { list -> list.map { if (it.id == _activeId.value) it.copy(name = name) else it } }
        save()
    }

    fun deleteActiveRoute() {
        _routes.update { list -> list.filter { it.id != _activeId.value } }
        if (_routes.value.isEmpty()) _routes.value = listOf(NamedRoute(newId(), "Trasa 1", Route()))
        _activeId.value = _routes.value.first().id
        activate()
    }

    // --- stops of the active route ---

    fun addStop(name: String, lat: Double, lon: Double) = changeLine { it + Stop(newId(), name, lat, lon) }

    fun removeStop(id: String) = changeLine { stops -> stops.filter { it.id != id } }

    fun rename(id: String, name: String) {
        _route.update { r -> r.copy(stops = r.stops.map { if (it.id == id) it.copy(name = name) else it }) }
        save()
    }

    /** Live reordering while dragging; call [commitOrder] when the drag ends. */
    fun move(from: Int, to: Int) {
        _route.update { r ->
            if (from !in r.stops.indices || to !in r.stops.indices) return@update r
            r.copy(stops = r.stops.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    fun commitOrder() = changeLine { it }

    fun setStatus(id: String, status: StopStatus) {
        val old = _route.value.stops.firstOrNull { it.id == id }?.status ?: return
        if (old == status) return
        if (old == StopStatus.SKIPPED || status == StopStatus.SKIPPED) {
            // Skipped stops are left out of the line, so it has to be drawn again.
            changeLine { stops -> stops.map { if (it.id == id) it.copy(status = status) else it } }
        } else {
            _route.update { it.withStatus(id, status) }
            save()
        }
    }

    fun resetProgress() = changeLine { stops -> stops.map { it.copy(status = StopStatus.PENDING) } }

    fun clear() = changeLine { emptyList() }

    /** Commands sent by the watch, see [Wire]. */
    fun applyCommand(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (o.optString("t")) {
            "remove" -> removeStop(o.getString("id"))
            "status" -> setStatus(o.getString("id"), StopStatus.valueOf(o.getString("s")))
            "add" -> {
                // "Here, where I'm standing": already visited, placed before the next target.
                val stop = Stop(
                    id = newId(),
                    name = "Punkt " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    status = StopStatus.DONE,
                )
                changeLine { stops ->
                    val index = stops.indexOfFirst { it.status == StopStatus.PENDING }
                    if (index < 0) stops + stop else stops.toMutableList().apply { add(index, stop) }
                }
            }
        }
    }

    // --- internals ---

    private fun newId() = UUID.randomUUID().toString()

    /** Makes the route with [_activeId] the working copy and publishes it. */
    private fun activate() {
        rerouteJob?.cancel()
        val active = _routes.value.first { it.id == _activeId.value }.route
        _route.value = active
        // Imported routes arrive without a line.
        if (!active.routed && active.activeStops.size >= 2) changeLine { it } else save()
    }

    private fun changeLine(transform: (List<Stop>) -> List<Stop>) {
        _route.update { Route(transform(it.stops)).straight() }
        save()
        rerouteJob?.cancel()
        rerouteJob = scope.launch {
            delay(400)
            val stops = _route.value.activeStops
            val routed = Router.route(stops) ?: return@launch
            _route.update { current ->
                if (current.activeStops != stops) current
                else current.copy(geometry = routed.geometry, distanceM = routed.distanceM, routed = true)
            }
            save()
        }
    }

    private fun storeActive() {
        _routes.update { list -> list.map { if (it.id == _activeId.value) it.copy(route = _route.value) else it } }
    }

    private fun save() {
        storeActive()
        val array = JSONArray()
        for (r in _routes.value) {
            array.put(JSONObject().put("id", r.id).put("name", r.name).put("route", JSONObject(r.route.toJson())))
        }
        val all = JSONObject().put("active", _activeId.value).put("routes", array).toString()
        scope.launch(io) { runCatching { file.writeText(all) } }

        val request = PutDataMapRequest.create(Wire.PATH_ROUTE).apply {
            dataMap.putString(Wire.KEY_JSON, _route.value.toJson())
            dataMap.putLong("ts", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(app).putDataItem(request)
    }

    private fun load(json: String) {
        val o = JSONObject(json)
        val array = o.getJSONArray("routes")
        val list = (0 until array.length()).map { i ->
            val r = array.getJSONObject(i)
            NamedRoute(r.getString("id"), r.getString("name"), Route.fromJson(r.getJSONObject("route").toString()))
        }
        if (list.isEmpty()) return
        _routes.value = list
        val active = o.optString("active")
        _activeId.value = if (list.any { it.id == active }) active else list.first().id
    }
}
