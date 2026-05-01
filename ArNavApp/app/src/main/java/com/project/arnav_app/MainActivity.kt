package com.project.arnav_app

import android.Manifest
import kotlinx.coroutines.delay
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.libraries.places.api.Places
import com.project.arnav_app.core.location.DefaultLocationProvider
import com.project.arnav_app.core.navigation.*
import com.project.arnav_app.ui.navigation.NavigationScreen
import com.project.arnav_app.ui.navigation.NavigationViewModel
import com.project.arnav_app.ui.navigation.NavigationViewModelFactory
import com.project.arnav_app.ui.theme.ArNavAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var navViewModel: NavigationViewModel? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val geminiApiKey = "AIzaSyBUyQPz1QBgpY1Zb7oy06qIIhqTeCS_KtU"
    private val googleMapsApiKey = "AIzaSyA-H9sCG0f14XtInSdBvnYjcJcY56-RTGY"

    private val placesRepository by lazy { PlacesRepository(applicationContext) }
    private val geminiRepository by lazy { GeminiRepository(geminiApiKey) }

    private val directionsRepository by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
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
        DirectionsRepository(apiService, googleMapsApiKey)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, googleMapsApiKey)
        }

        val locationProvider = DefaultLocationProvider(applicationContext)
        val destinationProvider = InMemoryDestinationProvider()
        val navigationEngine = RealTimeNavigationEngine()

        val factory = NavigationViewModelFactory(
            locationProvider = locationProvider,
            destinationProvider = destinationProvider,
            directionsRepository = directionsRepository,
            navigationEngine = navigationEngine,
            placesRepository = placesRepository,
            geminiRepository = geminiRepository,
            onSpeak = { text, id ->
                speak(text, id)
            }
        )

        setContent {
            ArNavAppTheme {
                val vm: NavigationViewModel = viewModel(factory = factory)
                navViewModel = vm
                val uiState by vm.uiState.collectAsState()
                val voiceState by vm.voiceState.collectAsState()
                val partialText by vm.partialText.collectAsState()
                val searchQuery by vm.searchQuery.collectAsState()
                val suggestions by vm.suggestions.collectAsState()
                val isMapTracking by vm.isMapTracking.collectAsState()

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    if (!permissions.values.all { it }) {
                        Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
                    }
                }

                LaunchedEffect(Unit) {
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.RECORD_AUDIO
                    ))
                    vm.onStartSTT = { startListening() }
                    
                    val prefs = getSharedPreferences("test_prefs", MODE_PRIVATE)
                    
                    // Add a way to reset the test via Logcat or a specific trigger if needed
                    // For now, let's allow re-running if we detect a specific flag or just every 5 minutes
                    val lastRun = prefs.getLong("last_test_time", 0)
                    val isRunning = prefs.getBoolean("test_run", false)
                    val currentTime = System.currentTimeMillis()
                    
                    if (!isRunning || (currentTime - lastRun > 300000)) { // 5 minutes reset
                        // Mark it as run IMMEDIATELY to prevent loops on crash/restart
                        prefs.edit()
                            .putBoolean("test_run", true)
                            .putLong("last_test_time", currentTime)
                            .apply()
                        
                        // Start the test flow
                        Log.d("ArNavTest", "Starting automated test sequence...")
                        delay(12000) // Increased delay for stability
                        Log.d("ArNavTest", "Simulating 'I want to go to Jayanagar'")
                        vm.onSpeechResult("I want to go to Jayanagar")
                        
                        // Wait for the search and TTS prompt to finish, then simulate "Yes"
                        var retryCount = 0
                        while (vm.voiceState.value != VoiceState.LISTENING && retryCount < 60) {
                            delay(1000)
                            retryCount++
                            if (retryCount % 5 == 0) {
                                Log.d("ArNavTest", "Waiting for VoiceState.LISTENING, current: ${vm.voiceState.value}, retry: $retryCount")
                            }
                        }

                        if (vm.voiceState.value == VoiceState.LISTENING) {
                            delay(1000) // Small extra buffer
                            Log.d("ArNavTest", "Simulating 'Yes' confirmation")
                            vm.onSpeechResult("Yes")
                        } else {
                            Log.e("ArNavTest", "Timed out waiting for LISTENING state. Current: ${vm.voiceState.value}")
                            // Reset test_run so it can try again next restart if it timed out?
                            // No, better to keep it true to avoid loops, but log the failure.
                        }
                    } else {
                        Log.d("ArNavTest", "Test already run recently (${(currentTime - lastRun)/1000}s ago), skipping simulation.")
                    }
                }


                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavigationScreen(
                        state = uiState,
                        voiceState = voiceState,
                        partialText = partialText,
                        searchQuery = searchQuery,
                        suggestions = suggestions,
                        onSearchQueryChanged = { vm.onSearchQueryChanged(it) },
                        onSuggestionSelected = { vm.onSuggestionSelected(it) },
                        onOverlayTap = { vm.onOverlayTap() },
                        onStartClick = { vm.startNavigation() },
                        onStopClick = { vm.stopNavigation() },
                        onSetDestination = { lat, lng -> vm.updateDestination(lat, lng) },
                        onSearchClicked = { vm.performSearch() },
                        isMapTracking = isMapTracking,
                        onReCenterClick = { vm.setMapTracking(true) },
                        onTestClick = {
                            Log.d("ArNavTest", "Manual debug test triggered: 'I want to go to Jayanagar'")
                            vm.onSpeechResult("I want to go to Jayanagar")
                        },
                        onTestConfirmClick = {
                            Log.d("ArNavTest", "Manual debug test triggered: 'Yes'")
                            vm.onSpeechResult("Yes")
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        setupSpeechListener()
    }

    private fun speak(text: String, id: String?) {
        try {
            val expanded = text.replace("Rd", "Road").replace("St", "Street")
            val result = tts?.speak(expanded, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result == TextToSpeech.ERROR) {
                Log.e("TTS", "Speak failed, attempting re-init")
                reinitTts()
                if (id != null) navViewModel?.onTtsDone(id)
            }
        } catch (e: Exception) {
            Log.e("TTS", "Speak exception: ${e.message}")
            reinitTts()
            if (id != null) navViewModel?.onTtsDone(id)
        }
    }

    private fun reinitTts() {
        try {
            tts?.shutdown()
        } catch (e: Exception) {}
        tts = TextToSpeech(this, this)
    }

    private fun startListening() {

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e("STT", "Speech recognition not available")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("STT", "Permission not granted")
            return
        }

        runOnUiThread {
            try {
                // Only recreate if absolutely necessary, but ensure it's not busy
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
                    setupSpeechListener()
                }

                abandonAudioFocus()
                requestAudioFocus()
                
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN") 
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                }

                speechRecognizer?.startListening(intent)
                Log.d("STT", "startListening called")
            } catch (e: Exception) {
                Log.e("STT", "Failed to prepare listening: ${e.message}")
                abandonAudioFocus()
                navViewModel?.onSpeechError()
            }
        }
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                
                val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                    Log.d("ArNav", "Audio focus changed: $focusChange")
                }

                // If setAcceptsDelayedFocusGain(true) is used, we should provide a Handler
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
                    .build()
                
                audioFocusRequest?.let { 
                    val result = audioManager.requestAudioFocus(it)
                    Log.d("ArNav", "Audio focus request result: $result")
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            }
        } catch (e: Exception) {
            Log.e("ArNav", "Error requesting audio focus: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun reinitSpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
            setupSpeechListener()
        } catch (e: Exception) {
            Log.e("STT", "Critical STT failure", e)
        }
    }

    private fun setupSpeechListener() {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("STT", "Ready for speech")
            }
            override fun onBeginningOfSpeech() {
                Log.d("STT", "Speech beginning")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d("STT", "Speech end")
            }
            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissions error"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    else -> "STT Error: $error"
                }
                Log.e("STT", "Error: $message ($error)")
                abandonAudioFocus()
                
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == 11) {
                    reinitSpeechRecognizer()
                }
                
                navViewModel?.onSpeechError()
            }
            override fun onResults(results: Bundle?) {
                abandonAudioFocus()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d("STT", "onResults matches: $matches")
                matches?.firstOrNull()?.let {
                        Log.d("STT", "Selected Result: $it")
                        navViewModel?.onSpeechResult(it) 
                    } ?: run {
                        Log.e("STT", "No text found in results")
                        navViewModel?.onSpeechError()
                    }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { 
                        Log.d("STT", "Partial: $it")
                        navViewModel?.onPartialSpeechResult(it) 
                    }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                Handler(Looper.getMainLooper()).postDelayed({
                    utteranceId?.let { navViewModel?.onTtsDone(it) }
                }, 500)
            }
            override fun onError(utteranceId: String?) {}
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                val result = tts?.setLanguage(Locale("en", "IN"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Language not supported")
                }
            } catch (e: Exception) {
                Log.e("TTS", "Error setting language", e)
            }
        } else {
            Log.e("TTS", "Init failed")
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}
