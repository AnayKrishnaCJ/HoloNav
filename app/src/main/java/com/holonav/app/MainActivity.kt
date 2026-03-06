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
import com.holonav.app.map.Node
import com.holonav.app.voice.VoiceGuideManager
import com.holonav.app.wifi.WifiLocalizationManager

/**
 * Main Activity for HoloNav.
 *
 * Hosts the bottom navigation (Map | AR | Calibrate) and orchestrates:
 * - Wi-Fi position polling loop
 * - Route tracking and voice guidance
 * - External camera crowd detection (message only, no rerouting)
 * - Status bar updates (position, crowd, confidence)
 */
class MainActivity : AppCompatActivity(), CrowdAnalyzer.CrowdCallback {

    companion object {
        private const val TAG = "HoloNav"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val POSITION_UPDATE_INTERVAL_MS = 2000L

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
    }

    private lateinit var binding: ActivityMainBinding

    // Core managers
    private lateinit var wifiManager: WifiLocalizationManager
    private lateinit var voiceGuide: VoiceGuideManager
    private lateinit var crowdAnalyzer: CrowdAnalyzer

    // State
    private var activeRoute: List<Node> = emptyList()
    private var currentRouteIndex: Int = 0
    private var currentX: Float = 100f
    private var currentY: Float = 500f
    private var currentConfidence: Float = 0f

    // Callbacks for fragments
    private var positionCallback: ((Float, Float, Float) -> Unit)? = null
    private var navigationCallback: ((List<Node>, Int, Float, Float) -> Unit)? = null
    private var crowdAlertCallback: ((String, Int, Boolean) -> Unit)? = null

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
        crowdAlertCallback?.invoke(cameraName, personCount, isCrowded)

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
        } else {
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
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)

        if (distance < 40f && currentRouteIndex < activeRoute.size - 1) {
            currentRouteIndex++
            Log.d(TAG, "Advanced to waypoint $currentRouteIndex: ${activeRoute[currentRouteIndex].name}")
        }

        // Voice guidance (turn-by-turn only, no rerouting)
        voiceGuide.evaluateAndSpeak(currentX, currentY, activeRoute, currentRouteIndex)

        // Navigation callback for AR
        navigationCallback?.invoke(activeRoute, currentRouteIndex, currentX, currentY)
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
     * Called by ARNavigationFragment to display crowd banners.
     */
    fun setCrowdAlertCallback(callback: (String, Int, Boolean) -> Unit) {
        crowdAlertCallback = callback
    }

    fun setActiveRoute(route: List<Node>) {
        activeRoute = route
        currentRouteIndex = 0
        voiceGuide.resetNavigation()
        voiceGuide.speak("Navigation started. Follow the arrows.")
        Log.d(TAG, "Route set with ${route.size} waypoints")
    }

    fun clearActiveRoute() {
        activeRoute = emptyList()
        currentRouteIndex = 0
        voiceGuide.resetNavigation()
        navigationCallback?.invoke(emptyList(), 0, currentX, currentY)
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
