package com.project.arnav_app.core.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.util.concurrent.atomic.AtomicBoolean

data class Detection(
    val box: RectF,   // normalized (0–1)
    val label: String,
    val score: Float
)

/**
 * PerceptionEngine: A production-grade object detection pipeline using MobileNet SSD TFLite.
 * 
 * - Frame Sampling: Processes every 3rd frame to conserve CPU.
 * - Non-blocking: Drops frames if the inference thread is busy.
 * - Single-threaded: Inference runs on a dedicated single-threaded dispatcher.
 * - Distance Estimation: Uses bounding box width ratio to estimate proximity.
 * - Risk Flow: Emits HIGH/MEDIUM risk events via SharedFlow with debouncing.
 */
class PerceptionEngine(private val context: Context) : ImageAnalysis.Analyzer {

    private val TAG = "PerceptionEngine"
    private val modelPath = "mobilenet_ssd.tflite"
    private var interpreter: Interpreter? = null

    private val _riskEvents = MutableSharedFlow<ObstacleRisk>()
    val riskEvents: SharedFlow<ObstacleRisk> = _riskEvents.asSharedFlow()

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()

    private val isProcessing = AtomicBoolean(false)
    private var frameCounter = 0
    private var lastRisk = ObstacleRisk.LOW
    private var lastEmissionTime = 0L
    private val riskHistory = mutableListOf<ObstacleRisk>()

    // Single-thread restricted dispatcher for inference
    @OptIn(ExperimentalCoroutinesApi::class)
    private val inferenceDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + inferenceDispatcher)

    init {
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(1)
            }
            val model = FileUtil.loadMappedFile(context, modelPath)
            interpreter = Interpreter(model, options)
            warmup()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model: ${e.message}. Ensure assets/mobilenet_ssd.tflite exists.")
        }
    }

    private fun warmup() {
        scope.launch {
            try {
                val dummyBitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
                processImage(dummyBitmap)
                Log.d(TAG, "Model warmup complete")
            } catch (e: Exception) {
                Log.e(TAG, "Warmup failed: ${e.message}")
            }
        }
    }

    override fun analyze(image: ImageProxy) {
        frameCounter++
        
        // 1. Frame Sampling: every 3rd frame
        // 2. Drop frame if processing
        if (frameCounter % 3 != 0 || isProcessing.get()) {
            if (frameCounter % 3 != 0) {
                PerceptionLogger.frameSkipped()
            }
            image.close()
            return
        }

        isProcessing.set(true)

        scope.launch {
            try {
                PerceptionLogger.inferenceStart()
                val start = System.currentTimeMillis()

                // Convert ImageProxy to Bitmap using helper
                val bitmap = imageToBitmap(image)
                image.close()
                
                if (bitmap != null && interpreter != null) {
                    processImage(bitmap)
                    PerceptionLogger.inferenceEnd(System.currentTimeMillis() - start)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Analysis error: ${e.message}")
            } finally {
                isProcessing.set(false)
            }
        }
    }

    private suspend fun processImage(bitmap: Bitmap) {
        val tensorImage = TensorImage(DataType.UINT8)
        tensorImage.load(bitmap)

        // MobileNet SSD requires 300x300
        val processor = ImageProcessor.Builder()
            .add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR))
            .build()
        
        val inputBuffer = processor.process(tensorImage).buffer

        // SSD Output Tensors:
        // 0: [1, 10, 4] Locations
        // 1: [1, 10] Classes
        // 2: [1, 10] Scores
        // 3: [1] Number of detections
        val locations = Array(1) { Array(10) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(10) }
        val scores = Array(1) { FloatArray(10) }
        val numDetections = FloatArray(1)

        val outputs = mutableMapOf<Int, Any>(
            0 to locations,
            1 to classes,
            2 to scores,
            3 to numDetections
        )

        interpreter?.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        parseResults(locations[0], classes[0], scores[0], numDetections[0].toInt())
    }

    private suspend fun parseResults(locations: Array<FloatArray>, classes: FloatArray, scores: FloatArray, count: Int) {
        PerceptionLogger.detection(count)
        var frameHighestRisk = ObstacleRisk.LOW
        val currentDetections = mutableListOf<Detection>()

        val labels = listOf("person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush")

        for (i in 0 until count) {
            if (scores[i] < 0.5f) continue
            
            val classId = classes[i].toInt()
            // MobileNet SSD COCO classes: 0: person, 2: car, 3: motorcycle, 6: bus, 8: truck
            val isObstacle = classId == 0 || classId == 2 || classId == 3 || classId == 6 || classId == 8
            
            // bbox: [top, left, bottom, right]
            val top = locations[i][0]
            val left = locations[i][1]
            val bottom = locations[i][2]
            val right = locations[i][3]

            val label = if (classId in labels.indices) labels[classId] else "unknown"
            currentDetections.add(Detection(RectF(left, top, right, bottom), label, scores[i]))

            if (!isObstacle) continue

            // ROI: Ignore objects outside the horizontal center 70%
            val centerX = (left + right) / 2f
            if (centerX < 0.15f || centerX > 0.85f) continue

            val width = right - left
            val risk = calculateRisk(width)
            
            if (risk.ordinal > frameHighestRisk.ordinal) {
                frameHighestRisk = risk
            }
        }
        
        _detections.value = currentDetections

        // Temporal Smoothing (last 3 frames)
        riskHistory.add(frameHighestRisk)
        if (riskHistory.size > 3) riskHistory.removeAt(0)
        
        // Final risk is the mode or highest of the last 3? 
        // 2/3 consistency requirement to avoid flickers
        val smoothedRisk = if (riskHistory.count { it == frameHighestRisk } >= 2) {
            frameHighestRisk
        } else {
            lastRisk
        }

        emitRisk(smoothedRisk)
    }

    private fun calculateRisk(widthRatio: Float): ObstacleRisk {
        // ROI Filtering: Only consider objects in the center 70% of the screen
        // Width ratio check for proximity (MobileNet SSD 300x300)
        
        return when {
            widthRatio > 0.45f -> ObstacleRisk.HIGH   // Very close
            widthRatio > 0.15f -> ObstacleRisk.MEDIUM // Moderately close
            else -> ObstacleRisk.LOW
        }
    }

    private suspend fun emitRisk(risk: ObstacleRisk) {
        val now = System.currentTimeMillis()
        // Event filter: state change + 500ms debounce
        if (risk != lastRisk && now - lastEmissionTime > 500) {
            lastRisk = risk
            lastEmissionTime = now
            _riskEvents.emit(risk)
            PerceptionLogger.risk(risk.name)
        }
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap? {
        val bitmap = image.toBitmap() ?: return null
        // Rotate if needed (SSD expects upright image)
        if (image.imageInfo.rotationDegrees == 0) return bitmap
        
        val matrix = Matrix()
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun close() {
        interpreter?.close()
        scope.cancel()
    }
}