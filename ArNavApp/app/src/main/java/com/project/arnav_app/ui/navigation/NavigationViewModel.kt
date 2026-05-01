package com.project.arnav_app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.project.arnav_app.core.location.LocationProvider
import com.project.arnav_app.core.navigation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.google.android.libraries.places.api.model.AutocompletePrediction
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class NavigationViewModel(
    private val locationProvider: LocationProvider,
    private val destinationProvider: DestinationProvider,
    private val directionsRepository: DirectionsRepository,
    private val navigationEngine: NavigationEngine,
    private val placesRepository: PlacesRepository,
    private val geminiRepository: GeminiRepository,
    private val onSpeak: ((String, String?) -> Unit)? = null
) : ViewModel() {

    private val _route = MutableStateFlow<Route?>(null)
    private val _isNavigating = MutableStateFlow(false)
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _suggestions = MutableStateFlow<List<AutocompletePrediction>>(emptyList())
    val suggestions = _suggestions.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState = _voiceState.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText = _partialText.asStateFlow()

    private val _isMapTracking = MutableStateFlow(true)
    val isMapTracking = _isMapTracking.asStateFlow()

    var onStartSTT: (() -> Unit)? = null

    fun setMapTracking(enabled: Boolean) {
        _isMapTracking.value = enabled
    }

    private fun shouldProcess(text: String): Boolean {
        return text.isNotBlank()
    }


    private var searchJob: Job? = null
    private val isFetchingRoute = AtomicBoolean(false)
    private val pendingStart = AtomicBoolean(false)
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
                _route.value = null
                _isNavigating.value = false
                _errorMessage.value = null
                lastFailedDestination = null
                
                val geoPoint = placesRepository.getPlaceCoordinates(prediction.placeId)
                if (geoPoint != null) {
                    destinationProvider.setDestination(geoPoint)
                } else {
                    _errorMessage.value = "Could not resolve place coordinates."
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to get location: ${e.message}"
            }
        }
    }

    fun onOverlayTap() {
        if (_voiceState.value != VoiceState.LISTENING) {
            _partialText.value = ""
            _voiceState.value = VoiceState.LISTENING
            onStartSTT?.invoke()
        }
    }

    fun onPartialSpeechResult(text: String) {
        _partialText.value = text
    }

    fun simulateSpeech(text: String) {
        onSpeechResult(text)
    }

    fun onSpeechResult(text: String) {
        val previousState = _voiceState.value
        Log.d("NavigationVM", "onSpeechResult: $text, state: $previousState")
        _partialText.value = text
        
        if (!shouldProcess(text)) {
            Log.d("NavigationVM", "onSpeechResult: shouldProcess was false")
            // Keep listening if we were expecting input
            if (previousState != VoiceState.IDLE) {
                onStartSTT?.invoke()
            }
            return
        }

        viewModelScope.launch {
            _voiceState.value = VoiceState.PROCESSING
            Log.d("NavigationVM", "Calling Gemini for text: $text")
            val result = try {
                geminiRepository.parseIntent(text)
            } catch (e: Exception) {
                Log.e("NavigationVM", "Gemini error", e)
                IntentResult("UNKNOWN")
            }
            Log.d("NavigationVM", "Gemini result: $result")
            
            handleIntent(result, previousState)
        }
    }


    fun onSpeechError() {
        Log.e("NavigationVM", "Speech error received. Current state: ${_voiceState.value}")
        // Don't reset to IDLE if we are in a state that's waiting for confirmation, 
        // to allow automated tests/simulation to continue.
        if (_voiceState.value != VoiceState.LISTENING && _voiceState.value != VoiceState.DEST_CONFIRM) {
            _voiceState.value = VoiceState.IDLE
        }
    }

    private suspend fun handleIntent(result: IntentResult, previousState: VoiceState) {
        Log.d("NavigationVM", "handleIntent: ${result.intent}, previousState: $previousState, destination: ${result.destination}")
        when (result.intent) {
            "NAVIGATE" -> {
                result.destination?.let {
                    _searchQuery.value = it
                    performSearch()
                    _voiceState.value = VoiceState.DEST_CONFIRM
                    onSpeak?.invoke("Found $it. Go there?", "DEST_CONFIRM")
                } ?: run {
                    _voiceState.value = VoiceState.IDLE
                    onSpeak?.invoke("Where would you like to go?", null)
                }
            }
            "CONFIRM" -> {
                Log.d("NavigationVM", "CONFIRM intent received in state: $previousState. Current state: ${_voiceState.value}")
                // If we were in a confirm state, or in LISTENING (which follows the confirm TTS)
                val isConfirming = previousState == VoiceState.DEST_CONFIRM || 
                                 previousState == VoiceState.ROUTE_CONFIRM || 
                                 previousState == VoiceState.LISTENING
                
                if (isConfirming) {
                    Log.d("NavigationVM", "Triggering navigation from CONFIRM")
                    if (_route.value != null) {
                        onSpeak?.invoke("Starting navigation.", null)
                        startNavigation()
                    } else {
                        Log.d("NavigationVM", "Route not ready yet, setting pendingStart = true")
                        pendingStart.set(true)
                        onSpeak?.invoke("Okay, starting once route is ready.", null)
                        _errorMessage.value = "Route calculating... starting automatically."
                    }
                    _voiceState.value = VoiceState.NAVIGATING
                } else {
                    Log.w("NavigationVM", "CONFIRM received but not in a confirmation state. resetting.")
                    _voiceState.value = VoiceState.IDLE
                }
            }
            "CANCEL" -> {
                stopNavigation()
                _voiceState.value = VoiceState.IDLE
            }
            "MODIFY" -> {
                _voiceState.value = VoiceState.IDLE
                onOverlayTap()
            }
            else -> {
                _voiceState.value = VoiceState.IDLE
                if (previousState != VoiceState.IDLE && previousState != VoiceState.NAVIGATING) {
                    onSpeak?.invoke("I didn't get that. Say again?", null)
                }
            }
        }
    }

    fun onTtsDone(utteranceId: String) {
        if (utteranceId == "DEST_CONFIRM" || utteranceId == "ROUTE_CONFIRM") {
            _voiceState.value = VoiceState.LISTENING
            onStartSTT?.invoke()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<NavigationState> = navigationEngine.observeNavigationState(
        locationFlow = locationProvider.locationFlow
            .map { GeoPoint(it.latitude, it.longitude, it.bearing) },
        destinationFlow = destinationProvider.destinationFlow,
        routeFlow = _route.asStateFlow(),
        isNavigatingFlow = _isNavigating.asStateFlow()
    ).combine(_errorMessage.asStateFlow()) { engineState, error ->
        engineState.copy(errorMessage = error)
    }.onEach { state ->
        if (state.isArrived && _isNavigating.value) {
            _isNavigating.value = false
            _voiceState.value = VoiceState.IDLE
        }
        handleNavigationEvents(state)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = NavigationState()
    )

    private fun handleNavigationEvents(state: NavigationState) {
        if (!state.isNavigating) return

        if (state.isOffRoute && state.currentLocation != null && state.destination != null && !isFetchingRoute.get()) {
            onSpeak?.invoke("Off route. Recalculating.", null)
            recalculateRoute(state.currentLocation, state.destination)
        }

        if (state.currentInstruction != lastInstruction && state.currentInstruction.isNotEmpty()) {
            lastInstruction = state.currentInstruction
            onSpeak?.invoke(state.currentInstruction, null)
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
                    val currentRoute = _route.value
                    val needsNewRoute = currentRoute == null || 
                                       calculateDistance(currentRoute.destination, destination) > 10.0

                    if (needsNewRoute && !isFetchingRoute.get() && destination != lastFailedDestination) {
                        fetchRoute(location, destination)
                    }
                } else {
                    _route.value = null
                    _isNavigating.value = false
                    lastInstruction = null
                    lastFailedDestination = null
                    _errorMessage.value = null
                }
            }
        }
    }

    private suspend fun fetchRoute(origin: GeoPoint, destination: GeoPoint) {
        if (isFetchingRoute.getAndSet(true)) return
        Log.d("NavigationVM", "fetchRoute starting: origin=$origin, dest=$destination")
        _errorMessage.value = "Calculating route..."
        try {
            val route = directionsRepository.getRoute(origin, destination)
            Log.d("NavigationVM", "fetchRoute result: ${if (route != null) "Success" else "Null"}")
            _route.value = route
            
            if (route != null) {
                lastFailedDestination = null
                _errorMessage.value = null
                
                if (pendingStart.getAndSet(false)) {
                    Log.d("NavigationVM", "Route ready and pendingStart was true. Starting navigation now.")
                    startNavigation()
                }
            } else {
                lastFailedDestination = destination
                _errorMessage.value = "Route not found. Ensure 'Directions API' is enabled."
                pendingStart.set(false)
            }
        } catch (e: Exception) {
            _errorMessage.value = "Navigation error: ${e.localizedMessage}"
            pendingStart.set(false)
        } finally {
            isFetchingRoute.set(false)
        }
    }

    private fun recalculateRoute(origin: GeoPoint, destination: GeoPoint) {
        viewModelScope.launch {
            fetchRoute(origin, destination)
        }
    }

    fun startNavigation() {
        Log.d("NavigationVM", "startNavigation() called. Route present: ${_route.value != null}")
        if (_route.value != null) {
            _isNavigating.value = true
            _errorMessage.value = null
            _voiceState.value = VoiceState.NAVIGATING
        } else {
            _errorMessage.value = "Please search for a destination first."
        }
    }

    fun stopNavigation() {
        _isNavigating.value = false
        _route.value = null
        destinationProvider.setDestination(null)
        _searchQuery.value = ""
        lastInstruction = null
        _errorMessage.value = null
        _voiceState.value = VoiceState.IDLE
        pendingStart.set(false)
    }

    fun updateDestination(lat: Double, lng: Double) {
        if (lat == 0.0 && lng == 0.0) {
            stopNavigation()
            return
        }
        val destination = GeoPoint(lat, lng)
        destinationProvider.setDestination(destination)
    }

    private fun calculateDistance(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val aVal = (Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)).coerceIn(0.0, 1.0)
        val c = 2 * Math.atan2(Math.sqrt(aVal), Math.sqrt(1 - aVal))
        return r * c
    }

    fun performSearch() {
        val query = _searchQuery.value
        if (query.length < 3) return
        
        viewModelScope.launch {
            _errorMessage.value = "Searching..."
            var geoPoint = try {
                placesRepository.searchPlaceByText(query)
            } catch (e: Exception) {
                null
            }

            if (geoPoint == null) {
                try {
                    val predictions = placesRepository.getAutocompleteSuggestions(query)
                    val firstPrediction = predictions.firstOrNull()
                    if (firstPrediction != null) {
                        geoPoint = placesRepository.getPlaceCoordinates(firstPrediction.placeId)
                        _searchQuery.value = firstPrediction.getPrimaryText(null).toString()
                    }
                } catch (e: Exception) { }
            }

            if (geoPoint != null) {
                destinationProvider.setDestination(geoPoint)
                _suggestions.value = emptyList()
                _errorMessage.value = null
            } else {
                _errorMessage.value = "Place not found. Please try a more specific name."
            }
        }
    }
}
