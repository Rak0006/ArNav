package com.project.arnav_app.ui.navigation.interaction

class VoiceController(
    private val onSpeak: (String) -> Unit,
    private val haptics: HapticManager,
    private val onSpeechResult: (String) -> Unit
) {
    fun onTrigger() {
        haptics.onListeningStart()
        onSpeak("Listening")
        // Future: Start recording/processing here
    }
}
