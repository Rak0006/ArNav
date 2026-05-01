package com.project.arnav_app.core.navigation

import android.content.Context
import android.util.Log
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.*
import kotlinx.coroutines.tasks.await

class PlacesRepository(context: Context) {
    private val placesClient: PlacesClient = Places.createClient(context)

    suspend fun getAutocompleteSuggestions(query: String): List<AutocompletePrediction> {
        if (query.isBlank()) return emptyList()
        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .build()
        return try {
            val response: FindAutocompletePredictionsResponse = placesClient.findAutocompletePredictions(request).await()
            response.autocompletePredictions
        } catch (e: Exception) {
            Log.e("PlacesRepo", "Autocomplete error: ${e.message}")
            emptyList()
        }
    }

    suspend fun searchPlaceByText(query: String): GeoPoint? {
        // Note: SearchByText requires Places API (New) to be enabled in Cloud Console
        val placeFields = listOf(Place.Field.LAT_LNG)
        val request = SearchByTextRequest.builder(query, placeFields).build()
        return try {
            val response: SearchByTextResponse = placesClient.searchByText(request).await()
            val firstPlace = response.places.firstOrNull()
            firstPlace?.latLng?.let { GeoPoint(it.latitude, it.longitude) }
        } catch (e: Exception) {
            Log.e("PlacesRepo", "SearchByText error (Ensure 'Places API (New)' is enabled): ${e.message}")
            // Fallback: If SearchByText fails, we might want to use autocomplete and then fetch coordinates
            // but for now, we'll just log and return null to avoid confusion.
            null
        }
    }

    suspend fun getPlaceCoordinates(placeId: String): GeoPoint? {
        val placeFields = listOf(Place.Field.LAT_LNG)
        val request = FetchPlaceRequest.newInstance(placeId, placeFields)
        return try {
            val response: FetchPlaceResponse = placesClient.fetchPlace(request).await()
            val latLng = response.place.latLng
            if (latLng != null) {
                GeoPoint(latLng.latitude, latLng.longitude)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("PlacesRepo", "FetchPlace error: ${e.message}")
            null
        }
    }
}
