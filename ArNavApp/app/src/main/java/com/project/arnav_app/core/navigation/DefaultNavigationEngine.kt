package com.project.arnav_app.core.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.math.*
import java.util.Locale

class DefaultNavigationEngine : NavigationEngine {

    override fun observeNavigationState(
        locationFlow: Flow<GeoPoint>,
        destinationFlow: Flow<GeoPoint?>,
        routeResponse: Flow<DirectionsResponse?>
    ): Flow<NavigationState> = combine(locationFlow, destinationFlow, routeResponse) { location, destination, route ->
        calculateNavigationState(location, destination, route)
    }

    override fun calculateNavigationState(
        location: GeoPoint,
        destination: GeoPoint?,
        routeResponse: DirectionsResponse?
    ): NavigationState {
        if (destination == null) {
            return NavigationState(currentLocation = location)
        }
        
        val distance = calculateDistance(location, destination)
        
        // Handle case where route is not yet fetched but destination exists
        if (routeResponse == null || routeResponse.routes.isNullOrEmpty()) {
            return NavigationState(
                currentLocation = location,
                destination = destination,
                currentInstruction = "Calculating route...",
                distanceRemaining = distance,
                isNavigating = false
            )
        }

        val instruction = when {
            distance < 10 -> "You have arrived!"
            distance < 50 -> "Arriving at destination in ${distance.toInt()} meters"
            else -> "Continue straight for ${distance.toInt()} meters"
        }

        return NavigationState(
            currentLocation = location,
            destination = destination,
            currentInstruction = instruction,
            distanceRemaining = distance,
            isNavigating = true,
            routePoints = listOf(location, destination),
            totalDistance = String.format(Locale.US, "%.1f km", distance / 1000.0),
            eta = "${(distance / 1.4 / 60).toInt()} min"
        )
    }

    private fun calculateDistance(p1: GeoPoint, p2: GeoPoint): Double {
        val r = 6371000.0 // Earth's radius in meters
        val lat1 = Math.toRadians(p1.latitude)
        val lat2 = Math.toRadians(p2.latitude)
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return r * c
    }
}
