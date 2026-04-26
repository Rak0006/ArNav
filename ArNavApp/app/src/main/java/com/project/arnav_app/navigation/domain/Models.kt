package com.project.arnav_app.navigation.domain

import com.google.android.gms.maps.model.LatLng

data class LocationData(
    val lat: Double,
    val lng: Double,
    val bearing: Float? = null,
    val accuracy: Float? = null
)

fun LocationData.toLatLng() = LatLng(lat, lng)

data class NavigationStep(
    val instruction: String,
    val distance: Int, // in meters
    val startLocation: LatLng,
    val endLocation: LatLng,
    val maneuver: String? = null
)

data class Route(
    val steps: List<NavigationStep>,
    val points: List<LatLng>,
    val totalDistance: Int,
    val totalDuration: Int
)

data class NavigationState(
    val currentLocation: LocationData? = null,
    val destination: LatLng? = null,
    val currentStep: NavigationStep? = null,
    val nextInstruction: String? = null,
    val distanceToNextStep: Int = 0,
    val routeProgress: Float = 0f,
    val isNavigating: Boolean = false,
    val error: String? = null
)
