package app.liquidspot.xposed.features.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.max

class ArtworkView(context: Context) : View(context) {
    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var fade = 1f
    private var fadeAnimator: ValueAnimator? = null

    var cornerRadius = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidateOutline()
                invalidate()
            }
        }

    var fadeBottom = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
    private var fadeKey = 0f
    private var fadeHeight = 0
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val matrix = Matrix()
    private val clip = Path()
    private val rect = RectF()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true
        elevation = dp(18f)
        outlineAmbientShadowColor = 0xFF000000.toInt()
        outlineSpotShadowColor = 0xFF000000.toInt()
    }

    val bitmap: Bitmap? get() = current

    fun showFrom(other: ArtworkView) {
        fadeAnimator?.cancel()
        previous = null
        current = other.bitmap
        fade = 1f
        invalidate()
    }

    fun setArtwork(bitmap: Bitmap?, animate: Boolean) {
        if (bitmap === current) return
        previous = current
        current = bitmap
        fadeAnimator?.cancel()
        if (animate && previous != null) {
            fade = 0f
            fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300
                addUpdateListener {
                    fade = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            fade = 1f
            previous = null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        val layer = if (fadeBottom > 0f) canvas.saveLayer(rect, null) else canvas.save()
        canvas.clipPath(clip)
        if (current == null && previous == null) canvas.drawRect(rect, placeholder)
        previous?.let { draw(canvas, it, 255) }
        current?.let { draw(canvas, it, (fade * 255).toInt()) }
        if (fadeBottom > 0f) {
            if (fadeKey != fadeBottom || fadeHeight != height) {
                fadeKey = fadeBottom
                fadeHeight = height
                fadePaint.shader = android.graphics.LinearGradient(
                    0f, h * (1f - fadeBottom), 0f, h,
                    0xFF000000.toInt(), 0x00000000, android.graphics.Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(rect, fadePaint)
        }
        canvas.restoreToCount(layer)
    }

    private fun draw(canvas: Canvas, bitmap: Bitmap, alpha: Int) {
        val scale = max(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate((width - bitmap.width * scale) / 2f, (height - bitmap.height * scale) / 2f)
        paint.alpha = alpha
        canvas.drawBitmap(bitmap, matrix, paint)
    }
}
