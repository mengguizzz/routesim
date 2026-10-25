package com.opensource.routesim

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opensource.routesim.core.LatLng
import com.opensource.routesim.core.MotionMode
import com.opensource.routesim.core.NativeKinematics
import com.opensource.routesim.core.SimulationConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KinematicsInstrumentedTest {
    @Test
    fun runningSpeedCadenceAndStepsStayConsistent() {
        NativeKinematics.initialize(
            points = longRoute(),
            config = SimulationConfig(
                mode = MotionMode.RUNNING,
                minSpeedKmh = 5f,
                maxSpeedKmh = 15f,
                minHoldSeconds = 2f,
                maxHoldSeconds = 5f,
                loopRoute = true,
            ),
        )

        val speeds = mutableListOf<Float>()
        var finalSnapshot = NativeKinematics.tick(1.0)
        repeat(35) {
            finalSnapshot = NativeKinematics.tick(1.0)
            speeds += finalSnapshot.speedKmh
        }

        assertTrue(speeds.drop(8).max() - speeds.drop(8).min() > 0.4f)
        assertTrue(finalSnapshot.speedKmh in 0f..15.2f)
        assertTrue(finalSnapshot.cadenceSpm in 130..200)
        assertTrue(finalSnapshot.totalSteps > 30)
        assertTrue(finalSnapshot.distanceMeters > 20.0)
        assertFalse(finalSnapshot.finished)
    }

    @Test
    fun cyclingAndDrivingRespectTheirConfiguredSpeedCeiling() {
        listOf(
            MotionMode.CYCLING to (22f..26f),
            MotionMode.DRIVING to (45f..70f),
        ).forEach { (mode, speedRange) ->
            NativeKinematics.initialize(
                points = longRoute(),
                config = SimulationConfig(
                    mode = mode,
                    minSpeedKmh = speedRange.start,
                    maxSpeedKmh = speedRange.endInclusive,
                    minHoldSeconds = 3f,
                    maxHoldSeconds = 6f,
                    loopRoute = true,
                ),
            )
            repeat(30) {
                val snapshot = NativeKinematics.tick(1.0)
                assertTrue(snapshot.speedKmh in 0f..speedRange.endInclusive + 0.2f)
                assertTrue(snapshot.totalSteps == 0)
            }
        }
    }

    @Test
    fun loopingRouteNeverFinishesAndAdvancesLaps() {
        NativeKinematics.initialize(
            points = listOf(
                LatLng(31.0, 121.0),
                LatLng(31.0002, 121.0),
                LatLng(31.0002, 121.0002),
            ),
            config = SimulationConfig(
                mode = MotionMode.CYCLING,
                minSpeedKmh = 18f,
                maxSpeedKmh = 20f,
                minHoldSeconds = 2f,
                maxHoldSeconds = 4f,
                loopRoute = true,
            ),
        )

        var snapshot = NativeKinematics.tick(1.0)
        repeat(60) {
            snapshot = NativeKinematics.tick(1.0)
        }
        assertFalse(snapshot.finished)
        assertTrue(snapshot.lapCount >= 1)
    }

    @Test
    fun nonLoopingRouteDeceleratesAndFinishes() {
        NativeKinematics.initialize(
            points = listOf(
                LatLng(31.0, 121.0),
                LatLng(31.0002, 121.0),
            ),
            config = SimulationConfig(
                mode = MotionMode.RUNNING,
                minSpeedKmh = 5f,
                maxSpeedKmh = 7f,
                minHoldSeconds = 2f,
                maxHoldSeconds = 4f,
                loopRoute = false,
            ),
        )

        var snapshot = NativeKinematics.tick(1.0)
        repeat(30) {
            snapshot = NativeKinematics.tick(1.0)
        }
        assertTrue(snapshot.finished)
        assertTrue(snapshot.speedKmh < 0.1f)
    }

    private fun longRoute(): List<LatLng> = listOf(
        LatLng(31.0, 121.0),
        LatLng(31.003, 121.0002),
        LatLng(31.006, 121.001),
    )
}
