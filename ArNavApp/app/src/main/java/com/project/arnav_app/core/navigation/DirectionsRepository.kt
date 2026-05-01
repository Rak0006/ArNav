package com.project.arnav_app.core.navigation

import android.text.Html
import android.util.Log

class DirectionsRepository(
    private val apiService: DirectionsApiService,
    private val apiKey: String
) {
    suspend fun getRoute(origin: GeoPoint, destination: GeoPoint): Route? {
        return try {
            val originStr = "${origin.latitude},${origin.longitude}"
            val destStr = "${destination.latitude},${destination.longitude}"
            val response = apiService.getDirections(originStr, destStr, "walking", apiKey)
            
            if (response.status == "OK" && response.routes.isNotEmpty()) {
                val directionsRoute = response.routes[0]
                if (directionsRoute.legs.isEmpty()) return null
                
                val leg = directionsRoute.legs[0]
                
                val decodedPolyline = directionsRoute.overviewPolyline?.points?.let { 
                    PolylineUtil.decode(it) 
                } ?: emptyList()

                if (decodedPolyline.isEmpty()) return null

                val steps = leg.steps.map { step ->
                    NavStep(
                        instruction = cleanHtml(step.htmlInstructions),
                        start = GeoPoint(step.startLocation?.lat ?: 0.0, step.startLocation?.lng ?: 0.0),
                        end = GeoPoint(step.endLocation?.lat ?: 0.0, step.endLocation?.lng ?: 0.0),
                        distanceMeters = step.distance?.value ?: 0,
                        maneuver = step.maneuver
                    )
                }

                Route(
                    destination = destination,
                    polyline = decodedPolyline,
                    steps = steps,
                    totalDistanceMeters = leg.distance?.value ?: 0,
                    totalDurationSeconds = leg.duration?.value ?: 0
                )
            } else {
                Log.e("DirectionsRepository", "API Error: ${response.status} - ${response.errorMessage}")
                null
            }
        } catch (e: Exception) {
            Log.e("DirectionsRepository", "Error fetching directions", e)
            null
        }
    }

    private fun cleanHtml(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
}
