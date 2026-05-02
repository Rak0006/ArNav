package com.project.arnav_app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.project.arnav_app.core.navigation.NavSystemState
import com.project.arnav_app.core.navigation.NavigationState
import com.project.arnav_app.core.perception.Detection
import com.project.arnav_app.core.perception.ObstacleRisk
import com.project.arnav_app.ui.map.MapView

@Composable
fun NavigationScreen(
    state: NavigationState,
    obstacleRisk: ObstacleRisk,
    detections: List<Detection> = emptyList(),
    searchQuery: String,
    suggestions: List<AutocompletePrediction>,
    speechText: String,
    partialText: String,
    isListening: Boolean,
    onSearchQueryChanged: (String) -> Unit,
    onSuggestionSelected: (AutocompletePrediction) -> Unit,
    onSpeechResult: (String) -> Unit,
    onStartNavigation: (Double, Double) -> Unit,
    onSearchClicked: () -> Unit,
    onOverlayTap: () -> Unit,
    onClearText: () -> Unit,
    isTestMode: Boolean,
    onPreviewViewReady: (PreviewView) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        // --- BASE LAYOUT: 50 / 40 / 10 ---
        Column(modifier = Modifier.fillMaxSize()) {
            
            // 1. TOP (50%) -> MAP LAYER
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.58f)
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
                        onMapLongClick = { latLng: LatLng ->
                            onStartNavigation(latLng.latitude, latLng.longitude)
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF00C853))
                    }
                }

                // Search Bar & Location Button Overlay
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    SearchSection(
                        query = searchQuery,
                        suggestions = suggestions,
                        onQueryChange = onSearchQueryChanged,
                        onSuggestionClick = onSuggestionSelected,
                        onSearch = onSearchClicked
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    IconButton(
                        onClick = {
                            // Recenter map
                        },
                        modifier = Modifier
                            .align(Alignment.End)
                            .size(44.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = "My Location",
                            tint = Color(0xFF00C853)
                        )
                    }
                }

                // 🎥 Camera PIP (Yellow Area from Image)
                val borderColor = when (obstacleRisk) {
                    ObstacleRisk.HIGH -> Color(0xFFFF1744) // Red
                    ObstacleRisk.MEDIUM -> Color(0xFFFFAB00) // Amber
                    else -> Color(0xFF00C853) // Green (LOW and others)
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart) // ✅ anchor to bottom-left
                        .padding(start = 16.dp, bottom = 16.dp)
                        .size(120.dp, 160.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .border(2.dp, borderColor, RoundedCornerShape(16.dp))
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { context ->
                                PreviewView(context).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    onPreviewViewReady(this)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        Canvas(modifier = Modifier.matchParentSize()) {
                            val w = size.width
                            val h = size.height

                            detections.forEach { det ->
                                val left = det.box.left * w
                                val top = det.box.top * h
                                val right = det.box.right * w
                                val bottom = det.box.bottom * h

                                drawRect(
                                    color = Color.Red,
                                    topLeft = Offset(left, top),
                                    size = Size(right - left, bottom - top),
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            }
                        }
                    }

                    if (obstacleRisk != ObstacleRisk.LOW) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(borderColor.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Surface(
                                color = borderColor,
                                shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = if (obstacleRisk == ObstacleRisk.HIGH) "HIGH RISK" else "WARNING",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. MIDDLE (40%) -> TOUCH OVERLAY (PRIMARY INPUT)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.30f)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xFF0A1A0A))
                        )
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = rememberRipple(bounded = true, color = Color(0xFF00C853)),
                        onClick = onOverlayTap
                    ),
                contentAlignment = Alignment.Center
            ) {
                OverlayContent(state, partialText, isTestMode)
            }

            // 3. BOTTOM (10%) -> CONTROL PANEL
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.12f)
                    .background(Color(0xFF0A1A0A))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                ControlPanel(state, onStartNavigation)
            }
        }
    }
}

@Composable
fun OverlayContent(state: NavigationState, partialText: String, isTestMode: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (state.systemState) {
            NavSystemState.LISTENING -> {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer(scaleX = glowScale, scaleY = glowScale)
                        .background(Color(0xFF00C853).copy(alpha = 0.2f), RoundedCornerShape(40.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = Color(0xFF00C853)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (isTestMode) {
                    Surface(
                        color = Color(0xFF00C853),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = "STT TEST MODE",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = partialText.ifEmpty { "Listening..." },
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
            NavSystemState.PROCESSING -> {
                CircularProgressIndicator(color = Color(0xFF00C853))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Processing...", color = Color.Gray)
            }
            NavSystemState.CONFIRMING -> {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2D1B)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Route Summary", fontWeight = FontWeight.Bold, color = Color(0xFF00C853))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (state.errorMessage != null) state.errorMessage!! else "Confirming your destination...",
                            textAlign = TextAlign.Center,
                            color = Color.White
                        )
                    }
                }
            }
            NavSystemState.NAVIGATING -> {
                // Main Navigation Instruction Card (Green Area)
                Card(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1A0A)),
                    shape = RoundedCornerShape(20.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(Color(0xFF00C853), Color(0xFF004D40))))
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = state.currentInstruction.ifEmpty { "Follow the path" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF00C853),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Next step in ${state.distanceToNextStep.toInt()}m",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            else -> {
                if (isTestMode) {
                    Surface(
                        color = Color(0xFF00C853).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = "STT TEST MODE",
                            color = Color(0xFF00C853),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = if (isTestMode) "Tap to type (Test Mode)" else "Tap to speak",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun ControlPanel(state: NavigationState, onStartNavigation: (Double, Double) -> Unit) {
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Info Section
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallInfoTile("Dist", state.totalDistance)
                SmallInfoTile("ETA", state.eta)
                SmallInfoTile("Rem", state.distanceRemainingFormatted)
            }
        }

        // Action Button
        Button(
            onClick = { 
                if (state.isNavigating) onStartNavigation(0.0, 0.0)
                else state.destination?.let { onStartNavigation(it.latitude, it.longitude) }
            },
            modifier = Modifier.height(48.dp).width(100.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isNavigating) Color(0xFFB00020) else Color(0xFF00C853),
                contentColor= Color.White,
            ),
            shape = RoundedCornerShape(12.dp),
            enabled = state.destination != null || state.isNavigating
        ) {
            Text(
                text = if (state.isNavigating) "STOP" else "START",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
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

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A2A2A), RoundedCornerShape(12.dp)),
            placeholder = { Text("Search destination", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        onSearch()
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Search", tint = Color.White)
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00C853),
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
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
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp)) {
                    items(suggestions) { prediction ->
                        ListItem(
                            headlineContent = { Text(prediction.getPrimaryText(null).toString(), color = Color.White) },
                            supportingContent = { Text(prediction.getSecondaryText(null).toString(), color = Color.Gray) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
fun SmallInfoTile(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
