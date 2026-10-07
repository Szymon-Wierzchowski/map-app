package com.szymi.mapy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.szymi.mapy.shared.Route
import com.szymi.mapy.shared.Stop
import com.szymi.mapy.shared.StopStatus
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.util.Locale
import org.osmdroid.views.overlay.Polyline as MapPolyline

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                MapyScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapyScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val route by RouteStore.route.collectAsState()
    val packStatus by TilePack.status.collectAsState()

    var hasLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> hasLocation = granted.values.any { it } }
    LaunchedEffect(Unit) {
        if (!hasLocation) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    val routes by RouteStore.routes.collectAsState()
    val activeId by RouteStore.activeId.collectAsState()
    val activeName = routes.firstOrNull { it.id == activeId }?.name ?: "Trasa"

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var renaming by remember { mutableStateOf<Stop?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var routesOpen by remember { mutableStateOf(false) }
    var routeDialog by remember { mutableStateOf<RouteDialog?>(null) }

    LaunchedEffect(activeId, mapView) { mapView?.fitTo(RouteStore.route.value) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clickable { routesOpen = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(activeName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Wybierz trasę")
                        }
                        Text(summary(route), style = MaterialTheme.typography.bodySmall)
                        DropdownMenu(expanded = routesOpen, onDismissRequest = { routesOpen = false }) {
                            for (r in routes) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            r.name,
                                            fontWeight = if (r.id == activeId) FontWeight.Bold else null,
                                        )
                                    },
                                    onClick = { routesOpen = false; RouteStore.selectRoute(r.id) },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Nowa trasa…") },
                                onClick = { routesOpen = false; routeDialog = RouteDialog.NEW },
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { TilePack.buildAndSend(context, route) } }) {
                        Icon(Icons.Default.Watch, contentDescription = "Wyślij mapy offline na zegarek")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Więcej")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Pokaż całą trasę") },
                            onClick = { menuOpen = false; mapView?.fitTo(route) },
                        )
                        DropdownMenuItem(
                            text = { Text("Zacznij od nowa (odznacz wszystkie)") },
                            onClick = { menuOpen = false; RouteStore.resetProgress() },
                        )
                        DropdownMenuItem(
                            text = { Text("Zmień nazwę trasy") },
                            onClick = { menuOpen = false; routeDialog = RouteDialog.RENAME },
                        )
                        DropdownMenuItem(
                            text = { Text("Usuń trasę") },
                            onClick = { menuOpen = false; routeDialog = RouteDialog.DELETE },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            RouteMap(
                route = route,
                hasLocation = hasLocation,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onReady = { mapView = it },
            )
            packStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            SearchBox(mapView)
            StopList(route, Modifier.weight(1f), onRename = { renaming = it })
        }
    }

    renaming?.let { stop ->
        NameDialog("Nazwa przystanku", stop.name, onDismiss = { renaming = null }) {
            RouteStore.rename(stop.id, it)
        }
    }
    when (routeDialog) {
        RouteDialog.NEW -> NameDialog("Nowa trasa", "", onDismiss = { routeDialog = null }) {
            RouteStore.newRoute(it)
        }
        RouteDialog.RENAME -> NameDialog("Nazwa trasy", activeName, onDismiss = { routeDialog = null }) {
            RouteStore.renameActiveRoute(it)
        }
        RouteDialog.DELETE -> AlertDialog(
            onDismissRequest = { routeDialog = null },
            title = { Text("Usunąć trasę?") },
            text = { Text("„$activeName” zostanie usunięta razem z przystankami.") },
            confirmButton = {
                TextButton(onClick = { RouteStore.deleteActiveRoute(); routeDialog = null }) { Text("Usuń") }
            },
            dismissButton = { TextButton(onClick = { routeDialog = null }) { Text("Anuluj") } },
        )
        null -> {}
    }
}

private enum class RouteDialog { NEW, RENAME, DELETE }

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onConfirm(name.trim())
                onDismiss()
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

private fun summary(route: Route): String {
    if (route.stops.isEmpty()) return "Brak przystanków"
    val km = String.format(Locale.US, "%.1f km", route.distanceM / 1000)
    val straight = if (!route.routed && route.activeStops.size >= 2) " (linie proste)" else ""
    return "Przystanki: ${route.stops.size} · $km$straight"
}

