package app.liquidspot.xposed.media

object PositionMath {
    fun interpolate(
        positionMs: Long,
        lastUpdateMs: Long,
        speed: Float,
        playing: Boolean,
        nowMs: Long,
        durationMs: Long,
    ): Long {
        val advanced = if (playing && lastUpdateMs > 0) positionMs + ((nowMs - lastUpdateMs) * speed).toLong() else positionMs
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return advanced.coerceIn(0L, upper)
    }

    fun format(ms: Long, negative: Boolean = false): String {
        val clamped = ms.coerceAtLeast(0)
        val total = if (negative) (clamped + 999) / 1000 else clamped / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val body = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        return if (negative) "-$body" else body
    }
}
