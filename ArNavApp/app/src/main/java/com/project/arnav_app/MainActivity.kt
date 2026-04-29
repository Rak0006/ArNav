package com.project.arnav_app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.project.arnav_app.core.location.DefaultLocationProvider
import com.google.android.libraries.places.api.Places
import com.project.arnav_app.core.navigation.*
import com.project.arnav_app.ui.navigation.NavigationScreen
import com.project.arnav_app.ui.navigation.NavigationViewModel
import com.project.arnav_app.ui.navigation.NavigationViewModelFactory
import com.project.arnav_app.ui.theme.ArNavAppTheme
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Manual DI for MVP
        val apiKey = "AIzaSyA-H9sCG0f14XtInSdBvnYjcJcY56-RTGY"

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, apiKey)
        }
        val placesRepository = PlacesRepository(applicationContext)

        val logging = HttpLoggingInterceptor()
        logging.level = HttpLoggingInterceptor.Level.BODY

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        val json = Json { ignoreUnknownKeys = true }
        val contentType = "application/json".toMediaType()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://maps.googleapis.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        val apiService = retrofit.create(DirectionsApiService::class.java)
        val directionsRepository = DirectionsRepository(apiService, apiKey)

        val locationProvider = DefaultLocationProvider(applicationContext)
        val destinationProvider = InMemoryDestinationProvider()
        val navigationEngine = RealTimeNavigationEngine()

        val factory = NavigationViewModelFactory(
            locationProvider,
            destinationProvider,
            directionsRepository,
            navigationEngine,
            placesRepository
        )

        setContent {
            ArNavAppTheme {
                val navViewModel: NavigationViewModel = viewModel(factory = factory)
                val uiState by navViewModel.uiState.collectAsState()

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val granted = permissions.values.all { it }
                    if (!granted) {
                        Toast.makeText(this@MainActivity, "Location permission is required for navigation", Toast.LENGTH_LONG).show()
                    }
                }

                LaunchedEffect(Unit) {
                    val permissions = arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (permissions.any { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }) {
                        permissionLauncher.launch(permissions)
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val searchQuery by navViewModel.searchQuery.collectAsState()
                    val suggestions by navViewModel.suggestions.collectAsState()

                    NavigationScreen(
                        state = uiState,
                        searchQuery = searchQuery,
                        suggestions = suggestions,
                        onSearchQueryChanged = { navViewModel.onSearchQueryChanged(it) },
                        onSuggestionSelected = { navViewModel.onSuggestionSelected(it) },
                        onSpeechResult = { navViewModel.onSpeechResult(it) },
                        onStartNavigation = { lat, lng ->
                            navViewModel.updateDestination(lat, lng)
                        },
                        onSearchClicked = { navViewModel.performSearch() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}
