package app.liquidspot.xposed.features.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.ceil

class LyricTicker(context: Context) : View(context) {
    var reduceMotion = false

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, resources.displayMetrics)
    }
    private var current = ""
    private var previous = ""
    private var progress = 1f
    private var animator: ValueAnimator? = null

    val text: String get() = current

    fun lineHeight(): Int = ceil(paint.fontMetrics.let { it.descent - it.ascent }).toInt()

    fun setLine(value: String) {
        if (value == current) return
        animator?.cancel()
        previous = current
        current = value
        if (reduceMotion || previous.isEmpty() || !isShown) {
            progress = 1f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        progress = 1f
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), lineHeight())
    }

    override fun onDraw(canvas: Canvas) {
        val baseline = -paint.fontMetrics.ascent
        val travel = height * TRAVEL
        if (progress < 1f && previous.isNotEmpty()) drawLine(canvas, previous, baseline - travel * progress, 1f - progress)
        drawLine(canvas, current, baseline + travel * (1f - progress), progress)
    }

    private fun drawLine(canvas: Canvas, value: String, y: Float, alpha: Float) {
        if (value.isEmpty() || alpha <= 0f) return
        val line = TextUtils.ellipsize(value, paint, width.toFloat(), TextUtils.TruncateAt.END)
        paint.alpha = (alpha * 255).toInt()
        canvas.drawText(line, 0, line.length, 0f, y, paint)
    }

    private companion object {
        const val TEXT_SP = 17f
        const val DURATION_MS = 360L
        const val TRAVEL = 0.9f
    }
}
