package com.opensource.routesim.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Root-only location plumbing.
 *
 * The app uses the normal Android test-provider APIs after granting its own
 * mock-location app-op through root. It intentionally does not hook target
 * processes or try to remove framework/root traces.
 */
object RootMockManager {
    private val createdProviders = linkedSetOf<String>()

    data class RootCommandResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false,
    ) {
        val succeeded: Boolean
            get() = !timedOut && exitCode == 0
    }

    fun checkRoot(): Boolean {
        val result = runRootCommand("id", timeoutSeconds = 45)
        val hasRootUid = Regex("""uid=0(?:\(|\s|$)""").containsMatchIn(result.output)
        SimulationLogger.event(
            "root_check",
            "granted" to hasRootUid,
            "exit" to result.exitCode,
            "timed_out" to result.timedOut,
            "output" to result.output.trim().replace('\n', ';'),
        )
        return result.succeeded && hasRootUid
    }

    /**
     * Grants only the app-ops needed by this app. Runtime location and
     * notification permissions are still requested through the normal UI.
     */
    fun prepareRootEnvironment(context: Context): Boolean {
        val packageName = context.packageName
        val mockLocation = runRootCommand(
            "appops set $packageName android:mock_location allow",
        )
        val overlay = runRootCommand(
            "appops set $packageName android:system_alert_window allow",
        )
        val whitelist = runRootCommand(
            "cmd deviceidle whitelist +$packageName",
        )

        // The last command returns non-zero if the package was already
        // whitelisted on some vendor ROMs, so it is not a hard failure.
        val ready = mockLocation.succeeded
        SimulationLogger.event(
            "root_environment",
            "ready" to ready,
            "mock_location" to mockLocation.succeeded,
            "overlay" to overlay.succeeded,
            "battery_whitelist" to whitelist.succeeded,
            "mock_output" to mockLocation.output.trim().replace('\n', ';'),
        )
        return ready
    }

    /**
     * Registers GPS and network providers as test providers. Existing vendor
     * providers are left in place when the platform rejects replacement.
     */
    @Synchronized
    fun initProviders(locationManager: LocationManager) {
        cleanupProviders(locationManager)

        addProvider(
            locationManager = locationManager,
            provider = LocationManager.GPS_PROVIDER,
            requiresNetwork = false,
            requiresSatellite = true,
            fineAccuracy = true,
        )
        addProvider(
            locationManager = locationManager,
            provider = LocationManager.NETWORK_PROVIDER,
            requiresNetwork = true,
            requiresSatellite = false,
            fineAccuracy = false,
        )

        if (LocationManager.GPS_PROVIDER !in createdProviders) {
            val message = "GPS test provider registration failed"
            SimulationLogger.error("provider_init_failed", null, "provider" to "gps")
            throw IllegalStateException(message)
        }

        SimulationLogger.event(
            "provider_initialized",
            "providers" to createdProviders.joinToString(","),
            "sdk" to Build.VERSION.SDK_INT,
        )
    }

    fun pushLocationToSystem(
        locationManager: LocationManager,
        latitude: Double,
        longitude: Double,
        speedMps: Double,
        bearingDegrees: Double,
        accuracyMeters: Double,
        moving: Boolean,
    ) {
        val nowMillis = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        val providers = createdProviders.toList()
        if (providers.isEmpty()) {
            SimulationLogger.warning("provider_push_skipped", "reason" to "no_active_providers")
            return
        }

        providers.forEach { provider ->
            val isGps = provider == LocationManager.GPS_PROVIDER
            val providerAccuracy = if (isGps) {
                accuracyMeters.toFloat().coerceIn(2.8f, 8f)
            } else {
                (accuracyMeters * 4.5 + 8.0).toFloat().coerceIn(15f, 55f)
            }
            val location = Location(provider).apply {
                this.latitude = latitude
                this.longitude = longitude
                altitude = 34.0 + Random.nextDouble(-0.8, 0.8)
                this.speed = if (moving) speedMps.toFloat() else 0f
                this.bearing = bearingDegrees.toFloat().normalizeBearing()
                this.accuracy = providerAccuracy
                time = nowMillis
                elapsedRealtimeNanos = elapsedNanos
                speedAccuracyMetersPerSecond = if (isGps) 0.22f else 1.2f
                bearingAccuracyDegrees = if (isGps) 2.2f else 12f
                verticalAccuracyMeters = if (isGps) 2.5f else 10f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    elapsedRealtimeUncertaintyNanos = 1_000_000.0
                    mslAltitudeMeters = altitude
                    mslAltitudeAccuracyMeters = 3f
                }
                extras = Bundle().apply {
                    putInt("satellites", if (isGps) 13 else 0)
                    putInt("satellitesUsedInFix", if (isGps) 11 else 0)
                    putInt("meanCn0", if (isGps) 37 else 0)
                    putInt("maxCn0", if (isGps) 46 else 0)
                }
            }

            try {
                locationManager.setTestProviderLocation(provider, location)
            } catch (error: Exception) {
                SimulationLogger.error(
                    "provider_push_failed",
                    error,
                    "provider" to provider,
                )
            }
        }
    }

    @Synchronized
    fun cleanupProviders(locationManager: LocationManager) {
        val providers = createdProviders.toList()
        providers.forEach { provider ->
            try {
                // removeTestProvider() also tears down the mock override. A
                // preceding setTestProviderEnabled(false) causes a second
                // removal on some Android 17 builds, which can discard the
                // vendor provider instead of restoring it.
                locationManager.removeTestProvider(provider)
                SimulationLogger.event("provider_removed", "provider" to provider)
            } catch (error: Exception) {
                SimulationLogger.warning(
                    "provider_remove_failed",
                    "provider" to provider,
                    "message" to error.message,
                )
            }
        }
        createdProviders.clear()
        if (providers.isNotEmpty()) {
            SimulationLogger.event("providers_cleaned", "providers" to providers.joinToString(","))
        }
    }

    @SuppressLint("WrongConstant")
    private fun addProvider(
        locationManager: LocationManager,
        provider: String,
        requiresNetwork: Boolean,
        requiresSatellite: Boolean,
        fineAccuracy: Boolean,
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val properties = ProviderProperties.Builder()
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(
                        if (fineAccuracy) {
                            ProviderProperties.ACCURACY_FINE
                        } else {
                            ProviderProperties.ACCURACY_COARSE
                        },
                    )
                    .setHasNetworkRequirement(requiresNetwork)
                    .setHasSatelliteRequirement(requiresSatellite)
                    .setHasCellRequirement(false)
                    .setHasMonetaryCost(false)
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .build()
                locationManager.addTestProvider(provider, properties)
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
                    if (fineAccuracy) LEGACY_ACCURACY_FINE else LEGACY_ACCURACY_COARSE,
                )
            }
            locationManager.setTestProviderEnabled(provider, true)
            @Suppress("DEPRECATION")
            locationManager.setTestProviderStatus(
                provider,
                PROVIDER_STATUS_AVAILABLE,
                null,
                SystemClock.elapsedRealtime(),
            )
            createdProviders += provider
            SimulationLogger.event("provider_registered", "provider" to provider)
        } catch (error: SecurityException) {
            SimulationLogger.error(
                "provider_register_denied",
                error,
                "provider" to provider,
            )
        } catch (error: IllegalArgumentException) {
            SimulationLogger.error(
                "provider_register_invalid",
                error,
                "provider" to provider,
            )
        }
    }

    private fun runRootCommand(command: String, timeoutSeconds: Long = 20): RootCommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.isNotEmpty()) output.append('\n')
                            output.append(line)
                        }
                    }
                }
            }.apply {
                name = "routesim-root-reader"
                isDaemon = true
                start()
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            reader.join(500)
            val outputText = synchronized(output) { output.toString() }
            RootCommandResult(
                exitCode = if (finished) process.exitValue() else -1,
                output = outputText,
                timedOut = !finished,
            )
        } catch (error: Exception) {
            SimulationLogger.error("root_command_exception", error, "command" to command)
            RootCommandResult(-1, error.message.orEmpty())
        }
    }

    private fun Float.normalizeBearing(): Float = ((this % 360f) + 360f) % 360f

    // The legacy addTestProvider signature reused these platform values before
    // ProviderProperties was introduced in API 31.
    private const val LEGACY_POWER_USAGE_LOW = 1
    private const val LEGACY_ACCURACY_FINE = 1
    private const val LEGACY_ACCURACY_COARSE = 2
    private const val PROVIDER_STATUS_AVAILABLE = 2
}
