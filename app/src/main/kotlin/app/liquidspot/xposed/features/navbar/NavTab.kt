package app.liquidspot.xposed.features.navbar

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import app.liquidspot.core.Constants

enum class NavTab(
    val titleRes: String,
    val iconRes: String,
    val activeIconRes: String,
) {
    HOME("navigationbar_musicappitems_home_title", "encore_icon_home_24", "encore_icon_home_active_24"),
    SEARCH("navigationbar_musicappitems_search_title", "encore_icon_search_24", "encore_icon_search_active_24"),
    LIBRARY("navigationbar_musicappitems_yourlibrary_title", "ic_eis_your_library", "encore_icon_collection_active_24"),
    CREATE("navigationbar_musicappitems_create_title", "encore_icon_plus_alt_24", "encore_icon_plus_alt_active_24"),
    PREMIUM("", "encore_icon_spotify_logo_24", "encore_icon_spotify_logo_24"),
    ;

    fun titleIds(res: Resources): List<Int> {
        val names = if (this == LIBRARY) listOf(titleRes, "navigationbar_musicappitems_library_title") else listOf(titleRes)
        return names.filter { it.isNotEmpty() }.map { res.getIdentifier(it, "string", Constants.SPOTIFY_PACKAGE) }.filter { it != 0 }
    }

    fun icon(context: Context, active: Boolean): Drawable {
        val res = context.resources
        val name = if (active) activeIconRes else iconRes
        val id = res.getIdentifier(name, "drawable", Constants.SPOTIFY_PACKAGE)
        val drawable = if (id != 0) runCatching { res.getDrawable(id, context.theme) }.getOrNull() else null
        return (drawable ?: PathIcon(this, active)).mutate()
    }
}

private class PathIcon(private val tab: NavTab, private val active: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = if (active) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        path.reset()
        when (tab) {
            NavTab.HOME -> {
                path.moveTo(4f, 10f); path.lineTo(12f, 3.5f); path.lineTo(20f, 10f)
                path.lineTo(20f, 20f); path.lineTo(14.5f, 20f); path.lineTo(14.5f, 14f)
                path.lineTo(9.5f, 14f); path.lineTo(9.5f, 20f); path.lineTo(4f, 20f); path.close()
                canvas.drawPath(path, paint)
            }
            NavTab.SEARCH -> {
                val stroke = Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = if (active) 3f else 2f }
                canvas.drawCircle(10.5f, 10.5f, 6.5f, stroke)
                canvas.drawLine(15.5f, 15.5f, 20.5f, 20.5f, stroke)
            }
            NavTab.LIBRARY -> {
                val stroke = Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = if (active) 3f else 2f }
                canvas.drawLine(5f, 4f, 5f, 20f, stroke)
                canvas.drawLine(10f, 4f, 10f, 20f, stroke)
                canvas.drawLine(14f, 4.5f, 19.5f, 19.5f, stroke)
            }
            NavTab.CREATE -> {
                val stroke = Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = if (active) 3f else 2f }
                canvas.drawLine(12f, 5f, 12f, 19f, stroke)
                canvas.drawLine(5f, 12f, 19f, 12f, stroke)
            }
            NavTab.PREMIUM -> canvas.drawCircle(12f, 12f, 8f, paint)
        }
        canvas.restore()
    }

    override fun setTint(tintColor: Int) {
        paint.color = tintColor
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 24
    override fun getIntrinsicHeight() = 24
}
