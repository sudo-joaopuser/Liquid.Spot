package app.liquidspot.xposed.theme

import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import app.liquidspot.core.PrefKeys
import app.liquidspot.core.ThemePalette
import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.hook.method
import app.liquidspot.xposed.util.Logger
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import org.lsposed.hiddenapibypass.HiddenApiBypass

object SpotifyTheme {
    @Volatile
    var amoled = false
        private set

    @Volatile
    var accent = ThemePalette.SPOTIFY_GREEN
        private set

    @Volatile
    private var keys = IntArray(0)

    @Volatile
    private var values = IntArray(0)

    private var amoledEnabled = false
    private var accentEnabled = false
    private var hooksInstalled = false
    private var listening = false

    fun enableAmoled(ctx: HookContext) {
        amoledEnabled = true
        configure(ctx)
    }

    fun enableAccent(ctx: HookContext) {
        accentEnabled = true
        configure(ctx)
    }

    fun map(color: Int): Int {
        val k = keys
        if (k.isEmpty()) return color
        val i = k.binarySearch(color)
        if (i >= 0) return values[i]
        if (!accentEnabled || !isGreenish(color)) return color
        for (j in k.indices) {
            if (closeRgb(k[j], color)) return (color and ALPHA_MASK) or (values[j] and RGB_MASK)
        }
        return color
    }

    private fun isGreenish(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return g > r + GREEN_MARGIN && g > b + GREEN_MARGIN
    }

    private fun closeRgb(a: Int, b: Int): Boolean {
        if ((a ushr 24) != 0xFF) return false
        return near(a, b, 16) && near(a, b, 8) && near(a, b, 0)
    }

    private fun near(a: Int, b: Int, shift: Int): Boolean =
        kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) <= COLOR_TOLERANCE

    private const val ALPHA_MASK = 0xFF000000.toInt()
    private const val RGB_MASK = 0x00FFFFFF
    private const val GREEN_MARGIN = 60
    private const val COLOR_TOLERANCE = 2

    private fun configure(ctx: HookContext) {
        rebuild(ctx)
        if (!listening) {
            listening = true
            ctx.prefs.onChange(PrefKeys.ACCENT_COLOR) { rebuild(ctx) }
        }
        if (!hooksInstalled) {
            hooksInstalled = true
            installHooks(ctx)
        }
    }

    private fun rebuild(ctx: HookContext) {
        amoled = amoledEnabled
        accent = if (accentEnabled) ctx.prefs.getInt(PrefKeys.ACCENT_COLOR, ThemePalette.SPOTIFY_GREEN) else ThemePalette.SPOTIFY_GREEN
        val palette = ThemePalette.build(amoled, if (accentEnabled) accent else null).toSortedMap()
        values = palette.values.toIntArray()
        keys = palette.keys.toIntArray()
        Logger.i("Tema: AMOLED=$amoled destaque=${ThemePalette.toHex(accent)} (${palette.size} cores trocadas)")
    }

    private fun installHooks(ctx: HookContext) {
        val hooks = ctx.hooks
        val theme = Resources.Theme::class.java
        val int = Int::class.javaPrimitiveType!!

        hooks.after(Resources::class.java.method("getColor", int, theme)) {
            (result as? Int)?.let { result = map(it) }
        }
        hooks.after(Resources::class.java.method("getColorStateList", int, theme)) {
            (result as? ColorStateList)?.let { result = mapList(it) }
        }
        hooks.after(TypedArray::class.java.method("getColor", int, int)) {
            (result as? Int)?.let { result = map(it) }
        }
        hooks.after(TypedArray::class.java.method("getColorStateList", int)) {
            (result as? ColorStateList)?.let { result = mapList(it) }
        }
        hooks.after(theme.method("resolveAttribute", int, TypedValue::class.java, Boolean::class.javaPrimitiveType!!)) {
            val value = arg<TypedValue?>(1) ?: return@after
            if (result == true && value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                value.data = map(value.data)
            }
        }
        hooks.before(ColorDrawable::class.java.method("setColor", int)) {
            args[0] = map(arg(0))
        }
        hooks.before(GradientDrawable::class.java.method("setColor", int)) {
            args[0] = map(arg(0))
        }
        hooks.before(GradientDrawable::class.java.method("setColors", IntArray::class.java)) {
            val colors = arg<IntArray?>(0) ?: return@before
            args[0] = IntArray(colors.size) { map(colors[it]) }
        }

        fastIntArg(ctx.xposed, Paint::class.java.method("setColor", int))
        fastIntArg(ctx.xposed, PorterDuffColorFilter::class.java.getConstructor(int, PorterDuff.Mode::class.java))
        fastIntArg(ctx.xposed, BlendModeColorFilter::class.java.getConstructor(int, BlendMode::class.java))
        ctx.xposed.hook(Paint::class.java.method("setColor", Long::class.javaPrimitiveType!!)).intercept { chain ->
            val color = chain.getArg(0) as Long
            val mapped = if (keys.isNotEmpty() && Color.isSrgb(color)) {
                val argb = Color.toArgb(color)
                val m = map(argb)
                if (m != argb) Color.pack(m) else color
            } else {
                color
            }
            if (mapped == color) chain.proceed() else chain.proceed(arrayOf(mapped, *chain.args.drop(1).toTypedArray()))
        }
        Logger.i("Tema: hooks de cor instalados")
    }

    private fun fastIntArg(xposed: XposedInterface, target: Executable) {
        xposed.hook(target).intercept { chain ->
            val color = chain.getArg(0) as Int
            val mapped = map(color)
            if (mapped == color) {
                chain.proceed()
            } else {
                val args = chain.args.toTypedArray()
                args[0] = mapped
                chain.proceed(args)
            }
        }
    }

    private fun mapList(list: ColorStateList): ColorStateList {
        if (keys.isEmpty()) return list
        if (!list.isStateful) {
            val mapped = map(list.defaultColor)
            return if (mapped == list.defaultColor) list else ColorStateList.valueOf(mapped)
        }
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val states = HiddenApiBypass.invoke(ColorStateList::class.java, list, "getStates") as Array<IntArray>
            val colors = HiddenApiBypass.invoke(ColorStateList::class.java, list, "getColors") as IntArray
            val mapped = IntArray(colors.size) { map(colors[it]) }
            if (mapped.contentEquals(colors)) list else ColorStateList(states, mapped)
        }.getOrDefault(list)
    }
}
