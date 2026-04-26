package com.project.arnav_app.core.navigation

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

data class NavStep(
    val instruction: String,
    val distance: Double,
    val maneuver: String?
)

data class NavigationState(
    val routePoints: List<GeoPoint> = emptyList(),
    val totalDistance: String = "",
    val eta: String = "",
    val currentInstruction: String = "",
    val nextInstruction: String? = null,
    val steps: List<NavStep> = emptyList(),
    val currentStepIndex: Int = 0,
    val distanceToNextStep: Double = 0.0,
    val distanceRemaining: Double = 0.0,
    val isNavigating: Boolean = false,
    val currentLocation: GeoPoint? = null,
    val destination: GeoPoint? = null,
    val isOffRoute: Boolean = false,
    val errorMessage: String? = null
)
