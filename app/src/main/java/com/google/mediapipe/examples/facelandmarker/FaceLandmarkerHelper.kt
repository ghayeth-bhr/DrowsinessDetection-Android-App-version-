package com.google.mediapipe.examples.facelandmarker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.pow

class FaceLandmarkerHelper(
    var minFaceDetectionConfidence: Float = DEFAULT_FACE_DETECTION_CONFIDENCE,
    var minFaceTrackingConfidence: Float = DEFAULT_FACE_TRACKING_CONFIDENCE,
    var minFacePresenceConfidence: Float = DEFAULT_FACE_PRESENCE_CONFIDENCE,
    var maxNumFaces: Int = DEFAULT_NUM_FACES,
    var currentDelegate: Int = DELEGATE_CPU,
    var earThreshold: Float = DEFAULT_EAR_THRESHOLD,
    var marThreshold: Float = DEFAULT_MAR_THRESHOLD,
    val context: Context,
    val faceLandmarkerHelperListener: LandmarkerListener? = null
) {
    private var faceLandmarker: FaceLandmarker? = null

    // State tracking variables
    private var earConsecutiveFrames = 0
    private var marConsecutiveFrames = 0
    private var isBlinking = false
    private var isYawning = false
    private var microsleepStartTime: Long = 0
    private var yawnStartTime: Long = 0
    private var microsleepDuration: Float = 0f
    private var yawnDuration: Float = 0f
    private var blinkCount = 0
    private var yawnCount = 0
    private var leftEyeStillClosed = false
    private var rightEyeStillClosed = false
    private var yawnInProgress = false

    init {
        setupFaceLandmarker()
    }

    fun clearFaceLandmarker() {
        faceLandmarker?.close()
        faceLandmarker = null
    }

    fun setupFaceLandmarker() {
        val baseOptions = BaseOptions.builder().apply {
            when (currentDelegate) {
                DELEGATE_CPU -> setDelegate(Delegate.CPU)
                DELEGATE_GPU -> setDelegate(Delegate.GPU)
                else -> setDelegate(Delegate.CPU)
            }
            setModelAssetPath(MP_FACE_LANDMARKER_TASK)
        }.build()

        try {
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinFaceDetectionConfidence(minFaceDetectionConfidence)
                .setMinTrackingConfidence(minFaceTrackingConfidence)
                .setMinFacePresenceConfidence(minFacePresenceConfidence)
                .setNumFaces(maxNumFaces)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::processLandmarkerResult)
                .setErrorListener(this::returnLivestreamError)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            faceLandmarkerHelperListener?.onError("Face Landmarker initialization failed: ${e.message}")
            Log.e(TAG, "MediaPipe failed to load the task with error: ${e.message}")
        }
    }

    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val bitmapBuffer = Bitmap.createBitmap(
            imageProxy.width,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        ).apply {
            copyPixelsFromBuffer(imageProxy.planes[0].buffer)
        }

        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            if (isFrontCamera) {
                postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
            }
        }

        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
            matrix, true
        )

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        faceLandmarker?.detectAsync(mpImage, SystemClock.uptimeMillis())
        imageProxy.close()
    }

    private fun processLandmarkerResult(result: FaceLandmarkerResult, input: MPImage) {
        if (result.faceLandmarks().isEmpty()) {
            faceLandmarkerHelperListener?.onEmpty()
            resetState()
            return
        }

        val landmarks = result.faceLandmarks()[0]
        val leftEAR = calculateEyeAspectRatio(landmarks, LEFT_EAR_POINTS)
        val rightEAR = calculateEyeAspectRatio(landmarks, RIGHT_EAR_POINTS)
        val ear = (leftEAR + rightEAR) / 2f
        val mar = calculateMAR(landmarks)

        detectBlinks(leftEAR, rightEAR, ear)
        detectYawns(mar)

        faceLandmarkerHelperListener?.onResults(
            earValue = ear,
            marValue = mar,
            isBlinking = isBlinking,
            isYawning = isYawning,
            landmarks = landmarks,
            blinkCount = blinkCount,
            yawnCount = yawnCount,
            microsleepDuration = microsleepDuration,
            yawnDuration = yawnDuration
        )
    }

    private fun detectBlinks(leftEAR: Float, rightEAR: Float, currentEAR: Float) {
        val currentLeftEyeClosed = leftEAR < earThreshold
        val currentRightEyeClosed = rightEAR < earThreshold

        if (currentLeftEyeClosed && currentRightEyeClosed) {
            if (!(leftEyeStillClosed && rightEyeStillClosed)) {
                // Start of eye closure
                leftEyeStillClosed = true
                rightEyeStillClosed = true
                microsleepStartTime = System.currentTimeMillis()
                earConsecutiveFrames = 1
            } else {
                // Eyes remain closed
                earConsecutiveFrames++
                microsleepDuration = (System.currentTimeMillis() - microsleepStartTime) / 1000f
            }
        } else {
            // At least one eye is open
            if (leftEyeStillClosed || rightEyeStillClosed) {
                // Eyes just opened
                if (earConsecutiveFrames > 0) {
                    blinkCount++
                    faceLandmarkerHelperListener?.onBlinkDetected()
                }
            }

            // Reset state
            leftEyeStillClosed = false
            rightEyeStillClosed = false
            microsleepStartTime = 0
            microsleepDuration = 0f
            earConsecutiveFrames = 0
        }

        isBlinking = leftEyeStillClosed && rightEyeStillClosed
    }

    private fun detectYawns(currentMAR: Float) {
        if (currentMAR > marThreshold) {
            marConsecutiveFrames++
            if (marConsecutiveFrames >= MAR_CONSECUTIVE_FRAMES && !yawnInProgress) {
                yawnInProgress = true
                yawnStartTime = System.currentTimeMillis()
                yawnCount++
                faceLandmarkerHelperListener?.onYawnDetected()
            }

            if (yawnInProgress) {
                yawnDuration = (System.currentTimeMillis() - yawnStartTime) / 1000f
            }
        } else {
            if (yawnInProgress) {
                faceLandmarkerHelperListener?.onYawnComplete()
            }
            yawnInProgress = false
            yawnStartTime = 0
            yawnDuration = 0f
            marConsecutiveFrames = 0
        }

        isYawning = yawnInProgress
    }

    private fun resetState() {
        leftEyeStillClosed = false
        rightEyeStillClosed = false
        microsleepStartTime = 0
        microsleepDuration = 0f
        earConsecutiveFrames = 0
        yawnInProgress = false
        yawnStartTime = 0
        yawnDuration = 0f
        marConsecutiveFrames = 0
    }

    private fun calculateEyeAspectRatio(
        landmarks: List<NormalizedLandmark>,
        eyeIndices: List<Int>
    ): Float {
        require(eyeIndices.size == 6) { "Eye indices must contain exactly 6 points" }

        val p1 = landmarks[eyeIndices[0]]
        val p2 = landmarks[eyeIndices[1]]
        val p3 = landmarks[eyeIndices[2]]
        val p4 = landmarks[eyeIndices[3]]
        val p5 = landmarks[eyeIndices[4]]
        val p6 = landmarks[eyeIndices[5]]

        val vertical1 = distance(p2, p6)
        val vertical2 = distance(p3, p5)
        val horizontal = distance(p1, p4)

        return (vertical1 + vertical2) / (2f * horizontal)
    }

    private fun calculateMAR(landmarks: List<NormalizedLandmark>): Float {
        if (landmarks.size < max(MOUTH_INDICES.maxOrNull() ?: 0, 405)) {
            return 0f
        }

        val leftCorner = landmarks[MOUTH_INDICES[0]]
        val rightCorner = landmarks[MOUTH_INDICES[1]]
        val topLipInner = landmarks[MOUTH_INDICES[2]]
        val bottomLipInner = landmarks[MOUTH_INDICES[3]]

        val horizontalDist = distance(leftCorner, rightCorner)
        val verticalDist = distance(topLipInner, bottomLipInner)

        if (horizontalDist < 1e-6) {
            return 0f
        }

        return verticalDist / horizontalDist
    }

    private fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        return sqrt((a.x() - b.x()).pow(2) + (a.y() - b.y()).pow(2))
    }

    private fun returnLivestreamError(error: RuntimeException) {
        faceLandmarkerHelperListener?.onError(error.message ?: "Unknown error")
    }

    companion object {
        const val TAG = "FaceLandmarkerHelper"
        private const val MP_FACE_LANDMARKER_TASK = "face_landmarker.task"

        // Detection thresholds (matching Python implementation)
        const val DEFAULT_EAR_THRESHOLD = 0.15f
        const val DEFAULT_MAR_THRESHOLD = 0.35f
        const val EAR_CONSECUTIVE_FRAMES = 3
        const val MAR_CONSECUTIVE_FRAMES = 10  // Matching Python's MIN_YAWN_FRAMES

        // Default values
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val DEFAULT_NUM_FACES = 1
        const val DEFAULT_FACE_DETECTION_CONFIDENCE = 0.5f
        const val DEFAULT_FACE_TRACKING_CONFIDENCE = 0.5f
        const val DEFAULT_FACE_PRESENCE_CONFIDENCE = 0.5f

        // Landmark indices (matching Python implementation)
        val LEFT_EAR_POINTS = listOf(33, 160, 158, 133, 153, 144)
        val RIGHT_EAR_POINTS = listOf(362, 385, 387, 263, 373, 380)
        val MOUTH_INDICES = listOf(61, 291, 13, 14)
    }

    interface LandmarkerListener {
        fun onError(error: String)
        fun onResults(
            earValue: Float,
            marValue: Float,
            isBlinking: Boolean,
            isYawning: Boolean,
            landmarks: List<NormalizedLandmark>,
            blinkCount: Int,
            yawnCount: Int,
            microsleepDuration: Float,
            yawnDuration: Float
        )
        fun onBlinkDetected()
        fun onBlinkComplete()
        fun onYawnDetected()
        fun onYawnComplete()
        fun onEmpty()
    }
}

