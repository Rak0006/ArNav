package com.project.arnav_app.core.navigation

import kotlinx.serialization.Serializable

@Serializable
data class IntentResult(
    val intent: String,
    val destination: String? = null,
    val query: String? = null
)

enum class NavSystemState {
    IDLE, LISTENING, PROCESSING, CONFIRMING, NAVIGATING, ERROR
}

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float = 0f
)

data class NavStep(
    val instruction: String,
    val start: GeoPoint,
    val end: GeoPoint,
    val distanceMeters: Int,
    val maneuver: String?
)

data class Route(
    val polyline: List<GeoPoint>,
    val steps: List<NavStep>,
    val totalDistanceMeters: Int,
    val totalDurationSeconds: Int
)

data class NavigationState(
    val systemState: NavSystemState = NavSystemState.IDLE,
    val route: Route? = null,
    val currentLocation: GeoPoint? = null,
    val destination: GeoPoint? = null,
    val currentStepIndex: Int = 0,
    val currentInstruction: String = "",
    val nextInstruction: String? = null,
    val distanceToNextStep: Float = 0f,
    val totalDistanceRemaining: Float = 0f,
    val isOffRoute: Boolean = false,
    val isNavigating: Boolean = false,
    val isArrived: Boolean = false,
    val errorMessage: String? = null,
    val closestPolylineIndex: Int = 0
) {
    val routePoints: List<GeoPoint> get() {
        val allPoints = route?.polyline ?: return emptyList()
        if (!isNavigating) return allPoints
        if (allPoints.isEmpty()) return emptyList()
        val index = closestPolylineIndex.coerceIn(0, allPoints.size - 1)
        return allPoints.subList(index, allPoints.size)
    }

    val distanceRemaining: Float get() = totalDistanceRemaining
    
    val distanceRemainingFormatted: String get() {
        return if (totalDistanceRemaining < 1000) {
            "${totalDistanceRemaining.toInt()} m"
        } else {
            "${"%.1f".format(totalDistanceRemaining / 1000.0)} km"
        }
    }

    val totalDistance: String get() = route?.let { 
        if (it.totalDistanceMeters < 1000) "${it.totalDistanceMeters} m"
        else "${"%.1f".format(it.totalDistanceMeters / 1000.0)} km" 
    } ?: "0 m"

    val eta: String get() = route?.let { "${it.totalDurationSeconds / 60} min" } ?: "0 min"

    private fun calculateDistance(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val aVal = (Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)).coerceIn(0.0, 1.0)
        val c = 2 * Math.atan2(Math.sqrt(aVal), Math.sqrt(1 - aVal))
        return r * c
    }
}
