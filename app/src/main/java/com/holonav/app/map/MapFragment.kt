package com.holonav.app.map

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.holonav.app.MainActivity
import com.holonav.app.R
import com.holonav.app.databinding.FragmentMapBinding

/**
 * Map Fragment — displays the indoor floor plan with the user's position
 * and A* navigation route.
 */
class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val astarEngine = AStarEngine()
    private var selectedDestinationId: String? = null
    private var isNavigating = false

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
        startPositionUpdates()
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

        val path = astarEngine.findPath(startNode.id, destId)
        if (path.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_route), Toast.LENGTH_SHORT).show()
            return
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

        // Pass route to main activity for AR and voice
        mainActivity?.setActiveRoute(path)
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
        // Register for position updates from the main activity
        val mainActivity = activity as? MainActivity
        mainActivity?.setPositionCallback { x, y, confidence ->
            binding.mapCanvas.setUserPosition(x, y, confidence)
        }
    }

    fun updateRoute(path: List<Node>) {
        val dest = if (path.isNotEmpty()) path.last() else null
        binding.mapCanvas.setRoute(path, dest)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
