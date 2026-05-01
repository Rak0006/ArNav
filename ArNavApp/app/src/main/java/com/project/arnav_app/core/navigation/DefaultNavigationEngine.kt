package com.project.arnav_app.core.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.math.*

class DefaultNavigationEngine : NavigationEngine {

    override fun observeNavigationState(
        locationFlow: Flow<GeoPoint>,
        destinationFlow: Flow<GeoPoint?>,
        routeFlow: Flow<Route?>,
        isNavigatingFlow: Flow<Boolean>
    ): Flow<NavigationState> = combine(locationFlow, destinationFlow, routeFlow, isNavigatingFlow) { location, destination, route, isNavigating ->
        calculateNavigationState(location, destination, route).copy(isNavigating = isNavigating)
    }

    override fun calculateNavigationState(
        location: GeoPoint,
        destination: GeoPoint?,
        route: Route?
    ): NavigationState {
        // Return basic state without navigation logic
        // The isNavigating flag will be applied by observeNavigationState
        if (destination == null || route == null || location.latitude == 0.0) {
            return NavigationState(
                currentLocation = if (location.latitude == 0.0) null else location,
                destination = destination,
                route = route,
                isNavigating = false
            )
        }

        val steps = route.steps
        if (steps.isEmpty()) return NavigationState(
            currentLocation = location,
            destination = destination,
            route = route,
            isNavigating = false
        )

        // Basic implementation: Find closest step start
        var activeIndex = 0
        var minDistance = Double.MAX_VALUE

        for (i in steps.indices) {
            val dist = calculateDistance(location, steps[i].start)
            if (dist < minDistance) {
                minDistance = dist
                activeIndex = i
            }
        }

        val currentStep = steps[activeIndex]
        val distanceToNextStep = calculateDistance(location, currentStep.end).toFloat()

        return NavigationState(
            route = route,
            currentLocation = location,
            destination = destination,
            currentStepIndex = activeIndex,
            currentInstruction = currentStep.instruction,
            distanceToNextStep = distanceToNextStep,
            isNavigating = false,
            closestPolylineIndex = 0
        )
    }

    private fun calculateDistance(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val aVal = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(aVal), sqrt(1 - aVal))
        return r * c
    }
}
