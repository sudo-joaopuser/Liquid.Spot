package app.liquidspot.xposed.features.player.lyrics

object LyricsShape {
    private const val MIN_GAP_MS = 4_000L
    private const val GROUP_AGENT = "v1000"
    private const val RIGHT_FLIP_SHARE = 0.85f
    private const val MS_PER_CHAR = 90L
    private const val MIN_LINE_MS = 1_500L
    private const val MAX_LINE_MS = 6_000L

    fun prepare(lyrics: Lyrics): List<LyricLine> {
        val withBackground = lyrics.lines.map(::splitBackground)
        if (!lyrics.synced) return withBackground
        return withGaps(aligned(withBackground))
    }

    private fun splitBackground(line: LyricLine): LyricLine {
        if (line.background != null) return line
        val text = line.text
        if (!text.endsWith(")")) return line
        val open = text.lastIndexOf('(')
        if (open <= 0 || text.substring(0, open).isBlank()) return line
        val main = text.substring(0, open).trimEnd()
        val inside = text.substring(open + 1, text.length - 1).trim()
        if (inside.isEmpty()) return line
        val insideStart = text.indexOf(inside, open)
        val mainWords = ArrayList<LyricWord>()
        val bgWords = ArrayList<LyricWord>()
        for (word in line.words) {
            if (word.from >= open) {
                val from = (word.from - insideStart).coerceIn(0, inside.length)
                val to = (word.to - insideStart).coerceIn(from, inside.length)
                if (to > from) bgWords += word.copy(from = from, to = to)
            } else {
                val to = word.to.coerceAtMost(main.length)
                if (to > word.from) mainWords += word.copy(to = to)
            }
        }
        return line.copy(text = main, words = mainWords, background = inside, backgroundWords = bgWords)
    }

    private fun aligned(lines: List<LyricLine>): List<LyricLine> {
        if (lines.any { it.opposite == true }) return flipIfMostlyRight(lines.map { it.copy(right = it.opposite == true) })
        val people = lines.mapNotNull { it.agent }.filter { it != GROUP_AGENT }.distinct()
        if (people.size < 2) return lines
        var side = false
        var previous: String? = null
        val out = lines.map { line ->
            val agent = line.agent
            when {
                agent == null || agent == GROUP_AGENT -> line.copy(right = false)
                else -> {
                    if (previous != null && agent != previous) side = !side
                    previous = agent
                    line.copy(right = side)
                }
            }
        }
        return flipIfMostlyRight(out)
    }

    private fun flipIfMostlyRight(lines: List<LyricLine>): List<LyricLine> {
        val right = lines.count { it.right }
        if (lines.isEmpty() || right.toFloat() / lines.size <= RIGHT_FLIP_SHARE) return lines
        return lines.map { it.copy(right = !it.right) }
    }

    private fun estimatedEnd(line: LyricLine, nextStart: Long): Long {
        if (line.endMs > line.timeMs && line.endMs < nextStart) return line.endMs
        val lastWord = (line.words + line.backgroundWords).maxOfOrNull { it.endMs }
        if (lastWord != null && lastWord > line.timeMs) return lastWord
        return line.timeMs + (line.text.length * MS_PER_CHAR).coerceIn(MIN_LINE_MS, MAX_LINE_MS)
    }

    private fun withGaps(lines: List<LyricLine>): List<LyricLine> {
        val timed = lines.filter { it.timeMs >= 0 }
        if (timed.isEmpty()) return lines
        val out = ArrayList<LyricLine>(lines.size + 8)
        val first = timed.first()
        if (first.timeMs >= MIN_GAP_MS) out += gap(0L, first.timeMs, first.right)
        for ((i, line) in lines.withIndex()) {
            out += line
            val next = lines.getOrNull(i + 1) ?: continue
            if (line.timeMs < 0 || next.timeMs < 0) continue
            val end = estimatedEnd(line, next.timeMs)
            if (next.timeMs - end >= MIN_GAP_MS) out += gap(end, next.timeMs, next.right)
        }
        return out
    }

    private fun gap(start: Long, end: Long, right: Boolean) =
        LyricLine(timeMs = start, text = "", endMs = end, gap = true, right = right)
}
