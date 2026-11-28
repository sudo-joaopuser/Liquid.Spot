package app.liquidspot.xposed.features.pages

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.RenderNode
import android.view.View
import app.liquidspot.core.GlassStyle
import app.liquidspot.xposed.ui.glass.BackdropSource
import app.liquidspot.xposed.ui.glass.GlassSurface

@SuppressLint("ViewConstructor")
class BlurBar(context: Context, backdrop: BackdropSource) : View(context) {
    private val surface = GlassSurface(this).apply {
        this.backdrop = backdrop
        style = GlassStyle.MATTE
        shadowEnabled = false
        saturation = BAR_SATURATION
        matteColor = BAR_TINT
    }
    private val rect = RectF()
    private val location = IntArray(2)

    private var toolbar: RenderNode? = null
    private var toolbarX = 0f
    private var toolbarY = 0f
    private var play: RenderNode? = null
    private var playX = 0f
    private var playY = 0f

    fun update(toolbar: RenderNode?, toolbarX: Float, toolbarY: Float, play: RenderNode?, playX: Float, playY: Float) {
        if (toolbar === this.toolbar && toolbarX == this.toolbarX && toolbarY == this.toolbarY &&
            play === this.play && playX == this.playX && playY == this.playY
        ) return
        this.toolbar = toolbar
        this.toolbarX = toolbarX
        this.toolbarY = toolbarY
        this.play = play
        this.playX = playX
        this.playY = playY
        invalidate()
    }

    var progress = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return
        getLocationInWindow(location)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        surface.draw(canvas, rect, 0f, progress)
        if (!canvas.isHardwareAccelerated) return
        ComposeHooks.beginOverlay()
        try {
            toolbar?.let { drawNode(canvas, it, toolbarX, toolbarY, clip = true) }
            play?.let { drawNode(canvas, it, playX, playY, clip = false) }
        } finally {
            ComposeHooks.endOverlay()
        }
    }

    private fun drawNode(canvas: Canvas, node: RenderNode, x: Float, y: Float, clip: Boolean) {
        if (!node.hasDisplayList()) return
        val save = canvas.save()
        if (clip) canvas.clipRect(rect)
        canvas.translate(x - location[0] - node.left - node.translationX, y - location[1] - node.top - node.translationY)
        canvas.drawRenderNode(node)
        canvas.restoreToCount(save)
    }

    companion object {
        const val BAR_SATURATION = 1f
        const val BAR_TINT = 0x8C000000.toInt()
    }
}
