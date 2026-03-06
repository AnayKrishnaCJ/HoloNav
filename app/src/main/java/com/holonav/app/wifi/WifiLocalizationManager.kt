package com.holonav.app.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Wi-Fi Fingerprinting & KNN Localization Manager.
 *
 * Handles:
 * 1. Calibration — scanning Wi-Fi networks and storing fingerprints at map coordinates
 * 2. Localization — using K-Nearest Neighbors to estimate position from live scan
 */
class WifiLocalizationManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiLocalization"
        private const val K_NEIGHBORS = 3
        private const val SCAN_DURATION_MS = 8000L // 8 seconds for calibration scan
    }

    /** A single Wi-Fi fingerprint: map of BSSID to RSSI */
    data class Fingerprint(
        val x: Float,
        val y: Float,
        val rssiMap: Map<String, Int> // BSSID -> average RSSI
    )

    /** Estimated position result */
    data class PositionEstimate(
        val x: Float,
        val y: Float,
        val confidence: Float // 0.0 - 1.0
    )

    // Calibration database
    private val calibrationDB = mutableListOf<Fingerprint>()
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // Scan receiver
    private var scanReceiver: BroadcastReceiver? = null
    private var isScanning = false

    /** Number of stored calibration points */
    val calibrationCount: Int get() = calibrationDB.size

    /**
     * Perform a calibration scan at the given map coordinates.
     * Scans Wi-Fi multiple times over SCAN_DURATION_MS and averages the RSSI values.
     */
    suspend fun calibrate(x: Float, y: Float): Fingerprint? = withContext(Dispatchers.IO) {
        if (isScanning) return@withContext null
        isScanning = true

        try {
            val allScans = mutableListOf<Map<String, Int>>()

            // Perform multiple scans over the duration
            val scanCount = 4
            val interval = SCAN_DURATION_MS / scanCount

            repeat(scanCount) {
                val scanResult = performSingleScan()
                if (scanResult.isNotEmpty()) {
                    allScans.add(scanResult)
                }
                delay(interval)
            }

            if (allScans.isEmpty()) {
                Log.w(TAG, "No Wi-Fi scan results during calibration")
                return@withContext null
            }

            // Average RSSI values across all scans
            val averagedRssi = averageScans(allScans)

            val fingerprint = Fingerprint(x, y, averagedRssi)
            calibrationDB.add(fingerprint)
            Log.d(TAG, "Calibration saved at ($x, $y) with ${averagedRssi.size} APs")

            fingerprint
        } finally {
            isScanning = false
        }
    }

    /**
     * Perform a single Wi-Fi scan and return BSSID -> RSSI map.
     */
    private fun performSingleScan(): Map<String, Int> {
        @Suppress("DEPRECATION")
        wifiManager.startScan()

        val results = wifiManager.scanResults ?: return emptyMap()
        return results.associate { it.BSSID to it.level }
    }

    /**
     * Average RSSI values across multiple scans.
     */
    private fun averageScans(scans: List<Map<String, Int>>): Map<String, Int> {
        val allBssids = scans.flatMap { it.keys }.toSet()
        val averaged = mutableMapOf<String, Int>()

        for (bssid in allBssids) {
            val values = scans.mapNotNull { it[bssid] }
            if (values.isNotEmpty()) {
                averaged[bssid] = values.average().toInt()
            }
        }

        return averaged
    }

    /**
     * Estimate current position using KNN against the calibration database.
     * Compares the live Wi-Fi scan's RSSI vector with stored fingerprints.
     */
    fun estimatePosition(): PositionEstimate? {
        if (calibrationDB.size < K_NEIGHBORS) {
            Log.w(TAG, "Need at least $K_NEIGHBORS calibration points, have ${calibrationDB.size}")
            return null
        }

        val currentScan = performSingleScan()
        if (currentScan.isEmpty()) {
            Log.w(TAG, "Empty Wi-Fi scan, cannot estimate position")
            return null
        }

        return knnEstimate(currentScan)
    }

    /**
     * K-Nearest Neighbors estimation.
     * Calculates Euclidean distance in RSSI-space between the live scan and each fingerprint.
     */
    private fun knnEstimate(liveScan: Map<String, Int>): PositionEstimate {
        // Calculate RSSI-space distance to each calibration point
        val distances = calibrationDB.map { fp ->
            val distance = rssiDistance(liveScan, fp.rssiMap)
            Pair(fp, distance)
        }.sortedBy { it.second }

        // Take K nearest neighbors
        val nearest = distances.take(K_NEIGHBORS)

        // Weighted average position (inverse distance weighting)
        var totalWeight = 0f
        var weightedX = 0f
        var weightedY = 0f

        for ((fp, dist) in nearest) {
            val weight = if (dist < 1f) 1000f else 1f / dist
            weightedX += fp.x * weight
            weightedY += fp.y * weight
            totalWeight += weight
        }

        val estimatedX = weightedX / totalWeight
        val estimatedY = weightedY / totalWeight

        // Confidence: inverse of average distance to nearest neighbors, normalized
        val avgDist = nearest.map { it.second }.average().toFloat()
        val confidence = (1f / (1f + avgDist / 100f)).coerceIn(0f, 1f)

        return PositionEstimate(estimatedX, estimatedY, confidence)
    }

    /**
     * Euclidean distance between two RSSI vectors.
     * For BSSIDs present in one but not the other, uses -100 dBm as the default (very weak signal).
     */
    private fun rssiDistance(scan1: Map<String, Int>, scan2: Map<String, Int>): Float {
        val allBssids = scan1.keys + scan2.keys
        var sumSquared = 0f

        for (bssid in allBssids) {
            val rssi1 = (scan1[bssid] ?: -100).toFloat()
            val rssi2 = (scan2[bssid] ?: -100).toFloat()
            sumSquared += (rssi1 - rssi2).pow(2)
        }

        return sqrt(sumSquared)
    }

    /**
     * Clear all calibration data.
     */
    fun clearCalibration() {
        calibrationDB.clear()
        Log.d(TAG, "Calibration database cleared")
    }

    /**
     * Cleanup resources.
     */
    fun destroy() {
        scanReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {}
        }
    }
}
