package com.holonav.app.ar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.holonav.app.crowd.CrowdAnalyzer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom overlay View drawn on top of the camera preview.
 *
 * Renders:
 * - Directional navigation arrows pointing toward the next waypoint
 * - Distance and direction text
 * - Crowd detection bounding boxes
 * - Warning overlays
 */
class AROverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Navigation State ---
    var bearingToNext: Float = 0f       // Degrees: 0=forward, negative=left, positive=right
    var distanceToNext: Float = 0f      // Map-units to next waypoint
    var directionLabel: String = ""     // "Turn Left", "Go Straight", etc.
    var isNavigating: Boolean = false
    var hasArrived: Boolean = false

    // --- Crowd State ---
    var crowdDetections: List<CrowdAnalyzer.Detection> = emptyList()
    var crowdLevel: CrowdAnalyzer.CrowdLevel = CrowdAnalyzer.CrowdLevel.CLEAR

    // --- Animation ---
    private var arrowPulse = 1f
    private var arrowGlowAlpha = 80

    // --- Paints ---
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
        setShadowLayer(20f, 0f, 0f, Color.parseColor("#8000E5FF"))
    }

    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

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

    private val subtextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 64f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(10f, 0f, 2f, Color.BLACK)
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

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // Required for shadow layers

        // Arrow pulse animation
        ValueAnimator.ofFloat(0.85f, 1.15f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                arrowPulse = it.animatedValue as Float
                arrowGlowAlpha = (80 * arrowPulse).toInt()
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

        // Draw navigation arrow
        drawDirectionArrow(canvas, centerX, centerY - 80f)

        // Draw crowd detection boxes
        drawCrowdDetections(canvas)
    }

    private fun drawDirectionArrow(canvas: Canvas, cx: Float, cy: Float) {
        canvas.save()
        canvas.translate(cx, cy)

        // Rotate arrow based on bearing
        canvas.rotate(bearingToNext)

        val arrowSize = 80f * arrowPulse

        // Glow circle behind arrow
        glowPaint.alpha = arrowGlowAlpha
        canvas.drawCircle(0f, 0f, arrowSize * 1.5f, glowPaint)

        // Arrow shape (pointing up by default)
        val arrowPath = Path().apply {
            moveTo(0f, -arrowSize)          // Tip
            lineTo(-arrowSize * 0.6f, arrowSize * 0.4f)  // Bottom-left
            lineTo(-arrowSize * 0.15f, arrowSize * 0.15f) // Inner-left
            lineTo(-arrowSize * 0.15f, arrowSize)          // Tail-left
            lineTo(arrowSize * 0.15f, arrowSize)           // Tail-right
            lineTo(arrowSize * 0.15f, arrowSize * 0.15f)  // Inner-right
            lineTo(arrowSize * 0.6f, arrowSize * 0.4f)    // Bottom-right
            close()
        }

        canvas.drawPath(arrowPath, arrowPaint)
        canvas.drawPath(arrowPath, arrowStrokePaint)

        canvas.restore()

        // Direction text below arrow
        canvas.drawText(directionLabel, cx, cy + 120f, textPaint)

        // Distance text
        val distText = if (distanceToNext > 0) "${distanceToNext.toInt()}m" else ""
        canvas.drawText(distText, cx, cy + 200f, subtextPaint)
    }

    private fun drawArrivalScreen(canvas: Canvas, cx: Float, cy: Float) {
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

        // Glow
        glowPaint.color = Color.parseColor("#2000E676")
        canvas.drawCircle(cx, cy, 120f, glowPaint)
        glowPaint.color = Color.parseColor("#4000E5FF")

        canvas.drawPath(checkPath, checkPaint)
        canvas.drawText("You Have Arrived!", cx, cy + 100f, arrivedPaint)
    }

    private fun drawCrowdDetections(canvas: Canvas) {
        if (crowdDetections.isEmpty()) return

        // Scale detection coordinates to view size
        for (det in crowdDetections) {
            val left = det.left / 300f * width  // Normalize from model coords
            val top = det.top / 300f * height
            val right = det.right / 300f * width
            val bottom = det.bottom / 300f * height

            canvas.drawRect(left, top, right, bottom, bboxFillPaint)
            canvas.drawRect(left, top, right, bottom, bboxPaint)

            // Confidence label
            val confText = "${(det.confidence * 100).toInt()}%"
            val smallText = Paint(textPaint).apply {
                textSize = 20f
                textAlign = Paint.Align.LEFT
            }
            canvas.drawText(confText, left + 4f, top + 20f, smallText)
        }
    }
}
