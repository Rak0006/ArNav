package com.project.arnav_app.core.navigation

import kotlinx.coroutines.flow.*
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
        routeFlow: Flow<Route?>,
        isNavigatingFlow: Flow<Boolean>
    ): Flow<NavigationState> = combine(
        locationFlow.onStart { emit(GeoPoint(0.0, 0.0)) },
        destinationFlow.onStart { emit(null) },
        routeFlow,
        isNavigatingFlow
    ) { loc, dest, route, isNav ->
        NavigationBundle(loc, dest, route, isNav)
    }.map { (location, destination, route, isNavigating) ->

        // If navigation not started → idle state
        if (!isNavigating) {
            return@map NavigationState(
                currentLocation = if (location.latitude == 0.0) null else location,
                destination = destination,
                route = route,
                isNavigating = false
            )
        }

        // Compute fresh every time (no stale state)
        val index = findClosestStepIndex(location, route)

        calculateNavigationStateInternal(
            location = location,
            destination = destination,
            route = route,
            startIndex = index,
            lastPolyIndex = 0,
            wasNavigating = true
        )
    }
//    .scan(NavigationState()) { prevState, bundle ->
//        val (location, destination, route, isNavigating) = bundle
//
//        // Reset index if route changes
//        val (index, lastPolyIndex) = if (route != prevState.route) {
//            findClosestStepIndex(location, route) to 0
//        } else {
//            prevState.currentStepIndex to prevState.closestPolylineIndex
//        }
//
//        calculateNavigationStateInternal(location, destination, route, index, lastPolyIndex, isNavigating)
//    }

    private data class NavigationBundle(
        val location: GeoPoint,
        val destination: GeoPoint?,
        val route: Route?,
        val isNavigating: Boolean
    )

    override fun calculateNavigationState(
        location: GeoPoint,
        destination: GeoPoint?,
        route: Route?
    ): NavigationState {
        val index = findClosestStepIndex(location, route)
        return calculateNavigationStateInternal(location, destination, route, index, 0, false)
    }

    private fun calculateNavigationStateInternal(
        location: GeoPoint,
        destination: GeoPoint?,
        route: Route?,
        startIndex: Int,
        lastPolyIndex: Int,
        wasNavigating: Boolean = false
    ): NavigationState {
        if (destination == null || location.latitude == 0.0) {
            return NavigationState(
                currentLocation = if (location.latitude == 0.0) null else location,
                destination = destination,
                route = route,
                isNavigating = wasNavigating
            )
        }

        if (route == null) {
            return NavigationState(
                currentLocation = location,
                destination = destination,
                isNavigating = wasNavigating
            )
        }

        val steps = route.steps
        if (steps.isEmpty()) {
            return NavigationState(
                route = route,
                currentLocation = location,
                destination = destination,
                isNavigating = wasNavigating
            )
        }

        // Check if destination is reached
        val distanceToDestination = calculateDistance(location, destination)
        val isArrived = distanceToDestination < ARRIVAL_THRESHOLD_METERS && wasNavigating

        if (isArrived) {
            return NavigationState(
                route = route,
                currentLocation = location,
                destination = destination,
                isNavigating = wasNavigating,
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

        // Closest polyline point
        var minPolyDist = Double.MAX_VALUE
        var closestPolylineIndex = lastPolyIndex
        val polyline = route.polyline
        
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

        if (minPolyDist > OFF_ROUTE_THRESHOLD_METERS) {
            for (i in polyline.indices) {
                val dist = calculateDistance(location, polyline[i])
                if (dist < minPolyDist) {
                    minPolyDist = dist
                    closestPolylineIndex = i
                }
            }
        }

        val isUserOffRoute = minPolyDist > OFF_ROUTE_THRESHOLD_METERS && wasNavigating

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
            isNavigating = wasNavigating,
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
