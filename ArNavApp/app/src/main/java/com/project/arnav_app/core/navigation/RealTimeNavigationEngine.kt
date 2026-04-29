package com.project.arnav_app.core.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.math.*

class RealTimeNavigationEngine : NavigationEngine {

    companion object {
        private const val STEP_THRESHOLD_METERS = 8.0 // Smaller for walking
        private const val ARRIVAL_THRESHOLD_METERS = 10.0
        private const val OFF_ROUTE_THRESHOLD_METERS = 30.0
    }

    override fun observeNavigationState(
        locationFlow: Flow<GeoPoint>,
        destinationFlow: Flow<GeoPoint?>,
        routeResponse: Flow<Route?>
    ): Flow<NavigationState> = combine(locationFlow, destinationFlow, routeResponse) { location, destination, route ->
        calculateNavigationState(location, destination, route)
    }

    override fun calculateNavigationState(
        location: GeoPoint,
        destination: GeoPoint?,
        route: Route?
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

        val steps = route.steps
        if (steps.isEmpty()) {
            return NavigationState(
                route = route,
                currentLocation = location,
                destination = destination,
                isNavigating = true
            )
        }

        // 1. Find the current step based on distance to step ends
        var activeStepIndex = 0
        var minDistanceToStep = Double.MAX_VALUE

        for (i in steps.indices) {
            val step = steps[i]
            val dist = calculateDistance(location, step.start)
            if (dist < minDistanceToStep) {
                minDistanceToStep = dist
                activeStepIndex = i
            }
        }

        // Logic to advance step if we are close to the end of the current step
        val currentStep = steps[activeStepIndex]
        val distanceToNextStep = calculateDistance(location, currentStep.end)
        
        var finalStepIndex = activeStepIndex
        if (distanceToNextStep < STEP_THRESHOLD_METERS && activeStepIndex < steps.size - 1) {
            finalStepIndex++
        }

        val activeStep = steps[finalStepIndex]
        val distToNext = calculateDistance(location, activeStep.end).toFloat()

        // 2. Calculate remaining total distance
        var totalRemaining = distToNext
        for (i in (finalStepIndex + 1) until steps.size) {
            totalRemaining += steps[i].distanceMeters
        }

        // 3. Off-route detection and Closest Point search
        var closestPolylineIndex = 0
        var minPolyDist = Double.MAX_VALUE
        val polyline = route.polyline

        for (i in polyline.indices) {
            val dist = calculateDistance(location, polyline[i])
            if (dist < minPolyDist) {
                minPolyDist = dist
                closestPolylineIndex = i
            }
        }

        val isUserOffRoute = minPolyDist > OFF_ROUTE_THRESHOLD_METERS

        return NavigationState(
            route = route,
            currentLocation = location,
            destination = destination,
            currentStepIndex = finalStepIndex,
            currentInstruction = activeStep.instruction,
            nextInstruction = steps.getOrNull(finalStepIndex + 1)?.instruction,
            distanceToNextStep = distToNext,
            totalDistanceRemaining = totalRemaining,
            isOffRoute = isUserOffRoute,
            isNavigating = true,
            closestPolylineIndex = closestPolylineIndex
        )
    }

    private fun calculateDistance(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val aVal = (sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
                sin(dLon / 2) * sin(dLon / 2)).coerceIn(0.0, 1.0)
        val c = 2 * atan2(sqrt(aVal), sqrt(1 - aVal))
        return r * c
    }
}
