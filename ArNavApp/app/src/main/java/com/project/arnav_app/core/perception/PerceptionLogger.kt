package com.project.arnav_app.core.perception

import android.util.Log

object PerceptionLogger {

    fun frameSkipped() {
        Log.d("Perception", "Frame skipped (sampling/busy)")
    }

    fun inferenceStart() {
        Log.d("Perception", "Inference started")
    }

    fun inferenceEnd(time: Long) {
        Log.d("Perception", "Inference done in ${time}ms")
    }

    fun detection(count: Int) {
        Log.d("Perception", "Detections: $count")
    }

    fun risk(risk: String) {
        Log.d("Perception", "Risk emitted: $risk")
    }

    fun cameraStarted() {
        Log.d("CameraX", "Camera started")
    }

    fun cameraError(e: Exception) {
        Log.e("CameraX", "Camera error: ${e.message}")
    }
}
