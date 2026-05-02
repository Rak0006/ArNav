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
        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .build()
        return try {
            val response = placesClient.findAutocompletePredictions(request).await()
            val firstPrediction = response.autocompletePredictions.firstOrNull()
            if (firstPrediction != null) {
                getPlaceCoordinates(firstPrediction.placeId)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("PlacesRepo", "Search error via Autocomplete: ${e.message}")
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
