package com.project.arnav_app.ui.navigation

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.project.arnav_app.core.navigation.VoiceState
import com.project.arnav_app.ui.map.MapView
import com.project.arnav_app.ui.navigation.interaction.VoiceTriggerOverlay

@Composable
fun NavigationScreen(
    state: NavigationState,
    voiceState: VoiceState,
    partialText: String, // Add this
    searchQuery: String,
    suggestions: List<AutocompletePrediction>,
    onSearchQueryChanged: (String) -> Unit,
    onSuggestionSelected: (AutocompletePrediction) -> Unit,
    onOverlayTap: () -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSetDestination: (Double, Double) -> Unit,
    onSearchClicked: () -> Unit,
    isMapTracking: Boolean,
    onReCenterClick: () -> Unit,
    onTestClick: () -> Unit = {}, 
    onTestConfirmClick: () -> Unit = {}, // New callback
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top 55%: Map
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f)
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
                            onSetDestination(latLng.latitude, latLng.longitude)
                        }
                    )

                    if (!isMapTracking) {
                        FloatingActionButton(
                            onClick = onReCenterClick,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = "Re-center")
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.DarkGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Waiting for GPS...", color = Color.White)
                    }
                }
            }

            // Middle 35%: Voice Zone & Details
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.35f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (state.isNavigating && state.route != null) {
                        NavigationInfoCard(state)
                    } else if (state.isArrived) {
                        ArrivalCard(onStopClick)
                    } else {
                        DefaultStatusSection(state)
                    }
                }

                // Voice Zone Overlay (Touch sensitive)
                VoiceTriggerOverlay(
                    onTrigger = onOverlayTap,
                    modifier = Modifier.fillMaxSize()
                )

                if (voiceState != VoiceState.IDLE && voiceState != VoiceState.NAVIGATING) {
                    val infiniteTransition = rememberInfiniteTransition(label = "voiceGlow")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 0.7f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "glowAlpha"
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color.Cyan.copy(alpha = alpha),
                                        Color.Transparent
                                    )
                                )
                            )
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (voiceState == VoiceState.PROCESSING) {
                                CircularProgressIndicator(
                                    color = Color.Cyan,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                            
                            Text(
                                text = when(voiceState) {
                                    VoiceState.LISTENING -> "Listening..."
                                    VoiceState.PROCESSING -> "Thinking..."
                                    VoiceState.DEST_CONFIRM -> "Confirm Destination?"
                                    VoiceState.ROUTE_CONFIRM -> "Start Route?"
                                    else -> ""
                                },
                                color = Color.White,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = partialText,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                        }
                    }
                }
            }

            // Bottom 10%: Buttons
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.1f)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!state.isNavigating) {
                    Button(
                        onClick = onStartClick,
                        modifier = Modifier.fillMaxSize(),
                        enabled = state.route != null,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("START NAVIGATION", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onStopClick,
                        modifier = Modifier.fillMaxSize(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("STOP NAVIGATION", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Floating Search Section
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

        // Debug Buttons
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 8.dp, top = 70.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = onTestClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Magenta.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(4.dp)
            ) {
                Text("NAV TEST", fontSize = 10.sp)
            }
            Button(
                onClick = onTestConfirmClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(4.dp)
            ) {
                Text("YES TEST", fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun NavigationInfoCard(state: NavigationState) {
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
}

@Composable
fun ArrivalCard(onStopClick: () -> Unit) {
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
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onStopClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("FINISH")
            }
        }
    }
}

@Composable
fun DefaultStatusSection(state: NavigationState) {
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
            text = if (state.destination != null && state.route != null)
                "Route found! Tap START or say 'yes'." 
            else if (state.destination != null)
                "Calculating route..."
            else 
                "Tap middle to search with voice",
            textAlign = TextAlign.Center,
            color = Color.Gray
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
