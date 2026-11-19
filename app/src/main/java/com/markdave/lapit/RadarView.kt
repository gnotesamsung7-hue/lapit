package com.markdave.lapit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/** Green sonar screen. Dots fade after the sweep passes, like a real sonar. Top of the screen = where the phone points. */
class RadarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    /** kind: 0 = device, 1 = Lapit friend, 2 = saved thing */
    data class Dot(
        val key: String,
        val label: String,
        val dist: Double,
        val bearing: Float?,
        val fallback: Float,
        val locked: Boolean,
        val kind: Int
    )

    var dots: List<Dot> = emptyList()
    var heading = 0f
    var hasCompass = true
    var saver = false
    var onTap: ((String) -> Unit)? = null

    private var sweep = 0f
    private var lastFrame = 0L
    private val pos = HashMap<String, PointF>()
    private val rings = doubleArrayOf(1.0, 3.0, 10.0, 30.0)
    private val maxD = 40.0

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF1F6B45.toInt(); strokeWidth = 2f
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF123D28.toInt(); strokeWidth = 1.5f
    }
    private val sweepLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF39FF88.toInt(); strokeWidth = 3f
    }
    private val sweepFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E8B5A.toInt() }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val northText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF4D6D.toInt(); isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gradMatrix = Matrix()
    private var gradient: SweepGradient? = null
    private var gradCx = -1f
    private var gradCy = -1f

    private fun radiusFor(d: Double, r: Float): Float =
        (ln(1.0 + d.coerceIn(0.0, maxD)) / ln(1.0 + maxD)).toFloat() * r

    private fun colorFor(kind: Int, locked: Boolean): Int = when {
        locked -> 0xFF5A7A
        kind == 1 -> 0x4CC9F0
        kind == 2 -> 0xFFD166
        else -> 0x39FF88
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame).coerceAtMost(200L)) / 1000f
        lastFrame = now
        sweep = (sweep + dt * (if (saver) 90f else 150f)) % 360f

        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 36f
        c.drawColor(Color.BLACK)
        if (r <= 10f) return

        val density = resources.displayMetrics.density
        ringText.textSize = 11f * density
        labelText.textSize = 12f * density
        northText.textSize = 14f * density

        // sweep glow
        if (gradient == null || gradCx != cx || gradCy != cy) {
            gradient = SweepGradient(
                cx, cy,
                intArrayOf(0x0039FF88, 0x0039FF88, 0x5539FF88),
                floatArrayOf(0f, 0.8f, 1f)
            )
            gradCx = cx
            gradCy = cy
        }
        gradMatrix.setRotate(sweep - 90f, cx, cy)
        gradient?.setLocalMatrix(gradMatrix)
        sweepFill.shader = gradient
        c.drawCircle(cx, cy, r, sweepFill)

        // rings and cross
        c.drawLine(cx - r, cy, cx + r, cy, crossPaint)
        c.drawLine(cx, cy - r, cx, cy + r, crossPaint)
        for (d in rings) {
            val rr = radiusFor(d, r)
            c.drawCircle(cx, cy, rr, ringPaint)
            c.drawText("${d.toInt()} m", cx + 6f, cy - rr - 6f, ringText)
        }
        c.drawCircle(cx, cy, r, ringPaint)

        // sweep line
        val sRad = Math.toRadians(sweep.toDouble())
        c.drawLine(cx, cy, cx + (r * sin(sRad)).toFloat(), cy - (r * cos(sRad)).toFloat(), sweepLine)

        // north marker (only meaningful with a compass)
        if (hasCompass) {
            val nRad = Math.toRadians((-heading).toDouble())
            val nx = cx + ((r + 18f) * sin(nRad)).toFloat()
            val ny = cy - ((r + 18f) * cos(nRad)).toFloat() + northText.textSize / 3f
            c.drawText("N", nx, ny, northText)
        }

        // you
        dotPaint.style = Paint.Style.FILL
        dotPaint.color = Color.WHITE
        c.drawCircle(cx, cy, 6f, dotPaint)

        // targets
        pos.clear()
        var labelled = 0
        for (d in dots) {
            val b = d.bearing
            val ang = if (b != null && hasCompass) b - heading else d.fallback
            val a360 = ((ang % 360f) + 360f) % 360f
            val rr = radiusFor(d.dist, r)
            val rad = Math.toRadians(a360.toDouble())
            val x = cx + (rr * sin(rad)).toFloat()
            val y = cy - (rr * cos(rad)).toFloat()
            pos[d.key] = PointF(x, y)

            val since = ((sweep - a360) + 360f) % 360f
            val glow = if (d.locked) 1f else (1f - since / 360f).coerceIn(0.3f, 1f)
            val alpha = (glow * 255).toInt()
            dotPaint.color = (alpha shl 24) or colorFor(d.kind, d.locked)
            val known = b != null && hasCompass
            dotPaint.style = if (known) Paint.Style.FILL else Paint.Style.STROKE
            dotPaint.strokeWidth = 3f
            val size = if (d.locked) 13f else 8f
            c.drawCircle(x, y, size, dotPaint)
            if (d.locked) {
                val pulse = ((now / 25) % 40).toFloat()
                dotPaint.style = Paint.Style.STROKE
                dotPaint.color = (((1f - pulse / 40f) * 255).toInt() shl 24) or 0xFF5A7A
                c.drawCircle(x, y, size + pulse, dotPaint)
            }
            if (d.locked || d.kind != 0 || labelled < 4) {
                labelled++
                labelText.alpha = alpha
                c.drawText(d.label.take(18), x + 14f, y + 5f, labelText)
            }
        }

        postInvalidateDelayed(if (saver) 66L else 16L)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                var best: String? = null
                var bestD = 70f * resources.displayMetrics.density / 2f
                for ((k, p) in pos) {
                    val d = hypot(p.x - e.x, p.y - e.y)
                    if (d < bestD) {
                        bestD = d
                        best = k
                    }
                }
                best?.let { onTap?.invoke(it) }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
