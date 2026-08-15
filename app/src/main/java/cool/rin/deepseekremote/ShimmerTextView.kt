package cool.rin.deepseekremote

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.TextView

/** Native equivalent of Harness Web's pale-blue sweep across “Deep diving...”. */
internal class ShimmerTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {
    private val shaderMatrix = Matrix()
    private var shimmer: LinearGradient? = null
    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        shimmer = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            0f,
            intArrayOf(
                Color.rgb(82, 139, 255),
                Color.rgb(82, 139, 255),
                Color.rgb(184, 211, 255),
                Color.rgb(82, 139, 255),
                Color.rgb(82, 139, 255),
            ),
            floatArrayOf(0f, .40f, .50f, .60f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.shader = shimmer
        startSweep(w)
    }

    private fun startSweep(width: Int) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(width.toFloat(), -width.toFloat()).apply {
            duration = 1_800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                shaderMatrix.setTranslate(it.animatedValue as Float, 0f)
                shimmer?.setLocalMatrix(shaderMatrix)
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
