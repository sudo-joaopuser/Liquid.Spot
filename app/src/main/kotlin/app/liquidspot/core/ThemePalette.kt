package app.liquidspot.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ThemePalette {
    const val SPOTIFY_GREEN = 0xFF1ED760.toInt()

    val ACCENT_PRESETS: List<Int> = listOf(
        SPOTIFY_GREEN,
        0xFF2E77FF.toInt(),
        0xFF8B5CF6.toInt(),
        0xFFFF4FA3.toInt(),
        0xFFFF4D4D.toInt(),
        0xFFFF8A00.toInt(),
        0xFFFFD60A.toInt(),
        0xFF00D1C1.toInt(),
        0xFFFFFFFF.toInt(),
    )

    private val SPOTIFY_GREENS = listOf(
        0xFF1ED760, 0xFF3BE477, 0xFF1ABC54, 0xFF1DB954, 0xFF96F0B6,
        0xFF159542, 0xFF169F47, 0xFF127E38, 0xFF107434, 0xFF073116,
    ).map { it.toInt() }

    private val AMOLED = mapOf(
        0xFF121212 to 0xFF000000,
        0xFF141414 to 0xFF000000,
        0xFF181818 to 0xFF000000,
        0xFF0B0B0B to 0xFF000000,
        0xFF1F1F1F to 0xFF0A0A0A,
        0xFF212121 to 0xFF0C0C0C,
        0xFF242424 to 0xFF111111,
        0xFF282828 to 0xFF141414,
        0xFF2A2A2A to 0xFF161616,
        0xFF2B2B2B to 0xFF171717,
    ).entries.associate { it.key.toInt() to it.value.toInt() }

    fun build(accent: Int?): Map<Int, Int> {
        val map = HashMap<Int, Int>(AMOLED)
        if (accent != null && accent != SPOTIFY_GREEN) {
            for (green in SPOTIFY_GREENS) map[green] = shiftLike(accent, SPOTIFY_GREEN, green)
        }
        return map
    }

    fun shiftLike(accent: Int, base: Int, original: Int): Int {
        val a = toHsl(accent)
        val b = toHsl(base)
        val o = toHsl(original)
        val saturation = if (b[1] > 0f) (a[1] * o[1] / b[1]).coerceIn(0f, 1f) else a[1]
        val lightness = (a[2] + (o[2] - b[2])).coerceIn(0f, 1f)
        return fromHsl(a[0], saturation, lightness, original ushr 24)
    }

    fun parseHex(text: String): Int? {
        val hex = text.trim().removePrefix("#")
        if (hex.length != 6 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return (0xFF000000.toInt()) or hex.toInt(16)
    }

    fun toHex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

    private fun toHsl(color: Int): FloatArray {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(h, s.coerceIn(0f, 1f), l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float, alpha: Int): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return (alpha shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
