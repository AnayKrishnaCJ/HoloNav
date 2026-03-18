package com.holonav.app.ar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.holonav.app.MainActivity
import com.holonav.app.R
import com.holonav.app.databinding.FragmentArBinding
import com.holonav.app.map.AStarEngine
import com.holonav.app.map.Node
import kotlin.math.atan2

/**
 * AR Navigation Fragment with ARCore 3D integration.
 *
 * Uses Sceneview's ArSceneView for:
 * - 3D arrow nodes at upcoming waypoints
 * - Glowing line segments connecting the route
 * - Progressive removal of passed nodes
 *
 * Keeps 2D HUD overlay for:
 * - Direction text + distance
 * - Crowd detection banners
 * - Wi-Fi error banners
 * - Arrival screen
 */
class ARNavigationFragment : Fragment() {

    companion object {
        private const val TAG = "ARNavFragment"
    }

    private var _binding: FragmentArBinding? = null
    private val binding get() = _binding!!

    private val astarEngine = AStarEngine()

    // 3D rendering
    private var routeRenderer: ARRouteRenderer? = null
    private val coordConverter = ARCoordinateConverter()

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

        // Initialize 3D route renderer
        routeRenderer = ARRouteRenderer(requireContext(), coordConverter)

        // Configure ArSceneView
        setupArSceneView()

        // Start navigation updates (both 2D HUD and 3D nodes)
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

    /**
     * Configure the ArSceneView.
     */
    private fun setupArSceneView() {
        try {
            // ArSceneView handles camera permissions and AR session internally
            binding.arSceneView.apply {
                // Enable plane detection for better spatial understanding
                planeRenderer.isVisible = false  // Hide plane visualization (we only need tracking)

                // Set up frame update listener for continuous route updates
                onSessionUpdated = { session, frame ->
                    // Frame updates are handled via navigation callback
                    // The 3D nodes auto-update their positions relative to the AR world
                }
            }

            Log.d(TAG, "ArSceneView configured successfully")
        } catch (e: Exception) {
            Log.e(TAG, "ArSceneView setup failed: ${e.message}", e)
        }
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

    /**
     * Register for navigation updates from MainActivity.
     *
     * Updates both:
     * 1. 3D scene: arrow nodes + line segments via ARRouteRenderer
     * 2. 2D HUD: direction text, distance, arrival screen via AROverlayView
     */
    private fun startNavigationUpdates() {
        val mainActivity = activity as? MainActivity ?: return

        mainActivity.setNavigationCallback { route, currentIndex, userX, userY ->
            if (route.isEmpty() || currentIndex >= route.size) {
                binding.arOverlay.isNavigating = false
                binding.arDirectionText.text = getString(R.string.ar_no_route)
                routeRenderer?.clearAll(binding.arSceneView)
                return@setNavigationCallback
            }

            binding.arOverlay.isNavigating = true

            // --- 3D Update: Place/update arrow nodes and line segments ---
            routeRenderer?.updateRoute(
                binding.arSceneView, route, currentIndex, userX, userY
            )

            // --- 2D HUD Update ---

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

                    // Show 3D arrival marker
                    routeRenderer?.showArrival(binding.arSceneView, target, userX, userY)
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
        routeRenderer?.destroy()
        _binding = null
    }
}
