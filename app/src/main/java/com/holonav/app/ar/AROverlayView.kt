package com.holonav.app.ar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.holonav.app.crowd.CrowdAnalyzer

/**
 * 2D HUD Overlay drawn on top of the ArSceneView.
 *
 * This is a lightweight canvas for elements that should be screen-space
 * (not 3D world-space):
 * - Arrival checkmark + text
 * - Crowd detection bounding boxes
 *
 * The 3D directional arrows and route line are now handled by
 * ARRouteRenderer in the ArSceneView scene graph.
 */
class AROverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Navigation State ---
    var bearingToNext: Float = 0f
    var distanceToNext: Float = 0f
    var directionLabel: String = ""
    var isNavigating: Boolean = false
    var hasArrived: Boolean = false

    // --- Crowd State ---
    var crowdDetections: List<CrowdAnalyzer.Detection> = emptyList()
    var crowdLevel: CrowdAnalyzer.CrowdLevel = CrowdAnalyzer.CrowdLevel.CLEAR

    // --- Animation ---
    private var pulseScale = 1f

    // --- Paints ---
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4000E5FF")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(8f, 0f, 2f, Color.BLACK)
    }

    private val bboxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val bboxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#20FF1744")
        style = Paint.Style.FILL
    }

    private val arrivedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        textSize = 72f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(12f, 0f, 2f, Color.BLACK)
    }

    // --- Navigation indicator (small compass-like bearing dot) ---
    private val bearingDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
        setShadowLayer(12f, 0f, 0f, Color.parseColor("#8000E5FF"))
    }

    private val bearingRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4000E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        // Pulse animation for arrival + bearing dot
        ValueAnimator.ofFloat(0.85f, 1.15f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulseScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isNavigating) return

        val centerX = width / 2f
        val centerY = height / 2f

        if (hasArrived) {
            drawArrivalScreen(canvas, centerX, centerY)
            return
        }

        // Draw small bearing indicator (compass dot at edge of screen)
        drawBearingIndicator(canvas, centerX, centerY)

        // Draw crowd detection boxes
        drawCrowdDetections(canvas)
    }

    /**
     * Draw a small bearing indicator at the edge of the screen.
     * Shows which direction the next waypoint is relative to the camera.
     */
    private fun drawBearingIndicator(canvas: Canvas, cx: Float, cy: Float) {
        val radius = 120f
        val dotRadius = 12f * pulseScale

        // Draw ring
        bearingRingPaint.alpha = (100 * pulseScale).toInt()
        canvas.drawCircle(cx, cy - 200f, radius, bearingRingPaint)

        // Draw bearing dot on the ring
        canvas.save()
        canvas.translate(cx, cy - 200f)
        canvas.rotate(bearingToNext)

        bearingDotPaint.alpha = (200 * pulseScale).toInt()
        canvas.drawCircle(0f, -radius, dotRadius, bearingDotPaint)

        canvas.restore()
    }

    private fun drawArrivalScreen(canvas: Canvas, cx: Float, cy: Float) {
        // Pulsing glow
        val glowRadius = 120f * pulseScale
        glowPaint.color = Color.parseColor("#2000E676")
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)
        glowPaint.color = Color.parseColor("#4000E5FF")

        // Checkmark
        val checkPath = Path().apply {
            moveTo(cx - 50f, cy)
            lineTo(cx - 10f, cy + 40f)
            lineTo(cx + 60f, cy - 40f)
        }
        val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E676")
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            setShadowLayer(15f, 0f, 0f, Color.parseColor("#00E676"))
        }

        canvas.drawPath(checkPath, checkPaint)
        canvas.drawText("You Have Arrived!", cx, cy + 100f, arrivedPaint)
    }

    private fun drawCrowdDetections(canvas: Canvas) {
        if (crowdDetections.isEmpty()) return

        for (det in crowdDetections) {
            val left = det.left / 300f * width
            val top = det.top / 300f * height
            val right = det.right / 300f * width
            val bottom = det.bottom / 300f * height

            canvas.drawRect(left, top, right, bottom, bboxFillPaint)
            canvas.drawRect(left, top, right, bottom, bboxPaint)

            val confText = "${(det.confidence * 100).toInt()}%"
            val smallText = Paint(textPaint).apply {
                textSize = 20f
                textAlign = Paint.Align.LEFT
            }
            canvas.drawText(confText, left + 4f, top + 20f, smallText)
        }
    }
}
