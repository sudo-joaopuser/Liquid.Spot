package app.liquidspot.xposed.features.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import app.liquidspot.xposed.media.PositionMath
import app.liquidspot.xposed.ui.glass.Spring

class LiquidSeekBar(context: Context) : View(context) {
    var onSeek: (Long) -> Unit = {}

    var durationMs = 0L
    private var positionMs = 0L
    private var dragging = false
    private var dragFraction = 0f

    private var thickness = 0f
    private val thicknessSpring = Spring(dampingRatio = 0.8f, stiffness = 600f) {
        thickness = it
        invalidate()
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        thicknessSpring.snapTo(dp(NORMAL_DP))
        timePaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        isClickable = true
    }

    fun preferredHeight(): Int = (dp(TOUCH_DP) + dp(TIMES_DP)).toInt()

    fun setPosition(ms: Long) {
        if (dragging || ms == positionMs) return
        positionMs = ms
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cy = dp(TOUCH_DP) / 2f
        val half = thickness / 2f
        val fraction = when {
            dragging -> dragFraction
            durationMs > 0 -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            else -> 0f
        }
        canvas.drawRoundRect(0f, cy - half, w, cy + half, half, half, trackPaint)
        if (fraction > 0f) canvas.drawRoundRect(0f, cy - half, (w * fraction).coerceAtLeast(thickness), cy + half, half, half, progressPaint)

        val shown = if (dragging) (dragFraction * durationMs).toLong() else positionMs
        val baseline = dp(TOUCH_DP) + dp(4f) + timePaint.textSize * 0.8f
        canvas.drawText(PositionMath.format(shown), 0f, baseline, timePaint)
        val remaining = PositionMath.format((durationMs - shown).coerceAtLeast(0), negative = true)
        canvas.drawText(remaining, w - timePaint.measureText(remaining), baseline, timePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0) return false
        val fraction = (event.x / width).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                dragFraction = fraction
                thicknessSpring.animateTo(dp(DRAGGING_DP))
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                dragFraction = fraction
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                dragFraction = fraction
                positionMs = (fraction * durationMs).toLong()
                dragging = false
                thicknessSpring.animateTo(dp(NORMAL_DP))
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onSeek(positionMs)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                thicknessSpring.animateTo(dp(NORMAL_DP))
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        thicknessSpring.stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val NORMAL_DP = 6f
        const val DRAGGING_DP = 10f
        const val TOUCH_DP = 28f
        const val TIMES_DP = 22f
    }
}
