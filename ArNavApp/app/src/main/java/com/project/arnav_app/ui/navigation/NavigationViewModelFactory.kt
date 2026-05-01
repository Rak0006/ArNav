package com.project.arnav_app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.project.arnav_app.core.location.LocationProvider
import com.project.arnav_app.core.navigation.*

class NavigationViewModelFactory(
    private val locationProvider: LocationProvider,
    private val destinationProvider: DestinationProvider,
    private val directionsRepository: DirectionsRepository,
    private val navigationEngine: NavigationEngine,
    private val placesRepository: PlacesRepository,
    private val geminiRepository: GeminiRepository,
    private val onSpeak: ((String, String?) -> Unit)? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NavigationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NavigationViewModel(
                locationProvider = locationProvider,
                destinationProvider = destinationProvider,
                directionsRepository = directionsRepository,
                navigationEngine = navigationEngine,
                placesRepository = placesRepository,
                geminiRepository = geminiRepository,
                onSpeak = onSpeak
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
