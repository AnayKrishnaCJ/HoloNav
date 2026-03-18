package com.holonav.app.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.holonav.app.MainActivity
import com.holonav.app.R
import com.holonav.app.databinding.FragmentMapBinding
import com.holonav.app.voice.VoiceCommandManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Map Fragment — displays the indoor floor plan with the user's position
 * and A* navigation route.
 *
 * Features:
 * - Loading animation during A* computation
 * - Wi-Fi error banner
 * - FAB to switch to AR view
 * - Live route updates from recalculation
 * - Voice command input (mic button)
 */
class MapFragment : Fragment(), VoiceCommandManager.VoiceCommandCallback {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val astarEngine = AStarEngine()
    private var selectedDestinationId: String? = null
    private var isNavigating = false

    // Voice command
    private var voiceCommandManager: VoiceCommandManager? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDestinationPicker()
        setupButtons()
        setupMap()
        setupVoiceCommand()
        startPositionUpdates()
        registerWifiErrorCallback()
        registerRouteUpdateCallback()
        registerCrowdAlertCallback()
        restoreNavigationState()
    }

    private fun setupDestinationPicker() {
        val destinations = BuildingGraph.getDestinations()
        val names = destinations.map { it.name }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            names
        )
        binding.destinationDropdown.setAdapter(adapter)
        binding.destinationDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedDestinationId = destinations[position].id
        }
    }

    private fun setupButtons() {
        binding.btnNavigate.setOnClickListener {
            if (isNavigating) {
                stopNavigation()
            } else {
                startNavigation()
            }
        }

        binding.btnVoiceToggle.setOnClickListener {
            val mainActivity = activity as? MainActivity
            val muted = mainActivity?.toggleVoiceMute() ?: false
            Toast.makeText(
                requireContext(),
                if (muted) getString(R.string.voice_muted) else getString(R.string.voice_unmuted),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Mode toggle: Map → AR
        binding.fabSwitchAr.setOnClickListener {
            findNavController().navigate(R.id.nav_ar)
        }

        // Toggle 2D/3D Map View
        binding.fabSwitch3d.setOnClickListener {
            binding.mapCanvas.toggle3DMode()
            if (binding.mapCanvas.is3DMode) {
                binding.fabSwitch3d.text = "2D"
                binding.fabSwitch3d.setIconResource(android.R.drawable.ic_menu_mapmode)
            } else {
                binding.fabSwitch3d.text = "3D"
                binding.fabSwitch3d.icon = null
            }
        }

        // Voice command mic button
        binding.btnVoiceCommand.setOnClickListener {
            startVoiceCommand()
        }
    }

    private fun setupMap() {
        binding.mapCanvas.onMapTapped = { x, y ->
            // Find nearest destination node when tapping the map
            val nearest = BuildingGraph.findNearestNode(x, y)
            if (nearest.isDestination) {
                selectedDestinationId = nearest.id
                binding.destinationDropdown.setText(nearest.name, false)
                Toast.makeText(requireContext(), "Selected: ${nearest.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Voice Command ---

    private fun setupVoiceCommand() {
        voiceCommandManager = VoiceCommandManager(requireContext()).apply {
            setCallback(this@MapFragment)

            // Set destinations for fuzzy matching
            val destinations = BuildingGraph.getDestinations()
            setDestinations(destinations.map { Pair(it.id, it.name) })
        }
    }

    private fun startVoiceCommand() {
        // Check RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2001)
            return
        }

        val vcm = voiceCommandManager ?: return
        if (!vcm.isAvailable()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.voice_not_available),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        vcm.startListening()
    }

    // --- VoiceCommandCallback ---

    override fun onListeningStarted() {
        binding.btnVoiceCommand.alpha = 0.5f
        Toast.makeText(requireContext(), getString(R.string.voice_listening), Toast.LENGTH_SHORT).show()
    }

    override fun onResult(destinationName: String, destinationId: String) {
        binding.btnVoiceCommand.alpha = 1.0f

        // Set the destination in the picker
        selectedDestinationId = destinationId
        binding.destinationDropdown.setText(destinationName, false)

        // Auto-start navigation
        val mainActivity = activity as? MainActivity ?: return
        val success = mainActivity.startNavigationByVoice(destinationId)

        if (success) {
            // Update map UI
            val route = mainActivity.getActiveRoute()
            if (route.isNotEmpty()) {
                val dest = route.last()
                binding.mapCanvas.setRoute(route, dest)
                val distance = astarEngine.pathDistance(route).toInt()
                binding.routeInfoText.text = getString(R.string.route_found, route.size, distance / 3)
                binding.routeInfoCard.visibility = View.VISIBLE
                isNavigating = true
                binding.btnNavigate.text = getString(R.string.stop_navigation)
            }
        } else {
            Toast.makeText(requireContext(), getString(R.string.no_route), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNoMatch(spokenText: String) {
        binding.btnVoiceCommand.alpha = 1.0f
        Toast.makeText(
            requireContext(),
            "${getString(R.string.voice_no_match)}: \"$spokenText\"",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onError(message: String) {
        binding.btnVoiceCommand.alpha = 1.0f
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onListeningStopped() {
        binding.btnVoiceCommand.alpha = 1.0f
    }

    // --- Navigation ---

    private fun startNavigation() {
        val destId = selectedDestinationId
        if (destId == null) {
            Toast.makeText(requireContext(), getString(R.string.select_destination), Toast.LENGTH_SHORT).show()
            return
        }

        val mainActivity = activity as? MainActivity
        val userPos = mainActivity?.getCurrentPosition()

        // Find nearest graph node to user's current position
        val startNode = if (userPos != null) {
            BuildingGraph.findNearestNode(userPos.first, userPos.second)
        } else {
            // Default to entrance if no position yet
            BuildingGraph.nodes["entrance"]!!
        }

        // Show loading overlay
        binding.loadingOverlay.visibility = View.VISIBLE

        // Run A* on background thread with coroutine
        viewLifecycleOwner.lifecycleScope.launch {
            val path = withContext(Dispatchers.Default) {
                astarEngine.findPath(startNode.id, destId)
            }

            // Hide loading overlay
            binding.loadingOverlay.visibility = View.GONE

            if (path.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.no_route), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val destination = BuildingGraph.nodes[destId]
            binding.mapCanvas.setRoute(path, destination)

            // Show route info
            val distance = astarEngine.pathDistance(path).toInt()
            binding.routeInfoText.text = getString(R.string.route_found, path.size, distance / 3)
            binding.routeInfoCard.visibility = View.VISIBLE

            // Update button
            isNavigating = true
            binding.btnNavigate.text = getString(R.string.stop_navigation)

            // Pass route to main activity for AR, voice, and live recalculation
            mainActivity?.setActiveRoute(path, destId)
        }
    }

    private fun stopNavigation() {
        binding.mapCanvas.clearRoute()
        binding.routeInfoCard.visibility = View.GONE
        isNavigating = false
        binding.btnNavigate.text = getString(R.string.start_navigation)

        val mainActivity = activity as? MainActivity
        mainActivity?.clearActiveRoute()
    }

    private fun startPositionUpdates() {
        val mainActivity = activity as? MainActivity
        mainActivity?.setPositionCallback { x, y, confidence ->
            binding.mapCanvas.setUserPosition(x, y, confidence)
        }
    }

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
     * Register for crowd alerts — show/hide the top warning banner.
     * Banner slides in with animation and includes a dismiss button.
     */
    private fun registerCrowdAlertCallback() {
        val mainActivity = activity as? MainActivity ?: return

        // Dismiss button
        binding.crowdWarningDismiss.setOnClickListener {
            binding.crowdWarningCard.animate()
                .translationY(-binding.crowdWarningCard.height.toFloat())
                .alpha(0f)
                .setDuration(250)
                .withEndAction { binding.crowdWarningCard.visibility = View.GONE }
                .start()
        }

        mainActivity.addCrowdAlertCallback { cameraName, personCount, isCrowded ->
            if (isCrowded) {
                binding.crowdWarningTitle.text = getString(R.string.crowd_area_ahead)
                binding.crowdWarningDetail.text =
                    getString(R.string.crowd_detail, cameraName, personCount)

                // Animate in
                binding.crowdWarningCard.translationY = -100f
                binding.crowdWarningCard.alpha = 0f
                binding.crowdWarningCard.visibility = View.VISIBLE
                binding.crowdWarningCard.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(350)
                    .start()
            } else {
                binding.crowdWarningCard.animate()
                    .translationY(-binding.crowdWarningCard.height.toFloat())
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction { binding.crowdWarningCard.visibility = View.GONE }
                    .start()
            }
        }
    }

    private fun registerRouteUpdateCallback() {
        val mainActivity = activity as? MainActivity ?: return
        mainActivity.setRouteUpdateCallback { newPath ->
            if (newPath.isNotEmpty()) {
                val dest = newPath.last()
                binding.mapCanvas.setRoute(newPath, dest)
                val distance = astarEngine.pathDistance(newPath).toInt()
                binding.routeInfoText.text = getString(R.string.route_found, newPath.size, distance / 3)
            }
        }
    }

    private fun restoreNavigationState() {
        val mainActivity = activity as? MainActivity ?: return
        if (mainActivity.isNavigationActive()) {
            val route = mainActivity.getActiveRoute()
            if (route.isNotEmpty()) {
                val dest = route.last()
                binding.mapCanvas.setRoute(route, dest)
                val distance = astarEngine.pathDistance(route).toInt()
                binding.routeInfoText.text = getString(R.string.route_found, route.size, distance / 3)
                binding.routeInfoCard.visibility = View.VISIBLE
                isNavigating = true
                binding.btnNavigate.text = getString(R.string.stop_navigation)
            }
        }
    }

    fun updateRoute(path: List<Node>) {
        val dest = if (path.isNotEmpty()) path.last() else null
        binding.mapCanvas.setRoute(path, dest)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voiceCommandManager?.destroy()
        _binding = null
    }
}
