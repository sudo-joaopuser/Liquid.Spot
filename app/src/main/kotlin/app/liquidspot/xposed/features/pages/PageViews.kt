package app.liquidspot.xposed.features.pages

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.roundToInt

interface PageStyler {
    fun tryStart()
    fun applySettings()
    fun stop()
}

@SuppressLint("ViewConstructor")
class GlassPillView(context: Context, first: View, second: View) : FrameLayout(context) {
    init {
        addView(first, LayoutParams(0, LayoutParams.MATCH_PARENT))
        addView(second, LayoutParams(0, LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val half = MeasureSpec.makeMeasureSpec(measuredWidth / 2, MeasureSpec.EXACTLY)
        val height = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(half, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val half = (right - left) / 2
        for (i in 0 until childCount) getChildAt(i).layout(i * half, 0, (i + 1) * half, bottom - top)
    }
}

class RoundOutline(private val inset: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        val i = inset.roundToInt()
        outline.setRoundRect(i, i, view.width - i, view.height - i, (view.height - 2 * i) / 2f)
    }
}

class SizedIcon(private val inner: Drawable, private val size: Int) : Drawable() {
    override fun draw(canvas: Canvas) {
        inner.bounds = bounds
        inner.draw(canvas)
    }

    override fun getIntrinsicWidth() = size

    override fun getIntrinsicHeight() = size

    override fun setAlpha(alpha: Int) {
        inner.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        inner.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
