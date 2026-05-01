package com.project.arnav_app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.project.arnav_app.core.navigation.NavigationState
import com.project.arnav_app.ui.map.MapView

@Composable
fun NavigationScreen(
    state: NavigationState,
    searchQuery: String,
    suggestions: List<AutocompletePrediction>,
    onSearchQueryChanged: (String) -> Unit,
    onSuggestionSelected: (AutocompletePrediction) -> Unit,
    onSpeechResult: (String) -> Unit,
    onStartNavigation: (Double, Double) -> Unit,
    onSearchClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Bottom Layer: Map and Instructions
        Column(modifier = Modifier.fillMaxSize()) {
            // Map Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
            ) {
                val location = state.currentLocation
                if (location != null && location.latitude != 0.0) {
                    val destinationLatLng = remember(state.route) {
                        state.route?.steps?.lastOrNull()?.end?.let { LatLng(it.latitude, it.longitude) }
                    }
                    val routeLatLngPoints = remember(state.route, state.closestPolylineIndex) {
                        state.routePoints.map { LatLng(it.latitude, it.longitude) }
                    }

                    MapView(
                        userLocation = LatLng(location.latitude, location.longitude),
                        userBearing = location.bearing,
                        isNavigating = state.isNavigating,
                        destination = destinationLatLng,
                        routePoints = routeLatLngPoints,
                        onMapLongClick = { latLng ->
                            onStartNavigation(latLng.latitude, latLng.longitude)
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.DarkGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Waiting for GPS...", color = Color.White)
                    }
                }
            }

            // Instructions / Status Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (state.isNavigating && state.routePoints.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20), contentColor = Color.White),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = state.currentInstruction.ifEmpty { "Calculating..." },
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Next step in ${state.distanceToNextStep.toInt()}m",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.Yellow
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        InfoTile(label = "Distance", value = state.totalDistance)
                        InfoTile(label = "ETA", value = state.eta)
                        InfoTile(label = "Remaining", value = state.distanceRemainingFormatted)
                    }
                } else if (state.isArrived) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1976D2), contentColor = Color.White),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "You have arrived!",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Your destination is nearby.",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { onStartNavigation(0.0, 0.0) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("FINISH")
                            }
                        }
                    }
                } else {
                    Text(
                        text = "ArNav Professional Navigation",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))

                    if (state.errorMessage != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = state.errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(12.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Text(
                            text = if (state.destination != null && state.routePoints.isNotEmpty()) 
                                "Route found! Tap START to begin." 
                            else if (state.destination != null)
                                "Calculating route..."
                            else 
                                "Enter a destination to start your journey",
                            textAlign = TextAlign.Center,
                            color = Color.Gray
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                if (!state.isNavigating) {
                    Button(
                        onClick = { 
                            state.destination?.let { 
                                onStartNavigation(it.latitude, it.longitude) 
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = state.currentLocation != null && state.destination != null && state.routePoints.isNotEmpty()
                    ) {
                        Text(
                            text = "START NAVIGATION",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = { 
                            onStartNavigation(0.0, 0.0) // Clear destination to stop
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "STOP NAVIGATION",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Top Layer: Floating Search Section
        SearchSection(
            query = searchQuery,
            suggestions = suggestions,
            onQueryChange = onSearchQueryChanged,
            onSuggestionClick = onSuggestionSelected,
            onSearch = onSearchClicked,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        )
    }
}

@Composable
fun SearchSection(
    query: String,
    suggestions: List<AutocompletePrediction>,
    onQueryChange: (String) -> Unit,
    onSuggestionClick: (AutocompletePrediction) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(12.dp)),
            placeholder = { Text("Search destination") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        onSearch()
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                onSearch()
                keyboardController?.hide()
                focusManager.clearFocus()
            })
        )

        if (suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                elevation = CardDefaults.cardElevation(8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                ) {
                    items(suggestions) { prediction ->
                        ListItem(
                            headlineContent = { Text(prediction.getPrimaryText(null).toString()) },
                            supportingContent = { Text(prediction.getSecondaryText(null).toString()) },
                            modifier = Modifier.clickable { 
                                onSuggestionClick(prediction)
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InfoTile(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

fun Double.format(digits: Int) = "%.${digits}f".format(this)
