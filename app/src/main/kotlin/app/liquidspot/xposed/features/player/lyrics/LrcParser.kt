package app.liquidspot.xposed.features.player.lyrics

data class LyricWord(val startMs: Long, val endMs: Long, val from: Int, val to: Int)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val endMs: Long = -1L,
    val agent: String? = null,
    val opposite: Boolean? = null,
    val background: String? = null,
    val backgroundWords: List<LyricWord> = emptyList(),
    val gap: Boolean = false,
    val right: Boolean = false,
)

data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val instrumental: Boolean = false,
    val source: String = "",
)

object LrcParser {
    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}
