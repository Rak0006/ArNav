package com.project.arnav_app.core.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

enum class HapticPriority(val value: Int) {
    LOW(0),      // UI Touch
    MEDIUM(1),   // Voice Events
    HIGH(2)      // Navigation Alerts
}

class HapticFeedbackManager(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var lastVibrationTime = 0L
    private var lastPriority = HapticPriority.LOW
    private val MIN_GAP_MS = 300L

    private fun vibrate(effect: VibrationEffect, priority: HapticPriority) {
        val now = System.currentTimeMillis()
        
        // Priority and overlapping check
        if (now - lastVibrationTime < MIN_GAP_MS && priority.value <= lastPriority.value) {
            return
        }

        vibrator.cancel()
        vibrator.vibrate(effect)
        lastVibrationTime = now
        lastPriority = priority
    }

    // --- 1. NAVIGATION HAPTICS ---

    fun playTurnPreparation() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 80), intArrayOf(0, 120), -1)
        vibrate(effect, HapticPriority.HIGH)
    }

    fun playTurnNow() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 180), intArrayOf(0, 200, 0, 200), -1)
        vibrate(effect, HapticPriority.HIGH)
    }

    fun playOffRoute() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 100, 150, 100), intArrayOf(0, 255, 0, 255), -1)
        vibrate(effect, HapticPriority.HIGH)
    }

    fun playRouteRecalculation() {
        val effect = VibrationEffect.createOneShot(150, 180)
        vibrate(effect, HapticPriority.HIGH)
    }

    fun playArrival() {
        val effect = VibrationEffect.createOneShot(300, 255)
        vibrate(effect, HapticPriority.HIGH)
    }

    // --- 2. VOICE INTERACTION HAPTICS ---

    fun playListeningStart() {
        val effect = VibrationEffect.createOneShot(50, 100)
        vibrate(effect, HapticPriority.MEDIUM)
    }

    fun playListeningEnd() {
        val effect = VibrationEffect.createOneShot(70, 120)
        vibrate(effect, HapticPriority.MEDIUM)
    }

    fun playProcessingAcknowledgement() {
        val effect = VibrationEffect.createOneShot(60, 90)
        vibrate(effect, HapticPriority.MEDIUM)
    }

    fun playVoiceError() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 80), intArrayOf(0, 200, 0, 200), -1)
        vibrate(effect, HapticPriority.MEDIUM)
    }

    fun playConfirmationPrompt() {
        val effect = VibrationEffect.createOneShot(100, 140)
        vibrate(effect, HapticPriority.MEDIUM)
    }

    fun playConfirmationAccepted() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 100), intArrayOf(0, 180, 0, 180), -1)
        vibrate(effect, HapticPriority.MEDIUM)
    }

    fun playConfirmationRejected() {
        val effect = VibrationEffect.createOneShot(150, 160)
        vibrate(effect, HapticPriority.MEDIUM)
    }

    // --- 3. TOUCH / UI HAPTICS ---

    fun playOverlayTap() {
        val effect = VibrationEffect.createOneShot(40, 90)
        vibrate(effect, HapticPriority.LOW)
    }

    fun playButtonPress() {
        val effect = VibrationEffect.createOneShot(60, 120)
        vibrate(effect, HapticPriority.LOW)
    }

    fun playNavigationStart() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 120), intArrayOf(0, 180, 0, 180), -1)
        vibrate(effect, HapticPriority.LOW)
    }

    fun playNavigationStop() {
        val effect = VibrationEffect.createOneShot(150, 160)
        vibrate(effect, HapticPriority.LOW)
    }

    // --- Perception / Obstacle ---

    fun playObstacleMedium() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1)
        vibrate(effect, HapticPriority.MEDIUM)
    }

    fun playObstacleHigh() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 200, 50, 200, 50, 200), -1)
        vibrate(effect, HapticPriority.HIGH)
    }
}
