package com.project.arnav_app.ui.navigation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.project.arnav_app.core.haptics.HapticFeedbackManager
import com.project.arnav_app.core.location.LocationProvider
import com.project.arnav_app.core.navigation.*
import com.project.arnav_app.core.perception.ObstacleRisk
import com.project.arnav_app.core.perception.Detection
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
    private val hapticFeedbackManager: HapticFeedbackManager,
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
    private var isSpeakingSummary = false
    private var lastInstruction: String? = null
    private var pendingDestination: String? = null
    private var pendingGeoPoint: GeoPoint? = null

    private var _obstacleRisk = MutableStateFlow(ObstacleRisk.LOW)
    val obstacleRisk = _obstacleRisk.asStateFlow()

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections = _detections.asStateFlow()

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

    fun observeObstacleRisk(riskFlow: SharedFlow<ObstacleRisk>, detectionsFlow: StateFlow<List<Detection>>) {
        viewModelScope.launch {
            riskFlow.collect { risk ->
                _obstacleRisk.value = risk
                when (risk) {
                    ObstacleRisk.HIGH -> hapticFeedbackManager.playObstacleHigh()
                    ObstacleRisk.MEDIUM -> hapticFeedbackManager.playObstacleMedium()
                    ObstacleRisk.LOW -> {} 
                }
            }
        }
        viewModelScope.launch {
            detectionsFlow.collect { detections ->
                _detections.value = detections
            }
        }
    }

    private var pendingWaypoints: List<GeoPoint> = emptyList()
    private var currentWaypoints: List<GeoPoint> = emptyList()

    private fun observeDestinationChanges() {
        viewModelScope.launch {
            combine(
                destinationProvider.destinationFlow,
                destinationProvider.waypointsFlow,
                locationProvider.locationFlow.filter { it.latitude != 0.0 }
            ) { dest, waypoints, loc ->
                if (dest != null) Triple(GeoPoint(loc.latitude, loc.longitude), dest, waypoints) else null
            }.collect { triple ->
                if (triple != null) {
                    val (location, destination, waypoints) = triple
                    if (_route.value == null && !isFetchingRoute.get()) {
                        currentWaypoints = waypoints
                        fetchRoute(location, destination, waypoints)
                    }
                } else {
                    _route.value = null
                    _systemState.value = NavSystemState.IDLE
                    routeSummaryCache = null
                    currentWaypoints = emptyList()
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
            hapticFeedbackManager.playProcessingAcknowledgement()
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
            hapticFeedbackManager.playVoiceError()
            IntentResult("UNKNOWN")
        }
    }

    private suspend fun handleIntent(result: IntentResult) {
        when (result.intent) {
            "NAVIGATE", "NAVIGATE_VIA" -> {
                val dest = result.destination
                val via = result.via
                if (dest != null) findAndConfirmDestination(dest, via)
                else {
                    speak("Where would you like to go?", shouldListen = true)
                    _systemState.value = NavSystemState.IDLE
                }
            }
            "ALTERNATIVE_ROUTE" -> {
                speak("Searching for an alternative route.", shouldListen = false)
                // Logic to trigger alternative route could be added here
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
        if (routeSummaryCache != null) {
            isSpeakingSummary = false
            return
        }
        isSpeakingSummary = true
        
        val distance = "%.1f".format(route.totalDistanceMeters / 1000.0)
        val eta = (route.totalDurationSeconds / 60).toString()
        val steps = route.steps.take(3).joinToString("\n") { "- ${it.instruction}" }

        val summary = geminiRepository.summarizeRoute(distance, eta, steps) ?: "The route is $distance km and will take about $eta minutes."
        routeSummaryCache = summary
        speak(summary)
        delay(6000) // Increased delay to prevent instructions from cutting off summary
        isSpeakingSummary = false
    }

    private suspend fun findAndConfirmDestination(query: String, via: String? = null) {
        _systemState.value = NavSystemState.PROCESSING
        
        val destGeoPoint = try {
            placesRepository.searchPlaceByText(query) ?: run {
                val suggestions = placesRepository.getAutocompleteSuggestions(query)
                if (suggestions.isNotEmpty()) placesRepository.getPlaceCoordinates(suggestions[0].placeId)
                else null
            }
        } catch (e: Exception) { null }

        if (destGeoPoint != null) {
            pendingGeoPoint = destGeoPoint
            pendingDestination = query
            
            val viaGeoPoint = via?.let {
                try {
                    placesRepository.searchPlaceByText(it) ?: run {
                        val suggestions = placesRepository.getAutocompleteSuggestions(it)
                        if (suggestions.isNotEmpty()) placesRepository.getPlaceCoordinates(suggestions[0].placeId)
                        else null
                    }
                } catch (e: Exception) { null }
            }
            
            pendingWaypoints = listOfNotNull(viaGeoPoint)
            _systemState.value = NavSystemState.CONFIRMING
            hapticFeedbackManager.playConfirmationPrompt()
            
            val confirmationMsg = if (via != null && viaGeoPoint != null) {
                "Do you want to go to $query via $via? Proceed with a yes."
            } else {
                "Do you want to go to $query? Proceed with a yes."
            }
            speak(confirmationMsg, shouldListen = true)
        } else {
            speak("I couldn't find $query. Please try another place. Proceed with a yes to retry.", shouldListen = true)
            _systemState.value = NavSystemState.IDLE
        }
    }

    private fun handleConfirmation(result: IntentResult) {
        when (result.intent) {
            "CONFIRM" -> {
                hapticFeedbackManager.playConfirmationAccepted()
                pendingGeoPoint?.let { geoPoint ->
                    _systemState.value = NavSystemState.PROCESSING
                    speak("Alright.", shouldListen = false)
                    
                    val waypoints = pendingWaypoints
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        hapticFeedbackManager.playNavigationStart()
                        destinationProvider.setDestination(geoPoint, waypoints)
                        _systemState.value = NavSystemState.NAVIGATING
                    }, 600)
                }
                pendingGeoPoint = null
                pendingDestination = null
                pendingWaypoints = emptyList()
            }
            "CANCEL" -> {
                hapticFeedbackManager.playConfirmationRejected()
                speak("Navigation cancelled.")
                _systemState.value = NavSystemState.IDLE
                pendingGeoPoint = null
                pendingDestination = null
                pendingWaypoints = emptyList()
                routeSummaryCache = null
            }
            else -> {
                speak("I didn't quite get that. Do you want to go to $pendingDestination? Please say yes or no.", shouldListen = true)
            }
        }
    }

    private fun stopNavigation() {
        hapticFeedbackManager.playNavigationStop()
        destinationProvider.setDestination(null, emptyList())
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

    private suspend fun fetchRoute(origin: GeoPoint, destination: GeoPoint, waypoints: List<GeoPoint> = emptyList()) {
        if (isFetchingRoute.getAndSet(true)) return
        try {
            var route = directionsRepository.getRoute(origin, destination, waypoints)
            if (route == null) {
                delay(500)
                route = directionsRepository.getRoute(origin, destination, waypoints)
            }
            if (_route.value != null && route != null) {
                hapticFeedbackManager.playRouteRecalculation()
            }
            
            if (route != null) {
                // Set isSpeakingSummary to true BEFORE updating _route.value to block initial navigation instructions
                if (routeSummaryCache == null) {
                    isSpeakingSummary = true
                }
                _route.value = route
                summarizeRouteOnce(route)
            } else {
                _route.value = null
                _errorMessage.value = "Could not find route."
                hapticFeedbackManager.playVoiceError()
                speak("I couldn't find a walking route.")
                _systemState.value = NavSystemState.ERROR
            }
        } catch (e: Exception) {
            _errorMessage.value = e.localizedMessage
            hapticFeedbackManager.playVoiceError()
        } finally {
            isFetchingRoute.set(false)
        }
    }

    private var hasPlayedTurnPrep = false
    private var hasPlayedTurnNow = false

    private fun handleNavigationEvents(state: NavigationState) {
        if (state.isArrived && _systemState.value == NavSystemState.NAVIGATING) {
            hapticFeedbackManager.playArrival()
            speak("You have arrived at your destination.")
            _systemState.value = NavSystemState.IDLE
            destinationProvider.setDestination(null, emptyList())
            routeSummaryCache = null
        }
        if (state.isOffRoute && state.currentLocation != null && state.destination != null && !isFetchingRoute.get()) {
            hapticFeedbackManager.playOffRoute()
            speak("Off route. Recalculating.")
            recalculateRoute(state.currentLocation, state.destination)
        }

        // Turn haptics
        if (state.isNavigating && !state.isArrived) {
            val dist = state.distanceToNextStep
            if (dist <= 10f) { // Immediate
                if (!hasPlayedTurnNow) {
                    hapticFeedbackManager.playTurnNow()
                    hasPlayedTurnNow = true
                }
            } else if (dist <= 30f) { // Preparation
                if (!hasPlayedTurnPrep) {
                    hapticFeedbackManager.playTurnPreparation()
                    hasPlayedTurnPrep = true
                }
            } else {
                hasPlayedTurnPrep = false
                hasPlayedTurnNow = false
            }
        }

        if (!isSpeakingSummary && state.currentInstruction != lastInstruction && state.currentInstruction.isNotEmpty()) {
            lastInstruction = state.currentInstruction
            speak(state.currentInstruction)
        }
    }

    private fun recalculateRoute(origin: GeoPoint, destination: GeoPoint) {
        viewModelScope.launch { fetchRoute(origin, destination, currentWaypoints) }
    }

    fun onOverlayTap() {
        hapticFeedbackManager.playOverlayTap()
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
            hapticFeedbackManager.playListeningStart()
            _systemState.value = NavSystemState.LISTENING
            _partialText.value = ""
            resetSilenceTimer()
        } else if (_systemState.value == NavSystemState.LISTENING) {
            hapticFeedbackManager.playListeningEnd()
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
        hapticFeedbackManager.playButtonPress()
        _searchQuery.value = prediction.getPrimaryText(null)?.toString() ?: ""
        _suggestions.value = emptyList()
        viewModelScope.launch {
            try {
                val geoPoint = placesRepository.getPlaceCoordinates(prediction.placeId)
                if (geoPoint != null) {
                    hapticFeedbackManager.playNavigationStart()
                    destinationProvider.setDestination(geoPoint, emptyList())
                    _systemState.value = NavSystemState.NAVIGATING
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to get location."
                hapticFeedbackManager.playVoiceError()
            }
        }
    }

    fun updateDestination(lat: Double, lng: Double) {
        if (lat == 0.0 && lng == 0.0) stopNavigation()
        else {
            destinationProvider.setDestination(GeoPoint(lat, lng), emptyList())
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
        viewModelScope.launch { findAndConfirmDestination(query, null) }
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
