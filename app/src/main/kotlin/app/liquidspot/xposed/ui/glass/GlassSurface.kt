package app.liquidspot.xposed.ui.glass

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.TypedValue
import android.view.View
import androidx.annotation.RequiresApi
import app.liquidspot.core.GlassStyle
import app.liquidspot.xposed.theme.SpotifyTheme
import app.liquidspot.xposed.util.Logger

class GlassSurface(private val owner: View) {
    var style: GlassStyle = GlassStyle.DEFAULT
        set(value) {
            if (field != value) {
                field = value
                cachedEffectKey = null
            }
        }

    var backdrop: BackdropSource? = null

    var tintColor: Int = if (SpotifyTheme.amoled) 0x80000000.toInt() else 0x66121212

    var solidColor: Int = if (SpotifyTheme.amoled) 0xFF0A0A0A.toInt() else 0xFF242424.toInt()

    var matteColor: Int = if (SpotifyTheme.amoled) 0xB3000000.toInt() else 0xB3121212.toInt()

    var shadowEnabled = true

    private val node = RenderNode("LiquidSpotGlass")
    private val path = Path()
    private val ownerLocation = IntArray(2)
    private val sourceLocation = IntArray(2)
    private var cachedEffectKey: String? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, owner.resources.displayMetrics)

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x01000000
        setShadowLayer(dp(24f) / 2f, 0f, dp(4f), 0x1A000000)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.5f)
        color = 0x1AFFFFFF
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        blendMode = BlendMode.PLUS
    }
    private val highlightShader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shadersBroken) {
            runCatching { RuntimeShader(HIGHLIGHT_SHADER) }
                .onFailure { markShadersBroken(it) }
                .getOrNull()
        } else {
            null
        }

    private var backdropBroken = false

    val effectiveStyle: GlassStyle
        get() = when {
            style == GlassStyle.SOLID -> GlassStyle.SOLID
            backdrop == null || backdropBroken -> GlassStyle.SOLID
            style == GlassStyle.LIQUID && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || shadersBroken) -> GlassStyle.FROSTED
            else -> style
        }

    fun draw(canvas: Canvas, rect: RectF, radius: Float, alpha: Float = 1f) {
        if (rect.isEmpty || alpha <= 0f) return
        val source = backdrop
        if (source?.isRecording == true) return

        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)

        val layer = if (alpha < 1f) canvas.saveLayerAlpha(rect.left - dp(32f), rect.top - dp(32f), rect.right + dp(32f), rect.bottom + dp(32f), (alpha * 255).toInt()) else -1

        val style = effectiveStyle
        val plain = style == GlassStyle.MATTE
        if (!plain && shadowEnabled) canvas.drawPath(path, shadowPaint)

        when (style) {
            GlassStyle.SOLID -> {
                fillPaint.color = solidColor
                canvas.drawPath(path, fillPaint)
            }
            else -> {
                val ok = try {
                    drawBackdrop(canvas, source!!, rect, radius, style)
                    true
                } catch (t: Throwable) {
                    Logger.e("Vidro: falha ao desenhar o fundo, usando cor sólida", t)
                    backdropBroken = true
                    false
                }
                fillPaint.color = if (!ok) solidColor else if (plain) matteColor else tintColor
                canvas.drawPath(path, fillPaint)
            }
        }

        if (!plain) {
            if (!shadersBroken && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { drawHighlight(canvas, rect, radius) }.onFailure { markShadersBroken(it) }
            }
            canvas.drawPath(path, edgePaint)
        }

        if (layer >= 0) canvas.restoreToCount(layer)
    }

    private fun drawBackdrop(canvas: Canvas, source: BackdropSource, rect: RectF, radius: Float, style: GlassStyle) {
        val blurPx = dp(if (style == GlassStyle.FROSTED || style == GlassStyle.MATTE) FROST_BLUR_DP else BLUR_DP)
        val pad = blurPx * 2f
        val w = rect.width()
        val h = rect.height()

        owner.getLocationInWindow(ownerLocation)
        source.locationInWindow(sourceLocation)
        val dx = ownerLocation[0] - sourceLocation[0] + rect.left
        val dy = ownerLocation[1] - sourceLocation[1] + rect.top

        node.setPosition(0, 0, (w + 2 * pad).toInt(), (h + 2 * pad).toInt())
        val rc = node.beginRecording()
        try {
            rc.translate(pad - dx, pad - dy)
            rc.drawRenderNode(source.node)
        } finally {
            node.endRecording()
        }
        node.setRenderEffect(effectFor(style, w, h, radius, pad, blurPx))

        canvas.save()
        canvas.clipPath(path)
        canvas.translate(rect.left - pad, rect.top - pad)
        canvas.drawRenderNode(node)
        canvas.restore()
    }

    private fun effectFor(style: GlassStyle, w: Float, h: Float, radius: Float, pad: Float, blurPx: Float): RenderEffect {
        val key = "$style:$w:$h:$radius:$pad"
        if (key == cachedEffectKey) return cachedEffect!!
        val saturate = RenderEffect.createColorFilterEffect(SATURATION_FILTER)
        var effect = RenderEffect.createBlurEffect(blurPx, blurPx, saturate, Shader.TileMode.CLAMP)
        if (style == GlassStyle.LIQUID && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shadersBroken) {
            runCatching {
                val lensHeight = dp(LENS_DP)
                val shader = RuntimeShader(LENS_WITH_DISPERSION_SHADER).apply {
                    setFloatUniform("size", w, h)
                    setFloatUniform("offset", -pad, -pad)
                    setFloatUniform("cornerRadii", radius, radius, radius, radius)
                    setFloatUniform("refractionHeight", lensHeight)
                    setFloatUniform("refractionAmount", -dp(LENS_DP))
                    setFloatUniform("depthEffect", 1f)
                    setFloatUniform("chromaticAberration", 1f)
                }
                RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), effect)
            }.onSuccess { effect = it }.onFailure { markShadersBroken(it) }
        }
        cachedEffect = effect
        cachedEffectKey = key
        return effect
    }

    private var cachedEffect: RenderEffect? = null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawHighlight(canvas: Canvas, rect: RectF, radius: Float) {
        val shader = highlightShader
        if (shader == null || effectiveStyle == GlassStyle.SOLID) return
        shader.setFloatUniform("size", rect.width(), rect.height())
        shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
        shader.setColorUniform("color", android.graphics.Color.WHITE)
        shader.setFloatUniform("angle", Math.toRadians(45.0).toFloat())
        shader.setFloatUniform("falloff", 1f)
        highlightPaint.shader = shader
        highlightPaint.alpha = 128
        val inset = highlightPaint.strokeWidth / 2f
        canvas.save()
        canvas.translate(rect.left, rect.top)
        canvas.drawRoundRect(inset, inset, rect.width() - inset, rect.height() - inset, radius, radius, highlightPaint)
        canvas.restore()
    }

    companion object {
        @Volatile
        var shadersBroken = false
            private set

        private fun markShadersBroken(t: Throwable) {
            if (!shadersBroken) Logger.e("Vidro: shaders AGSL indisponíveis, usando o modo Fosco", t)
            shadersBroken = true
        }

        const val BLUR_DP = 8f
        const val FROST_BLUR_DP = 16f
        const val LENS_DP = 24f

        val SATURATION_FILTER = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) })
    }
}
