package com.holonav.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.holonav.app.map.AStarEngine
import com.holonav.app.map.Node
import java.util.Locale
import kotlin.math.abs

/**
 * Voice Navigation Guide using Android TextToSpeech.
 *
 * Provides turn-by-turn spoken directions based on the A* route.
 * Enhanced with distance-aware instructions:
 * - "Turn left after 5 meters"
 * - "Continue straight for 12 meters"
 * - "You have reached your destination"
 *
 * Debounces instructions to avoid repeating too frequently.
 */
class VoiceGuideManager(context: Context) {

    companion object {
        private const val TAG = "VoiceGuide"
        private const val DEBOUNCE_MS = 5000L       // Min time between announcements
        private const val PROXIMITY_THRESHOLD = 50f  // Map units to trigger next instruction
        private const val TURN_THRESHOLD = 35f       // Degrees to qualify as a turn
        private const val MAP_UNITS_PER_METER = 3f   // Conversion factor: map units → meters
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    var isMuted = false
        private set

    private var lastAnnouncementTime = 0L
    private var lastAnnouncedNodeIndex = -1
    private val astarEngine = AStarEngine()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isReady = true
                Log.d(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }

    /**
     * Evaluate current position against the route and speak if needed.
     *
     * Enhanced with distance-aware messages:
     * - "Turn left after X meters"
     * - "Continue straight for X meters"
     * - "You have reached your destination"
     *
     * @param currentX User's estimated X position
     * @param currentY User's estimated Y position
     * @param route The full A* route (list of nodes)
     * @param currentNodeIndex The index of the node the user is currently approaching
     */
    fun evaluateAndSpeak(
        currentX: Float,
        currentY: Float,
        route: List<Node>,
        currentNodeIndex: Int
    ) {
        if (!isReady || isMuted || route.size < 2) return
        if (currentNodeIndex < 0 || currentNodeIndex >= route.size) return

        val now = System.currentTimeMillis()
        if (now - lastAnnouncementTime < DEBOUNCE_MS) return

        val targetNode = route[currentNodeIndex]
        val dx = currentX - targetNode.x
        val dy = currentY - targetNode.y
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)

        // Check if close enough to trigger instruction
        if (distance > PROXIMITY_THRESHOLD) return
        if (currentNodeIndex == lastAnnouncedNodeIndex) return

        // Calculate distance to next turn or destination (in meters)
        val distToNextMeters = (distance / MAP_UNITS_PER_METER).toInt()

        val instruction = when {
            // Arrived at final destination
            currentNodeIndex == route.size - 1 -> {
                "You have reached your destination"
            }
            // Calculate turn direction with distance
            currentNodeIndex > 0 && currentNodeIndex < route.size - 1 -> {
                val prev = route[currentNodeIndex - 1]
                val current = route[currentNodeIndex]
                val next = route[currentNodeIndex + 1]
                val angle = astarEngine.turnAngle(prev, current, next)

                // Calculate distance from current waypoint to next waypoint (in meters)
                val segmentDist = (current.distanceTo(next) / MAP_UNITS_PER_METER).toInt()

                when {
                    angle < -TURN_THRESHOLD -> "Turn left after $segmentDist meters"
                    angle > TURN_THRESHOLD -> "Turn right after $segmentDist meters"
                    else -> "Continue straight for $segmentDist meters"
                }
            }
            // First node — announce direction to next
            currentNodeIndex == 0 && route.size > 1 -> {
                val next = route[1]
                val segmentDist = (targetNode.distanceTo(next) / MAP_UNITS_PER_METER).toInt()
                "Continue straight for $segmentDist meters"
            }
            else -> "Continue straight"
        }

        speak(instruction)
        lastAnnouncedNodeIndex = currentNodeIndex
        lastAnnouncementTime = now
    }

    /**
     * Speak a message through TTS.
     */
    fun speak(message: String) {
        if (!isReady || isMuted) return
        Log.d(TAG, "Speaking: $message")
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "holonav_${System.currentTimeMillis()}")
    }

    /**
     * Toggle mute state.
     */
    fun toggleMute(): Boolean {
        isMuted = !isMuted
        if (isMuted) {
            tts?.stop()
        }
        return isMuted
    }

    /**
     * Reset announcement tracking (e.g., when a new route is set).
     */
    fun resetNavigation() {
        lastAnnouncedNodeIndex = -1
        lastAnnouncementTime = 0L
    }

    /**
     * Cleanup TTS resources.
     */
    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
