package com.opensource.routesim.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val latitude: Double, val longitude: Double)

object RouteMath {
    private const val EARTH_RADIUS_METERS = 6_378_137.0

    fun distanceMeters(first: LatLng, second: LatLng): Double {
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val deltaLat = lat2 - lat1
        val deltaLng = Math.toRadians(second.longitude - first.longitude)

        val sinLat = sin(deltaLat / 2.0)
        val sinLng = sin(deltaLng / 2.0)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLng * sinLng
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun polylineDistanceMeters(points: List<LatLng>): Double =
        points.zipWithNext().sumOf { (first, second) -> distanceMeters(first, second) }
}
