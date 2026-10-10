package com.szymi.mapy.wear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import com.szymi.mapy.shared.Geo
import com.szymi.mapy.shared.Polyline
import com.szymi.mapy.shared.StopStatus
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

// Tile zoom level and how much the tiles are magnified on top of the screen density.
// Offline packs hold zooms 14..16, so the closest step enlarges zoom 16.
private val ZOOM_STEPS = listOf(14 to 1f, 15 to 1f, 16 to 1f, 16 to 2f)

private const val FAR_FROM_ROUTE_M = 50_000.0

private val ROUTE_COLOR = Color(0xFF1565C0)
private val NEXT_COLOR = Color(0xFFEF6C00)
private val DONE_COLOR = Color(0xFF9E9E9E)
private val ME_COLOR = Color(0xFF2979FF)

@Composable
fun MapScreen(onMenu: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val route by WatchState.route.collectAsState()
    val fix by WatchState.fix.collectAsState()
    val tilesVersion by WatchState.tilesVersion.collectAsState()

    var zoomStep by rememberSaveable { mutableIntStateOf(2) }
    var follow by remember { mutableStateOf(true) }
    var panX by remember { mutableDoubleStateOf(0.0) }
    var panY by remember { mutableDoubleStateOf(0.0) }

    val (zoom, magnify) = ZOOM_STEPS[zoomStep]
    val tileSize = 256f * density * magnify
    val worldPx = tileSize.toDouble() * (1 shl zoom)

    val next = route.nextStop
    val anchor = next ?: route.stops.firstOrNull()
    // Follow my position. Before the first fix, or when the route is somewhere else entirely
    // (planning a trip from home), show the next stop instead: that is where the offline map is.
    val here = fix?.takeIf { f ->
        route.stops.isEmpty() || route.stops.any { Geo.distanceM(f.lat, f.lon, it.lat, it.lon) < FAR_FROM_ROUTE_M }
    }
    val home = here?.let { Geo.mercX(it.lon) to Geo.mercY(it.lat) }
        ?: anchor?.let { Geo.mercX(it.lon) to Geo.mercY(it.lat) }
        ?: (Geo.mercX(19.4) to Geo.mercY(52.1))
    val center = if (follow) home else panX to panY
    val currentCenter by rememberUpdatedState(center)
    val currentWorldPx by rememberUpdatedState(worldPx)

    val line = remember(route.geometry) {
        Polyline.decode(route.geometry).map { Geo.mercX(it.lon) to Geo.mercY(it.lat) }
    }
    val tiles = remember(tilesVersion) { TileCache(File(context.filesDir, WearSyncService.TILES_DIR)) }
    val numberPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 11 * density
            isFakeBoldText = true
        }
    }

    val focusRequester = remember { FocusRequester() }
    var crown by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF202124))
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { event ->
                    crown += event.verticalScrollPixels
                    val threshold = 48 * density
                    if (abs(crown) > threshold) {
                        zoomStep = (zoomStep + if (crown > 0) 1 else -1).coerceIn(ZOOM_STEPS.indices)
                        crown = 0f
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            panX = currentCenter.first
                            panY = currentCenter.second
                            follow = false
                        },
                    ) { change, drag ->
                        change.consume()
                        panX -= drag.x / currentWorldPx
                        panY = (panY - drag.y / currentWorldPx).coerceIn(0.0, 1.0)
                    }
                }
        ) {
            val left = center.first * worldPx - size.width / 2
            val top = center.second * worldPx - size.height / 2
            fun screen(p: Pair<Double, Double>) =
                Offset((p.first * worldPx - left).toFloat(), (p.second * worldPx - top).toFloat())

            val tileCount = 1 shl zoom
            val drawSize = IntSize(ceil(tileSize).toInt() + 1, ceil(tileSize).toInt() + 1)
            for (tx in floor(left / tileSize).toInt()..floor((left + size.width) / tileSize).toInt()) {
                for (ty in floor(top / tileSize).toInt()..floor((top + size.height) / tileSize).toInt()) {
                    if (ty !in 0 until tileCount) continue
                    val image = tiles.get(zoom, Math.floorMod(tx, tileCount), ty) ?: continue
                    drawImage(
                        image,
                        dstOffset = IntOffset(
                            floor(tx * tileSize.toDouble() - left).toInt(),
                            floor(ty * tileSize.toDouble() - top).toInt(),
                        ),
                        dstSize = drawSize,
                    )
                }
            }

            if (line.size >= 2) {
                val path = Path()
                line.forEachIndexed { index, point ->
                    val o = screen(point)
                    if (index == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                fun stroke(width: Float) = Stroke(width * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(path, Color.White, style = stroke(7f))
                drawPath(path, ROUTE_COLOR, style = stroke(4f))
            }

            route.stops.forEachIndexed { index, stop ->
                if (stop.status == StopStatus.SKIPPED) return@forEachIndexed
                val o = screen(Geo.mercX(stop.lon) to Geo.mercY(stop.lat))
                val color = when {
                    stop.status == StopStatus.DONE -> DONE_COLOR
                    stop.id == next?.id -> NEXT_COLOR
                    else -> ROUTE_COLOR
                }
                drawCircle(Color.White, radius = 11 * density, center = o)
                drawCircle(color, radius = 9 * density, center = o)
                drawIntoCanvas {
                    it.nativeCanvas.drawText(
                        (index + 1).toString(),
                        o.x,
                        o.y - (numberPaint.ascent() + numberPaint.descent()) / 2,
                        numberPaint,
                    )
                }
            }

            fix?.let { f ->
                val o = screen(Geo.mercX(f.lon) to Geo.mercY(f.lat))
                val metersPerPx = Geo.EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(f.lat)) / worldPx
                drawCircle(ME_COLOR.copy(alpha = 0.18f), radius = (f.accuracyM / metersPerPx).toFloat(), center = o)
                drawCircle(Color.White, radius = 8 * density, center = o)
                drawCircle(ME_COLOR, radius = 6 * density, center = o)
            }
        }

        val label = when {
            route.stops.isEmpty() -> "Dodaj przystanki w telefonie"
            next == null -> "Trasa ukończona"
            else -> {
                val f = fix
                if (f == null) "${next.name} · szukam GPS…"
                else "${next.name} · ${formatDistance(Geo.distanceM(f.lat, f.lon, next.lat, next.lon))}"
            }
        }
        Text(
            label,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
                .fillMaxWidth(0.62f)
                .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )

        if (!follow) {
            MapButton("◎", Modifier.align(Alignment.CenterStart).padding(start = 6.dp)) { follow = true }
        }
        MapButton("✕", Modifier.align(Alignment.CenterEnd).padding(end = 6.dp), onClick = onClose)
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MapButton("−") { zoomStep = (zoomStep - 1).coerceAtLeast(0) }
            MapButton("≡", onClick = onMenu)
            MapButton("+") { zoomStep = (zoomStep + 1).coerceAtMost(ZOOM_STEPS.lastIndex) }
        }
    }
}

@Composable
private fun MapButton(symbol: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.size(ButtonDefaults.ExtraSmallButtonSize),
        colors = ButtonDefaults.secondaryButtonColors(),
    ) { Text(symbol) }
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} m" else String.format(Locale.US, "%.1f km", meters / 1000)

/** Decoded offline tiles; a new instance is created whenever a fresh pack arrives. */
private class TileCache(private val dir: File) {
    private val cache = LruCache<Long, ImageBitmap>(24)
    private val missing = HashSet<Long>()
    private val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

    fun get(z: Int, x: Int, y: Int): ImageBitmap? {
        val key = (z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()
        cache.get(key)?.let { return it }
        if (key in missing) return null
        val bitmap = BitmapFactory.decodeFile(File(dir, "$z/$x/$y.png").path, options)
        if (bitmap == null) {
            missing += key
            return null
        }
        return bitmap.asImageBitmap().also { cache.put(key, it) }
    }
}
