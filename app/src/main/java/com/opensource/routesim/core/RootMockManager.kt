package com.opensource.routesim.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * RootMockManager.kt (自适应双模式升级版：支持【KernelSU/Root 增强模式】与【免 Root 开发者模式】平滑切换)
 *
 * 核心改进：
 * 1. 有 Root 时自动配置 mock-location app-op；无 Root 时使用系统开发者选项授权。
 * 2. 海拔、卫星数和信噪比保持动态变化，用于定位功能测试。
 * 3. 不在本地/测试构建中实现清除系统 mock 标记或规避检测。
 */
object RootMockManager {

    private const val LEGACY_POWER_USAGE_LOW = 1
    private const val LEGACY_ACCURACY_FINE = 1
    private const val LEGACY_ACCURACY_COARSE = 2
    private const val PROVIDER_STATUS_AVAILABLE = 2

    private val createdProviders = linkedSetOf<String>()

    // 记录当前会话是否处于已获取 Root 权限状态
    @Volatile
    var hasRootAccess: Boolean = false
        private set

    val isRootModeActive: Boolean
        get() = hasRootAccess

    // 用于模拟真实 GPS 海拔缓慢起伏的相位累加器
    private var altitudePhase: Double = 0.0

    data class RootCommandResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false
    ) {
        val succeeded: Boolean
            get() = !timedOut && exitCode == 0
    }

    /**
     * 检测设备是否已在 KernelSU / Magisk 中授予本应用 su 权限
     * 注意：KernelSU 默认不主动弹窗，需用户在 KernelSU 管理器中为 Routesim 开启超级用户开关
     */
    fun checkRoot(): Boolean {
        val result = runRootCommand("id")
        val hasRootUid = result.succeeded && Regex("""uid=0(?:\(|\s|$)""").containsMatchIn(result.output)
        hasRootAccess = hasRootUid
        SimulationLogger.event(
            "root_check",
            "granted" to hasRootUid,
            "exit_code" to result.exitCode,
            "timed_out" to result.timedOut
        )
        return hasRootUid
    }

    /**
     * 准备模拟定位环境：
     * - 若已获得 Root (KernelSU/Magisk)：通过 su 自动静默开启 mock_location 与后台白名单
     * - 若未获得 Root (无 Root 模式)：直接返回 true 放行，交由系统开发者选项中的“选择模拟位置信息应用”控制
     */
    fun prepareRootEnvironment(context: Context): Boolean {
        val packageName = context.packageName
        if (!hasRootAccess) {
            SimulationLogger.event("root_environment", "mode" to "no_root_fallback", "package" to packageName)
            // 无 Root 模式下直接放行，不阻断用户使用
            return true
        }

        val mockLocation = runRootCommand("appops set $packageName android:mock_location allow")
        val overlay = runRootCommand("appops set $packageName android:system_alert_window allow")
        // Some vendor ROMs return non-zero when the app is already whitelisted.
        val whitelist = runRootCommand("cmd deviceidle whitelist +$packageName")
        SimulationLogger.event(
            "root_environment",
            "mode" to "root_enhanced",
            "package" to packageName,
            "mock_location" to mockLocation.succeeded,
            "overlay" to overlay.succeeded,
            "battery_whitelist" to whitelist.succeeded
        )
        if (!mockLocation.succeeded) {
            hasRootAccess = false
        }
        return mockLocation.succeeded
    }

    /**
     * 在 Root Provider 模式与免 Root 模式之间切换。这里只改变提权配置方式，
     * 不修改 Android 的 mock 标记或尝试规避目标 App 检测。
     */
    fun toggleRootMode(context: Context, enableRoot: Boolean): Boolean {
        if (!enableRoot) {
            hasRootAccess = false
            SimulationLogger.event("root_mode_changed", "mode" to "no_root")
            return false
        }
        val granted = checkRoot()
        hasRootAccess = granted
        if (granted) {
            hasRootAccess = prepareRootEnvironment(context)
        }
        val active = hasRootAccess
        SimulationLogger.event("root_mode_changed", "mode" to if (active) "root" else "no_root")
        return active
    }

    /**
     * 初始化 GPS 与 Network 测试 Provider
     */
    fun initProviders(locationManager: LocationManager) {
        createdProviders.clear()
        cleanupStaleProviders(locationManager)

        try {
            addProvider(
                locationManager = locationManager,
                provider = LocationManager.GPS_PROVIDER,
                requiresNetwork = false,
                requiresSatellite = true,
                fineAccuracy = true
            )
            addProvider(
                locationManager = locationManager,
                provider = LocationManager.NETWORK_PROVIDER,
                requiresNetwork = true,
                requiresSatellite = false,
                fineAccuracy = false
            )
        } catch (e: SecurityException) {
            val hint = if (hasRootAccess) {
                "Root 授权未生效，请检查 KernelSU/Magisk 是否已允许本应用"
            } else {
                "当前为免 Root 模式，请先前往【系统设置 -> 开发者选项 -> 选择模拟位置信息应用】选中 Routesim"
            }
            throw IllegalStateException(hint, e)
        }

        if (createdProviders.isEmpty()) {
            throw IllegalStateException("无法创建定位通道，请检查开发者选项模拟位置设置或 KernelSU 授权")
        }
    }

    /**
     * 推送高拟真定位数据到系统服务（消除机械固定值特征）
     */
    fun pushLocationToSystem(
        locationManager: LocationManager,
        latitude: Double,
        longitude: Double,
        speedMps: Double,
        bearingDegrees: Double,
        accuracyMeters: Double,
        isMoving: Boolean
    ) {
        val nowMillis = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()

        // 1. 动态海拔拟真：摒弃死板的固定 35.0 米，采用正弦缓变 + 微小高斯波动 (在 33.5m ~ 38.5m 之间自然起伏)
        if (isMoving) {
            altitudePhase += 0.08
        }
        val dynamicAltitude = 35.5 + sin(altitudePhase) * 2.2 + cos(altitudePhase * 0.37) * 0.8 + Random.nextDouble(-0.25, 0.25)

        // 2. 动态卫星颗数与信噪比拟真：避免恒定不变的卫星数被风控规则识破
        val dynamicSatellites = (10 + (sin(altitudePhase * 0.5) * 2.0).roundToInt() + Random.nextInt(-1, 2)).coerceIn(7, 15)
        val dynamicMaxCn0 = (41 + Random.nextInt(-2, 3)).coerceIn(35, 48)
        val dynamicMeanCn0 = (dynamicMaxCn0 - 6 - Random.nextInt(0, 3)).coerceIn(28, 42)

        for (provider in createdProviders) {
            val isGps = provider == LocationManager.GPS_PROVIDER
            val providerAccuracy = if (isGps) {
                accuracyMeters.toFloat().coerceIn(2.8f, 8f)
            } else {
                (accuracyMeters * 4.5 + 8.0).toFloat().coerceIn(15f, 55f)
            }

            val location = Location(provider).apply {
                this.latitude = latitude
                this.longitude = longitude
                this.altitude = dynamicAltitude
                this.speed = if (isMoving) speedMps.toFloat() else 0f
                this.bearing = normalizeBearing(bearingDegrees.toFloat())
                this.accuracy = providerAccuracy.coerceAtLeast(1.8f)
                this.time = nowMillis
                this.elapsedRealtimeNanos = elapsedNanos

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    speedAccuracyMetersPerSecond = if (isMoving) Random.nextDouble(0.18, 0.42).toFloat() else 0.08f
                    bearingAccuracyDegrees = if (isMoving) Random.nextDouble(2.5, 6.0).toFloat() else 12.0f
                    verticalAccuracyMeters = Random.nextDouble(1.8, 3.2).toFloat()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    elapsedRealtimeUncertaintyNanos = Random.nextDouble(15000.0, 45000.0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    mslAltitudeMeters = dynamicAltitude + 1.2
                    mslAltitudeAccuracyMeters = 2.4f
                }

                extras = Bundle().apply {
                    putInt("satellites", if (isGps) dynamicSatellites else 0)
                    putInt("satellitesUsedInFix", if (isGps) dynamicSatellites else 0)
                    putInt("maxCn0", if (isGps) dynamicMaxCn0 else 0)
                    putInt("meanCn0", if (isGps) dynamicMeanCn0 else 0)
                }
            }

            try {
                locationManager.setTestProviderLocation(provider, location)
            } catch (e: Throwable) {
                SimulationLogger.error("provider_push_failed", e, "provider" to provider)
            }
        }
    }

    fun cleanupProviders(locationManager: LocationManager) {
        for (provider in createdProviders) {
            try {
                locationManager.removeTestProvider(provider)
            } catch (_: Throwable) {}
        }
        createdProviders.clear()
    }

    fun cleanupStaleProviders(locationManager: LocationManager) {
        val targets = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in targets) {
            try {
                locationManager.removeTestProvider(provider)
            } catch (_: Throwable) {}
        }
    }

    @SuppressLint("WrongConstant")
    private fun addProvider(
        locationManager: LocationManager,
        provider: String,
        requiresNetwork: Boolean,
        requiresSatellite: Boolean,
        fineAccuracy: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val props = ProviderProperties.Builder()
                .setHasNetworkRequirement(requiresNetwork)
                .setHasSatelliteRequirement(requiresSatellite)
                .setHasCellRequirement(false)
                .setHasMonetaryCost(false)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(if (fineAccuracy) ProviderProperties.ACCURACY_FINE else ProviderProperties.ACCURACY_COARSE)
                .build()
            locationManager.addTestProvider(provider, props)
        } else {
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                provider,
                requiresNetwork,
                requiresSatellite,
                false,
                false,
                true,
                true,
                true,
                LEGACY_POWER_USAGE_LOW,
                if (fineAccuracy) LEGACY_ACCURACY_FINE else LEGACY_ACCURACY_COARSE
            )
        }
        locationManager.setTestProviderEnabled(provider, true)
        @Suppress("DEPRECATION")
        locationManager.setTestProviderStatus(
            provider,
            PROVIDER_STATUS_AVAILABLE,
            null,
            SystemClock.elapsedRealtime()
        )
        createdProviders.add(provider)
    }

    private fun normalizeBearing(bearing: Float): Float {
        var result = bearing % 360f
        if (result < 0f) result += 360f
        return result
    }

    private fun runRootCommand(command: String, timeoutSeconds: Long = 5L): RootCommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(output) {
                                output.appendLine(line)
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }.apply {
                name = "routesim-root-reader"
                isDaemon = true
                start()
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return RootCommandResult(-1, output.toString().trim(), timedOut = true)
            }
            reader.join(500)
            RootCommandResult(process.exitValue(), output.toString().trim(), timedOut = false)
        } catch (e: Throwable) {
            RootCommandResult(-1, e.message ?: "su not available", timedOut = false)
        }
    }
}
