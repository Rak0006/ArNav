package com.project.arnav_app.ui.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

@Composable
fun MapView(
    userLocation: LatLng,
    destination: LatLng?,
    routePoints: List<LatLng> = emptyList(),
    onMapLongClick: (LatLng) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Check permission safely to avoid crash
    val hasLocationPermission = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(userLocation, 15f)
    }

    LaunchedEffect(userLocation) {
        if (userLocation.latitude != 0.0) {
            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLng(userLocation),
                durationMs = 1000
            )
        }
    }

    val uiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = true,
            myLocationButtonEnabled = hasLocationPermission
        )
    }
    
    val properties = remember(hasLocationPermission) {
        MapProperties(
            isMyLocationEnabled = hasLocationPermission,
            isTrafficEnabled = false
        )
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        uiSettings = uiSettings,
        properties = properties,
        onMapLongClick = onMapLongClick
    ) {
        destination?.let {
            Marker(
                state = MarkerState(position = it),
                title = "Destination"
            )
        }

        if (routePoints.isNotEmpty()) {
            Polyline(
                points = routePoints,
                color = Color(0xFF2196F3),
                width = 12f
            )
        }
    }
}
