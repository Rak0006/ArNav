package com.project.arnav_app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.Locale
import android.app.AlertDialog
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.arnav_app.ui.navigation.NavigationScreen
import com.project.arnav_app.ui.navigation.NavigationViewModel
import com.project.arnav_app.ui.navigation.NavigationViewModelFactory
import com.project.arnav_app.ui.theme.MarigoTheme
import com.project.arnav_app.core.navigation.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import okhttp3.MediaType.Companion.toMediaType
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private lateinit var speechRecognizer: SpeechRecognizer
    private var sttRetryCount = 0
    private val MAX_STT_RETRIES = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        tts = TextToSpeech(this, this)
        
        val apiKey = "AIzaSyA-H9sCG0f14XtInSdBvnYjcJcY56-RTGY"

        if (!com.google.android.libraries.places.api.Places.isInitialized()) {
            com.google.android.libraries.places.api.Places.initialize(applicationContext, apiKey)
        }
        
        val placesRepository = PlacesRepository(applicationContext)
        val geminiRepository = GeminiRepository("AIzaSyCwplM9R4UcvF6WQZ_4C6SdGSnLTqhrDvE")
        val directionsRepository = DirectionsRepository(
            Retrofit.Builder()
                .baseUrl("https://maps.googleapis.com/")
                .client(OkHttpClient.Builder().addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }).build())
                .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(DirectionsApiService::class.java),
            apiKey
        )

        val factory = NavigationViewModelFactory(
            locationProvider = com.project.arnav_app.core.location.DefaultLocationProvider(applicationContext),
            destinationProvider = InMemoryDestinationProvider(),
            directionsRepository = directionsRepository,
            navigationEngine = RealTimeNavigationEngine(),
            placesRepository = placesRepository,
            geminiRepository = geminiRepository,
            hapticFeedbackManager = com.project.arnav_app.core.haptics.HapticFeedbackManager(applicationContext),
            onSpeak = { text, shouldListen ->
                val params = Bundle()
                val utteranceId = if (shouldListen) "VOICE_LOOP" else "NORMAL"
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            }
        )

        setContent {
            MarigoTheme {
                val viewModel: NavigationViewModel = viewModel(factory = factory)
                val uiState by viewModel.uiState.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()
                val suggestions by viewModel.suggestions.collectAsState()
                val speechText by viewModel.speechText.collectAsState()
                val partialText by viewModel.partialText.collectAsState()
                val isListening by viewModel.isListening.collectAsState()

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val granted = permissions.values.all { it }
                    if (!granted) {
                        Toast.makeText(this@MainActivity, "Permissions needed", Toast.LENGTH_LONG).show()
                    }
                }

                LaunchedEffect(Unit) {
                    val permissions = arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                    if (permissions.any { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }) {
                        permissionLauncher.launch(permissions)
                    }
                    initializeSpeechRecognizer(viewModel)
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavigationScreen(
                        state = uiState,
                        searchQuery = searchQuery,
                        suggestions = suggestions,
                        speechText = speechText,
                        partialText = partialText,
                        isListening = isListening,
                        onSearchQueryChanged = { viewModel.onSearchQueryChanged(it) },
                        onSuggestionSelected = { viewModel.onSuggestionSelected(it) },
                        onSpeechResult = { viewModel.onSpeechResult(it) },
                        onStartNavigation = { lat, lng -> viewModel.updateDestination(lat, lng) },
                        onSearchClicked = { viewModel.performSearch() },
                        onOverlayTap = { 
                            if (viewModel.isTestMode) {
                                showTestInputDialog(viewModel)
                            } else {
                                viewModel.onOverlayTap()
                                startListening() 
                            }
                        },
                        onClearText = { viewModel.clearText() },
                        isTestMode = viewModel.isTestMode,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun initializeSpeechRecognizer(viewModel: NavigationViewModel) {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                viewModel.setListening(true)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                viewModel.setListening(false)
            }
            override fun onError(error: Int) {
                viewModel.setListening(false)
                Log.e("STT", "Error code: $error")
                
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    if (sttRetryCount < MAX_STT_RETRIES) {
                        val delayMs = when (sttRetryCount) {
                            0 -> 300L
                            1 -> 800L
                            else -> 1500L
                        }
                        sttRetryCount++
                        lifecycleScope.launch {
                            delay(delayMs)
                            startListening()
                        }
                    } else {
                        sttRetryCount = 0
                        val errorMessage = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "I didn't hear anything. Try again."
                            else -> "Speech timed out. Try again."
                        }
                        tts?.speak(errorMessage, TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                sttRetryCount = 0
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    viewModel.onSpeechResult(matches[0])
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    viewModel.onPartialSpeechResult(matches[0])
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer.startListening(intent)
    }

    private fun showTestInputDialog(viewModel: NavigationViewModel) {
        val editText = EditText(this).apply {
            hint = "Enter test command (e.g. Go to Indiranagar)"
            setPadding(48, 32, 48, 32)
        }
        val container = FrameLayout(this)
        container.addView(editText)

        AlertDialog.Builder(this)
            .setTitle("STT Test Mode")
            .setMessage("Gemini is functional. Type your command:")
            .setView(container)
            .setPositiveButton("Send") { _, _ ->
                val text = editText.text.toString()
                if (text.isNotEmpty()) {
                    viewModel.onSpeechResult(text)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "VOICE_LOOP") {
                        Handler(Looper.getMainLooper()).postDelayed({
                            startListening()
                        }, 500)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        super.onDestroy()
    }
}
