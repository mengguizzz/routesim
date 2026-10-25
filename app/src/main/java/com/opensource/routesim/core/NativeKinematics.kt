package com.opensource.routesim.core

/**
 * JNI bridge for the native motion engine. Keeping this class in a dedicated
 * file also lets instrumentation tests exercise the engine without starting
 * the Android UI or root location provider.
 */
object NativeKinematics {
    init {
        System.loadLibrary("routesim")
        SimulationLogger.event("native_library_loaded")
    }

    external fun nativeInitRoute(
        latLngFlatArray: DoubleArray,
        mode: Int,
        minSpeedKmh: Double,
        maxSpeedKmh: Double,
        minHoldSeconds: Double,
        maxHoldSeconds: Double,
        targetLaps: Int,
    )

    external fun nativeTick(dtSeconds: Double): DoubleArray

    fun initialize(points: List<LatLng>, config: SimulationConfig) {
        require(points.size >= 2) { "At least two route points are required" }
        val flat = DoubleArray(points.size * 2)
        points.forEachIndexed { index, point ->
            flat[index * 2] = point.latitude
            flat[index * 2 + 1] = point.longitude
        }
        nativeInitRoute(
            flat,
            config.mode.nativeId,
            config.minSpeedKmh.toDouble(),
            config.maxSpeedKmh.toDouble(),
            config.minHoldSeconds.toDouble(),
            config.maxHoldSeconds.toDouble(),
            config.targetLaps,
        )
        SimulationLogger.event(
            "native_route_initialized",
            "mode" to config.mode.name,
            "points" to points.size,
            "speed_kmh" to "${config.minSpeedKmh}..${config.maxSpeedKmh}",
            "hold_sec" to "${config.minHoldSeconds}..${config.maxHoldSeconds}",
            "target_laps" to config.targetLaps,
        )
    }

    fun tick(dtSeconds: Double): SimulationSnapshot {
        val raw = nativeTick(dtSeconds.coerceIn(0.01, 5.0))
        check(raw.size >= SNAPSHOT_FIELD_COUNT) {
            "Native snapshot returned ${raw.size} fields, expected $SNAPSHOT_FIELD_COUNT"
        }
        return SimulationSnapshot(
            latitude = raw[0],
            longitude = raw[1],
            speedMps = raw[2],
            bearingDegrees = raw[3],
            accuracyMeters = raw[4],
            totalSteps = raw[5].toInt(),
            cadenceSpm = raw[6].toInt(),
            distanceMeters = raw[7],
            elapsedSeconds = raw[8],
            lapCount = raw[9].toInt(),
            finished = raw[10] >= 0.5,
        )
    }

    private const val SNAPSHOT_FIELD_COUNT = 11
}

data class SimulationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double,
    val bearingDegrees: Double,
    val accuracyMeters: Double,
    val totalSteps: Int,
    val cadenceSpm: Int,
    val distanceMeters: Double,
    val elapsedSeconds: Double,
    val lapCount: Int,
    val finished: Boolean,
) {
    val speedKmh: Float
        get() = (speedMps * 3.6).toFloat()
}
