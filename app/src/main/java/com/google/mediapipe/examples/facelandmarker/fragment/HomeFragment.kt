package com.google.mediapipe.examples.facelandmarker.fragment

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.facelandmarker.R
import com.google.mediapipe.examples.facelandmarker.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                navigateToCamera()
            } else {
                Toast.makeText(requireContext(), 
                    "Camera permission is required for drowsiness detection",
                    Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.startButton.setOnClickListener {
            checkCameraPermissionAndNavigate()
        }
    }

    private fun checkCameraPermissionAndNavigate() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                navigateToCamera()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun navigateToCamera() {
        findNavController().navigate(R.id.action_homeFragment_to_cameraFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
