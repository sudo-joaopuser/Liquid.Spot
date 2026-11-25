package app.liquidspot.xposed.features.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.max

class ArtworkBackdropView(context: Context) : View(context) {
    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var fade = 1f
    private var fadeAnimator: ValueAnimator? = null

    private val node = RenderNode("LiquidSpotArtworkBackdrop")
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val scrim = Paint()
    private var effectKey = 0

    var isRecordingBackdrop: () -> Boolean = { false }

    private var videoFrame: Bitmap? = null

    fun setVideoFrame(bitmap: Bitmap?) {
        videoFrame = bitmap
        if (artAlpha < VIDEO_GLASS_THRESHOLD) invalidate()
    }

    var artAlpha = 1f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val nextNode = RenderNode("LiquidSpotArtworkBackdropNext")
    private var swipeBitmap: Bitmap? = null
    private var swipeOffset = 0f
    private var swipeDirection = 0
    private var fadeZoom = true
    private var blurEffect: RenderEffect? = null

    fun setSwipe(bitmap: Bitmap?, offset: Float, direction: Int) {
        swipeBitmap = bitmap
        swipeOffset = offset
        swipeDirection = direction
        invalidate()
    }

    fun clearSwipe() {
        if (swipeBitmap == null && swipeOffset == 0f) return
        swipeBitmap = null
        swipeOffset = 0f
        swipeDirection = 0
        invalidate()
    }

    fun commitSwipe() {
        val bitmap = swipeBitmap
        clearSwipe()
        if (bitmap == null) return
        fadeAnimator?.cancel()
        previous = null
        current = bitmap
        fade = 1f
        invalidate()
    }

    fun setArtwork(bitmap: Bitmap?, animate: Boolean, zoom: Boolean = true) {
        if (bitmap === current) return
        fadeZoom = zoom
        previous = current
        current = bitmap
        fadeAnimator?.cancel()
        if (animate && previous != null) {
            fade = 0f
            fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FADE_MS
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scrim.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x59000000, 0x40000000, 0x8C000000.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val pad = dp(BLUR_DP).toInt() * 2
        node.setPosition(-pad, -pad, w + pad, h + pad)
        val key = w * 31 + h
        if (key != effectKey) {
            effectKey = key
            val blur = dp(BLUR_DP)
            blurEffect = RenderEffect.createBlurEffect(
                blur, blur,
                RenderEffect.createColorFilterEffect(SATURATE),
                Shader.TileMode.CLAMP,
            )
            node.setRenderEffect(blurEffect)
        }
        val frame = videoFrame
        if (frame != null && artAlpha < VIDEO_GLASS_THRESHOLD && isRecordingBackdrop()) {
            drawCropped(canvas, frame, w, h, 255)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), scrim)
            return
        }
        val rc = node.beginRecording()
        try {
            rc.drawColor(FALLBACK_COLOR)
            previous?.let { drawCropped(rc, it, w + 2 * pad, h + 2 * pad, 255) }
            current?.let { drawCropped(rc, it, w + 2 * pad, h + 2 * pad, (fade * 255).toInt(), if (fadeZoom) 1f + FADE_ZOOM * (1f - fade) else 1f) }
        } finally {
            node.endRecording()
        }
        val alpha = if (isRecordingBackdrop()) 1f else artAlpha
        node.setAlpha(alpha)
        val swiping = swipeBitmap != null && swipeDirection != 0
        if (alpha > 0f) {
            canvas.save()
            if (swiping) canvas.clipRect(swipeOffset, 0f, swipeOffset + w, h.toFloat())
            canvas.translate(swipeOffset, 0f)
            canvas.drawRenderNode(node)
            canvas.restore()
        }
        val next = swipeBitmap
        if (next != null && swipeDirection != 0) {
            nextNode.setPosition(-pad, -pad, w + pad, h + pad)
            nextNode.setRenderEffect(blurEffect)
            val nrc = nextNode.beginRecording()
            try {
                nrc.drawColor(FALLBACK_COLOR)
                drawCropped(nrc, next, w + 2 * pad, h + 2 * pad, 255)
            } finally {
                nextNode.endRecording()
            }
            val nextLeft = swipeOffset + swipeDirection * w
            canvas.save()
            canvas.clipRect(nextLeft, 0f, nextLeft + w, h.toFloat())
            canvas.translate(nextLeft, 0f)
            canvas.drawRenderNode(nextNode)
            canvas.restore()
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), scrim)
    }

    private fun drawCropped(canvas: Canvas, bitmap: Bitmap, w: Int, h: Int, alpha: Int, zoom: Float = 1f) {
        val scale = max(w / bitmap.width.toFloat(), h / bitmap.height.toFloat()) * zoom
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate((w - bitmap.width * scale) / 2f, (h - bitmap.height * scale) / 2f)
        paint.alpha = alpha
        canvas.drawBitmap(bitmap, matrix, paint)
    }

    private companion object {
        const val BLUR_DP = 80f
        const val FADE_MS = 650L
        const val FADE_ZOOM = 0.12f
        const val VIDEO_GLASS_THRESHOLD = 0.5f
        const val FALLBACK_COLOR = 0xFF2A2A2A.toInt()
        val SATURATE = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.4f) })
    }
}
