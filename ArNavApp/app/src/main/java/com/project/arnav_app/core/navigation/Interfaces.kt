package com.project.arnav_app.core.navigation

import kotlinx.coroutines.flow.Flow

interface DestinationProvider {
    val destinationFlow: Flow<GeoPoint?>
    fun setDestination(destination: GeoPoint?)
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
