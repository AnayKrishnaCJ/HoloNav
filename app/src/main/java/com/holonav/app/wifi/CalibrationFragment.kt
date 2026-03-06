package com.holonav.app.wifi

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.holonav.app.MainActivity
import com.holonav.app.R
import com.holonav.app.databinding.FragmentCalibrationBinding
import kotlinx.coroutines.launch

/**
 * Calibration Fragment — allows the user to tap locations on the map
 * and capture Wi-Fi fingerprints for indoor positioning.
 */
class CalibrationFragment : Fragment() {

    private var _binding: FragmentCalibrationBinding? = null
    private val binding get() = _binding!!

    private val calibrationCoords = mutableListOf<Pair<Float, Float>>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCalibrationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Enable AP overlay mode on the map
        binding.calibrationMap.showAccessPoints = true

        // Handle map taps for calibration
        binding.calibrationMap.onMapTapped = { x, y ->
            performCalibration(x, y)
        }

        // Clear button
        binding.btnClearCalibration.setOnClickListener {
            clearCalibration()
        }

        updateCalibrationCount()
    }

    private fun performCalibration(x: Float, y: Float) {
        val mainActivity = activity as? MainActivity ?: return
        val wifiManager = mainActivity.getWifiLocalizationManager()

        // Show scanning overlay
        binding.scanningOverlay.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val fingerprint = wifiManager.calibrate(x, y)

                if (fingerprint != null) {
                    calibrationCoords.add(Pair(x, y))
                    binding.calibrationMap.calibrationPoints = calibrationCoords.toList()
                    binding.calibrationMap.invalidate()

                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.calibration_saved,
                            fingerprint.x,
                            fingerprint.y,
                            fingerprint.rssiMap.size
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Calibration failed — no Wi-Fi results",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                binding.scanningOverlay.visibility = View.GONE
                updateCalibrationCount()
            }
        }
    }

    private fun clearCalibration() {
        val mainActivity = activity as? MainActivity ?: return
        mainActivity.getWifiLocalizationManager().clearCalibration()
        calibrationCoords.clear()
        binding.calibrationMap.calibrationPoints = emptyList()
        binding.calibrationMap.invalidate()
        updateCalibrationCount()
        Toast.makeText(requireContext(), "Calibration data cleared", Toast.LENGTH_SHORT).show()
    }

    private fun updateCalibrationCount() {
        val mainActivity = activity as? MainActivity
        val count = mainActivity?.getWifiLocalizationManager()?.calibrationCount ?: 0
        binding.calibrationCount.text = getString(R.string.calibration_points, count)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
