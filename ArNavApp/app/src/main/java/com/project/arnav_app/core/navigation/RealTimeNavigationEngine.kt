package com.project.arnav_app.core.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.math.*

class RealTimeNavigationEngine : NavigationEngine {

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
        // Return early if we don't have enough data to navigate or invalid location
        if (destination == null || routeResponse == null || routeResponse.routes.isNullOrEmpty() || location.latitude == 0.0) {
            val errorMsg = if (routeResponse?.status != "OK" && routeResponse?.status != "") {
                routeResponse?.errorMessage ?: "Directions API error: ${routeResponse?.status}"
            } else if (routeResponse != null && routeResponse.routes.isEmpty() && routeResponse.status == "OK") {
                "No route found to destination"
            } else {
                null
            }

            return NavigationState(
                currentLocation = if (location.latitude == 0.0) null else location,
                destination = destination,
                isNavigating = false,
                routePoints = emptyList(),
                errorMessage = errorMsg
            )
        }

        return try {
            val route = routeResponse.routes.getOrNull(0) ?: return NavigationState(currentLocation = location, destination = destination)
            val leg = route.legs.getOrNull(0) ?: return NavigationState(currentLocation = location, destination = destination)
            val steps = leg.steps ?: emptyList()
            if (steps.isEmpty()) return NavigationState(currentLocation = location, destination = destination)
            val points = route.overviewPolyline?.points ?: ""
            val decodedPath = if (points.isNotEmpty()) PolylineUtil.decode(points) else emptyList()

            // 1. Find the current step based on location
            var activeStepIndex = 0
            var minDistance = Double.MAX_VALUE

            for (i in steps.indices) {
                val step = steps[i]
                val startLoc = step.startLocation ?: continue
                val dist = calculateDistance(location.latitude, location.longitude, startLoc.lat, startLoc.lng)
                if (dist < minDistance) {
                    minDistance = dist
                    activeStepIndex = i
                }
            }

            val activeStep = steps.getOrNull(activeStepIndex)
            val distanceToActiveStepEnd = activeStep?.endLocation?.let { endLoc ->
                calculateDistance(location.latitude, location.longitude, endLoc.lat, endLoc.lng)
            } ?: 0.0

            // 2. Calculate remaining distance
            var remainingDistance = distanceToActiveStepEnd
            for (i in (activeStepIndex + 1) until steps.size) {
                remainingDistance += (steps[i].distance?.value ?: 0).toDouble()
            }

            NavigationState(
                routePoints = decodedPath,
                totalDistance = leg.distance?.text ?: "Unknown",
                eta = leg.duration?.text ?: "Unknown",
                currentInstruction = parseHtml(activeStep?.htmlInstructions ?: "Follow the route"),
                distanceToNextStep = distanceToActiveStepEnd,
                distanceRemaining = remainingDistance,
                isNavigating = true,
                currentLocation = location,
                destination = destination,
                isOffRoute = isUserOffRoute(location, decodedPath, 50.0)
            )
        } catch (e: Exception) {
            android.util.Log.e("NavigationEngine", "Error calculating state", e)
            NavigationState(currentLocation = location, destination = destination)
        }
    }

    private fun isUserOffRoute(location: GeoPoint, path: List<GeoPoint>, threshold: Double): Boolean {
        if (path.isEmpty()) return false
        return path.none { calculateDistance(location.latitude, location.longitude, it.latitude, it.longitude) < threshold }
    }

    private fun parseHtml(html: String): String {
        return html.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
