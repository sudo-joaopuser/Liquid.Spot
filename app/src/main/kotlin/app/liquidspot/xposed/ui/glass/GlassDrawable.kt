package app.liquidspot.xposed.ui.glass

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

class GlassDrawable(owner: View) : Drawable() {
    val surface = GlassSurface(owner)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        surface.draw(canvas, rect, rect.height() / 2f, alpha / 255f)
    }

    private var alpha = 255

    override fun setAlpha(alpha: Int) {
        this.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = alpha

    override fun setTintList(tint: ColorStateList?) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
