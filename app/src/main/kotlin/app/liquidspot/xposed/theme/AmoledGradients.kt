package app.liquidspot.xposed.theme

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import app.liquidspot.core.Constants
import app.liquidspot.xposed.hook.Hooks
import app.liquidspot.xposed.hook.method
import app.liquidspot.xposed.util.Logger

internal object AmoledGradients {
    private const val VISIBLE_ALPHA = 0.1f
    private const val MIN_SATURATION = 0.2f
    private const val MIN_TINT_VALUE = 0.12f
    private const val MIN_GRAY_VALUE = 0.2f
    private const val DARK_END_LUMINANCE = 40f
    private const val OPAQUE = 0xFF000000.toInt()
    private const val RGB = 0x00FFFFFF
    private val HEADER_BACKGROUND_IDS = listOf("cwp_header_artwork_background", "artwork_background")

    @Volatile
    private var headerBackgroundIds: IntArray? = null

    fun install(hooks: Hooks) {
        installHeaderFade(hooks)
        installHeaderBackgrounds(hooks)
    }

    private fun installHeaderFade(hooks: Hooks) {
        val float = Float::class.javaPrimitiveType!!
        val constructor = runCatching {
            LinearGradient::class.java.getDeclaredConstructor(
                float, float, float, float, LongArray::class.java, FloatArray::class.java, Shader.TileMode::class.java,
            )
        }.getOrElse {
            Logger.w("AMOLED: construtor de degradê do Compose não encontrado", it)
            return
        }
        hooks.before(constructor) {
            if (!SpotifyTheme.amoled) return@before
            if (arg<Float>(0) != arg<Float>(2)) return@before
            val colors = arg<LongArray?>(4) ?: return@before
            args[4] = if (isHeaderFade(colors)) {
                LongArray(colors.size) { Color.pack(0f, 0f, 0f, Color.alpha(colors[it])) }
            } else {
                mapBases(colors) ?: return@before
            }
        }
        val intConstructor = runCatching {
            LinearGradient::class.java.getDeclaredConstructor(
                float, float, float, float, IntArray::class.java, FloatArray::class.java, Shader.TileMode::class.java,
            )
        }.getOrNull() ?: return
        hooks.before(intConstructor) {
            if (!SpotifyTheme.amoled) return@before
            val colors = arg<IntArray?>(4) ?: return@before
            val mapped = IntArray(colors.size) { mapBase(colors[it]) }
            if (!mapped.contentEquals(colors)) args[4] = mapped
        }
    }

    private fun mapBases(colors: LongArray): LongArray? {
        var changed = false
        val mapped = LongArray(colors.size) { i ->
            val color = colors[i]
            if (!Color.isSrgb(color)) return@LongArray color
            val argb = Color.toArgb(color)
            val base = mapBase(argb)
            if (base == argb) color else Color.pack(base).also { changed = true }
        }
        return if (changed) mapped else null
    }

    private fun mapBase(color: Int): Int {
        val opaque = color or OPAQUE
        val mapped = SpotifyTheme.map(opaque)
        return if (mapped == opaque) color else (color and OPAQUE) or (mapped and RGB)
    }

    private fun installHeaderBackgrounds(hooks: Hooks) {
        hooks.before(View::class.java.method("setBackgroundDrawable", Drawable::class.java)) {
            if (!SpotifyTheme.amoled || args[0] == null) return@before
            val view = thisObject as? View ?: return@before
            if (view.id != View.NO_ID && view.id in headerIds(view)) args[0] = null
        }
    }

    private fun headerIds(view: View): IntArray = headerBackgroundIds ?: HEADER_BACKGROUND_IDS
        .map { view.resources.getIdentifier(it, "id", Constants.SPOTIFY_PACKAGE) }
        .filter { it != 0 }
        .toIntArray()
        .also { headerBackgroundIds = it }

    private fun isHeaderFade(colors: LongArray): Boolean {
        if (colors.size < 2) return false
        val end = Color.toArgb(colors.last())
        val fadesOut = Color.alpha(end) < VISIBLE_ALPHA * 255 || luminance(end) < DARK_END_LUMINANCE
        return fadesOut && colors.any { color ->
            if (Color.alpha(color) < VISIBLE_ALPHA) return@any false
            val h = FloatArray(3).also { Color.colorToHSV(Color.toArgb(color), it) }
            (h[1] > MIN_SATURATION && h[2] > MIN_TINT_VALUE) || h[2] > MIN_GRAY_VALUE
        }
    }

    private fun luminance(color: Int): Float =
        0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)
}
