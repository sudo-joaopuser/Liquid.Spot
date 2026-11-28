package app.liquidspot.xposed.features.pages

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import app.liquidspot.core.GlassStyle
import app.liquidspot.xposed.ui.glass.BackdropSource
import app.liquidspot.xposed.ui.glass.GlassSurface
import kotlin.math.max

class BlurScrim(private val owner: View, backdrop: BackdropSource) : Drawable() {
    private val surface = GlassSurface(owner).apply {
        saturation = BlurBar.BAR_SATURATION
        matteColor = BlurBar.BAR_TINT
        this.backdrop = backdrop
        style = GlassStyle.MATTE
        shadowEnabled = false
    }
    private val rect = RectF()
    private val ownerLocation = IntArray(2)
    private val windowLocation = IntArray(2)
    private var alpha = 255

    override fun draw(canvas: Canvas) {
        if (alpha == 0) return
        val b = bounds
        owner.getLocationInWindow(ownerLocation)
        owner.rootView.getLocationInWindow(windowLocation)
        val visibleTop = max(b.top.toFloat(), (windowLocation[1] - ownerLocation[1]).toFloat())
        if (visibleTop >= b.bottom) return
        rect.set(b.left.toFloat(), visibleTop, b.right.toFloat(), b.bottom.toFloat())
        surface.draw(canvas, rect, 0f, strength)
    }

    override fun setAlpha(alpha: Int) {
        if (this.alpha == alpha) return
        this.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = alpha

    val strength: Float get() = (alpha / 255f * RAMP).coerceAtMost(1f)

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val RAMP = 3f
    }
}
