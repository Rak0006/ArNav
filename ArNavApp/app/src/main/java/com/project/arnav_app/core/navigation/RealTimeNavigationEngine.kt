package com.project.arnav_app.core.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.scan
import kotlin.math.*

class RealTimeNavigationEngine : NavigationEngine {

    companion object {
        private const val STEP_TRANSITION_THRESHOLD = 12.0 // Slightly increased for walking
        private const val PREPARE_THRESHOLD = 25.0 // Give more time to react
        private const val ARRIVAL_THRESHOLD_METERS = 15.0
        private const val OFF_ROUTE_THRESHOLD_METERS = 40.0 // Adjusted for urban GPS bounce
    }

    override fun observeNavigationState(
        locationFlow: Flow<GeoPoint>,
        destinationFlow: Flow<GeoPoint?>,
        routeFlow: Flow<Route?>
    ): Flow<NavigationState> = combine(locationFlow, destinationFlow, routeFlow) { location, destination, route ->
        Triple(location, destination, route)
    }.scan(NavigationState()) { prevState, (location, destination, route) ->
        val currentRoute = route
        // Reset index if route changes
        val (index, lastPolyIndex) = if (currentRoute != prevState.route) {
            findClosestStepIndex(location, currentRoute) to 0
        } else {
            prevState.currentStepIndex to prevState.closestPolylineIndex
        }
        
        calculateNavigationStateInternal(location, destination, currentRoute, index, lastPolyIndex)
    }

    override fun calculateNavigationState(
        location: GeoPoint,
        destination: GeoPoint?,
        route: Route?
    ): NavigationState {
        // Stateless fallback
        val index = findClosestStepIndex(location, route)
        return calculateNavigationStateInternal(location, destination, route, index, 0)
    }

    private fun calculateNavigationStateInternal(
        location: GeoPoint,
        destination: GeoPoint?,
        route: Route?,
        startIndex: Int,
        lastPolyIndex: Int
    ): NavigationState {
        if (destination == null || location.latitude == 0.0) {
            return NavigationState(
                currentLocation = if (location.latitude == 0.0) null else location,
                destination = destination,
                route = route,
                isNavigating = false
            )
        }

        if (route == null) {
            return NavigationState(
                currentLocation = location,
                destination = destination,
                isNavigating = false
            )
        }

        val steps = route.steps
        if (steps.isEmpty()) {
            return NavigationState(
                route = route,
                currentLocation = location,
                destination = destination,
                isNavigating = true
            )
        }

        // Check if destination is reached
        val distanceToDestination = calculateDistance(location, destination)
        if (distanceToDestination < ARRIVAL_THRESHOLD_METERS) {
            return NavigationState(
                route = route,
                currentLocation = location,
                destination = destination,
                isNavigating = false,
                isArrived = true,
                currentInstruction = "You have arrived at your destination"
            )
        }

        // 1. Get current step
        var currentStepIndex = startIndex
        var currentStep = steps.getOrNull(currentStepIndex) ?: steps.last()

        // 2. Compute distance to end of current step
        var distanceToNext = calculateDistance(location, currentStep.end)

        // 3. Step transition: If near the end of the current step, advance
        if (distanceToNext < STEP_TRANSITION_THRESHOLD && currentStepIndex < steps.size - 1) {
            currentStepIndex++
            currentStep = steps[currentStepIndex]
            distanceToNext = calculateDistance(location, currentStep.end)
        }

        val distToNext = distanceToNext.toFloat()

        // 4. Pre-warning: If near the end of the step and there's a next step
        var currentInstruction = currentStep.instruction
        val nextStep = steps.getOrNull(currentStepIndex + 1)
        if (distToNext < PREPARE_THRESHOLD && nextStep != null) {
            currentInstruction = "Prepare to ${nextStep.instruction}"
        }

        // 5. Calculate remaining total distance
        var totalRemaining = distToNext
        for (i in (currentStepIndex + 1) until steps.size) {
            totalRemaining += steps[i].distanceMeters
        }

        // Closest polyline point for map display slicing
        var minPolyDist = Double.MAX_VALUE
        var closestPolylineIndex = lastPolyIndex
        val polyline = route.polyline
        
        // Search a window around the last index first
        val windowSize = 30
        val startSearch = (lastPolyIndex - 5).coerceAtLeast(0)
        val endSearch = (lastPolyIndex + windowSize).coerceAtMost(polyline.size - 1)
        
        for (i in startSearch..endSearch) {
            val dist = calculateDistance(location, polyline[i])
            if (dist < minPolyDist) {
                minPolyDist = dist
                closestPolylineIndex = i
            }
        }

        // If even the best in the window is too far, search everything
        if (minPolyDist > OFF_ROUTE_THRESHOLD_METERS) {
            for (i in polyline.indices) {
                val dist = calculateDistance(location, polyline[i])
                if (dist < minPolyDist) {
                    minPolyDist = dist
                    closestPolylineIndex = i
                }
            }
        }

        val isUserOffRoute = minPolyDist > OFF_ROUTE_THRESHOLD_METERS

        return NavigationState(
            route = route,
            currentLocation = location,
            destination = destination,
            currentStepIndex = currentStepIndex,
            currentInstruction = currentInstruction,
            nextInstruction = nextStep?.instruction,
            distanceToNextStep = distToNext,
            totalDistanceRemaining = totalRemaining,
            isOffRoute = isUserOffRoute,
            isNavigating = true,
            closestPolylineIndex = closestPolylineIndex
        )
    }

    private fun findClosestStepIndex(location: GeoPoint, route: Route?): Int {
        if (route == null || route.steps.isEmpty()) return 0
        var activeStepIndex = 0
        var minDistanceToStep = Double.MAX_VALUE
        for (i in route.steps.indices) {
            val step = route.steps[i]
            val dist = calculateDistance(location, step.start)
            if (dist < minDistanceToStep) {
                minDistanceToStep = dist
                activeStepIndex = i
            }
        }
        return activeStepIndex
    }

    private fun calculateDistance(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val aVal = (sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
                sin(dLon / 2) * sin(dLon / 2)).coerceIn(0.0, 1.0)
        val c = 2 * atan2(sqrt(aVal), sqrt(1 - aVal))
        return r * c
    }
}
