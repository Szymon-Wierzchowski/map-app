package com.szymi.mapy.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.Text
import com.szymi.mapy.shared.StopStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Ambient support keeps this activity on screen when the display dims,
    // instead of the system dropping back to the watch face.
    private val ambientObserver =
        AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {})

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        WatchState.init(this)
        lifecycleScope.launch { runCatching { WatchState.loadRoute(applicationContext) } }
        setContent {
            MaterialTheme {
                WearApp(onExit = {
                    TrackingService.stop(this)
                    finish()
                })
            }
        }
    }
}

private sealed interface Screen {
    data object Map : Screen
    data object Stops : Screen
    data class Actions(val stopId: String) : Screen
}

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun WearApp(onExit: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasLocationPermission(context)) }
    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasLocationPermission(context)
    }
    LaunchedEffect(granted) {
        if (granted) TrackingService.start(context) else launcher.launch(permissions)
    }

    if (!granted) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Mapy potrzebują dostępu do lokalizacji.", textAlign = TextAlign.Center)
            MenuChip("Zezwól", onClick = { launcher.launch(permissions) })
        }
        return
    }

    var screen by remember { mutableStateOf<Screen>(Screen.Map) }
    val back = { screen = if (screen is Screen.Actions) Screen.Stops else Screen.Map }

    when (val current = screen) {
        Screen.Map -> MapScreen(onMenu = { screen = Screen.Stops })
        else -> {
            BackHandler(onBack = back)
            // The map needs sideways drags for panning, so only the menus are swipe-dismissable.
            SwipeToDismissBox(onDismissed = back) { isBackground ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    if (!isBackground) {
                        if (current is Screen.Actions) {
                            StopActionsScreen(current.stopId, onDone = { screen = Screen.Stops })
                        } else {
                            StopsScreen(
                                onStop = { screen = Screen.Actions(it) },
                                onBack = { screen = Screen.Map },
                                onExit = onExit,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StopsScreen(onStop: (String) -> Unit, onBack: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val route by WatchState.route.collectAsState()
    val powerSave by WatchState.powerSave.collectAsState()
    val next = route.nextStop

    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item {
            MenuChip("Dodaj przystanek tutaj", primary = true) {
                if (WatchState.addHere(context)) {
                    onBack()
                } else {
                    Toast.makeText(context, "Brak pozycji GPS", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (next != null) {
            item {
                MenuChip("Pomiń następny", secondary = next.name) {
                    WatchState.setStatus(context, next.id, StopStatus.SKIPPED)
                    onBack()
                }
            }
        }
        itemsIndexed(route.stops, key = { _, stop -> stop.id }) { index, stop ->
            val status = when (stop.status) {
                StopStatus.DONE -> "zaliczony"
                StopStatus.SKIPPED -> "pominięty"
                StopStatus.PENDING -> if (stop.id == next?.id) "następny" else null
            }
            MenuChip("${index + 1}. ${stop.name}", secondary = status) { onStop(stop.id) }
        }
        item {
            MenuChip(
                if (powerSave) "GPS: oszczędny" else "GPS: dokładny",
                secondary = if (powerSave) "co 10 s / 15 m" else "co 3 s / 5 m",
            ) { WatchState.setPowerSave(context, !powerSave) }
        }
        item { MenuChip("Zakończ śledzenie", onClick = onExit) }
    }
}

@Composable
private fun StopActionsScreen(stopId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val route by WatchState.route.collectAsState()
    val stop = route.stops.firstOrNull { it.id == stopId }
    if (stop == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    fun set(status: StopStatus) {
        WatchState.setStatus(context, stop.id, status)
        onDone()
    }
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { Text(stop.name, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (stop.status == StopStatus.PENDING) {
            item { MenuChip("Zaliczony", primary = true) { set(StopStatus.DONE) } }
            item { MenuChip("Pomiń") { set(StopStatus.SKIPPED) } }
        } else {
            item { MenuChip("Przywróć", primary = true) { set(StopStatus.PENDING) } }
        }
        item {
            MenuChip("Usuń") {
                WatchState.remove(context, stop.id)
                onDone()
            }
        }
    }
}

@Composable
private fun MenuChip(text: String, secondary: String? = null, primary: Boolean = false, onClick: () -> Unit) {
    Chip(
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        onClick = onClick,
        colors = if (primary) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
