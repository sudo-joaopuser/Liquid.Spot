package app.liquidspot.xposed.features.pages

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ImageView
import kotlin.math.max

class PageHero(private val host: View, private val artwork: ImageView) : Drawable() {
    private var image: Drawable? = null
    private var signature = 0
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint()
    private val topPaint = Paint()
    private val src = Rect()
    private val dst = RectF()
    private var shaderSize = -1
    private val background = 0xFF000000.toInt()

    fun refresh() {
        val current = artwork.drawable?.takeUnless(::isPlaceholder)
        val next = signatureOf(current)
        if (current === image && next == signature) return
        image = current
        signature = next
        invalidateSelf()
        host.invalidate()
    }

    private fun signatureOf(d: Drawable?): Int {
        d ?: return 0
        var h = System.identityHashCode(d) * 31 + d.intrinsicWidth
        if (d is LayerDrawable) {
            h = h * 31 + d.numberOfLayers
            for (i in 0 until d.numberOfLayers) h = h * 31 + System.identityHashCode(d.getDrawable(i))
        }
        return h
    }

    private fun isPlaceholder(d: Drawable): Boolean = when (d) {
        is ColorDrawable -> true
        is LayerDrawable -> (0 until d.numberOfLayers).all { isPlaceholder(d.getDrawable(it)) }
        else -> false
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        if (w <= 0f) return
        canvas.drawColor(background)
        val size = w
        val image = image ?: return
        canvas.save()
        canvas.clipRect(0f, 0f, w, size)
        val bitmap = (image as? BitmapDrawable)?.bitmap
        if (bitmap != null && !bitmap.isRecycled) {
            centerCrop(bitmap.width, bitmap.height, w, size)
            canvas.drawBitmap(bitmap, src, dst, bitmapPaint)
        } else {
            val iw = image.intrinsicWidth.takeIf { it > 0 } ?: size.toInt()
            val ih = image.intrinsicHeight.takeIf { it > 0 } ?: size.toInt()
            val scale = max(w / iw, size / ih)
            val dw = iw * scale
            val dh = ih * scale
            val current = image.bounds
            if (!current.isEmpty) {
                canvas.translate((w - dw) / 2f, (size - dh) / 2f)
                canvas.scale(dw / current.width(), dh / current.height())
                canvas.translate(-current.left.toFloat(), -current.top.toFloat())
                image.draw(canvas)
            }
        }
        canvas.restore()
        ensureShaders(size)
        canvas.drawRect(0f, 0f, w, size * TOP_SHADE, topPaint)
        canvas.drawRect(0f, size * FADE_START, w, size + 1f, fadePaint)
    }

    private fun centerCrop(bw: Int, bh: Int, w: Float, h: Float) {
        val scale = max(w / bw, h / bh)
        val cw = (w / scale).toInt()
        val ch = (h / scale).toInt()
        val left = (bw - cw) / 2
        val top = (bh - ch) / 2
        src.set(left, top, left + cw, top + ch)
        dst.set(0f, 0f, w, h)
    }

    private fun ensureShaders(size: Float) {
        val key = size.toInt() * 31 + background
        if (key == shaderSize) return
        shaderSize = key
        val bg = background
        val clear = bg and 0x00FFFFFF
        fadePaint.shader = LinearGradient(
            0f, size * FADE_START, 0f, size,
            intArrayOf(clear, (bg and 0x00FFFFFF) or 0x99000000.toInt(), bg),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        topPaint.shader = LinearGradient(0f, 0f, 0f, size * TOP_SHADE, 0x59000000, 0x00000000, Shader.TileMode.CLAMP)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        const val FADE_START = 0.42f
        const val TOP_SHADE = 0.22f
    }
}
