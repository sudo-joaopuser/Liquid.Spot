package app.liquidspot.xposed.features.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.liquidspot.xposed.theme.SpotifyTheme
import app.liquidspot.xposed.ui.PlayerIcon
import app.liquidspot.xposed.ui.glass.BackdropSource
import app.liquidspot.xposed.ui.glass.GlassSurface
import app.liquidspot.xposed.ui.glass.Spring
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class IconButton(
    context: Context,
    icon: PlayerIcon,
    private val iconSizeDp: Float,
    glass: Boolean = false,
) : View(context) {
    var icon: PlayerIcon = icon
        set(value) {
            if (field != value) {
                field = value
                drawable = value.load(context)
                invalidate()
            }
        }

    private var drawable: Drawable = icon.load(context)

    var active = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var showActiveDot = false

    var dimmed = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var ringProgress: Float? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringRect = RectF()

    var tint = WHITE

    val glassSurface: GlassSurface? = if (glass) GlassSurface(this).apply { shadowEnabled = false } else null

    var backdrop: BackdropSource?
        get() = glassSurface?.backdrop
        set(value) {
            glassSurface?.backdrop?.removeClient(this)
            glassSurface?.backdrop = value
            if (isAttachedToWindow) value?.addClient(this)
        }

    private var pressScale = 1f
    private val pressSpring = Spring(dampingRatio = 0.6f, stiffness = 500f) {
        pressScale = it
        invalidate()
    }.also { it.snapTo(1f) }

    private val rect = RectF()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var tintAccent = false

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        backdrop?.addClient(this)
    }

    override fun onDetachedFromWindow() {
        backdrop?.removeClient(this)
        pressSpring.stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.save()
        canvas.scale(pressScale, pressScale, cx, cy)

        glassSurface?.let { surface ->
            val r = minOf(width, height) / 2f
            rect.set(cx - r, cy - r, cx + r, cy + r)
            surface.draw(canvas, rect, r, if (dimmed) DIMMED_GLASS else 1f)
            if (surface.backdrop?.isRecording == true) {
                canvas.restore()
                return
            }
        }

        val half = dp(iconSizeDp) / 2f
        drawable.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
        drawable.setTint(if (active || tintAccent) SpotifyTheme.accent else tint)
        drawable.alpha = if (isEnabled && !dimmed) 255 else 90
        drawable.draw(canvas)

        ringProgress?.let { drawRing(canvas, cx, cy, it) }

        if (active && showActiveDot) {
            dotPaint.color = SpotifyTheme.accent
            canvas.drawCircle(cx, cy + half + dp(6f), dp(2f), dotPaint)
        }
        canvas.restore()
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, progress: Float) {
        val stroke = dp(RING_DP)
        val r = minOf(width, height) / 2f - stroke / 2f
        ringRect.set(cx - r, cy - r, cx + r, cy + r)
        ringPaint.strokeWidth = stroke
        ringPaint.color = RING_TRACK
        canvas.drawOval(ringRect, ringPaint)
        if (progress <= 0f) return
        ringPaint.color = SpotifyTheme.accent
        canvas.drawArc(ringRect, -90f, 360f * progress.coerceAtMost(1f), false, ringPaint)
    }

    private var longPressed = false
    private val longPress = Runnable {
        if (isLongClickable) {
            longPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            performLongClick()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                pressSpring.animateTo(PRESSED_SCALE)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                pressSpring.animateTo(1f)
                if (!longPressed && event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                pressSpring.animateTo(1f)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val PRESSED_SCALE = 0.86f
        const val DIMMED_GLASS = 0.45f
        const val RING_DP = 2.5f
        const val RING_TRACK = 0x33FFFFFF
    }
}