@Composable
private fun SearchBox(mapView: MapView?) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    fun search() {
        if (query.isBlank()) return
        message = "Szukam…"
        scope.launch {
            results = runCatching { Geocoder.search(query.trim(), mapView?.boundingBox) }.getOrElse {
                message = "Wyszukiwanie nie działa. Sprawdź internet."
                return@launch
            }
            message = if (results.isEmpty()) "Nic nie znaleziono." else null
        }
    }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Dodaj przystanek: nazwa lub adres") },
            trailingIcon = {
                Row {
                    if (query.isNotEmpty() || results.isNotEmpty()) {
                        IconButton(onClick = { query = ""; results = emptyList(); message = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Wyczyść")
                        }
                    }
                    IconButton(onClick = { search() }) {
                        Icon(Icons.Default.Search, contentDescription = "Szukaj")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp)) }
        for (place in results) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        RouteStore.addStop(place.name, place.lat, place.lon)
                        mapView?.controller?.animateTo(GeoPoint(place.lat, place.lon), 16.0, null)
                        query = ""
                        results = emptyList()
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    place.detail,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun StopList(route: Route, modifier: Modifier, onRename: (Stop) -> Unit) {
    if (route.stops.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "Wyszukaj miejsce albo przytrzymaj palec na mapie, żeby dodać przystanek.",
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        RouteStore.move(from.index, to.index)
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        itemsIndexed(route.stops, key = { _, stop -> stop.id }) { index, stop ->
            ReorderableItem(reorderState, key = stop.id) { isDragging ->
                Surface(tonalElevation = if (isDragging) 8.dp else 0.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = "Przeciągnij, żeby zmienić kolejność",
                            modifier = Modifier
                                .draggableHandle(onDragStopped = { RouteStore.commitOrder() })
                                .padding(14.dp),
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { onRename(stop) }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                "${index + 1}. ${stop.name}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textDecoration = if (stop.status == StopStatus.PENDING) null
                                else TextDecoration.LineThrough,
                            )
                            if (stop.status == StopStatus.SKIPPED) {
                                Text("pominięty", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Checkbox(
                            checked = stop.status == StopStatus.DONE,
                            onCheckedChange = {
                                RouteStore.setStatus(stop.id, if (it) StopStatus.DONE else StopStatus.PENDING)
                            },
                        )
                        IconButton(onClick = { RouteStore.removeStop(stop.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Usuń przystanek")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteMap(route: Route, hasLocation: Boolean, modifier: Modifier, onReady: (MapView) -> Unit) {
    val context = LocalContext.current
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(52.1, 19.4))
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint) = false
                override fun longPressHelper(p: GeoPoint): Boolean {
                    val number = RouteStore.route.value.stops.size + 1
                    RouteStore.addStop("Punkt $number", p.latitude, p.longitude)
                    return true
                }
            }))
            addOnFirstLayoutListener { _, _, _, _, _ -> fitTo(RouteStore.route.value) }
        }
    }
    val myLocation = remember { MyLocationNewOverlay(GpsMyLocationProvider(context), map) }

    LaunchedEffect(Unit) { onReady(map) }
    LaunchedEffect(hasLocation) {
        if (hasLocation) {
            myLocation.enableMyLocation()
            if (myLocation !in map.overlays) map.overlays.add(myLocation)
            myLocation.runOnFirstFix {
                val here = myLocation.myLocation ?: return@runOnFirstFix
                map.post { if (RouteStore.route.value.stops.isEmpty()) map.controller.animateTo(here, 15.0, null) }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            myLocation.disableMyLocation()
            map.onDetach()
        }
    }

    // osmdroid draws past its own bounds unless clipped.
    AndroidView(factory = { map }, modifier = modifier.clipToBounds(), update = { m ->
        m.overlays.removeAll { it is Marker || it is MapPolyline }
        val density = context.resources.displayMetrics.density
        val line = route.points().map { GeoPoint(it.lat, it.lon) }
        if (line.size >= 2) {
            m.overlays.add(MapPolyline(m).apply {
                setPoints(line)
                outlinePaint.color = 0xFF1565C0.toInt()
                outlinePaint.strokeWidth = 5 * density
                outlinePaint.strokeCap = Paint.Cap.ROUND
            })
        }
        val next = route.nextStop
        route.stops.forEachIndexed { index, stop ->
            val color = when {
                stop.status != StopStatus.PENDING -> 0xFF9E9E9E.toInt()
                stop.id == next?.id -> 0xFFEF6C00.toInt()
                else -> 0xFF1565C0.toInt()
            }
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(stop.lat, stop.lon)
                title = stop.name
                icon = numberIcon(context, index + 1, color)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            })
        }
        m.invalidate()
    })
}

private fun MapView.fitTo(route: Route) {
    val points = route.stops.map { GeoPoint(it.lat, it.lon) }
    when {
        points.isEmpty() -> return
        points.size == 1 -> {
            controller.setZoom(16.0)
            controller.setCenter(points[0])
        }
        else -> zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, 120)
    }
}

private fun numberIcon(context: Context, number: Int, color: Int): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val size = (28 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xFFFFFFFF.toInt()
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.color = color
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2 * density, paint)
    paint.color = 0xFFFFFFFF.toInt()
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = 13 * density
    paint.isFakeBoldText = true
    canvas.drawText(number.toString(), size / 2f, size / 2f - (paint.ascent() + paint.descent()) / 2, paint)
    return BitmapDrawable(context.resources, bitmap)
}
