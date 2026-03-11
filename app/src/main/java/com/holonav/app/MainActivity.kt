package com.holonav.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.holonav.app.crowd.CrowdAnalyzer
import com.holonav.app.databinding.ActivityMainBinding
import com.holonav.app.map.AStarEngine
import com.holonav.app.map.BuildingGraph
import com.holonav.app.map.Node
import com.holonav.app.voice.VoiceGuideManager
import com.holonav.app.wifi.WifiLocalizationManager
import kotlin.math.sqrt

/**
 * Main Activity for HoloNav.
 *
 * Hosts the bottom navigation (Map | AR | Calibrate) and orchestrates:
 * - Wi-Fi position polling loop
 * - Route tracking, live recalculation, and voice guidance
 * - External camera crowd detection (message only, no rerouting)
 * - Status bar updates (position, crowd, confidence)
 */
class MainActivity : AppCompatActivity(), CrowdAnalyzer.CrowdCallback {

    companion object {
        private const val TAG = "HoloNav"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val POSITION_UPDATE_INTERVAL_MS = 2000L
        private const val DEVIATION_THRESHOLD = 60f       // px from route to trigger recalc
        private const val RECALC_COOLDOWN_MS = 5000L      // min time between recalculations

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private lateinit var binding: ActivityMainBinding

    // Core managers
    private lateinit var wifiManager: WifiLocalizationManager
    private lateinit var voiceGuide: VoiceGuideManager
    private lateinit var crowdAnalyzer: CrowdAnalyzer
    private val astarEngine = AStarEngine()

    // State
    private var activeRoute: List<Node> = emptyList()
    private var currentRouteIndex: Int = 0
    private var activeDestinationId: String? = null    // For live recalculation
    private var currentX: Float = 100f
    private var currentY: Float = 500f
    private var currentConfidence: Float = 0f
    private var lastRecalcTimeMs: Long = 0L            // Recalculation cooldown

    // Callbacks for fragments
    private var positionCallback: ((Float, Float, Float) -> Unit)? = null
    private var navigationCallback: ((List<Node>, Int, Float, Float) -> Unit)? = null
    private val crowdAlertCallbacks = mutableListOf<(String, Int, Boolean) -> Unit>()
    private var wifiErrorCallback: ((Boolean) -> Unit)? = null     // true = error visible
    private var routeUpdateCallback: ((List<Node>) -> Unit)? = null // Route changed externally

    // Position update loop
    private val handler = Handler(Looper.getMainLooper())
    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            updatePosition()
            handler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers
        wifiManager = WifiLocalizationManager(this)
        voiceGuide = VoiceGuideManager(this)
        crowdAnalyzer = CrowdAnalyzer(this)

        // Setup navigation
        setupNavigation()

        // Request permissions
        if (!hasAllPermissions()) {
            requestPermissions()
        }

        // Initialize crowd detection from external cameras
        initExternalCrowdDetection()

        // Start position update loop
        handler.postDelayed(positionUpdateRunnable, POSITION_UPDATE_INTERVAL_MS)

        Log.d(TAG, "HoloNav initialized")
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNav.setupWithNavController(navController)
    }

    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Log.w(TAG, "Some permissions denied — features may be limited")
            }
        }
    }

    // --- External Camera Crowd Detection ---

    /**
     * Initialize crowd detection from external college cameras.
     * Polls each camera periodically and shows informational messages.
     * Does NOT reroute — just informs the user.
     */
    private fun initExternalCrowdDetection() {
        crowdAnalyzer.setCallback(this)

        // Initialize TFLite model on background thread, then start polling
        Thread {
            crowdAnalyzer.initialize()
            runOnUiThread {
                crowdAnalyzer.startPolling()
                Log.d(TAG, "External camera crowd detection started")
            }
        }.start()
    }

    /**
     * Callback from CrowdAnalyzer when an external camera reports results.
     * Shows informational message — does NOT change the navigation route.
     */
    override fun onCrowdAnalyzed(
        level: CrowdAnalyzer.CrowdLevel,
        personCount: Int,
        cameraName: String,
        detections: List<CrowdAnalyzer.Detection>
    ) {
        // Update status bar
        updateCrowdStatus(level, personCount, cameraName)

        // Notify AR fragment
        val isCrowded = level != CrowdAnalyzer.CrowdLevel.CLEAR
        crowdAlertCallbacks.forEach { it.invoke(cameraName, personCount, isCrowded) }

        // Show toast for significant crowd events
        if (level == CrowdAnalyzer.CrowdLevel.DENSE) {
            Toast.makeText(
                this,
                "⚠ $cameraName: This area is crowded ($personCount people detected)",
                Toast.LENGTH_LONG
            ).show()

            // Also announce via voice (informational only)
            voiceGuide.speak("Attention: $cameraName area is crowded with $personCount people")
        }
    }

    // --- Position Management ---

    private fun updatePosition() {
        val estimate = wifiManager.estimatePosition()

        if (estimate != null) {
            currentX = estimate.x
            currentY = estimate.y
            currentConfidence = estimate.confidence
            // Clear Wi-Fi error
            wifiErrorCallback?.invoke(false)
        } else {
            // Check if this is due to missing calibration or empty scan
            if (wifiManager.calibrationCount >= 3) {
                // We have calibration data but scan returned nothing → real error
                wifiErrorCallback?.invoke(true)
            }
            simulateDemoPosition()
        }

        // Update status bar
        binding.positionText.text = String.format("Position: (%.0f, %.0f)", currentX, currentY)
        binding.confidenceText.text = String.format("Confidence: %d%%", (currentConfidence * 100).toInt())

        // Notify position callback
        positionCallback?.invoke(currentX, currentY, currentConfidence)

        // Route tracking
        if (activeRoute.isNotEmpty()) {
            updateRouteProgress()
        }
    }

    private var demoT = 0f
    private fun simulateDemoPosition() {
        demoT += 0.02f
        if (demoT > 1f) demoT = 0f
        currentX = 100f + (750f * demoT)
        currentY = 350f + (kotlin.math.sin(demoT * Math.PI * 2).toFloat() * 50f)
        currentConfidence = 0.65f
    }

    private fun updateRouteProgress() {
        if (currentRouteIndex >= activeRoute.size) return

        val target = activeRoute[currentRouteIndex]
        val dx = currentX - target.x
        val dy = currentY - target.y
        val distance = sqrt(dx * dx + dy * dy)

        if (distance < 40f && currentRouteIndex < activeRoute.size - 1) {
            currentRouteIndex++
            Log.d(TAG, "Advanced to waypoint $currentRouteIndex: ${activeRoute[currentRouteIndex].name}")
        }

        // --- LIVE RECALCULATION: detect deviation from route ---
        if (activeDestinationId != null && isDeviatedFromRoute()) {
            val now = System.currentTimeMillis()
            if (now - lastRecalcTimeMs > RECALC_COOLDOWN_MS) {
                lastRecalcTimeMs = now
                recalculateRoute()
            }
        }

        // Voice guidance (turn-by-turn only, no rerouting)
        voiceGuide.evaluateAndSpeak(currentX, currentY, activeRoute, currentRouteIndex)

        // Navigation callback for AR
        navigationCallback?.invoke(activeRoute, currentRouteIndex, currentX, currentY)
    }

    /**
     * Check if the user has deviated too far from the current route segment.
     * Measures the perpendicular distance from user's position to the line
     * between current waypoint and the next waypoint.
     */
    private fun isDeviatedFromRoute(): Boolean {
        if (activeRoute.size < 2) return false
        val idx = currentRouteIndex.coerceIn(0, activeRoute.size - 2)
        val a = activeRoute[idx]
        val b = activeRoute[idx + 1]

        // Distance from point (currentX, currentY) to line segment A→B
        val abx = b.x - a.x
        val aby = b.y - a.y
        val apx = currentX - a.x
        val apy = currentY - a.y

        val abLenSq = abx * abx + aby * aby
        if (abLenSq < 1f) return false // Degenerate segment

        val t = ((apx * abx + apy * aby) / abLenSq).coerceIn(0f, 1f)
        val closestX = a.x + t * abx
        val closestY = a.y + t * aby

        val distSq = (currentX - closestX) * (currentX - closestX) +
                     (currentY - closestY) * (currentY - closestY)

        return distSq > DEVIATION_THRESHOLD * DEVIATION_THRESHOLD
    }

    /**
     * Recompute A* route from current position to stored destination.
     * Notifies map + AR fragments and announces via voice.
     */
    private fun recalculateRoute() {
        val destId = activeDestinationId ?: return

        val startNode = BuildingGraph.findNearestNode(currentX, currentY)
        val newPath = astarEngine.findPath(startNode.id, destId)

        if (newPath.isNotEmpty()) {
            activeRoute = newPath
            currentRouteIndex = 0
            Log.d(TAG, "Route recalculated: ${newPath.size} waypoints")

            // Voice announcement
            voiceGuide.speak(getString(R.string.voice_rerouting))

            // Notify fragments
            routeUpdateCallback?.invoke(newPath)
            navigationCallback?.invoke(activeRoute, currentRouteIndex, currentX, currentY)

            // Toast
            Toast.makeText(this, getString(R.string.rerouting), Toast.LENGTH_SHORT).show()
        } else {
            Log.w(TAG, "Recalculation failed — no path to $destId")
        }
    }

    // --- Public API for Fragments ---

    fun getWifiLocalizationManager(): WifiLocalizationManager = wifiManager

    fun getCurrentPosition(): Pair<Float, Float> = Pair(currentX, currentY)

    fun setPositionCallback(callback: (Float, Float, Float) -> Unit) {
        positionCallback = callback
    }

    fun setNavigationCallback(callback: (List<Node>, Int, Float, Float) -> Unit) {
        navigationCallback = callback
    }

    /**
     * Register a callback for crowd alert messages from external cameras.
     * Called by MapFragment and ARNavigationFragment to display crowd banners.
     * Multiple fragments can register — all will be notified.
     */
    fun addCrowdAlertCallback(callback: (String, Int, Boolean) -> Unit) {
        crowdAlertCallbacks.add(callback)
    }

    fun removeCrowdAlertCallback(callback: (String, Int, Boolean) -> Unit) {
        crowdAlertCallbacks.remove(callback)
    }

    /**
     * Register a callback for Wi-Fi scan errors.
     * Called by MapFragment and ARNavigationFragment to show error banners.
     */
    fun setWifiErrorCallback(callback: (Boolean) -> Unit) {
        wifiErrorCallback = callback
    }

    /**
     * Register a callback for route updates (from live recalculation).
     * Called by MapFragment to redraw the route on the map.
     */
    fun setRouteUpdateCallback(callback: (List<Node>) -> Unit) {
        routeUpdateCallback = callback
    }

    fun setActiveRoute(route: List<Node>, destinationId: String? = null) {
        activeRoute = route
        currentRouteIndex = 0
        activeDestinationId = destinationId
        lastRecalcTimeMs = System.currentTimeMillis()  // Reset cooldown
        voiceGuide.resetNavigation()
        voiceGuide.speak("Navigation started. Follow the arrows.")
        Log.d(TAG, "Route set with ${route.size} waypoints")
    }

    fun clearActiveRoute() {
        activeRoute = emptyList()
        currentRouteIndex = 0
        activeDestinationId = null
        voiceGuide.resetNavigation()
        navigationCallback?.invoke(emptyList(), 0, currentX, currentY)
    }

    fun isNavigationActive(): Boolean = activeRoute.isNotEmpty()

    fun getActiveRoute(): List<Node> = activeRoute

    /**
     * Start navigation to a destination by its ID (called from voice command).
     * Returns true if navigation started successfully.
     */
    fun startNavigationByVoice(destinationId: String): Boolean {
        val startNode = BuildingGraph.findNearestNode(currentX, currentY)
        val path = astarEngine.findPath(startNode.id, destinationId)

        if (path.isEmpty()) {
            Log.w(TAG, "Voice nav: no path to $destinationId")
            return false
        }

        setActiveRoute(path, destinationId)

        // Notify fragments
        routeUpdateCallback?.invoke(path)
        navigationCallback?.invoke(activeRoute, currentRouteIndex, currentX, currentY)

        val destName = BuildingGraph.nodes[destinationId]?.name ?: destinationId
        voiceGuide.speak("Navigating to $destName")
        Toast.makeText(this, getString(R.string.voice_navigating_to, destName), Toast.LENGTH_SHORT).show()

        return true
    }

    fun toggleVoiceMute(): Boolean {
        return voiceGuide.toggleMute()
    }

    fun updateCrowdStatus(level: CrowdAnalyzer.CrowdLevel, count: Int, cameraName: String = "") {
        val statusText = when (level) {
            CrowdAnalyzer.CrowdLevel.CLEAR -> getString(R.string.crowd_clear)
            CrowdAnalyzer.CrowdLevel.MODERATE -> "$cameraName: ${getString(R.string.crowd_moderate, count)}"
            CrowdAnalyzer.CrowdLevel.DENSE -> "$cameraName: ${getString(R.string.crowd_dense, count)}"
        }
        binding.crowdStatus.text = statusText
        binding.crowdStatus.setTextColor(
            ContextCompat.getColor(
                this, when (level) {
                    CrowdAnalyzer.CrowdLevel.CLEAR -> R.color.success
                    CrowdAnalyzer.CrowdLevel.MODERATE -> R.color.warning
                    CrowdAnalyzer.CrowdLevel.DENSE -> R.color.error
                }
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(positionUpdateRunnable)
        wifiManager.destroy()
        voiceGuide.destroy()
        crowdAnalyzer.destroy()
    }
}
