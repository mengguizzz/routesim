package com.opensource.routesim.core

import kotlin.math.max

enum class MotionMode(
    val nativeId: Int,
    val displayName: String,
    val defaultSpeedKmh: ClosedFloatingPointRange<Float>,
    val defaultHoldSeconds: ClosedFloatingPointRange<Float>,
    val speedStep: Float,
) {
    RUNNING(
        nativeId = 0,
        displayName = "跑步",
        defaultSpeedKmh = 5f..15f,
        defaultHoldSeconds = 8f..35f,
        speedStep = 0.5f,
    ),
    CYCLING(
        nativeId = 1,
        displayName = "骑行",
        defaultSpeedKmh = 14f..28f,
        defaultHoldSeconds = 15f..60f,
        speedStep = 1f,
    ),
    DRIVING(
        nativeId = 2,
        displayName = "驾车",
        defaultSpeedKmh = 25f..75f,
        defaultHoldSeconds = 12f..45f,
        speedStep = 5f,
    ),
}

data class SimulationConfig(
    val mode: MotionMode,
    val minSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val minHoldSeconds: Float,
    val maxHoldSeconds: Float,
    val loopRoute: Boolean,
    val sampleIntervalMs: Long = 1_000L,
) {
    init {
        require(minSpeedKmh > 0f) { "minSpeedKmh must be positive" }
        require(maxSpeedKmh >= minSpeedKmh) {
            "maxSpeedKmh must not be less than minSpeedKmh"
        }
        require(minHoldSeconds > 0f) { "minHoldSeconds must be positive" }
        require(maxHoldSeconds >= minHoldSeconds) {
            "maxHoldSeconds must not be less than minHoldSeconds"
        }
        require(sampleIntervalMs in 250L..5_000L) { "sampleIntervalMs is outside the safe range" }
    }

    companion object {
        fun defaults(mode: MotionMode, loopRoute: Boolean = true): SimulationConfig {
            val speed = mode.defaultSpeedKmh
            val hold = mode.defaultHoldSeconds
            return SimulationConfig(
                mode = mode,
                minSpeedKmh = speed.start,
                maxSpeedKmh = max(speed.endInclusive, speed.start + mode.speedStep),
                minHoldSeconds = hold.start,
                maxHoldSeconds = hold.endInclusive,
                loopRoute = loopRoute,
            )
        }
    }
}
