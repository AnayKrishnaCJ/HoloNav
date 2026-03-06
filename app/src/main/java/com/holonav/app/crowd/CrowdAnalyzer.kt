package com.holonav.app.crowd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Crowd detection using external fixed cameras (college CCTV / IP cameras).
 *
 * Periodically fetches MJPEG/JPEG snapshots from configured IP camera URLs,
 * runs TFLite person detection, and reports crowd density.
 *
 * When a crowd is detected it shows an informational message —
 * it does NOT reroute the user.
 */
class CrowdAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "CrowdAnalyzer"
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
        private const val PERSON_LABEL = "person"
        private const val CONFIDENCE_THRESHOLD = 0.4f
        private const val CROWD_THRESHOLD = 3
        private const val DENSE_THRESHOLD = 6
        private const val POLL_INTERVAL_MS = 3000L // Poll cameras every 3 seconds
    }

    /** Crowd density levels */
    enum class CrowdLevel {
        CLEAR,    // 0-2 people
        MODERATE, // 3-5 people
        DENSE     // 6+ people
    }

    /** Callback for crowd detection results */
    interface CrowdCallback {
        fun onCrowdAnalyzed(
            level: CrowdLevel,
            personCount: Int,
            cameraName: String,
            detections: List<Detection>
        )
    }

    /** Simplified detection result */
    data class Detection(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val confidence: Float
    )

    /**
     * An external camera source installed in the college.
     * @param id       Unique identifier
     * @param name     Human-readable name (e.g. "Main Corridor Camera")
     * @param url      JPEG snapshot URL (e.g. http://192.168.1.50/snapshot.jpg)
     * @param areaId   Which map area this camera covers (matches a Node id or zone)
     */
    data class CameraSource(
        val id: String,
        val name: String,
        val url: String,
        val areaId: String
    )

    private var detector: ObjectDetector? = null
    private var callback: CrowdCallback? = null
    private var isInitialized = false
    private var isPolling = false

    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- Configured external cameras ---
    // Update these URLs to match your college's actual IP camera endpoints.
    // Common formats:
    //   MJPEG snapshot: http://<ip>/snapshot.jpg
    //   RTSP converted: http://<ip>:8080/shot.jpg (via IP Webcam app or similar)
    private val cameras = mutableListOf(
        CameraSource("cam_corridor", "Main Corridor", "http://192.168.1.50/snapshot.jpg", "c3"),
        CameraSource("cam_lobby", "Lobby Camera", "http://192.168.1.51/snapshot.jpg", "lobby"),
        CameraSource("cam_cafeteria", "Cafeteria Camera", "http://192.168.1.52/snapshot.jpg", "cafeteria"),
        CameraSource("cam_entrance", "Entrance Camera", "http://192.168.1.53/snapshot.jpg", "entrance")
    )

    // Last known crowd status per camera
    private val crowdStatus = mutableMapOf<String, CrowdLevel>()

    /**
     * Initialize the TFLite object detector.
     */
    fun initialize() {
        try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setMaxResults(20)
                .setScoreThreshold(CONFIDENCE_THRESHOLD)
                .build()

            detector = ObjectDetector.createFromFileAndOptions(
                context, MODEL_FILE, options
            )
            isInitialized = true
            Log.d(TAG, "TFLite detector initialized for external camera analysis")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize detector: ${e.message}", e)
            isInitialized = false
        }
    }

    fun setCallback(cb: CrowdCallback) {
        callback = cb
    }

    /**
     * Add or update an external camera source.
     */
    fun addCamera(camera: CameraSource) {
        cameras.removeAll { it.id == camera.id }
        cameras.add(camera)
    }

    /**
     * Start polling all configured external cameras periodically.
     */
    fun startPolling() {
        if (!isInitialized || isPolling) return
        isPolling = true
        Log.d(TAG, "Started polling ${cameras.size} external cameras")
        pollCameras()
    }

    /**
     * Stop the polling loop.
     */
    fun stopPolling() {
        isPolling = false
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun pollCameras() {
        if (!isPolling) return

        for (camera in cameras) {
            executor.execute {
                fetchAndAnalyze(camera)
            }
        }

        // Schedule next poll
        mainHandler.postDelayed({ pollCameras() }, POLL_INTERVAL_MS)
    }

    /**
     * Fetch a snapshot from an external camera and analyze it.
     */
    private fun fetchAndAnalyze(camera: CameraSource) {
        try {
            val bitmap = fetchSnapshot(camera.url)
            if (bitmap != null) {
                val result = analyzeFrame(bitmap)
                val level = result.first
                val count = result.second
                val detections = result.third

                crowdStatus[camera.id] = level

                // Notify on main thread
                mainHandler.post {
                    callback?.onCrowdAnalyzed(level, count, camera.name, detections)
                }

                Log.d(TAG, "${camera.name}: $count people detected (${level.name})")
            } else {
                Log.w(TAG, "Failed to fetch snapshot from ${camera.name} (${camera.url})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing ${camera.name}: ${e.message}")
        }
    }

    /**
     * Fetch a JPEG snapshot from an IP camera URL.
     */
    private fun fetchSnapshot(url: String): Bitmap? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.doInput = true
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                connection.disconnect()
                bitmap
            } else {
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Snapshot fetch error: ${e.message}")
            null
        }
    }

    /**
     * Analyze a single frame for person detection.
     * Returns (CrowdLevel, personCount, detections).
     */
    private fun analyzeFrame(bitmap: Bitmap): Triple<CrowdLevel, Int, List<Detection>> {
        if (!isInitialized || detector == null) {
            return Triple(CrowdLevel.CLEAR, 0, emptyList())
        }

        try {
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val results = detector!!.detect(tensorImage)

            val personDetections = results.filter { detection ->
                detection.categories.any { cat ->
                    cat.label.equals(PERSON_LABEL, ignoreCase = true)
                }
            }

            val detectionList = personDetections.map { det ->
                val box = det.boundingBox
                Detection(
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom,
                    confidence = det.categories.first().score
                )
            }

            val personCount = detectionList.size
            val level = when {
                personCount >= DENSE_THRESHOLD -> CrowdLevel.DENSE
                personCount >= CROWD_THRESHOLD -> CrowdLevel.MODERATE
                else -> CrowdLevel.CLEAR
            }

            return Triple(level, personCount, detectionList)
        } catch (e: Exception) {
            Log.e(TAG, "Frame analysis error: ${e.message}")
            return Triple(CrowdLevel.CLEAR, 0, emptyList())
        }
    }

    /**
     * Get the current crowd status for a specific area.
     */
    fun getCrowdStatusForArea(areaId: String): CrowdLevel {
        val camera = cameras.find { it.areaId == areaId }
        return if (camera != null) crowdStatus[camera.id] ?: CrowdLevel.CLEAR else CrowdLevel.CLEAR
    }

    /**
     * Get all current crowd statuses.
     */
    fun getAllCrowdStatuses(): Map<String, CrowdLevel> {
        return cameras.associate { cam ->
            cam.name to (crowdStatus[cam.id] ?: CrowdLevel.CLEAR)
        }
    }

    fun destroy() {
        stopPolling()
        executor.shutdownNow()
        detector?.close()
        detector = null
        isInitialized = false
    }
}
