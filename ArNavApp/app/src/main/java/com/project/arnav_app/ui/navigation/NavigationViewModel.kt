package com.project.arnav_app.ui.navigation

import android.util.Log
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
    private val geminiRepository: GeminiRepository,
    private val onSpeak: ((String, Boolean) -> Unit)? = null
) : ViewModel() {

    private val TAG = "NavigationViewModel"
    val isTestMode = false // Mock mode flag

    private val affirmations = setOf("yes", "yeah", "yep", "yup", "haan", "haa", "han", "ok", "okay", "sure", "go ahead", "proceed", "hajo", "theek hai", "sahi hai")
    private val negations = setOf("no", "nope", "nah", "cancel", "stop", "dont", "not now", "nahi", "naa", "mat karo")

    private val _systemState = MutableStateFlow(NavSystemState.IDLE)
    private val _route = MutableStateFlow<Route?>(null)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _speechText = MutableStateFlow("")
    private val _partialText = MutableStateFlow("")
    private val _isListening = MutableStateFlow(false)
    private val _searchQuery = MutableStateFlow("")
    private val _suggestions = MutableStateFlow<List<AutocompletePrediction>>(emptyList())

    val speechText = _speechText.asStateFlow()
    val partialText = _partialText.asStateFlow()
    val isListening = _isListening.asStateFlow()
    val searchQuery = _searchQuery.asStateFlow()
    val suggestions = _suggestions.asStateFlow()

    private val isFetchingRoute = AtomicBoolean(false)
    private var lastInstruction: String? = null
    private var pendingDestination: String? = null
    private var pendingGeoPoint: GeoPoint? = null

    private var routeSummaryCache: String? = null
    private var lastLlmCallTime = 0L
    private val MIN_LLM_INTERVAL = 2000L

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<NavigationState> = navigationEngine.observeNavigationState(
        locationFlow = locationProvider.locationFlow
            .map { GeoPoint(it.latitude, it.longitude, it.bearing) }
            .distinctUntilChanged(),
        destinationFlow = destinationProvider.destinationFlow,
        routeFlow = _route.asStateFlow()
    ).combine(_systemState) { state, systemState ->
        state.copy(systemState = systemState)
    }.combine(_errorMessage.asStateFlow()) { state, error ->
        state.copy(errorMessage = error)
    }.onEach { state ->
        handleNavigationEvents(state)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NavigationState()
    )

    init {
        observeDestinationChanges()
    }

    private fun observeDestinationChanges() {
        viewModelScope.launch {
            combine(
                destinationProvider.destinationFlow,
                locationProvider.locationFlow.filter { it.latitude != 0.0 }
            ) { dest, loc ->
                if (dest != null) GeoPoint(loc.latitude, loc.longitude) to dest else null
            }.collect { pair ->
                if (pair != null) {
                    val (location, destination) = pair
                    if (_route.value == null && !isFetchingRoute.get()) {
                        fetchRoute(location, destination)
                    }
                } else {
                    _route.value = null
                    _systemState.value = NavSystemState.IDLE
                    routeSummaryCache = null
                }
            }
        }
    }

    // --- Core Flow ---

    fun onSpeechResult(text: String) {
        _speechText.value = text
        Log.d(TAG, "STT -> text: $text")
        processSpeech(text)
    }

    private fun processSpeech(text: String) {
        viewModelScope.launch {
            // Priority: Local Confirmation Detection (Bypasses LLM in CONFIRMING state)
            if (_systemState.value == NavSystemState.CONFIRMING) {
                val detection = detectConfirmation(text)
                if (detection != null) {
                    handleConfirmation(IntentResult(detection))
                } else {
                    speak("I didn't quite get that. Do you want to go to $pendingDestination? Please say yes or no.", shouldListen = true)
                }
                return@launch
            }

            if (!shouldProcess(text)) {
                if (_systemState.value == NavSystemState.LISTENING) {
                    _systemState.value = if (_route.value != null) NavSystemState.NAVIGATING else NavSystemState.IDLE
                }
                return@launch
            }
            
            if (!canCallLlm()) {
                Log.w(TAG, "LLM call rate limited")
                return@launch
            }

            _systemState.value = NavSystemState.PROCESSING
            val result = callGemini(text)
            Log.d(TAG, "Parsed -> intent: ${result.intent}")

            if (pendingGeoPoint != null && (result.intent == "CONFIRM" || result.intent == "CANCEL")) {
                handleConfirmation(result)
            } else {
                handleIntent(result)
            }
        }
    }

    private fun canCallLlm(): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastLlmCallTime >= MIN_LLM_INTERVAL) {
            lastLlmCallTime = now
            true
        } else false
    }

    private fun shouldProcess(text: String): Boolean {
        if (text.length < 2) return false // Lowered for short queries like "yes"
        return true
    }

    private suspend fun callGemini(text: String): IntentResult {
        return try {
            geminiRepository.classifyIntent(text)
        } catch (e: Exception) {
            IntentResult("UNKNOWN")
        }
    }

    private suspend fun handleIntent(result: IntentResult) {
        when (result.intent) {
            "NAVIGATE" -> {
                val dest = result.destination
                if (dest != null) findAndConfirmDestination(dest)
                else {
                    speak("Where would you like to go?", shouldListen = true)
                    _systemState.value = NavSystemState.IDLE
                }
            }
            "QUERY" -> {
                handleQueryIntent(result.query ?: speechText.value)
            }
            "CANCEL" -> stopNavigation()
            "MODIFY" -> {
                val dest = result.destination
                if (dest != null) findAndConfirmDestination(dest)
            }
            else -> {
                speak("I didn't catch that. Try again.", shouldListen = true)
                _systemState.value = if (_route.value != null) NavSystemState.NAVIGATING else NavSystemState.IDLE
            }
        }
    }

    private suspend fun handleQueryIntent(query: String) {
        val state = uiState.value
        if (!state.isNavigating) {
            speak("I can only answer questions during navigation.")
            _systemState.value = NavSystemState.IDLE
            return
        }

        val eta = state.eta.filter { it.isDigit() }
        val distance = "%.1f".format(state.totalDistanceRemaining / 1000.0)
        val next = state.currentInstruction

        val response = geminiRepository.answerQuery(query, eta, distance, next) ?: fallbackQuery(query, eta, distance)
        
        speak(response)
        _systemState.value = NavSystemState.NAVIGATING
    }

    private fun fallbackQuery(query: String, eta: String, distance: String): String {
        return when {
            query.contains("time", true) || query.contains("long", true) -> "$eta minutes remaining"
            query.contains("distance", true) || query.contains("far", true) -> "$distance km remaining"
            else -> "Continuing navigation to your destination."
        }
    }

    private suspend fun summarizeRouteOnce(route: Route) {
        if (routeSummaryCache != null) return
        
        val distance = "%.1f".format(route.totalDistanceMeters / 1000.0)
        val eta = (route.totalDurationSeconds / 60).toString()
        val steps = route.steps.take(3).joinToString("\n") { "- ${it.instruction}" }

        val summary = geminiRepository.summarizeRoute(distance, eta, steps) ?: "The route is $distance km and will take about $eta minutes."
        routeSummaryCache = summary
        speak(summary)
    }

    private suspend fun findAndConfirmDestination(query: String) {
        _systemState.value = NavSystemState.PROCESSING
        val geoPoint = try {
            placesRepository.searchPlaceByText(query) ?: run {
                val suggestions = placesRepository.getAutocompleteSuggestions(query)
                if (suggestions.isNotEmpty()) placesRepository.getPlaceCoordinates(suggestions[0].placeId)
                else null
            }
        } catch (e: Exception) { null }

        if (geoPoint != null) {
            pendingGeoPoint = geoPoint
            pendingDestination = query
            _systemState.value = NavSystemState.CONFIRMING
            speak("Do you want to go to $query?", shouldListen = true)
        } else {
            speak("I couldn't find $query. Please try another place.", shouldListen = true)
            _systemState.value = NavSystemState.IDLE
        }
    }

    private fun handleConfirmation(result: IntentResult) {
        when (result.intent) {
            "CONFIRM" -> {
                pendingGeoPoint?.let {
                    speak("Alright.")
                    destinationProvider.setDestination(it)
                    _systemState.value = NavSystemState.NAVIGATING
                }
                pendingGeoPoint = null
                pendingDestination = null
            }
            "CANCEL" -> {
                speak("Navigation cancelled.")
                _systemState.value = NavSystemState.IDLE
                pendingGeoPoint = null
                pendingDestination = null
                routeSummaryCache = null
            }
            else -> {
                speak("I didn't quite get that. Do you want to go to $pendingDestination? Please say yes or no.", shouldListen = true)
            }
        }
    }

    private fun stopNavigation() {
        destinationProvider.setDestination(null)
        _route.value = null
        _systemState.value = NavSystemState.IDLE
        routeSummaryCache = null
        speak("Navigation stopped.")
    }

    private fun speak(text: String, shouldListen: Boolean = false) {
        onSpeak?.invoke(text, shouldListen)
    }

    private fun mockIntent(text: String): IntentResult {
        val lower = text.lowercase()
        return when {
            lower.contains("indiranagar") -> IntentResult("NAVIGATE", "Indiranagar")
            lower.contains("mg road") -> IntentResult("NAVIGATE", "MG Road")
            lower.contains("yes") || lower.contains("yeah") -> IntentResult("CONFIRM")
            lower.contains("no") || lower.contains("cancel") || lower.contains("stop") -> IntentResult("CANCEL")
            lower.contains("how far") || lower.contains("time") || lower.contains("eta") || lower.contains("close") || lower.contains("route") -> IntentResult("QUERY", text)
            lower.contains("change to") -> IntentResult("MODIFY", text.substringAfter("change to").trim())
            else -> IntentResult("UNKNOWN")
        }
    }

    private suspend fun fetchRoute(origin: GeoPoint, destination: GeoPoint) {
        if (isFetchingRoute.getAndSet(true)) return
        try {
            var route = directionsRepository.getRoute(origin, destination)
            if (route == null) {
                delay(500)
                route = directionsRepository.getRoute(origin, destination)
            }
            _route.value = route
            if (route != null) {
                summarizeRouteOnce(route)
            } else {
                _errorMessage.value = "Could not find route."
                speak("I couldn't find a walking route.")
                _systemState.value = NavSystemState.ERROR
            }
        } catch (e: Exception) {
            _errorMessage.value = e.localizedMessage
        } finally {
            isFetchingRoute.set(false)
        }
    }

    private fun handleNavigationEvents(state: NavigationState) {
        if (state.isArrived && _systemState.value == NavSystemState.NAVIGATING) {
            speak("You have arrived at your destination.")
            _systemState.value = NavSystemState.IDLE
            destinationProvider.setDestination(null)
            routeSummaryCache = null
        }
        if (state.isOffRoute && state.currentLocation != null && state.destination != null && !isFetchingRoute.get()) {
            speak("Off route. Recalculating.")
            recalculateRoute(state.currentLocation, state.destination)
        }
        if (state.currentInstruction != lastInstruction && state.currentInstruction.isNotEmpty()) {
            lastInstruction = state.currentInstruction
            speak(state.currentInstruction)
        }
    }

    private fun recalculateRoute(origin: GeoPoint, destination: GeoPoint) {
        viewModelScope.launch { fetchRoute(origin, destination) }
    }

    fun onOverlayTap() {
        val now = System.currentTimeMillis()
        if (now - lastLlmCallTime < 500) return // Debounce taps (500ms)

        if (_systemState.value != NavSystemState.LISTENING && _systemState.value != NavSystemState.PROCESSING) {
            viewModelScope.launch {
                onSpeak?.invoke("", false) // Interrupt any current speech
                _partialText.value = ""
                _speechText.value = ""
                _systemState.value = NavSystemState.LISTENING
                _isListening.value = true
                // onStartListening is triggered via the UI callback to MainActivity
            }
        }
    }

    fun onPartialSpeechResult(text: String) {
        _partialText.value = text
        resetSilenceTimer()
    }

    private var silenceJob: Job? = null
    private fun resetSilenceTimer() {
        silenceJob?.cancel()
        silenceJob = viewModelScope.launch {
            delay(5000) // 5s timeout
            if (_systemState.value == NavSystemState.LISTENING) {
                _isListening.value = false
                _systemState.value = if (_route.value != null) NavSystemState.NAVIGATING else NavSystemState.IDLE
            }
        }
    }

    fun setListening(listening: Boolean) {
        _isListening.value = listening
        if (listening) {
            _systemState.value = NavSystemState.LISTENING
            _partialText.value = ""
            resetSilenceTimer()
        } else if (_systemState.value == NavSystemState.LISTENING) {
            viewModelScope.launch {
                delay(500)
                if (_speechText.value.isEmpty() && _partialText.value.isEmpty()) {
                    _systemState.value = if (_route.value != null) NavSystemState.NAVIGATING else NavSystemState.IDLE
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        if (query.length >= 3) {
            viewModelScope.launch {
                try {
                    _suggestions.value = placesRepository.getAutocompleteSuggestions(query)
                } catch (e: Exception) {
                    _suggestions.value = emptyList()
                }
            }
        } else _suggestions.value = emptyList()
    }

    fun onSuggestionSelected(prediction: AutocompletePrediction) {
        _searchQuery.value = prediction.getPrimaryText(null)?.toString() ?: ""
        _suggestions.value = emptyList()
        viewModelScope.launch {
            try {
                val geoPoint = placesRepository.getPlaceCoordinates(prediction.placeId)
                if (geoPoint != null) {
                    destinationProvider.setDestination(geoPoint)
                    _systemState.value = NavSystemState.NAVIGATING
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to get location."
            }
        }
    }

    fun updateDestination(lat: Double, lng: Double) {
        if (lat == 0.0 && lng == 0.0) stopNavigation()
        else {
            destinationProvider.setDestination(GeoPoint(lat, lng))
            _systemState.value = NavSystemState.NAVIGATING
        }
    }

    fun clearText() {
        _speechText.value = ""
        _partialText.value = ""
    }

    fun performSearch() {
        val query = _searchQuery.value
        if (query.length < 3) return
        viewModelScope.launch { findAndConfirmDestination(query) }
    }

    // --- Confirmation Helpers ---

    private fun normalizeText(text: String): String =
        text.lowercase().trim().replace(Regex("[^a-z\\s]"), "")

    private fun detectConfirmation(text: String): String? {
        val normalized = normalizeText(text)
        if (normalized.isEmpty()) return null

        // 1. Exact match
        if (affirmations.contains(normalized)) return "CONFIRM"
        if (negations.contains(normalized)) return "CANCEL"

        // 2. Contains match
        if (affirmations.any { normalized.containsWord(it) }) return "CONFIRM"
        if (negations.any { normalized.containsWord(it) }) return "CANCEL"

        // 3. Fuzzy match
        for (term in affirmations) {
            if (isFuzzyMatch(normalized, term)) return "CONFIRM"
        }
        for (term in negations) {
            if (isFuzzyMatch(normalized, term)) return "CANCEL"
        }

        return null
    }

    private fun String.containsWord(word: String): Boolean {
        val escaped = Regex.escape(word)
        return Regex("\\b$escaped\\b").containsMatchIn(this)
    }

    private fun isFuzzyMatch(text: String, target: String): Boolean {
        if (text.isEmpty() || target.isEmpty()) return false
        if (text.length < 3 || target.length < 3) return text == target
        val distance = levenshteinDistance(text, target)
        val similarity = 1.0 - (distance.toDouble() / maxOf(text.length, target.length))
        return similarity >= 0.8
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, minOf(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost))
            }
        }
        return dp[s1.length][s2.length]
    }
}
