package cool.rin.deepseekremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Compact native counterpart of Harness Web's context-occupancy ring. */
internal class ContextMeterView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    var percent: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            contentDescription = context.resolvedAppLanguage().text("已使用 ${field}% 上下文", "${field}% of context used")
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - 3f * density
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.color = Color.rgb(76, 78, 82)
        canvas.drawOval(oval, paint)
        if (percent > 0) {
            paint.color = Color.rgb(145, 148, 154)
            canvas.drawArc(oval, -90f, 360f * percent / 100f, false, paint)
        }
    }
}
