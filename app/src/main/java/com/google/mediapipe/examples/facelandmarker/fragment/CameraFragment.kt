package com.google.mediapipe.examples.facelandmarker.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.mediapipe.examples.facelandmarker.FaceLandmarkerHelper
import com.google.mediapipe.examples.facelandmarker.MainViewModel
import com.google.mediapipe.examples.facelandmarker.R
import com.google.mediapipe.examples.facelandmarker.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class CameraFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!
    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var cameraExecutor: ExecutorService
    private var earThreshold = FaceLandmarkerHelper.DEFAULT_EAR_THRESHOLD
    private var marThreshold = FaceLandmarkerHelper.DEFAULT_MAR_THRESHOLD

    // State tracking variables
    private var microsleepStartTime: Long = 0
    private var yawnStartTime: Long = 0
    private var microsleepDuration: Float = 0f
    private var yawnDuration: Float = 0f
    private var leftEyeClosed = false
    private var rightEyeClosed = false
    private var yawnInProgress = false
    private var yawnFrames = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        faceLandmarkerHelper = FaceLandmarkerHelper(
            context = requireContext(),
            faceLandmarkerHelperListener = this
        )
        setupCamera()
        setupThresholdControls()

    }

    @SuppressLint("MissingPermission")

    private fun setupThresholdControls() {
        // Initialize with default values
        earThreshold = FaceLandmarkerHelper.DEFAULT_EAR_THRESHOLD
        marThreshold = FaceLandmarkerHelper.DEFAULT_MAR_THRESHOLD

        // Update UI with initial values
        binding.bottomSheetLayout.earThresholdValue.text = "%.2f".format(earThreshold)
        binding.bottomSheetLayout.marThresholdValue.text = "%.2f".format(marThreshold)

        // Set up click listeners
        binding.bottomSheetLayout.earThresholdPlus.setOnClickListener {
            earThreshold = min(earThreshold + 0.01f, 0.5f)
            binding.bottomSheetLayout.earThresholdValue.text = "%.2f".format(earThreshold)
            faceLandmarkerHelper?.earThreshold = earThreshold
        }

        binding.bottomSheetLayout.earThresholdMinus.setOnClickListener {
            earThreshold = max(earThreshold - 0.01f, 0.05f)
            binding.bottomSheetLayout.earThresholdValue.text = "%.2f".format(earThreshold)
            faceLandmarkerHelper?.earThreshold = earThreshold
        }

        binding.bottomSheetLayout.marThresholdPlus.setOnClickListener {
            marThreshold = min(marThreshold + 0.01f, 1.0f)
            binding.bottomSheetLayout.marThresholdValue.text = "%.2f".format(marThreshold)
            faceLandmarkerHelper?.marThreshold = marThreshold
        }

        binding.bottomSheetLayout.marThresholdMinus.setOnClickListener {
            marThreshold = max(marThreshold - 0.01f, 0.1f)
            binding.bottomSheetLayout.marThresholdValue.text = "%.2f".format(marThreshold)
            faceLandmarkerHelper?.marThreshold = marThreshold
        }
    }
    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(cameraProvider)

            faceLandmarkerHelper = FaceLandmarkerHelper(
                context = requireContext(),
                faceLandmarkerHelperListener = this
            )
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(cameraExecutor) { image ->
                faceLandmarkerHelper.detectLiveStream(image, true)
            } }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            viewLifecycleOwner, cameraSelector, preview, imageAnalysis
        )
        preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }

    override fun onResults(
        earValue: Float,
        marValue: Float,
        isBlinking: Boolean,
        isYawning: Boolean,
        landmarks: List<NormalizedLandmark>,
        blinkCount: Int,
        yawnCount: Int,
        microsleepDuration: Float,
        yawnDuration: Float
    ) {
        activity?.runOnUiThread {
            // Update local state
            this.microsleepDuration = microsleepDuration
            this.yawnDuration = yawnDuration

            binding.overlay.setDrowsinessData(
                ear = earValue,
                mar = marValue,
                drowsy = isYawning || microsleepDuration > 0.7f,
                newLandmarks = landmarks,
                blinkCount = blinkCount,
                yawnCount = yawnCount,
                microsleepDuration = microsleepDuration,
                yawnDuration = yawnDuration,
                alertText = when {
                    microsleepDuration > 0.7f -> "ALERT: Microsleep (${microsleepDuration.format(1)}s)"
                    isYawning && yawnDuration > 2.0f -> "ALERT: Yawn (${yawnDuration.format(1)}s)"
                    else -> ""
                }
            )

            updateCounters()
        }
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)

    override fun onBlinkDetected() {
        viewModel.incrementBlinkCount()
        updateCounters()
    }

    override fun onYawnDetected() {
        viewModel.incrementYawnCount()
        updateCounters()
    }

    override fun onBlinkComplete() {
        // Optional: Add visual feedback if needed
    }

    override fun onYawnComplete() {
        // Optional: Add visual feedback if needed
    }

    private fun updateCounters() {
        activity?.runOnUiThread {
            binding.apply {
                blinkCount.text = getString(R.string.blink_count, viewModel.blinkCount.value)
                yawnCount.text = getString(R.string.yawn_count, viewModel.yawnCount.value)
                microSleepCount.text = getString(R.string.microsleep_count, viewModel.drowsinessAlerts.value)
            }
        }
    }

    override fun onError(error: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onEmpty() {
        activity?.runOnUiThread {
            leftEyeClosed = false
            rightEyeClosed = false
            yawnInProgress = false
            microsleepDuration = 0f
            yawnDuration = 0f
            yawnFrames = 0
            binding.overlay.clear()
        }
    }

    override fun onDestroyView() {
        _binding = null
        cameraExecutor.shutdown()
        faceLandmarkerHelper.clearFaceLandmarker()
        super.onDestroyView()
    }

}