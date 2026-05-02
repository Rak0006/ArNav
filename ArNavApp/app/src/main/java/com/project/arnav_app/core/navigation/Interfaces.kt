package com.project.arnav_app.core.navigation

import kotlinx.coroutines.flow.Flow

interface DestinationProvider {
    val destinationFlow: Flow<GeoPoint?>
    val waypointsFlow: Flow<List<GeoPoint>>
    fun setDestination(destination: GeoPoint?, waypoints: List<GeoPoint> = emptyList())
}

interface NavigationEngine {
    fun observeNavigationState(
        locationFlow: Flow<GeoPoint>,
        destinationFlow: Flow<GeoPoint?>,
        routeFlow: Flow<Route?>
    ): Flow<NavigationState>

    fun calculateNavigationState(
        location: GeoPoint,
        destination: GeoPoint?,
        route: Route?
    ): NavigationState
}
