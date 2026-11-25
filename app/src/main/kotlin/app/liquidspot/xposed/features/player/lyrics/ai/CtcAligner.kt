package app.liquidspot.xposed.features.player.lyrics.ai

import app.liquidspot.xposed.features.player.lyrics.LyricWord
import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

class CtcAligner(private val vocab: Map<String, Int>) {
    class Tokens(val ids: IntArray, val chars: IntArray)

    private val blank = vocab["<pad>"] ?: 0
    private val separator = vocab["|"] ?: 4

    fun tokenize(text: String): Tokens? {
        val ids = ArrayList<Int>()
        val chars = ArrayList<Int>()
        var pendingSpace = false
        var unknown = 0
        for ((index, ch) in text.withIndex()) {
            val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                .uppercase()
            val c = base.firstOrNull()?.let { if (it == RIGHT_QUOTE) APOSTROPHE else it } ?: continue
            when {
                c.isWhitespace() || c == '-' -> pendingSpace = ids.isNotEmpty()
                vocab.containsKey(c.toString()) -> {
                    if (pendingSpace) {
                        ids += separator
                        chars += -1
                        pendingSpace = false
                    }
                    ids += vocab.getValue(c.toString())
                    chars += index
                }
                c.isLetter() -> unknown++
            }
        }
        val known = ids.count { it != separator }
        if (known < MIN_TOKENS || unknown > known * MAX_UNKNOWN) return null
        return Tokens(ids.toIntArray(), chars.toIntArray())
    }

    class Partial(val spans: Array<IntArray>, val reached: Int)

    fun align(logits: Array<FloatArray>, tokens: Tokens): Array<IntArray>? {
        if (logits.size < tokens.ids.size) return null
        val result = viterbi(logits, tokens, freeEnd = false) ?: return null
        return if (result.spans.any { it[0] < 0 }) null else result.spans
    }

    fun alignPartial(logits: Array<FloatArray>, tokens: Tokens): Partial? =
        viterbi(logits, tokens, freeEnd = true)?.takeIf { it.reached >= 0 }

    private fun viterbi(logits: Array<FloatArray>, tokens: Tokens, freeEnd: Boolean): Partial? {
        val frames = logits.size
        val labels = tokens.ids
        val states = labels.size * 2 + 1
        if (frames == 0) return null
        val logProbs = Array(frames) { logSoftmax(logits[it]) }
        val score = Array(frames) { FloatArray(states) { NEG } }
        val from = Array(frames) { IntArray(states) }
        fun label(s: Int) = if (s % 2 == 0) blank else labels[s / 2]
        score[0][0] = logProbs[0][blank]
        score[0][1] = logProbs[0][labels[0]]
        for (t in 1 until frames) {
            val lp = logProbs[t]
            for (s in 0 until states) {
                var best = score[t - 1][s]
                var bestFrom = s
                if (s >= 1 && score[t - 1][s - 1] > best) {
                    best = score[t - 1][s - 1]
                    bestFrom = s - 1
                }
                if (s >= 2 && s % 2 == 1 && labels[s / 2] != labels[s / 2 - 1] && score[t - 1][s - 2] > best) {
                    best = score[t - 1][s - 2]
                    bestFrom = s - 2
                }
                if (best <= NEG) continue
                score[t][s] = best + lp[label(s)]
                from[t][s] = bestFrom
            }
        }
        val last = score[frames - 1]
        var s = if (freeEnd) {
            val best = last.indices.maxByOrNull { last[it] } ?: return null
            (states - 1 downTo best).firstOrNull { last[it] >= last[best] - FREE_END_MARGIN } ?: best
        } else if (last[states - 1] >= last[states - 2]) {
            states - 1
        } else {
            states - 2
        }
        if (last[s] <= NEG) return null
        val reached = if (s % 2 == 1) s / 2 else s / 2 - 1
        val spans = Array(labels.size) { intArrayOf(-1, -1) }
        for (t in frames - 1 downTo 0) {
            if (s % 2 == 1) {
                val k = s / 2
                if (spans[k][1] < 0) spans[k][1] = t
                spans[k][0] = t
            }
            s = from[t][s]
        }
        return Partial(spans, reached)
    }

    fun weightOf(text: String): Float = syllableRanges(text).let { ranges ->
        ranges.withIndex().sumOf { (i, r) -> weight(r.second - r.first, i == ranges.lastIndex).toDouble() }.toFloat()
    }

    private fun weight(chars: Int, last: Boolean): Float = 1f + (chars - 1).coerceAtLeast(0) * CHAR_WEIGHT + if (last) LAST_HOLD else 0f

    fun predictedSyllables(text: String, startMs: Long, limitMs: Long, beatMs: Long): List<LyricWord> {
        val ranges = syllableRanges(text)
        if (ranges.isEmpty()) return emptyList()
        return spread(ranges, ranges.indices.toList(), startMs, limitMs, beatMs.toFloat())
    }

    private fun spread(ranges: List<Pair<Int, Int>>, indices: List<Int>, startMs: Long, limitMs: Long, beatMs: Float): List<LyricWord> {
        val weights = indices.map { weight(ranges[it].second - ranges[it].first, it == ranges.lastIndex) }
        val total = weights.sum()
        val room = (limitMs - startMs).toFloat()
        val step = if (room > 0f && total * beatMs > room) room / total else beatMs
        var cursor = startMs.toFloat()
        return indices.mapIndexed { n, i ->
            val end = cursor + weights[n] * step
            LyricWord(cursor.toLong(), max(end.toLong(), cursor.toLong() + 1), ranges[i].first, ranges[i].second).also { cursor = end }
        }
    }

