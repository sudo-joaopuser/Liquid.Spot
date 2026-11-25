package app.liquidspot.xposed.features.sheets

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import app.liquidspot.core.Constants
import app.liquidspot.core.FeatureCatalog
import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.feature.Feature
import app.liquidspot.xposed.features.debug.UiDumper
import app.liquidspot.xposed.ui.glass.GlassSurface
import app.liquidspot.xposed.util.Logger
import kotlin.math.max

object GlassSheetsFeature : Feature {
    override val info = FeatureCatalog.GLASS_SHEETS

    override fun install(ctx: HookContext) {
        @SuppressLint("PrivateApi")
        val wmImpl = Class.forName("android.view.WindowManagerImpl")
        val addView = wmImpl.getDeclaredMethod("addView", View::class.java, ViewGroup.LayoutParams::class.java)

        ctx.hooks.before(addView) {
            val view = arg<View?>(0) ?: return@before
            val params = arg<ViewGroup.LayoutParams?>(1) as? WindowManager.LayoutParams ?: return@before
            val wm = thisObject as? WindowManager ?: return@before
            if (!isDialogWindow(params)) return@before
            if (UiDumper.enabled) {
                Logger.i("Folha/diálogo: tipo=${params.type} flags=0x${params.flags.toString(16)} título='${params.title}' view=${view.javaClass.name}")
            }
            if (!wm.isCrossWindowBlurEnabled) return@before
            val dm = view.resources.displayMetrics
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            params.blurBehindRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, BLUR_DP, dm).toInt()
            params.dimAmount = max(params.dimAmount, DIM)
        }

        ctx.hooks.after(addView) {
            val view = arg<View?>(0) ?: return@after
            val params = arg<ViewGroup.LayoutParams?>(1) as? WindowManager.LayoutParams ?: return@after
            if (!isDialogWindow(params)) return@after
            view.post { runCatching { styleViewSheet(view) }.onFailure { Logger.w("Folha: não foi possível aplicar o vidro", it) } }
        }
    }

    private fun isDialogWindow(p: WindowManager.LayoutParams): Boolean {
        val dims = p.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
        return when (p.type) {
            WindowManager.LayoutParams.TYPE_APPLICATION -> true
            in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW -> dims
            else -> false
        }
    }

    private fun styleViewSheet(root: View) {
        val id = root.resources.getIdentifier("design_bottom_sheet", "id", Constants.SPOTIFY_PACKAGE)
        if (id == 0) return
        val sheet = root.findViewById<View>(id) ?: return
        if (sheet.background is SheetGlassDrawable) return
        sheet.background = SheetGlassDrawable(sheet)
        UiDumper.dumpOnce(root, "folha do Spotify")
    }

    private const val BLUR_DP = 30f
    private const val DIM = 0.35f
}

private class SheetGlassDrawable(owner: View) : Drawable() {
    private val surface = GlassSurface(owner).apply { solidColor = 0xC71E1E1E.toInt() }
    private val rect = RectF()
    private val radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, owner.resources.displayMetrics)

    override fun draw(canvas: Canvas) {
        rect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom + radius)
        canvas.save()
        canvas.clipRect(bounds)
        surface.draw(canvas, rect, radius)
        canvas.restore()
    }

    override fun setTintList(tint: ColorStateList?) = Unit
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
