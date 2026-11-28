package app.liquidspot.xposed.features.pages

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max

class TrackDividers(val list: ViewGroup) : Drawable() {
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, list.resources.displayMetrics)

    private val paint = Paint().apply {
        color = LINE_COLOR
        strokeWidth = max(1f, dp(LINE_DP))
    }
    private val minRow = dp(MIN_ROW_DP)
    private val maxRow = dp(MAX_ROW_DP)
    private val tolerance = dp(SAME_HEIGHT_DP)
    private val inset = dp(INSET_DP)

    override fun draw(canvas: Canvas) {
        val count = list.childCount
        if (count < 2) return
        val right = list.width - inset
        for (i in 0 until count - 1) {
            val a = list.getChildAt(i)
            val b = list.getChildAt(i + 1)
            if (!isRow(a) || !isRow(b)) continue
            if (a.javaClass != b.javaClass || abs(a.height - b.height) > tolerance) continue
            val y = a.bottom + a.translationY
            if (abs(b.top + b.translationY - y) > tolerance) continue
            canvas.drawLine(inset, y, right, y, paint)
        }
    }

    private fun isRow(view: View): Boolean =
        view.visibility == View.VISIBLE && view.alpha > 0.5f && view.height in minRow.toInt()..maxRow.toInt()

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val LINE_COLOR = 0x26FFFFFF
        const val LINE_DP = 0.6f
        const val MIN_ROW_DP = 44f
        const val MAX_ROW_DP = 96f
        const val SAME_HEIGHT_DP = 6f
        const val INSET_DP = 16f
    }
}
