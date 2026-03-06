package com.holonav.app.map

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.LinearInterpolator
import com.holonav.app.R
import kotlin.math.max
import kotlin.math.min

/**
 * Custom Canvas View for rendering the indoor floor plan.
 *
 * Draws:
 * - Room walls and corridors
 * - Room labels
 * - User position (pulsing blue dot)
 * - A* route (animated dashed line)
 * - Destination marker
 * - Wi-Fi AP locations (in calibration mode)
 * - Calibration points
 */
class MapCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Map Data ---
    private val mapWidth = 1000f
    private val mapHeight = 700f

    // --- State ---
    var userX: Float = -1f
        private set
    var userY: Float = -1f
        private set
    var userConfidence: Float = 0f
        private set
    var routePath: List<Node> = emptyList()
        private set
    var destinationNode: Node? = null
        private set
    var showAccessPoints: Boolean = false
    var calibrationPoints: List<Pair<Float, Float>> = emptyList()

    // --- Touch & Interaction ---
    var onMapTapped: ((Float, Float) -> Unit)? = null
    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())

    // --- Animation ---
    private var routeAnimPhase = 0f
    private var userPulseRadius = 0f
    private var userPulseAlpha = 255

    // --- Paints ---
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2D3748")
        style = Paint.Style.FILL
    }
    private val corridorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#243055")
        style = Paint.Style.FILL
    }
    private val roomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2340")
        style = Paint.Style.FILL
    }
    private val roomStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A4A6B")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B95A8")
        textSize = 14f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val userDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }
    private val userGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4000E5FF")
        style = Paint.Style.FILL
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C4DFF")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    private val routeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#407C4DFF")
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val destPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00")
        style = Paint.Style.FILL
    }
    private val apPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
    }
    private val calibPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF00")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D1B2A")
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
    }

    // --- Room Definitions (for drawing) ---
    data class Room(val rect: RectF, val name: String, val isHighlight: Boolean = false)

    private val rooms = listOf(
        Room(RectF(80f, 80f, 220f, 220f), "Room A\n(Conference)"),
        Room(RectF(470f, 60f, 630f, 180f), "Room B\n(Lab)"),
        Room(RectF(770f, 120f, 930f, 270f), "Room C\n(Office)"),
        Room(RectF(770f, 420f, 940f, 580f), "Cafeteria"),
        Room(RectF(380f, 400f, 520f, 500f), "Restrooms"),
        Room(RectF(180f, 240f, 310f, 320f), "Stairwell"),
        Room(RectF(580f, 200f, 720f, 300f), "Elevator"),
        Room(RectF(40f, 440f, 170f, 580f), "Entrance"),
        Room(RectF(790f, 580f, 920f, 680f), "Exit", true)
    )

    // Corridors
    private val corridors = listOf(
        RectF(40f, 310f, 960f, 390f),    // Main horizontal
        RectF(210f, 100f, 290f, 600f),   // Left vertical
        RectF(410f, 100f, 490f, 600f),   // Center vertical
        RectF(610f, 100f, 690f, 600f),   // Right vertical
    )

    init {
        startAnimations()
    }

    private fun startAnimations() {
        // Route dash animation
        ValueAnimator.ofFloat(0f, 30f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                routeAnimPhase = it.animatedValue as Float
                routePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 10f), routeAnimPhase)
                invalidate()
            }
            start()
        }

        // User pulse animation
        ValueAnimator.ofFloat(8f, 28f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                userPulseRadius = it.animatedValue as Float
                userPulseAlpha = (255 * (1f - (userPulseRadius - 8f) / 20f)).toInt()
                invalidate()
            }
            start()
        }
    }

    fun setUserPosition(x: Float, y: Float, confidence: Float) {
        userX = x
        userY = y
        userConfidence = confidence
        invalidate()
    }

    fun setRoute(path: List<Node>, destination: Node?) {
        routePath = path
        destinationNode = destination
        invalidate()
    }

    fun clearRoute() {
        routePath = emptyList()
        destinationNode = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()

        // Calculate scale to fit map in view
        val sx = width / mapWidth
        val sy = height / mapHeight
        val baseScale = min(sx, sy) * 0.9f

        val offsetX = (width - mapWidth * baseScale) / 2f
        val offsetY = (height - mapHeight * baseScale) / 2f

        canvas.translate(offsetX + translateX, offsetY + translateY)
        canvas.scale(baseScale * scaleFactor, baseScale * scaleFactor)

        // Background
        canvas.drawColor(Color.parseColor("#0A0E1A"))

        // Grid
        drawGrid(canvas)

        // Corridors
        for (corridor in corridors) {
            canvas.drawRoundRect(corridor, 8f, 8f, corridorPaint)
        }

        // Rooms
        for (room in rooms) {
            canvas.drawRoundRect(room.rect, 12f, 12f, roomPaint)
            canvas.drawRoundRect(room.rect, 12f, 12f, roomStrokePaint)

            // Room label
            val lines = room.name.split("\n")
            val centerX = room.rect.centerX()
            val centerY = room.rect.centerY() - (lines.size - 1) * 8f
            for ((i, line) in lines.withIndex()) {
                canvas.drawText(line, centerX, centerY + i * 18f, labelPaint)
            }
        }

        // Wi-Fi AP locations
        if (showAccessPoints) {
            drawAccessPoints(canvas)
        }

        // Calibration points
        for ((cx, cy) in calibrationPoints) {
            canvas.drawCircle(cx, cy, 8f, calibPaint)
            canvas.drawCircle(cx, cy, 3f, apPaint)
        }

        // A* Route
        if (routePath.size >= 2) {
            drawRoute(canvas)
        }

        // Destination marker
        destinationNode?.let { dest ->
            canvas.drawCircle(dest.x, dest.y, 14f, destPaint)
            destPaint.alpha = 100
            canvas.drawCircle(dest.x, dest.y, 22f, destPaint)
            destPaint.alpha = 255
        }

        // User position
        if (userX >= 0 && userY >= 0) {
            drawUserDot(canvas)
        }

        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        val step = 50f
        var x = 0f
        while (x <= mapWidth) {
            canvas.drawLine(x, 0f, x, mapHeight, gridPaint)
            x += step
        }
        var y = 0f
        while (y <= mapHeight) {
            canvas.drawLine(0f, y, mapWidth, y, gridPaint)
            y += step
        }
    }

    private fun drawRoute(canvas: Canvas) {
        val path = Path()
        path.moveTo(routePath[0].x, routePath[0].y)
        for (i in 1 until routePath.size) {
            path.lineTo(routePath[i].x, routePath[i].y)
        }
        canvas.drawPath(path, routeGlowPaint)
        canvas.drawPath(path, routePaint)

        // Draw waypoint dots
        for (node in routePath) {
            canvas.drawCircle(node.x, node.y, 4f, routePaint.apply { style = Paint.Style.FILL })
        }
        routePaint.style = Paint.Style.STROKE
    }

    private fun drawUserDot(canvas: Canvas) {
        // Pulse glow
        userGlowPaint.alpha = userPulseAlpha
        canvas.drawCircle(userX, userY, userPulseRadius, userGlowPaint)

        // Core dot
        canvas.drawCircle(userX, userY, 10f, userDotPaint)

        // Inner white dot
        userDotPaint.color = Color.WHITE
        canvas.drawCircle(userX, userY, 4f, userDotPaint)
        userDotPaint.color = Color.parseColor("#00E5FF")
    }

    private fun drawAccessPoints(canvas: Canvas) {
        // Draw virtual AP locations (matching the Wi-Fi fingerprint APs)
        val apPositions = listOf(
            PointF(100f, 100f), PointF(500f, 100f), PointF(900f, 100f),
            PointF(100f, 350f), PointF(500f, 350f), PointF(900f, 350f),
            PointF(100f, 600f), PointF(500f, 600f)
        )
        for (ap in apPositions) {
            // Signal radius ring
            apPaint.style = Paint.Style.STROKE
            apPaint.alpha = 40
            canvas.drawCircle(ap.x, ap.y, 80f, apPaint)
            canvas.drawCircle(ap.x, ap.y, 160f, apPaint)

            // AP dot
            apPaint.style = Paint.Style.FILL
            apPaint.alpha = 255
            canvas.drawCircle(ap.x, ap.y, 6f, apPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
            // Convert screen coordinates to map coordinates
            val sx = width / mapWidth
            val sy = height / mapHeight
            val baseScale = min(sx, sy) * 0.9f
            val offsetX = (width - mapWidth * baseScale) / 2f
            val offsetY = (height - mapHeight * baseScale) / 2f

            val mapX = (event.x - offsetX - translateX) / (baseScale * scaleFactor)
            val mapY = (event.y - offsetY - translateY) / (baseScale * scaleFactor)

            if (mapX in 0f..mapWidth && mapY in 0f..mapHeight) {
                onMapTapped?.invoke(mapX, mapY)
            }
        }

        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = max(0.5f, min(scaleFactor, 3.0f))
            invalidate()
            return true
        }
    }
}
