package com.opensource.routesim

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import com.opensource.routesim.core.LatLng
import com.opensource.routesim.core.MotionMode
import com.opensource.routesim.core.RootMockManager
import com.opensource.routesim.core.RouteMath
import com.opensource.routesim.core.SimulationConfig
import com.opensource.routesim.core.SimulationLogger
import com.opensource.routesim.databinding.ActivityMainBinding
import com.opensource.routesim.service.SimulationService
import com.opensource.routesim.ui.AppTileSources
import com.opensource.routesim.ui.RouteDrawingOverlay
import org.osmdroid.config.Configuration as OsmConfiguration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private lateinit var locationManager: LocationManager
    private lateinit var routeDrawingOverlay: RouteDrawingOverlay
    private lateinit var routeCasing: Polyline
    private lateinit var routeCore: Polyline

    private val routePoints = mutableListOf<GeoPoint>()
    private val rootExecutor = Executors.newSingleThreadExecutor()
    private val preferences by lazy {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    private var rootReady = false
    private var isPlaying = false
    private var currentMode = MotionMode.RUNNING
    private var updatingSliders = false
    private var pendingStartAfterPermission = false
    private var currentPositionMarker: Marker? = null
    private var receiverRegistered = false
    private var disclaimerShowing = false
    private var tileSourceId = AppTileSources.OSM_GERMANY

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION]
            ?: hasFineLocationPermission()
        SimulationLogger.event(
            "permission_result",
            "fine_location" to fineGranted,
            "notification" to (
                result[POST_NOTIFICATIONS_PERMISSION]
                    ?: (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                ),
        )
        if (fineGranted) {
            centerOnLastKnownLocationIfAvailable()
        } else {
            toast(getString(R.string.permission_required))
        }
        if (pendingStartAfterPermission) {
            pendingStartAfterPermission = false
            if (fineGranted) {
                beginSimulation()
            }
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SimulationService.ACTION_STATE) return
            applyServiceState(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val lightBars = !isNightModeActive()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }

        configureOsmdroid()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        initMap()
        restoreRoute(savedInstanceState)
        setupControls()
        restoreUiPreferences()
        setupSystemBack()
        startRootCheck()

        if (SimulationService.isRunning) {
            setPlaybackUi(
                playing = true,
                status = "模拟服务运行中",
            )
        }
        SimulationLogger.event("activity_created")
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                stateReceiver,
                IntentFilter(SimulationService.ACTION_STATE),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        saveUiPreferences()
        super.onPause()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        rootExecutor.shutdownNow()
        routePoints.map { LatLng(it.latitude, it.longitude) }
        SimulationLogger.event(
            "activity_destroyed",
            "route_points" to routePoints.size,
            "service_running" to SimulationService.isRunning,
        )
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (routePoints.isNotEmpty()) {
            outState.putDoubleArray(
                STATE_ROUTE,
                DoubleArray(routePoints.size * 2).also { values ->
                    routePoints.forEachIndexed { index, point ->
                        values[index * 2] = point.latitude
                        values[index * 2 + 1] = point.longitude
                    }
                },
            )
        }
        outState.putString(STATE_MODE, currentMode.name)
        super.onSaveInstanceState(outState)
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }
    }

    private fun initMap() {
        mapView = binding.mapView
        tileSourceId = preferences.getString(PREF_TILE_SOURCE, AppTileSources.OSM_GERMANY)
            ?: AppTileSources.OSM_GERMANY
        applyTileSource(tileSourceId, persist = false)
        mapView.setMultiTouchControls(false)
        mapView.setFlingEnabled(false)
        mapView.setTilesScaledToDpi(true)
        mapView.minZoomLevel = 4.0
        mapView.maxZoomLevel = 20.0
        mapView.controller.setZoom(16.5)
        val savedLat = preferences.getString(PREF_MAP_LAT, null)?.toDoubleOrNull()
        val savedLng = preferences.getString(PREF_MAP_LNG, null)?.toDoubleOrNull()
        mapView.controller.setCenter(
            if (savedLat != null && savedLng != null) {
                GeoPoint(savedLat, savedLng)
            } else {
                GeoPoint(39.9087, 116.3975)
            },
        )

        routeCasing = Polyline().apply {
            outlinePaint.color = ColorUtils.setAlphaComponent(Color.WHITE, 235)
            outlinePaint.strokeWidth = 13f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
        routeCore = Polyline().apply {
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.route_accent)
            outlinePaint.strokeWidth = 7f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
        mapView.overlays.add(routeCasing)
        mapView.overlays.add(routeCore)

        routeDrawingOverlay = RouteDrawingOverlay(routePoints) {
            renderRoute()
        }
        mapView.overlays.add(routeDrawingOverlay)
        renderRoute()
    }

    private fun configureOsmdroid() {
        val osmdroidPreferences = getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        val basePath = File(filesDir, "osmdroid")
        val tileCache = File(basePath, "tiles")
        tileCache.mkdirs()
        OsmConfiguration.getInstance().apply {
            load(this@MainActivity, osmdroidPreferences)
            osmdroidBasePath = basePath
            osmdroidTileCache = tileCache
            userAgentValue = "Routesim/1.0 ($packageName)"
            save(this@MainActivity, osmdroidPreferences)
        }
        SimulationLogger.event(
            "map_cache_configured",
            "base_path" to basePath.absolutePath,
            "tile_cache" to tileCache.absolutePath,
        )
    }

    private fun setupControls() {
        binding.btnStartStop.setOnClickListener {
            if (isPlaying) {
                SimulationLogger.event("ui_stop_requested")
                SimulationService.stop(this)
            } else {
                requestSimulationStart()
            }
        }

        binding.btnClearRoute.setOnClickListener {
            if (isPlaying) {
                toast("请先停止模拟")
                return@setOnClickListener
            }
            routeDrawingOverlay.clear()
            resetStatistics()
            SimulationLogger.event("ui_route_cleared")
        }

        binding.btnUndo.setOnClickListener {
            if (isPlaying) {
                toast("请先停止模拟")
            } else if (!routeDrawingOverlay.undoLastStroke()) {
                toast("没有可撤回的笔画")
            }
        }

        binding.btnFitRoute.setOnClickListener {
            fitRouteOnMap()
        }
        binding.btnZoomIn.setOnClickListener {
            mapView.controller.zoomIn()
        }
        binding.btnZoomOut.setOnClickListener {
            mapView.controller.zoomOut()
        }
        binding.btnAbout.setOnClickListener {
            SimulationLogger.event("ui_about_opened")
            startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            if (isPlaying) {
                toast("运行中不能修改运动参数")
            } else {
                toggleSettings(true)
            }
        }
        binding.btnCloseSettings.setOnClickListener {
            toggleSettings(false)
        }
        binding.tileSourceGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingSliders) return@addOnButtonCheckedListener
            val source = when (checkedId) {
                R.id.btnTileOfficial -> AppTileSources.OSM_OFFICIAL
                else -> AppTileSources.OSM_GERMANY
            }
            applyTileSource(source, persist = true)
        }

        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingSliders) return@addOnButtonCheckedListener
            currentMode = when (checkedId) {
                R.id.btnModeCycling -> MotionMode.CYCLING
                R.id.btnModeDriving -> MotionMode.DRIVING
                else -> MotionMode.RUNNING
            }
            applyModeControls(savedConfig(currentMode))
            updateModeMetrics()
            updateModeButtonStyles()
            saveUiPreferences()
            SimulationLogger.event("ui_mode_changed", "mode" to currentMode.name)
        }

        binding.speedRangeSlider.addOnChangeListener { slider, _, _ ->
            if (!updatingSliders) {
                updateRangeLabels(slider, binding.holdRangeSlider)
                saveUiPreferences()
            }
        }
        binding.holdRangeSlider.addOnChangeListener { slider, _, _ ->
            if (!updatingSliders) {
                updateRangeLabels(binding.speedRangeSlider, slider)
                saveUiPreferences()
            }
        }
        binding.switchLoop.setOnCheckedChangeListener { _, checked ->
            if (!updatingSliders) {
                updateRunMeta()
                saveUiPreferences()
                SimulationLogger.event("ui_loop_changed", "loop" to checked)
            }
        }
    }

    private fun setupSystemBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.settingsCard.isVisible) {
                    toggleSettings(false)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun startRootCheck() {
        binding.tvRootStatus.text = getString(R.string.root_checking)
        rootExecutor.execute {
            SimulationLogger.startSession("boot-${System.currentTimeMillis().toString().takeLast(6)}")
            val rooted = RootMockManager.checkRoot()
            val environmentReady = rooted && RootMockManager.prepareRootEnvironment(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                rootReady = environmentReady
                binding.tvRootStatus.text = if (environmentReady) {
                    getString(R.string.root_ready)
                } else {
                    "ROOT 未就绪"
                }
                binding.tvRootStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        if (environmentReady) R.color.route_primary_soft else R.color.route_accent_soft,
                    ),
                )
                binding.tvRootStatus.setTextColor(
                    ContextCompat.getColor(
                        this,
                        if (environmentReady) R.color.route_primary_dark else R.color.route_accent,
                    ),
                )
                setPlaybackUi(
                    playing = isPlaying,
                    status = if (environmentReady) {
                        getString(R.string.route_hint)
                    } else {
                        "Root 授权未完成，定位模拟不可用"
                    },
                )
                if (environmentReady) {
                    showDisclaimerOnce()
                    centerOnLastKnownLocationIfAvailable()
                } else {
                    showRootBlockedDialog()
                }
            }
        }
    }

    private fun showRootBlockedDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.root_required_title)
            .setMessage(R.string.root_required_message)
            .setCancelable(false)
            .setPositiveButton(R.string.retry) { _, _ -> startRootCheck() }
            .setNegativeButton(R.string.exit) { _, _ -> finishAffinity() }
            .show()
    }

    private fun showDisclaimerOnce() {
        if (preferences.getBoolean(PREF_DISCLAIMER_ACCEPTED, false) ||
            disclaimerShowing ||
            isFinishing ||
            isDestroyed
        ) {
            return
        }
        disclaimerShowing = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_message)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_accept) { _, _ ->
                preferences.edit { putBoolean(PREF_DISCLAIMER_ACCEPTED, true) }
                disclaimerShowing = false
                SimulationLogger.event("disclaimer_accepted")
            }
            .setNegativeButton(R.string.disclaimer_exit) { _, _ ->
                disclaimerShowing = false
                finishAffinity()
            }
            .show()
    }

    private fun requestSimulationStart() {
        if (!rootReady) {
            showRootBlockedDialog()
            return
        }
        if (routePoints.size < 2) {
            toast("请先在地图上绘制路线")
            return
        }
        val permissions = requiredPermissions().filterNot {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isNotEmpty()) {
            pendingStartAfterPermission = true
            permissionLauncher.launch(permissions.toTypedArray())
            return
        }
        beginSimulation()
    }

    private fun beginSimulation() {
        val config = currentConfig()
        saveUiPreferences(config)
        resetStatistics()
        val route = routePoints.map { LatLng(it.latitude, it.longitude) }
        SimulationLogger.event(
            "ui_start_confirmed",
            "mode" to config.mode.name,
            "points" to route.size,
            "loop" to config.loopRoute,
        )
        try {
            SimulationService.start(this, route, config)
            setPlaybackUi(
                playing = true,
                status = "正在建立 Root 定位通道",
            )
            routePoints.firstOrNull()?.let { mapView.controller.animateTo(it) }
        } catch (error: Exception) {
            SimulationLogger.error("ui_start_failed", error)
            toast("启动失败：${error.message ?: "未知错误"}")
        }
    }

    private fun applyServiceState(intent: Intent) {
        val status = intent.getStringExtra(SimulationService.STATE_STATUS).orEmpty()
        val playing = intent.getBooleanExtra(SimulationService.STATE_PLAYING, false)
        val error = intent.getStringExtra(SimulationService.STATE_ERROR)
        val stateMode = intent.getStringExtra(SimulationService.STATE_MODE)
            ?.let { name -> MotionMode.entries.firstOrNull { it.name == name } }
        if (stateMode != null && stateMode != currentMode) {
            currentMode = stateMode
            binding.modeGroup.check(modeButtonId(currentMode))
        }

        if (intent.hasExtra(SimulationService.STATE_SPEED)) {
            updateStatistics(
                speedKmh = intent.getFloatExtra(SimulationService.STATE_SPEED, 0f),
                cadence = intent.getIntExtra(SimulationService.STATE_CADENCE, 0),
                distanceMeters = intent.getDoubleExtra(SimulationService.STATE_DISTANCE_M, 0.0),
                steps = intent.getIntExtra(SimulationService.STATE_STEPS, 0),
                elapsedSeconds = intent.getDoubleExtra(SimulationService.STATE_ELAPSED_SEC, 0.0),
                laps = intent.getIntExtra(SimulationService.STATE_LAP, 0),
            )
            val lat = intent.getDoubleExtra(SimulationService.STATE_LAT, Double.NaN)
            val lng = intent.getDoubleExtra(SimulationService.STATE_LNG, Double.NaN)
            val bearing = intent.getFloatExtra(SimulationService.STATE_BEARING, 0f)
            if (lat.isFinite() && lng.isFinite()) {
                updatePositionMarker(lat, lng, bearing)
            }
        }

        setPlaybackUi(playing = playing, status = status.ifBlank {
            if (playing) "模拟运行中" else "模拟已停止"
        })
        if (!error.isNullOrBlank()) {
            toast(error)
        }
        SimulationLogger.event(
            "ui_state_received",
            "playing" to playing,
            "status" to status,
            "error" to error,
        )
    }

    private fun setPlaybackUi(playing: Boolean, status: String) {
        isPlaying = playing
        routeDrawingOverlay.drawingEnabled = !playing
        mapView.setMultiTouchControls(playing)
        mapView.setFlingEnabled(playing)
        binding.tvStatus.text = status
        binding.tvMapHint.text = if (playing) {
            "双指移动和缩放 · 每秒更新定位"
        } else {
            getString(R.string.route_hint)
        }

        binding.modeGroup.children.filterIsInstance<MaterialButton>().forEach {
            it.isEnabled = !playing
        }
        binding.btnSettings.isEnabled = !playing
        binding.btnClearRoute.isEnabled = !playing
        binding.btnUndo.isEnabled = !playing
        binding.speedRangeSlider.isEnabled = !playing
        binding.holdRangeSlider.isEnabled = !playing
        binding.switchLoop.isEnabled = !playing
        if (playing) {
            toggleSettings(false)
        }

        binding.btnStartStop.text = getString(
            if (playing) R.string.stop_simulation else R.string.start_simulation,
        )
        binding.btnStartStop.setIconResource(
            if (playing) R.drawable.ic_stop else R.drawable.ic_notification,
        )
        binding.btnStartStop.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (playing) R.color.route_accent else R.color.route_primary,
            ),
        )
        updateModeButtonStyles()
        updateRunMeta()
    }

    private fun renderRoute() {
        val points = routePoints.toList()
        routeCasing.setPoints(points)
        routeCore.setPoints(points)
        mapView.invalidate()

        val distanceMeters = RouteMath.polylineDistanceMeters(
            points.map { LatLng(it.latitude, it.longitude) },
        )
        binding.tvRouteInfo.text = if (points.isEmpty()) {
            getString(R.string.route_empty)
        } else {
            "路线 ${formatDistance(distanceMeters)} km · ${points.size} 个路径点"
        }
        binding.btnUndo.isEnabled = points.isNotEmpty() && !isPlaying
        binding.btnClearRoute.isEnabled = points.isNotEmpty() && !isPlaying
        binding.btnStartStop.isEnabled = points.size >= 2 || isPlaying
        updateRunMeta()
    }

    private fun updateRunMeta() {
        val loopText = if (binding.switchLoop.isChecked) "终点循环" else "终点停止"
        val routeKm = RouteMath.polylineDistanceMeters(
            routePoints.map { LatLng(it.latitude, it.longitude) },
        ) / 1000.0
        binding.tvRunMeta.text = if (isPlaying) {
            "$loopText · 路线 ${String.format(Locale.US, "%.2f", routeKm)} km · 1 秒定位"
        } else {
            "$loopText · ${speedText()} · 保持 ${holdText()}"
        }
    }

    private fun updateStatistics(
        speedKmh: Float,
        cadence: Int,
        distanceMeters: Double,
        steps: Int,
        elapsedSeconds: Double,
        laps: Int,
    ) {
        binding.tvSpeed.text = String.format(Locale.US, "%.1f", speedKmh)
        binding.tvDistance.text = String.format(Locale.US, "%.2f", distanceMeters / 1000.0)
        when (currentMode) {
            MotionMode.RUNNING -> {
                binding.tvCadence.text = String.format(Locale.US, "%d", cadence)
                binding.tvSteps.text = String.format(Locale.US, "%d", steps)
            }

            MotionMode.CYCLING -> {
                binding.tvCadence.text = String.format(Locale.US, "%d", cadence)
                binding.tvSteps.text = String.format(Locale.US, "%d", laps)
            }

            MotionMode.DRIVING -> {
                binding.tvCadence.text = formatDuration(elapsedSeconds)
                binding.tvSteps.text = String.format(Locale.US, "%d", laps)
            }
        }
    }

    private fun resetStatistics() {
        binding.tvSpeed.text = getString(R.string.zero_speed)
        binding.tvCadence.text = getString(R.string.zero_count)
        binding.tvDistance.text = getString(R.string.zero_distance)
        binding.tvSteps.text = getString(R.string.zero_count)
    }

    private fun updateModeMetrics() {
        when (currentMode) {
            MotionMode.RUNNING -> {
                binding.tvCadenceLabel.text = "步频"
                binding.tvStepsLabel.text = "步数"
            }

            MotionMode.CYCLING -> {
                binding.tvCadenceLabel.text = "踏频"
                binding.tvStepsLabel.text = "圈数"
            }

            MotionMode.DRIVING -> {
                binding.tvCadenceLabel.text = "用时"
                binding.tvStepsLabel.text = "圈数"
            }
        }
        resetStatistics()
    }

    private fun updatePositionMarker(latitude: Double, longitude: Double, bearing: Float) {
        val marker = currentPositionMarker ?: Marker(mapView).also {
            it.icon = ContextCompat.getDrawable(this, R.drawable.ic_location_dot)
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            it.title = "模拟位置"
            currentPositionMarker = it
            mapView.overlays.add(it)
        }
        marker.position = GeoPoint(latitude, longitude)
        marker.rotation = bearing
        mapView.invalidate()
    }

    private fun fitRouteOnMap() {
        if (routePoints.size < 2) {
            toast("路线点不足")
            return
        }
        val boundingBox = BoundingBox.fromGeoPoints(routePoints)
        mapView.zoomToBoundingBox(boundingBox, true, 100)
        SimulationLogger.event("ui_fit_route", "points" to routePoints.size)
    }

    private fun centerOnLastKnownLocationIfAvailable() {
        if (!hasFineLocationPermission() || routePoints.isNotEmpty()) return
        try {
            val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastKnown != null) {
                mapView.controller.setCenter(GeoPoint(lastKnown.latitude, lastKnown.longitude))
                mapView.controller.setZoom(17.0)
                SimulationLogger.event(
                    "map_centered_on_last_known",
                    "lat" to "%.6f".format(Locale.US, lastKnown.latitude),
                    "lng" to "%.6f".format(Locale.US, lastKnown.longitude),
                )
            }
        } catch (error: SecurityException) {
            SimulationLogger.warning("last_known_location_denied", "message" to error.message)
        }
    }

    private fun toggleSettings(show: Boolean) {
        if (show == binding.settingsCard.isVisible) return
        if (show) {
            binding.settingsCard.alpha = 0f
            binding.settingsCard.translationY = 18f * resources.displayMetrics.density
            binding.settingsCard.isVisible = true
            binding.settingsCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .start()
        } else if (binding.settingsCard.isVisible) {
            binding.settingsCard.animate()
                .alpha(0f)
                .translationY(12f * resources.displayMetrics.density)
                .setDuration(140L)
                .withEndAction { binding.settingsCard.isVisible = false }
                .start()
        }
    }

    private fun applyModeControls(config: SimulationConfig) {
        updatingSliders = true
        try {
            binding.speedRangeSlider.apply {
                valueFrom = when (currentMode) {
                    MotionMode.RUNNING -> 1f
                    MotionMode.CYCLING -> 5f
                    MotionMode.DRIVING -> 10f
                }
                valueTo = 130f
                stepSize = currentMode.speedStep
                setValues(config.minSpeedKmh, config.maxSpeedKmh)
            }
            binding.holdRangeSlider.apply {
                valueFrom = 3f
                valueTo = 120f
                stepSize = 1f
                setValues(config.minHoldSeconds, config.maxHoldSeconds)
            }
            binding.switchLoop.isChecked = config.loopRoute
            updateRangeLabels(binding.speedRangeSlider, binding.holdRangeSlider)
        } finally {
            updatingSliders = false
        }
        updateRunMeta()
    }

    private fun updateRangeLabels(speedSlider: RangeSlider, holdSlider: RangeSlider) {
        val speedValues = speedSlider.values
        val holdValues = holdSlider.values
        if (speedValues.size < 2 || holdValues.size < 2) return
        binding.tvSpeedRangeValue.text = String.format(
            Locale.US,
            "%.1f – %.1f km/h",
            speedValues.first(),
            speedValues.last(),
        )
        binding.tvHoldRangeValue.text = String.format(
            Locale.US,
            "%.0f – %.0f 秒",
            holdValues.first(),
            holdValues.last(),
        )
    }

    private fun currentConfig(): SimulationConfig {
        val speedValues = binding.speedRangeSlider.values.sorted()
        val holdValues = binding.holdRangeSlider.values.sorted()
        return SimulationConfig(
            mode = currentMode,
            minSpeedKmh = speedValues.first(),
            maxSpeedKmh = speedValues.last(),
            minHoldSeconds = holdValues.first(),
            maxHoldSeconds = holdValues.last(),
            loopRoute = binding.switchLoop.isChecked,
        )
    }

    private fun savedConfig(mode: MotionMode): SimulationConfig {
        val defaults = SimulationConfig.defaults(
            mode,
            preferences.getBoolean(PREF_LOOP, true),
        )
        val minSpeed = preferences.getFloat(key(mode, "speed_min"), defaults.minSpeedKmh)
        val maxSpeed = preferences.getFloat(key(mode, "speed_max"), defaults.maxSpeedKmh)
        val minHold = preferences.getFloat(key(mode, "hold_min"), defaults.minHoldSeconds)
        val maxHold = preferences.getFloat(key(mode, "hold_max"), defaults.maxHoldSeconds)
        return try {
            SimulationConfig(mode, minSpeed, maxSpeed, minHold, maxHold, defaults.loopRoute)
        } catch (_: IllegalArgumentException) {
            defaults
        }
    }

    private fun applySavedMode() {
        currentMode = preferences.getString(PREF_MODE, MotionMode.RUNNING.name)
            ?.let { name -> MotionMode.entries.firstOrNull { it.name == name } }
            ?: MotionMode.RUNNING
        updatingSliders = true
        binding.modeGroup.check(modeButtonId(currentMode))
        updatingSliders = false
        updatingSliders = true
        binding.tileSourceGroup.check(
            if (tileSourceId == AppTileSources.OSM_OFFICIAL) {
                R.id.btnTileOfficial
            } else {
                R.id.btnTileMirror
            },
        )
        updatingSliders = false
        applyTileSource(tileSourceId, persist = false)
        applyModeControls(savedConfig(currentMode))
        updateModeMetrics()
        updateModeButtonStyles()
    }

    private fun restoreUiPreferences() {
        applySavedMode()
    }

    private fun saveUiPreferences(config: SimulationConfig = currentConfig()) {
        preferences.edit {
            putString(PREF_MODE, currentMode.name)
            putBoolean(PREF_LOOP, config.loopRoute)
            putFloat(key(currentMode, "speed_min"), config.minSpeedKmh)
            putFloat(key(currentMode, "speed_max"), config.maxSpeedKmh)
            putFloat(key(currentMode, "hold_min"), config.minHoldSeconds)
            putFloat(key(currentMode, "hold_max"), config.maxHoldSeconds)
        }
    }

    private fun updateModeButtonStyles() {
        binding.modeGroup.children.filterIsInstance<MaterialButton>().forEach { button ->
            val selected = button.id == modeButtonId(currentMode)
            button.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.route_primary_soft else R.color.route_surface,
                ),
            )
            button.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.route_primary else R.color.route_outline,
                ),
            )
            button.strokeWidth = if (selected) resources.displayMetrics.density.roundToInt() else 0
            button.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.route_primary_dark else R.color.route_ink,
                ),
            )
        }
    }

    private fun applyTileSource(sourceId: String, persist: Boolean) {
        tileSourceId = sourceId
        val source = AppTileSources.resolve(sourceId)
        mapView.setTileSource(source)
        binding.tvAttribution.text = source.copyrightNotice
        mapView.invalidate()
        if (persist) {
            preferences.edit { putString(PREF_TILE_SOURCE, sourceId) }
        }
        SimulationLogger.event(
            "map_tile_source",
            "source" to source.name(),
            "url_policy" to "online",
        )
    }

    private fun restoreRoute(savedInstanceState: Bundle?) {
        val modeName = savedInstanceState?.getString(STATE_MODE)
        if (modeName != null) {
            currentMode = MotionMode.entries.firstOrNull { it.name == modeName } ?: currentMode
        }
        val values = savedInstanceState?.getDoubleArray(STATE_ROUTE) ?: return
        routePoints.clear()
        for (index in 0 until values.size - 1 step 2) {
            routePoints += GeoPoint(values[index], values[index + 1])
        }
        renderRoute()
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(POST_NOTIFICATIONS_PERMISSION)
        }
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private fun isNightModeActive(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun speedText(): String {
        val values = binding.speedRangeSlider.values
        return String.format(Locale.US, "%.1f–%.1f km/h", values.first(), values.last())
    }

    private fun holdText(): String {
        val values = binding.holdRangeSlider.values
        return String.format(Locale.US, "%.0f–%.0f 秒", values.first(), values.last())
    }

    private fun modeButtonId(mode: MotionMode): Int = when (mode) {
        MotionMode.RUNNING -> R.id.btnModeRunning
        MotionMode.CYCLING -> R.id.btnModeCycling
        MotionMode.DRIVING -> R.id.btnModeDriving
    }

    private fun key(mode: MotionMode, suffix: String): String = "mode_${mode.name}_$suffix"

    private fun formatDistance(meters: Double): String =
        String.format(Locale.US, "%.2f", meters / 1000.0)

    private fun formatDuration(seconds: Double): String {
        val total = seconds.roundToInt().coerceAtLeast(0)
        return "%02d:%02d".format(Locale.US, total / 60, total % 60)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val PREFERENCES_NAME = "routesim_ui"
        private const val PREF_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
        private const val PREF_MODE = "selected_mode"
        private const val PREF_LOOP = "loop_route"
        private const val PREF_MAP_LAT = "map_lat"
        private const val PREF_MAP_LNG = "map_lng"
        private const val PREF_TILE_SOURCE = "tile_source"
        private const val STATE_ROUTE = "activity_route"
        private const val STATE_MODE = "activity_mode"
        private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    }
}
