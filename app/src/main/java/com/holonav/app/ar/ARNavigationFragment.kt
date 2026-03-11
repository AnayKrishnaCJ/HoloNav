package com.holonav.app.ar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.holonav.app.MainActivity
import com.holonav.app.R
import com.holonav.app.databinding.FragmentArBinding
import com.holonav.app.map.AStarEngine
import com.holonav.app.map.Node
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan2

/**
 * AR Navigation Fragment.
 *
 * Displays the camera preview with directional arrows overlaid via AROverlayView.
 * Features:
 * - Wi-Fi error banner
 * - FAB to switch back to Map view
 * - Crowd detection status from external cameras
 */
class ARNavigationFragment : Fragment() {

    companion object {
        private const val TAG = "ARNavFragment"
    }

    private var _binding: FragmentArBinding? = null
    private val binding get() = _binding!!

    private var cameraExecutor: ExecutorService? = null
    private val astarEngine = AStarEngine()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (hasCameraPermission()) {
            startCamera()
        }

        // Start navigation updates
        startNavigationUpdates()

        // Register for crowd status updates from external cameras
        registerCrowdUpdates()

        // Register for Wi-Fi error updates
        registerWifiErrorCallback()

        // Mode toggle: AR → Map
        binding.fabSwitchMap.setOnClickListener {
            findNavController().navigate(R.id.nav_map)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Preview only — no image analysis needed since crowd detection uses external cameras
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.surfaceProvider = binding.cameraPreview.surfaceProvider
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /**
     * Register for crowd alerts from external cameras (via MainActivity).
     * Shows informational message only — does NOT reroute.
     */
    private fun registerCrowdUpdates() {
        val mainActivity = activity as? MainActivity ?: return

        mainActivity.addCrowdAlertCallback { cameraName, personCount, isCrowded ->
            if (isCrowded) {
                binding.crowdWarningBanner.visibility = View.VISIBLE
                binding.crowdWarningBanner.text =
                    "⚠ $cameraName: This area is crowded ($personCount people detected)"
            } else {
                binding.crowdWarningBanner.visibility = View.GONE
            }
        }
    }

    /**
     * Register for Wi-Fi error notifications to show/hide the error banner.
     */
    private fun registerWifiErrorCallback() {
        val mainActivity = activity as? MainActivity ?: return
        mainActivity.setWifiErrorCallback { hasError ->
            if (hasError) {
                binding.wifiErrorCard.visibility = View.VISIBLE
                binding.wifiErrorText.text = getString(R.string.wifi_error)
            } else {
                binding.wifiErrorCard.visibility = View.GONE
            }
        }
    }

    private fun startNavigationUpdates() {
        val mainActivity = activity as? MainActivity ?: return

        mainActivity.setNavigationCallback { route, currentIndex, userX, userY ->
            if (route.isEmpty() || currentIndex >= route.size) {
                binding.arOverlay.isNavigating = false
                binding.arDirectionText.text = getString(R.string.ar_no_route)
                return@setNavigationCallback
            }

            binding.arOverlay.isNavigating = true

            // Check if arrived
            if (currentIndex == route.size - 1) {
                val target = route[currentIndex]
                val dist = kotlin.math.sqrt(
                    (userX - target.x) * (userX - target.x) +
                    (userY - target.y) * (userY - target.y)
                )
                if (dist < 30f) {
                    binding.arOverlay.hasArrived = true
                    binding.arDirectionText.text = getString(R.string.arrived)
                    binding.arDistanceText.text = ""
                    binding.arOverlay.invalidate()
                    return@setNavigationCallback
                }
            }

            binding.arOverlay.hasArrived = false

            // Calculate bearing to next waypoint
            val target = route[currentIndex]
            val bearing = Math.toDegrees(
                atan2((target.x - userX).toDouble(), (target.y - userY).toDouble())
            ).toFloat()

            binding.arOverlay.bearingToNext = bearing

            // Calculate distance
            val dist = kotlin.math.sqrt(
                (userX - target.x) * (userX - target.x) +
                (userY - target.y) * (userY - target.y)
            )
            binding.arOverlay.distanceToNext = dist / 3f

            // Direction label
            val direction = when {
                currentIndex > 0 && currentIndex < route.size - 1 -> {
                    val angle = astarEngine.turnAngle(
                        route[currentIndex - 1], route[currentIndex], route[currentIndex + 1]
                    )
                    when {
                        angle < -35f -> "↰ Turn Left"
                        angle > 35f -> "Turn Right ↱"
                        else -> "↑ Go Straight"
                    }
                }
                else -> "↑ Go Straight"
            }
            binding.arOverlay.directionLabel = direction
            binding.arDirectionText.text = direction
            binding.arDistanceText.text = "${(dist / 3f).toInt()}m"

            binding.arOverlay.invalidate()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor?.shutdown()
        _binding = null
    }
}
