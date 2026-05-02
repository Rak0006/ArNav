package com.project.arnav_app.core.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryDestinationProvider : DestinationProvider {
    private val _destinationFlow = MutableStateFlow<GeoPoint?>(null)
    override val destinationFlow: Flow<GeoPoint?> = _destinationFlow.asStateFlow()

    private val _waypointsFlow = MutableStateFlow<List<GeoPoint>>(emptyList())
    override val waypointsFlow: Flow<List<GeoPoint>> = _waypointsFlow.asStateFlow()

    override fun setDestination(destination: GeoPoint?, waypoints: List<GeoPoint>) {
        _destinationFlow.value = destination
        _waypointsFlow.value = waypoints
    }
}
