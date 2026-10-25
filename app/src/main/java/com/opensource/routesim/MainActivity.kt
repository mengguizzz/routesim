package com.opensource.routesim

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
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
import com.opensource.routesim.ui.PrivacyConsentDialog
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
    private var privacyDialogShowing = false
    private var accessGranted = false
    private var controlPanelExpanded = true
    private var tileSourceId = AppTileSources.OSM_GERMANY
    private var updatingTheme = false
    private var initialBottomTab = R.id.navHome

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
            refreshCurrentLocation()
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
        initialBottomTab = savedInstanceState?.getInt(STATE_BOTTOM_TAB, R.id.navHome)
            ?: R.id.navHome
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
        alignWideLayouts()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        initMap()
        restoreRoute(savedInstanceState)
        setupControls()
        setupAboutPage()
        setupBottomNavigation()
        setupControlPanelCollapse()
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
        binding.root.keepScreenOn = false
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
        outState.putInt(STATE_BOTTOM_TAB, binding.bottomNavGroup.checkedButtonId)
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

    private fun alignWideLayouts() {
        val maxContentWidth = (820 * resources.displayMetrics.density).toInt()
        val sidePadding = ((resources.displayMetrics.widthPixels - maxContentWidth) / 2)
            .coerceAtLeast((18 * resources.displayMetrics.density).toInt())
        binding.aboutContent.aboutHeader.setPadding(sidePadding, 0, sidePadding, 0)
        binding.aboutContent.aboutScroll.setPadding(sidePadding, 0, sidePadding, 0)
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
        val savedLat = preferences.getString(PREF_CURRENT_LAT, null)?.toDoubleOrNull()
        val savedLng = preferences.getString(PREF_CURRENT_LNG, null)?.toDoubleOrNull()
        if (savedLat != null && savedLng != null) {
            mapView.controller.setCenter(GeoPoint(savedLat, savedLng))
            mapView.controller.setZoom(16.5)
        } else {
            mapView.controller.setCenter(GeoPoint(35.0, 105.0))
            mapView.controller.setZoom(4.0)
        }

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
        if (savedLat != null && savedLng != null) {
            updatePositionMarker(savedLat, savedLng, 0f)
        }
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
	// 点击顶部状态胶囊：动态开启/重测 KernelSU Root 或切回免 Root 模式
binding.tvRootStatus.setOnClickListener {
    if (isPlaying) {
        toast("请先停止模拟，再切换 Root 模式")
        return@setOnClickListener
    }
    val wantEnable = !RootMockManager.isRootModeActive
    binding.tvRootStatus.text = getString(R.string.root_checking)
    rootExecutor.execute {
        val active = RootMockManager.toggleRootMode(this, wantEnable)
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            updateRootStatusBadge(active)
            if (active) {
                toast("已开启 Root 模式（自动配置定位 Provider）")
            } else if (wantEnable) {
                toast("未检测到 Root 授权，请先在 KernelSU 中开启本应用超级用户权限后再点此按钮")
            } else {
                toast("已切换为免 Root 模式（需在系统开发者选项中选择模拟位置应用）")
            }
        }
    }
}
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
        binding.btnCurrentLocation.setOnClickListener {
            if (isPlaying) {
                toast("模拟运行中不刷新真实位置")
            } else if (!hasFineLocationPermission()) {
                requestInitialLocation()
            } else {
                binding.tvStatus.text = "正在获取当前位置…"
                refreshCurrentLocation()
            }
        }
        binding.btnZoomIn.setOnClickListener {
            mapView.controller.zoomIn()
        }
        binding.btnZoomOut.setOnClickListener {
            mapView.controller.zoomOut()
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
        binding.etTargetLaps.doAfterTextChanged {
            if (!updatingSliders) {
                updateRunMeta()
                saveUiPreferences()
                SimulationLogger.event("ui_target_laps_changed", "laps" to targetLaps())
            }
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            updateBottomNavigationStyles()
            when (checkedId) {
                R.id.navAbout -> {
                    showAboutScreen()
                    SimulationLogger.event("bottom_nav_selected", "tab" to "about")
                }

                else -> {
                    showHomeScreen()
                    SimulationLogger.event("bottom_nav_selected", "tab" to "home")
                }
            }
        }
        binding.bottomNavGroup.check(initialBottomTab)
        updateBottomNavigationStyles()
    }

    private fun updateBottomNavigationStyles() {
        val selectedId = binding.bottomNavGroup.checkedButtonId.takeIf { it != View.NO_ID }
            ?: R.id.navHome
        binding.bottomNavGroup.children.filterIsInstance<MaterialButton>().forEach { button ->
            val selected = button.id == selectedId
            val selectedColor = ContextCompat.getColor(this, R.color.route_primary_dark)
            val normalColor = ContextCompat.getColor(this, R.color.route_ink)
            button.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.route_primary_soft else R.color.route_surface_elevated,
                ),
            )
            button.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.route_primary else R.color.route_outline,
                ),
            )
            button.setTextColor(if (selected) selectedColor else normalColor)
            button.iconTint = ColorStateList.valueOf(if (selected) selectedColor else normalColor)
        }
    }

    private fun setupAboutPage() {
        binding.aboutContent.tvAboutVersion.text = getString(
            R.string.version_format,
            getVersionName(),
        )
        binding.aboutContent.btnGithub.setOnClickListener {
            val repository = getString(R.string.github_repository_url)
            SimulationLogger.event("about_open_repository", "url" to repository)
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(repository)))
        }
        binding.aboutContent.btnLicense.setOnClickListener {
            val licenseUrl = getString(R.string.github_license_url)
            SimulationLogger.event("about_open_license", "url" to licenseUrl)
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(licenseUrl)))
        }

        updatingTheme = true
        binding.aboutContent.themeGroup.check(
            when (RoutesimApplication.loadThemeMode(this)) {
                AppCompatDelegate.MODE_NIGHT_NO -> R.id.btnThemeLight
                AppCompatDelegate.MODE_NIGHT_YES -> R.id.btnThemeDark
                else -> R.id.btnThemeSystem
            },
        )
        updatingTheme = false
        updateThemeButtonStyles()
        binding.aboutContent.themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingTheme) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.btnThemeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            SimulationLogger.event("theme_changed", "mode" to mode)
            RoutesimApplication.setThemeMode(mode)
        }
    }

    private fun setupControlPanelCollapse() {
        controlPanelExpanded = preferences.getBoolean(PREF_CONTROL_PANEL_EXPANDED, true)
        binding.btnToggleControl.setOnClickListener {
            setControlPanelExpanded(!controlPanelExpanded, persist = true)
        }
        setControlPanelExpanded(controlPanelExpanded, persist = false)
    }

    private fun setControlPanelExpanded(expanded: Boolean, persist: Boolean) {
        controlPanelExpanded = expanded
        binding.controlPanelExpandable.isVisible = expanded
        binding.btnToggleControl.rotation = if (expanded) 180f else 0f
        binding.btnToggleControl.contentDescription = getString(
            if (expanded) R.string.collapse_control_panel else R.string.expand_control_panel,
        )
        if (!expanded) {
            toggleSettings(false)
        }
        if (persist) {
            preferences.edit { putBoolean(PREF_CONTROL_PANEL_EXPANDED, expanded) }
            SimulationLogger.event("control_panel_toggled", "expanded" to expanded)
        }
    }

    private fun showHomeScreen() {
        binding.aboutContent.root.isVisible = false
        binding.mapView.isVisible = true
        binding.topScrim.isVisible = true
        binding.topPanel.isVisible = true
        binding.mapTools.isVisible = true
        binding.controlPanel.isVisible = true
        binding.tvMapHint.isVisible = true
        binding.tvAttribution.isVisible = true
        setControlPanelExpanded(controlPanelExpanded, persist = false)
    }

    private fun showAboutScreen() {
        toggleSettings(false)
        binding.mapView.isVisible = false
        binding.topScrim.isVisible = false
        binding.topPanel.isVisible = false
        binding.mapTools.isVisible = false
        binding.controlPanel.isVisible = false
        binding.tvMapHint.isVisible = false
        binding.tvAttribution.isVisible = false
        binding.aboutContent.root.isVisible = true
        updateThemeButtonStyles()
    }

    private fun updateThemeButtonStyles() {
        val selectedId = when (RoutesimApplication.loadThemeMode(this)) {
            AppCompatDelegate.MODE_NIGHT_NO -> R.id.btnThemeLight
            AppCompatDelegate.MODE_NIGHT_YES -> R.id.btnThemeDark
            else -> R.id.btnThemeSystem
        }
        binding.aboutContent.themeGroup.children
            .filterIsInstance<MaterialButton>()
            .forEach { button ->
                val selected = button.id == selectedId
                button.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        if (selected) R.color.route_primary_soft else R.color.route_surface_elevated,
                    ),
                )
                button.strokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        if (selected) R.color.route_primary else R.color.route_outline,
                    ),
                )
                button.setTextColor(
                    ContextCompat.getColor(
                        this,
                        if (selected) R.color.route_primary_dark else R.color.route_ink,
                    ),
                )
            }
    }

    private fun getVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"

    private fun setupSystemBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.settingsCard.isVisible) {
                    toggleSettings(false)
                } else if (binding.bottomNavGroup.checkedButtonId == R.id.navAbout) {
                    binding.bottomNavGroup.check(R.id.navHome)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

private fun startRootCheck() {
    rootReady = false
    binding.gateOverlay.isVisible = true
    binding.gateProgress.isVisible = true
    binding.tvGateStatus.text = getString(R.string.root_gate_checking)
    binding.tvRootStatus.text = getString(R.string.root_checking)
    rootExecutor.execute {
        SimulationLogger.startSession("boot-${System.currentTimeMillis().toString().takeLast(6)}")
        val rooted = RootMockManager.checkRoot()
        RootMockManager.prepareRootEnvironment(this)
        if (rooted && !SimulationService.isRunning) {
            RootMockManager.cleanupStaleProviders(locationManager)
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            // 无论有无 Root 均将环境标记为就绪，允许进入地图
            rootReady = true
            updateRootStatusBadge(RootMockManager.isRootModeActive)
            binding.tvGateStatus.text = if (rooted) {
                getString(R.string.root_gate_success)
            } else {
                "未检测到 Root，已进入免 Root 模式"
            }
            continueAfterRootVerification()
        }
    }
}

private fun updateRootStatusBadge(rootActive: Boolean) {
    binding.tvRootStatus.text = if (rootActive) {
        "ROOT模式"
    } else {
        "免ROOT模式"
    }
    binding.tvRootStatus.backgroundTintList = ColorStateList.valueOf(
        ContextCompat.getColor(
            this,
            if (rootActive) R.color.route_primary_soft else R.color.route_accent_soft
        )
    )
    binding.tvRootStatus.setTextColor(
        ContextCompat.getColor(
            this,
            if (rootActive) R.color.route_primary_dark else R.color.route_accent
        )
    )
}

    private fun continueAfterRootVerification() {
        if (preferences.getBoolean(PREF_PRIVACY_POLICY_ACCEPTED, false)) {
            grantAccess()
        } else {
            showPrivacyConsentDialog()
        }
    }

    private fun showPrivacyConsentDialog() {
        if (privacyDialogShowing ||
            isFinishing ||
            isDestroyed
        ) {
            return
        }
        privacyDialogShowing = true
        PrivacyConsentDialog(this) {
            privacyDialogShowing = false
            preferences.edit {
                putBoolean(PREF_PRIVACY_POLICY_ACCEPTED, true)
            }
            grantAccess()
        }.also { dialog ->
            dialog.setOnDismissListener {
                privacyDialogShowing = false
            }
        }.show()
    }

    private fun grantAccess() {
        if (accessGranted || isFinishing || isDestroyed) return
        accessGranted = true
        binding.gateOverlay.isVisible = false
        showHomeScreen()
        setPlaybackUi(
            playing = SimulationService.isRunning,
            status = if (SimulationService.isRunning) "模拟服务运行中" else getString(R.string.route_hint),
        )
        requestInitialLocation()
        SimulationLogger.event("access_granted")
    }

    private fun requestInitialLocation() {
        val requiredLocationPermissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val missing = requiredLocationPermissions.filterNot {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            refreshCurrentLocation()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestSimulationStart() {
        if (!rootReady) {
            startRootCheck()
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
            "target_laps" to config.targetLaps,
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
        if (binding.root.keepScreenOn != playing) {
            binding.root.keepScreenOn = playing
            SimulationLogger.event("screen_keep_awake", "enabled" to playing)
        }
        routeDrawingOverlay.drawingEnabled = !playing
        mapView.setMultiTouchControls(playing)
        mapView.setFlingEnabled(playing)
        binding.tvStatus.text = status
        binding.tvMapHint.text = if (playing) {
            getString(R.string.screen_always_on_hint)
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
        binding.etTargetLaps.isEnabled = !playing
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
        val lapText = "目标 ${targetLaps()} 圈"
        val routeKm = RouteMath.polylineDistanceMeters(
            routePoints.map { LatLng(it.latitude, it.longitude) },
        ) / 1000.0
        binding.tvRunMeta.text = if (isPlaying) {
            "$lapText · 路线 ${String.format(Locale.US, "%.2f", routeKm)} km · 1 秒定位"
        } else {
            "$lapText · ${speedText()} · 保持 ${holdText()}"
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
        marker.title = if (isPlaying) "模拟位置" else "当前位置"
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

    @SuppressLint("MissingPermission")
    private fun refreshCurrentLocation() {
        if (!hasFineLocationPermission() || isPlaying) return
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            providers.forEach { provider ->
                try {
                    locationManager.getCurrentLocation(
                        provider,
                        null as CancellationSignal?,
                        ContextCompat.getMainExecutor(this),
                    ) { location ->
                        if (location != null) applyCurrentLocation(location, "fresh_$provider")
                    }
                } catch (error: Exception) {
                    SimulationLogger.warning(
                        "current_location_request_failed",
                        "provider" to provider,
                        "message" to error.message,
                    )
                }
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                locationManager.requestSingleUpdate(
                    LocationManager.NETWORK_PROVIDER,
                    object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            applyCurrentLocation(location, "single_update")
                        }

                        override fun onProviderDisabled(provider: String) = Unit
                        override fun onProviderEnabled(provider: String) = Unit
                        @Deprecated("Deprecated by Android")
                        override fun onStatusChanged(
                            provider: String?,
                            status: Int,
                            extras: Bundle?,
                        ) = Unit
                    },
                    Looper.getMainLooper(),
                )
            } catch (error: Exception) {
                SimulationLogger.warning(
                    "current_location_request_failed",
                    "provider" to LocationManager.NETWORK_PROVIDER,
                    "message" to error.message,
                )
            }
        }
    }

    private fun applyCurrentLocation(location: Location, source: String) {
        if (!accessGranted || isPlaying) return
        mapView.controller.setCenter(GeoPoint(location.latitude, location.longitude))
        mapView.controller.setZoom(17.0)
        updatePositionMarker(
            latitude = location.latitude,
            longitude = location.longitude,
            bearing = if (location.hasBearing()) location.bearing else 0f,
        )
        binding.tvStatus.text = "已刷新到当前位置"
        preferences.edit {
            putString(PREF_CURRENT_LAT, location.latitude.toString())
            putString(PREF_CURRENT_LNG, location.longitude.toString())
        }
        SimulationLogger.event(
            "map_centered_on_current_location",
            "source" to source,
            "provider" to location.provider,
            "lat" to "%.6f".format(Locale.US, location.latitude),
            "lng" to "%.6f".format(Locale.US, location.longitude),
            "accuracy_m" to location.accuracy,
        )
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
            binding.etTargetLaps.setText(config.targetLaps.toString())
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
            targetLaps = targetLaps(),
        )
    }

    private fun savedConfig(mode: MotionMode): SimulationConfig {
        val defaults = SimulationConfig.defaults(
            mode,
            preferences.getInt(PREF_TARGET_LAPS, 1),
        )
        val minSpeed = preferences.getFloat(key(mode, "speed_min"), defaults.minSpeedKmh)
        val maxSpeed = preferences.getFloat(key(mode, "speed_max"), defaults.maxSpeedKmh)
        val minHold = preferences.getFloat(key(mode, "hold_min"), defaults.minHoldSeconds)
        val maxHold = preferences.getFloat(key(mode, "hold_max"), defaults.maxHoldSeconds)
        return try {
            SimulationConfig(
                mode,
                minSpeed,
                maxSpeed,
                minHold,
                maxHold,
                preferences.getInt(PREF_TARGET_LAPS, defaults.targetLaps),
            )
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
            putInt(PREF_TARGET_LAPS, config.targetLaps)
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

    private fun targetLaps(): Int =
        binding.etTargetLaps.text?.toString()?.toIntOrNull()?.coerceIn(1, 999) ?: 1

    companion object {
        private const val PREFERENCES_NAME = "routesim_ui"
        private const val PREF_PRIVACY_POLICY_ACCEPTED = "privacy_policy_v2_accepted"
        private const val PREF_CONTROL_PANEL_EXPANDED = "control_panel_expanded"
        private const val PREF_MODE = "selected_mode"
        private const val PREF_TARGET_LAPS = "target_laps"
        private const val PREF_CURRENT_LAT = "current_location_lat"
        private const val PREF_CURRENT_LNG = "current_location_lng"
        private const val PREF_TILE_SOURCE = "tile_source"
        private const val STATE_ROUTE = "activity_route"
        private const val STATE_MODE = "activity_mode"
        private const val STATE_BOTTOM_TAB = "activity_bottom_tab"
        private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
        private const val ROOT_FAILURE_EXIT_DELAY_MS = 1_800L
    }
}
