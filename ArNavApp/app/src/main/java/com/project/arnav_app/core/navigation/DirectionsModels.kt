package com.project.arnav_app.core.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DirectionsResponse(
    val routes: List<DirectionsRoute> = emptyList(),
    val status: String = "",
    @SerialName("error_message") val errorMessage: String? = null
)

@Serializable
data class DirectionsRoute(
    @SerialName("overview_polyline") val overviewPolyline: Polyline? = null,
    val legs: List<Leg> = emptyList()
)

@Serializable
data class Polyline(
    val points: String = ""
)

@Serializable
data class Leg(
    val distance: TextValue? = null,
    val duration: TextValue? = null,
    val steps: List<Step> = emptyList(),
    @SerialName("start_location") val startLocation: LatLngLiteral? = null,
    @SerialName("end_location") val endLocation: LatLngLiteral? = null
)

@Serializable
data class Step(
    @SerialName("html_instructions") val htmlInstructions: String = "",
    val distance: TextValue? = null,
    val duration: TextValue? = null,
    @SerialName("start_location") val startLocation: LatLngLiteral? = null,
    @SerialName("end_location") val endLocation: LatLngLiteral? = null,
    val maneuver: String? = null,
    val polyline: Polyline? = null
)

@Serializable
data class TextValue(
    val text: String = "",
    val value: Int = 0
)

@Serializable
data class LatLngLiteral(
    val lat: Double = 0.0,
    val lng: Double = 0.0
)
