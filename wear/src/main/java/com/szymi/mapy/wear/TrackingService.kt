package com.szymi.mapy.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.szymi.mapy.shared.Geo
import com.szymi.mapy.shared.StopStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps position updates coming while the screen is off
 * or the watch face is showing, and reacts to reaching stops.
 */
class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var client: FusedLocationProviderClient
    private var modeJob: Job? = null
    private val approached = HashSet<String>()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            WatchState.fix.value = Fix(location.latitude, location.longitude, location.accuracy)
            checkArrival(location)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val started = granted && runCatching { startInForeground() }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        WatchState.tracking.value = true
        if (modeJob == null) {
            modeJob = scope.launch { WatchState.powerSave.collect { requestUpdates(it) } }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        scope.cancel()
        WatchState.tracking.value = false
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(powerSave: Boolean) {
        client.removeLocationUpdates(callback)
        // The distance filter stops updates (and wake-ups) while standing still.
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, if (powerSave) 10_000L else 3_000L)
            .setMinUpdateIntervalMillis(if (powerSave) 5_000L else 2_000L)
            .setMinUpdateDistanceMeters(if (powerSave) 15f else 5f)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun checkArrival(location: Location) {
        if (location.accuracy > MAX_ACCURACY_M) return
        val next = WatchState.route.value.nextStop ?: return
        val distance = Geo.distanceM(location.latitude, location.longitude, next.lat, next.lon)
        if (distance < ARRIVE_M) {
            vibrate(longArrayOf(0, 250, 150, 250))
            WatchState.setStatus(this, next.id, StopStatus.DONE)
        } else if (distance < APPROACH_M && approached.add(next.id)) {
            vibrate(longArrayOf(0, 150))
        }
    }

    private fun vibrate(pattern: LongArray) {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Śledzenie trasy", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_route)
            .setContentTitle("Mapy")
            .setContentText("Trasa aktywna")
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setContentIntent(open)
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_route)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate("Trasa aktywna").build())
            .build()
            .apply(applicationContext)
        startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    companion object {
        private const val CHANNEL = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val ARRIVE_M = 25.0
        private const val APPROACH_M = 100.0
        private const val MAX_ACCURACY_M = 60f

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
