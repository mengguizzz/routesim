package com.opensource.routesim.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.opensource.routesim.MainActivity
import com.opensource.routesim.R
import com.opensource.routesim.core.LatLng
import com.opensource.routesim.core.MotionMode
import com.opensource.routesim.core.NativeKinematics
import com.opensource.routesim.core.RootMockManager
import com.opensource.routesim.core.SimulationConfig
import com.opensource.routesim.core.SimulationLogger
import com.opensource.routesim.core.SimulationSnapshot
import kotlin.math.roundToInt

/**
 * Foreground simulation owner. It survives temporary Activity backgrounding and
 * is the only component that writes to the location test providers.
 */
class SimulationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var running = false
    private var mode = MotionMode.RUNNING
    private var config: SimulationConfig? = null
    private var lastTickNanos = 0L
    private var lastSnapshot: SimulationSnapshot? = null
    private var tickNumber = 0L

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopSimulation("user_stop")
            else -> {
                SimulationLogger.warning("service_empty_intent")
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        if (running) {
            RootMockManager.cleanupProviders(locationManager)
            SimulationLogger.event("service_destroyed_while_running")
        }
        releaseWakeLock()
        running = false
        isRunning = false
        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        if (running) {
            SimulationLogger.warning("service_start_ignored", "reason" to "already_running")
            return
        }

        val routeValues = intent.getDoubleArrayExtra(EXTRA_ROUTE) ?: DoubleArray(0)
        if (routeValues.size < 4 || routeValues.size % 2 != 0) {
            failStart("路线数据无效")
            return
        }

        val selectedMode = MotionMode.entries.firstOrNull {
            it.name == intent.getStringExtra(EXTRA_MODE)
        } ?: MotionMode.RUNNING
        val selectedConfig = SimulationConfig(
            mode = selectedMode,
            minSpeedKmh = intent.getFloatExtra(EXTRA_MIN_SPEED, selectedMode.defaultSpeedKmh.start),
            maxSpeedKmh = intent.getFloatExtra(EXTRA_MAX_SPEED, selectedMode.defaultSpeedKmh.endInclusive),
            minHoldSeconds = intent.getFloatExtra(
                EXTRA_MIN_HOLD,
                selectedMode.defaultHoldSeconds.start,
            ),
            maxHoldSeconds = intent.getFloatExtra(
                EXTRA_MAX_HOLD,
                selectedMode.defaultHoldSeconds.endInclusive,
            ),
            targetLaps = intent.getIntExtra(EXTRA_TARGET_LAPS, 1),
        )

        val points = routeValues.toList().chunked(2).map { pair ->
            LatLng(pair[0], pair[1])
        }

        mode = selectedMode
        config = selectedConfig
        tickNumber = 0L
        lastSnapshot = null
        startForegroundNow(buildNotification("正在准备模拟", "Root 定位通道"))
        SimulationLogger.startSession(
            "${selectedMode.name.lowercase()}-${System.currentTimeMillis().toString().takeLast(7)}",
        )
        SimulationLogger.event(
            "service_start",
            "mode" to selectedMode.name,
            "route_points" to points.size,
            "speed_kmh" to "${selectedConfig.minSpeedKmh}..${selectedConfig.maxSpeedKmh}",
            "hold_sec" to "${selectedConfig.minHoldSeconds}..${selectedConfig.maxHoldSeconds}",
            "target_laps" to selectedConfig.targetLaps,
            "interval_ms" to selectedConfig.sampleIntervalMs,
        )

        try {
            NativeKinematics.initialize(points, selectedConfig)
            RootMockManager.initProviders(locationManager)
        } catch (error: Exception) {
            SimulationLogger.error("service_init_failed", error)
            failStart("无法建立定位通道：${error.message ?: "未知错误"}")
            return
        }

        running = true
        isRunning = true
        acquireWakeLock()
        lastTickNanos = SystemClock.elapsedRealtimeNanos()
        broadcastState(
            status = "模拟已启动",
            playing = true,
            finished = false,
        )
        handler.postDelayed(tickRunnable, selectedConfig.sampleIntervalMs)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val activeConfig = config ?: return

            try {
                val now = SystemClock.elapsedRealtimeNanos()
                val dtSeconds = ((now - lastTickNanos) / 1_000_000_000.0).coerceIn(0.05, 3.0)
                lastTickNanos = now
                val snapshot = NativeKinematics.tick(dtSeconds)
                lastSnapshot = snapshot
                tickNumber++
                if (tickNumber % WAKE_LOCK_REFRESH_TICKS == 0L) {
                    releaseWakeLock()
                    acquireWakeLock()
                }

                RootMockManager.pushLocationToSystem(
                    locationManager = locationManager,
                    latitude = snapshot.latitude,
                    longitude = snapshot.longitude,
                    speedMps = snapshot.speedMps,
                    bearingDegrees = snapshot.bearingDegrees,
                    accuracyMeters = snapshot.accuracyMeters,
                    isMoving = !snapshot.finished,
                )

                SimulationLogger.event(
                    "gps_sample",
                    "n" to tickNumber,
                    "dt" to "%.3f".format(java.util.Locale.US, dtSeconds),
                    "lat" to "%.7f".format(java.util.Locale.US, snapshot.latitude),
                    "lng" to "%.7f".format(java.util.Locale.US, snapshot.longitude),
                    "speed_kmh" to "%.2f".format(java.util.Locale.US, snapshot.speedKmh),
                    "bearing" to "%.1f".format(java.util.Locale.US, snapshot.bearingDegrees),
                    "cadence" to snapshot.cadenceSpm,
                    "steps" to snapshot.totalSteps,
                    "distance_m" to "%.1f".format(java.util.Locale.US, snapshot.distanceMeters),
                    "lap" to snapshot.lapCount,
                    "finished" to snapshot.finished,
                )

                if (tickNumber % 5L == 0L) {
                    updateNotification(snapshot)
                }
                broadcastState(
                    status = buildStatusText(snapshot),
                    playing = true,
                    finished = snapshot.finished,
                )

                if (snapshot.finished) {
                    stopSimulation("route_finished")
                } else {
                    handler.postDelayed(this, activeConfig.sampleIntervalMs)
                }
            } catch (error: Exception) {
                SimulationLogger.error("simulation_tick_failed", error)
                failDuringRun(error)
            }
        }
    }

    private fun stopSimulation(reason: String) {
        handler.removeCallbacks(tickRunnable)
        val snapshot = lastSnapshot
        if (running) {
            RootMockManager.cleanupProviders(locationManager)
        }
        running = false
        isRunning = false
        releaseWakeLock()
        config = null
        SimulationLogger.event(
            "service_stop",
            "reason" to reason,
            "elapsed_sec" to (snapshot?.elapsedSeconds ?: 0.0),
            "distance_m" to (snapshot?.distanceMeters ?: 0.0),
        )
        broadcastState(
            status = if (reason == "route_finished") "已到达终点" else "模拟已停止",
            playing = false,
            finished = reason == "route_finished",
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failStart(message: String) {
        running = false
        isRunning = false
        releaseWakeLock()
        SimulationLogger.error("service_start_rejected", null, "reason" to message)
        broadcastState(status = message, playing = false, finished = false, error = message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failDuringRun(error: Exception) {
        handler.removeCallbacks(tickRunnable)
        if (running) {
            RootMockManager.cleanupProviders(locationManager)
        }
        running = false
        isRunning = false
        releaseWakeLock()
        SimulationLogger.event("service_stopped_after_error", "message" to error.message)
        broadcastState(
            status = "模拟异常：${error.message ?: "未知错误"}",
            playing = false,
            finished = false,
            error = error.message,
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildStatusText(snapshot: SimulationSnapshot): String {
        val base = "时速 ${"%.1f".format(java.util.Locale.US, snapshot.speedKmh)} km/h · " +
                "距离 ${"%.2f".format(java.util.Locale.US, snapshot.distanceMeters / 1000.0)} km"
        return when (mode) {
            MotionMode.RUNNING -> "$base · ${snapshot.cadenceSpm} spm"
            MotionMode.CYCLING -> "$base · 踏频 ${snapshot.cadenceSpm}"
            MotionMode.DRIVING -> base
        }
    }

    private fun broadcastState(
        status: String,
        playing: Boolean,
        finished: Boolean,
        error: String? = null,
    ) {
        val snapshot = lastSnapshot
        val intent = Intent(ACTION_STATE)
            .setPackage(packageName)
            .putExtra(STATE_STATUS, status)
            .putExtra(STATE_PLAYING, playing)
            .putExtra(STATE_FINISHED, finished)
            .putExtra(STATE_ERROR, error)
            .putExtra(STATE_MODE, mode.name)
            .putExtra(STATE_TARGET_LAPS, config?.targetLaps ?: 1)
        if (snapshot != null) {
            intent
                .putExtra(STATE_LAT, snapshot.latitude)
                .putExtra(STATE_LNG, snapshot.longitude)
                .putExtra(STATE_SPEED, snapshot.speedKmh)
                .putExtra(STATE_BEARING, snapshot.bearingDegrees.toFloat())
                .putExtra(STATE_CADENCE, snapshot.cadenceSpm)
                .putExtra(STATE_STEPS, snapshot.totalSteps)
                .putExtra(STATE_DISTANCE_M, snapshot.distanceMeters)
                .putExtra(STATE_ELAPSED_SEC, snapshot.elapsedSeconds)
                .putExtra(STATE_LAP, snapshot.lapCount)
        }
        sendBroadcast(intent)
    }

    private fun startForegroundNow(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        finished: Boolean = false,
    ): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SimulationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (!finished) {
                    addAction(R.drawable.ic_stop, "停止", stopIntent)
                }
            }
            .build()
    }

    private fun updateNotification(snapshot: SimulationSnapshot, finished: Boolean = false) {
        val speed = snapshot.speedKmh.roundToInt()
        val distance = String.format(
            java.util.Locale.US,
            "%.2f km",
            snapshot.distanceMeters / 1000.0,
        )
        val title = if (finished) "模拟已结束" else "${mode.displayName}模拟运行中"
        val text = if (finished) distance else "$speed km/h · $distance"
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(title, text, finished))
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "路线模拟",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示正在运行的虚拟定位路线"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Routesim:LocationSimulation",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        SimulationLogger.event("wake_lock_acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                SimulationLogger.event("wake_lock_released")
            }
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "route_simulation"
        private const val NOTIFICATION_ID = 4107
        private const val WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1_000L
        private const val WAKE_LOCK_REFRESH_TICKS = 300L

        @Volatile
        var isRunning: Boolean = false
            private set

        const val ACTION_START = "com.opensource.routesim.action.START"
        const val ACTION_STOP = "com.opensource.routesim.action.STOP"
        const val ACTION_STATE = "com.opensource.routesim.action.STATE"

        private const val EXTRA_ROUTE = "route"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_MIN_SPEED = "min_speed"
        private const val EXTRA_MAX_SPEED = "max_speed"
        private const val EXTRA_MIN_HOLD = "min_hold"
        private const val EXTRA_MAX_HOLD = "max_hold"
        private const val EXTRA_TARGET_LAPS = "target_laps"

        const val STATE_STATUS = "status"
        const val STATE_PLAYING = "playing"
        const val STATE_FINISHED = "finished"
        const val STATE_ERROR = "error"
        const val STATE_MODE = "state_mode"
        const val STATE_TARGET_LAPS = "state_target_laps"
        const val STATE_LAT = "lat"
        const val STATE_LNG = "lng"
        const val STATE_SPEED = "speed"
        const val STATE_BEARING = "bearing"
        const val STATE_CADENCE = "cadence"
        const val STATE_STEPS = "steps"
        const val STATE_DISTANCE_M = "distance_m"
        const val STATE_ELAPSED_SEC = "elapsed_sec"
        const val STATE_LAP = "lap"

        fun start(context: Context, route: List<LatLng>, config: SimulationConfig) {
            val flatRoute = DoubleArray(route.size * 2)
            route.forEachIndexed { index, point ->
                flatRoute[index * 2] = point.latitude
                flatRoute[index * 2 + 1] = point.longitude
            }
            val intent = Intent(context, SimulationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ROUTE, flatRoute)
                .putExtra(EXTRA_MODE, config.mode.name)
                .putExtra(EXTRA_MIN_SPEED, config.minSpeedKmh)
                .putExtra(EXTRA_MAX_SPEED, config.maxSpeedKmh)
                .putExtra(EXTRA_MIN_HOLD, config.minHoldSeconds)
                .putExtra(EXTRA_MAX_HOLD, config.maxHoldSeconds)
                .putExtra(EXTRA_TARGET_LAPS, config.targetLaps)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SimulationService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
