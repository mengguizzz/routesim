package com.opensource.routesim

import com.opensource.routesim.core.LatLng
import com.opensource.routesim.core.MotionMode
import com.opensource.routesim.core.RouteMath
import com.opensource.routesim.core.SimulationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMathTest {
    @Test
    fun equatorLongitudeDegreeIsAbout111Kilometers() {
        val distance = RouteMath.distanceMeters(
            LatLng(0.0, 0.0),
            LatLng(0.0, 1.0),
        )
        assertEquals(111_319.0, distance, 5.0)
    }

    @Test
    fun polylineDistanceAddsEachSegment() {
        val firstSegment = RouteMath.distanceMeters(
            LatLng(31.0, 121.0),
            LatLng(31.001, 121.0),
        )
        val secondSegment = RouteMath.distanceMeters(
            LatLng(31.001, 121.0),
            LatLng(31.001, 121.001),
        )
        val total = RouteMath.polylineDistanceMeters(
            listOf(
                LatLng(31.0, 121.0),
                LatLng(31.001, 121.0),
                LatLng(31.001, 121.001),
            ),
        )
        assertEquals(firstSegment + secondSegment, total, 0.001)
        assertTrue(total > 100.0)
    }

    @Test
    fun equalSpeedEndpointsRepresentConstantSpeed() {
        val config = SimulationConfig(
            mode = MotionMode.RUNNING,
            minSpeedKmh = 9f,
            maxSpeedKmh = 9f,
            minHoldSeconds = 10f,
            maxHoldSeconds = 10f,
            targetLaps = 1,
        )
        assertEquals(9f, config.minSpeedKmh, 0f)
        assertEquals(9f, config.maxSpeedKmh, 0f)
    }
}
