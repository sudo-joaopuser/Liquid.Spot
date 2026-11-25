package app.liquidspot.xposed.features.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.TypedValue
import android.widget.LinearLayout
import app.liquidspot.xposed.ui.glass.BackdropSource
import app.liquidspot.xposed.ui.glass.GlassSurface
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class GlassPanel(context: Context, backdrop: BackdropSource) : LinearLayout(context) {
    private val surface = GlassSurface(this).also {
        it.backdrop = backdrop
        it.tintColor = 0xB3121212.toInt()
    }
    private val rect = RectF()
    private val radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        isClickable = true
        val p = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics).roundToInt()
        setPadding(p, p, p, p)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        surface.backdrop?.addClient(this)
    }

    override fun onDetachedFromWindow() {
        surface.backdrop?.removeClient(this)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        surface.draw(canvas, rect, radius)
    }
}