    fun liveSyllables(
        text: String,
        tokens: Tokens,
        partial: Partial,
        frameMs: Float,
        startMs: Long,
        limitMs: Long,
        beatMs: Long,
    ): List<LyricWord> {
        val tokenOfChar = IntArray(text.length) { -1 }
        for (k in tokens.ids.indices) {
            val c = tokens.chars[k]
            if (c >= 0) tokenOfChar[c] = k
        }
        fun at(frame: Int) = startMs + (frame * frameMs).toLong()
        val reached = partial.reached
        val spans = partial.spans
        val frontier = at(spans[reached][1] + 1)
        val ranges = syllableRanges(text)
        val out = ArrayList<LyricWord>()
        val predicted = ArrayList<Int>()
        var doneWeight = 0f
        var firstStart = Long.MAX_VALUE
        for ((i, range) in ranges.withIndex()) {
            val (from, to) = range
            val ks = (from until to).map { tokenOfChar[it] }.filter { it >= 0 }
            if (ks.isEmpty()) continue
            val done = ks.filter { it <= reached && spans[it][0] >= 0 }
            val w = weight(to - from, i == ranges.lastIndex)
            when {
                done.size == ks.size -> {
                    val s = at(done.minOf { spans[it][0] })
                    firstStart = minOf(firstStart, s)
                    doneWeight += w
                    out += LyricWord(s, max(at(done.maxOf { spans[it][1] } + 1), s + 1), from, to)
                }
                done.isNotEmpty() -> {
                    val s = at(done.minOf { spans[it][0] })
                    firstStart = minOf(firstStart, s)
                    doneWeight += w * done.size / ks.size
                    out += LyricWord(s, max(frontier, s + 1), from, to)
                }
                else -> predicted += i
            }
        }
        if (predicted.isNotEmpty()) {
            val measured = if (doneWeight > 0f && firstStart < frontier) (frontier - firstStart) / doneWeight else beatMs.toFloat()
            val beat = ((measured * doneWeight + beatMs * PRIOR_WEIGHT) / (doneWeight + PRIOR_WEIGHT)).coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
            val cursor = max(frontier, out.lastOrNull()?.endMs ?: frontier)
            out += spread(ranges, predicted, cursor, limitMs, beat)
        }
        return out
    }

    fun syllables(text: String, tokens: Tokens, spans: Array<IntArray>, frameMs: Float, startMs: Long): List<LyricWord> {
        val charTimes = HashMap<Int, IntArray>()
        for (k in tokens.ids.indices) {
            val c = tokens.chars[k]
            if (c >= 0) charTimes[c] = spans[k]
        }
        val out = ArrayList<LyricWord>()
        for ((from, to) in syllableRanges(text)) {
            val times = (from until to).mapNotNull { charTimes[it] }
            if (times.isEmpty()) continue
            val s = startMs + (times.minOf { it[0] } * frameMs).toLong()
            val e = startMs + ((times.maxOf { it[1] } + 1) * frameMs).toLong()
            out += LyricWord(s, max(e, s + 1), from, to)
        }
        for (i in 0 until out.size - 1) {
            val next = out[i + 1]
            if (next.startMs - out[i].endMs in 1..HOLD_GAP_MS) out[i] = out[i].copy(endMs = next.startMs)
        }
        return out
    }

    private fun syllableRanges(text: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < text.length) {
            if (!text[i].isLetterOrDigit() && text[i] != '\'') {
                i++
                continue
            }
            var end = i
            while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '\'')) end++
            splitWord(text, i, end, out)
            i = end
        }
        return out
    }

    private fun splitWord(text: String, start: Int, end: Int, out: MutableList<Pair<Int, Int>>) {
        val vowels = (start until end).filter { isVowel(text[it]) }
        if (vowels.size <= 1) {
            out += start to end
            return
        }
        val groups = ArrayList<IntRange>()
        var g = vowels.first()
        var prev = g
        for (v in vowels.drop(1)) {
            if (v != prev + 1) {
                groups += g..prev
                g = v
            }
            prev = v
        }
        groups += g..prev
        if (groups.size == 1) {
            out += start to end
            return
        }
        var cursor = start
        for (j in 0 until groups.size - 1) {
            val gapStart = groups[j].last + 1
            val gapEnd = groups[j + 1].first
            val consonants = gapEnd - gapStart
            val cut = if (consonants <= 1) gapStart else gapStart + consonants / 2
            out += cursor to cut
            cursor = cut
        }
        out += cursor to end
    }

    private fun isVowel(c: Char): Boolean {
        val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull()?.lowercaseChar() ?: return false
        return base in "aeiouy"
    }

    private fun logSoftmax(row: FloatArray): FloatArray {
        var m = Float.NEGATIVE_INFINITY
        for (v in row) if (v > m) m = v
        var sum = 0.0
        for (v in row) sum += exp((v - m).toDouble())
        val lse = m + ln(sum).toFloat()
        return FloatArray(row.size) { row[it] - lse }
    }

    private companion object {
        const val NEG = -1e30f
        const val MIN_TOKENS = 2
        const val HOLD_GAP_MS = 250L
        const val FREE_END_MARGIN = 4f
        const val CHAR_WEIGHT = 0.18f
        const val LAST_HOLD = 0.6f
        const val PRIOR_WEIGHT = 2f
        const val MIN_BEAT_MS = 110f
        const val MAX_BEAT_MS = 800f
        const val MAX_UNKNOWN = 0.3f
        const val RIGHT_QUOTE = '’'
        const val APOSTROPHE = '\''
    }
}
