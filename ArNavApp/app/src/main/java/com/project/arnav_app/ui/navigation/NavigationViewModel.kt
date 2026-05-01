package com.project.arnav_app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.project.arnav_app.core.location.LocationProvider
import com.project.arnav_app.core.navigation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.google.android.libraries.places.api.model.AutocompletePrediction
import java.util.concurrent.atomic.AtomicBoolean

class NavigationViewModel(
    private val locationProvider: LocationProvider,
    private val destinationProvider: DestinationProvider,
    private val directionsRepository: DirectionsRepository,
    private val navigationEngine: NavigationEngine,
    private val placesRepository: PlacesRepository,
    private val onSpeak: ((String) -> Unit)? = null
) : ViewModel() {

    private val _route = MutableStateFlow<Route?>(null)
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _suggestions = MutableStateFlow<List<AutocompletePrediction>>(emptyList())
    val suggestions = _suggestions.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)

    private var searchJob: Job? = null
    private val isFetchingRoute = AtomicBoolean(false)
    private var lastInstruction: String? = null
    private var lastFailedDestination: GeoPoint? = null

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.length >= 3) {
            searchJob = viewModelScope.launch {
                try {
                    val results = placesRepository.getAutocompleteSuggestions(query)
                    _suggestions.value = results
                } catch (e: Exception) {
                    _suggestions.value = emptyList()
                }
            }
        } else {
            _suggestions.value = emptyList()
        }
    }

    fun onSuggestionSelected(prediction: AutocompletePrediction) {
        _searchQuery.value = prediction.getPrimaryText(null)?.toString() ?: ""
        _suggestions.value = emptyList()
        viewModelScope.launch {
            try {
                val geoPoint = placesRepository.getPlaceCoordinates(prediction.placeId)
                if (geoPoint != null) {
                    destinationProvider.setDestination(geoPoint)
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to get location for selected place."
            }
        }
    }

    fun onSpeechResult(text: String) {
        _searchQuery.value = text
        onSearchQueryChanged(text)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<NavigationState> = navigationEngine.observeNavigationState(
        locationFlow = locationProvider.locationFlow
            .map { GeoPoint(it.latitude, it.longitude, it.bearing) }
            .distinctUntilChanged(),
        destinationFlow = destinationProvider.destinationFlow,
        routeFlow = _route.asStateFlow()
    ).combine(_errorMessage.asStateFlow()) { state, error ->
        state.copy(errorMessage = error)
    }.onEach { state: NavigationState ->
        handleNavigationEvents(state)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NavigationState()
    )

    private fun handleNavigationEvents(state: NavigationState) {
        // Off-route detection
        if (state.isOffRoute && state.currentLocation != null && state.destination != null && !isFetchingRoute.get()) {
            onSpeak?.invoke("Off route. Recalculating.")
            recalculateRoute(state.currentLocation, state.destination)
        }

        // TTS Trigger: If instruction changed
        // Check if instruction is significantly different or if we just moved to a new step
        if (state.currentInstruction != lastInstruction && state.currentInstruction.isNotEmpty()) {
            lastInstruction = state.currentInstruction
            onSpeak?.invoke(state.currentInstruction)
        }
    }

    init {
        viewModelScope.launch {
            combine(
                destinationProvider.destinationFlow,
                locationProvider.locationFlow.filter { it.latitude != 0.0 }
            ) { dest, loc ->
                if (dest != null) GeoPoint(loc.latitude, loc.longitude) to dest else null
            }.collect { pair ->
                if (pair != null) {
                    val (location, destination) = pair
                    if (_route.value == null && !isFetchingRoute.get() && destination != lastFailedDestination) {
                        fetchRoute(location, destination)
                    }
                } else {
                    _route.value = null
                    lastInstruction = null
                    lastFailedDestination = null
                    _errorMessage.value = null
                }
            }
        }
    }

    private suspend fun fetchRoute(origin: GeoPoint, destination: GeoPoint) {
        if (isFetchingRoute.getAndSet(true)) return
        _errorMessage.value = null
        try {
            val route = directionsRepository.getRoute(origin, destination)
            _route.value = route
            if (route == null) {
                lastFailedDestination = destination
                _errorMessage.value = "Could not find a walking route to this destination."
            } else {
                lastFailedDestination = null
            }
        } catch (e: Exception) {
            _errorMessage.value = "Navigation error: ${e.localizedMessage}"
        } finally {
            isFetchingRoute.set(false)
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
            _route.value = null
            lastInstruction = null
            _errorMessage.value = null
        } else {
            val destination = GeoPoint(lat, lng)
            destinationProvider.setDestination(destination)
        }
    }

    fun performSearch() {
        val query = _searchQuery.value
        if (query.length < 3) return
        
        viewModelScope.launch {
            _errorMessage.value = null
            val geoPoint = try {
                placesRepository.searchPlaceByText(query)
            } catch (e: Exception) {
                null
            }

            if (geoPoint != null) {
                destinationProvider.setDestination(geoPoint)
                _suggestions.value = emptyList()
            } else {
                _errorMessage.value = "Place not found."
            }
        }
    }
}
