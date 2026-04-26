package com.project.arnav_app.navigation.manager

import android.location.Location
import com.google.android.gms.maps.model.LatLng
import com.project.arnav_app.navigation.domain.LocationData
import com.project.arnav_app.navigation.domain.NavigationStep
import com.project.arnav_app.navigation.domain.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

class NavigationManager {
    private val _currentRoute = MutableStateFlow<Route?>(null)
    val currentRoute: StateFlow<Route?> = _currentRoute

    // For MVP, we mock the route fetching
    fun fetchRoute(start: LatLng, destination: LatLng): Route {
        // Mocked route with two steps
        val steps = listOf(
            NavigationStep(
                instruction = "Head north on Main St",
                distance = 100,
                startLocation = start,
                endLocation = LatLng(start.latitude + 0.001, start.longitude)
            ),
            NavigationStep(
                instruction = "Turn right onto Broadway",
                distance = 150,
                startLocation = LatLng(start.latitude + 0.001, start.longitude),
                endLocation = destination
            )
        )
        val route = Route(
            steps = steps,
            points = listOf(start, LatLng(start.latitude + 0.001, start.longitude), destination),
            totalDistance = 250,
            totalDuration = 300
        )
        _currentRoute.value = route
        return route
    }

    fun getNextInstruction(currentLocation: LocationData, route: Route): Pair<NavigationStep, Int>? {
        val currentLatLng = LatLng(currentLocation.lat, currentLocation.lng)
        
        // Find the closest upcoming step
        // For MVP simplicity, we just look at the first step that is ahead of us
        for (step in route.steps) {
            val distanceToStepEnd = calculateDistance(currentLatLng, step.endLocation)
            if (distanceToStepEnd > 10) { // 10 meters threshold
                return Pair(step, distanceToStepEnd.roundToInt())
            }
        }
        return null
    }

    private fun calculateDistance(start: LatLng, end: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(start.latitude, start.longitude, end.latitude, end.longitude, results)
        return results[0]
    }
}
