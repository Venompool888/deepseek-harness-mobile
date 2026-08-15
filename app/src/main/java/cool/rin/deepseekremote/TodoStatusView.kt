package cool.rin.deepseekremote

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator

/** Exact native status language used by Harness Web's todo panel. */
internal class TodoStatusView(context: Context, initialStatus: String) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.2f
        strokeCap = Paint.Cap.ROUND
    }
    private var status = initialStatus
    private var angle = 0f
    private var animator: ValueAnimator? = null

    init {
        contentDescription = when (status) {
            "completed" -> "已完成"
            "in_progress" -> "进行中"
            else -> "待处理"
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (status == "in_progress") startSpin()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val radius = 6.4f * density
        val ring = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.shader = null
        paint.pathEffect = null
        when (status) {
            "completed" -> {
                paint.color = Color.rgb(91, 207, 139)
                canvas.drawOval(ring, paint)
                val check = Path().apply {
                    moveTo(cx - 3.6f * density, cy)
                    lineTo(cx - .8f * density, cy + 2.8f * density)
                    lineTo(cx + 4.2f * density, cy - 3.2f * density)
                }
                canvas.drawPath(check, paint)
            }
            "in_progress" -> {
                paint.shader = SweepGradient(
                    cx,
                    cy,
                    intArrayOf(Color.TRANSPARENT, Color.rgb(82, 139, 255), Color.rgb(82, 139, 255), Color.TRANSPARENT),
                    floatArrayOf(0f, .30f, .72f, 1f),
                )
                canvas.save()
                canvas.rotate(angle, cx, cy)
                canvas.drawOval(ring, paint)
                canvas.restore()
            }
            else -> {
                paint.color = Color.rgb(145, 148, 154)
                paint.pathEffect = DashPathEffect(floatArrayOf(2.4f * density, 2.4f * density), 0f)
                canvas.drawOval(ring, paint)
            }
        }
    }

    private fun startSpin() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}
