package com.project.arnav_app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.project.arnav_app.core.location.LocationProvider
import com.project.arnav_app.core.navigation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

import com.google.android.libraries.places.api.model.AutocompletePrediction

class NavigationViewModel(
    private val locationProvider: LocationProvider,
    private val destinationProvider: DestinationProvider,
    private val directionsRepository: DirectionsRepository,
    private val navigationEngine: NavigationEngine,
    private val placesRepository: PlacesRepository
) : ViewModel() {

    private val _routeResponse = MutableStateFlow<DirectionsResponse?>(null)
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _suggestions = MutableStateFlow<List<AutocompletePrediction>>(emptyList())
    val suggestions = _suggestions.asStateFlow()

    private var searchJob: Job? = null
    private var isFetchingRoute = false

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.length >= 3) {
            searchJob = viewModelScope.launch {
                val results = placesRepository.getAutocompleteSuggestions(query)
                _suggestions.value = results
            }
        } else {
            _suggestions.value = emptyList()
        }
    }

    fun onSuggestionSelected(prediction: AutocompletePrediction) {
        _searchQuery.value = prediction.getPrimaryText(null).toString()
        _suggestions.value = emptyList()
        viewModelScope.launch {
            val geoPoint = placesRepository.getPlaceCoordinates(prediction.placeId)
            if (geoPoint != null) {
                destinationProvider.setDestination(geoPoint)
                // Navigation will auto-start because NavigationEngine will 
                // produce a state with routePoints when destination is set.
            }
        }
    }

    fun onSpeechResult(text: String) {
        _searchQuery.value = text
        onSearchQueryChanged(text)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<NavigationState> = combine(
        locationProvider.locationFlow
            .map { GeoPoint(it.latitude, it.longitude) }
            .onStart { emit(GeoPoint(0.0, 0.0)) }
            .catch { e -> 
                android.util.Log.e("NavigationViewModel", "Location flow error", e)
                emit(GeoPoint(0.0, 0.0)) 
            }
            .distinctUntilChanged(),
        destinationProvider.destinationFlow,
        _routeResponse.asStateFlow()
    ) { location, destination, response ->
        withContext(Dispatchers.Default) {
            navigationEngine.calculateNavigationState(location, destination, response)
        }
    }.onEach { state: NavigationState ->
        if (state.isOffRoute && state.currentLocation != null && state.destination != null && state.currentLocation.latitude != 0.0 && !isFetchingRoute) {
            recalculateRoute(state.currentLocation, state.destination)
        }
    }.flowOn(Dispatchers.Default)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NavigationState()
    )

    init {
        // Automatically fetch route when both destination and location are available
        viewModelScope.launch {
            combine(
                destinationProvider.destinationFlow,
                locationProvider.locationFlow.filter { it.latitude != 0.0 }
            ) { dest, loc ->
                if (dest != null) GeoPoint(loc.latitude, loc.longitude) to dest else null
            }.collect { pair ->
                if (pair != null) {
                    val (location, destination) = pair
                    // Only fetch if we don't have a route yet
                    if (_routeResponse.value == null && !isFetchingRoute) {
                        fetchRoute(location, destination)
                    }
                } else {
                    _routeResponse.value = null
                }
            }
        }
    }

    private suspend fun fetchRoute(origin: GeoPoint, destination: GeoPoint) {
        if (isFetchingRoute) return
        isFetchingRoute = true
        try {
            val response = directionsRepository.getRoute(origin, destination)
            _routeResponse.value = response
        } finally {
            isFetchingRoute = false
        }
    }

    private fun recalculateRoute(origin: GeoPoint, destination: GeoPoint) {
        viewModelScope.launch {
            fetchRoute(origin, destination)
        }
    }

    fun updateDestination(lat: Double, lng: Double) {
        if (lat == 0.0 && lng == 0.0) {
            destinationProvider.setDestination(null)
            _searchQuery.value = ""
            _routeResponse.value = null
        } else {
            val destination = GeoPoint(lat, lng)
            destinationProvider.setDestination(destination)
        }
    }

    fun performSearch() {
        val query = _searchQuery.value
        if (query.length < 3) return
        
        viewModelScope.launch {
            val geoPoint = try {
                placesRepository.searchPlaceByText(query)
            } catch (e: Exception) {
                null
            }

            if (geoPoint != null) {
                destinationProvider.setDestination(geoPoint)
                _suggestions.value = emptyList()
            } else {
                // Fallback: try using the first suggestion if direct search fails
                _suggestions.value.firstOrNull()?.let { suggestion ->
                    onSuggestionSelected(suggestion)
                }
            }
        }
    }
}
