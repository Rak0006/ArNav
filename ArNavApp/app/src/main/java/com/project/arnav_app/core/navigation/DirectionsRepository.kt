package com.project.arnav_app.core.navigation

import android.util.Log

class DirectionsRepository(
    private val apiService: DirectionsApiService,
    private val apiKey: String
) {
    suspend fun getRoute(origin: GeoPoint, destination: GeoPoint): DirectionsResponse? {
        return try {
            val originStr = "${origin.latitude},${origin.longitude}"
            val destStr = "${destination.latitude},${destination.longitude}"
            apiService.getDirections(originStr, destStr, "walking", apiKey)
        } catch (e: java.net.UnknownHostException) {
            Log.e("DirectionsRepository", "Network unreachable: Unable to resolve host", e)
            null
        } catch (e: Exception) {
            Log.e("DirectionsRepository", "Error fetching directions", e)
            null
        }
    }
}
