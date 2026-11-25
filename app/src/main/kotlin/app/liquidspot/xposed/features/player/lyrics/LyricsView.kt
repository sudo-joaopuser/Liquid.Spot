package app.liquidspot.xposed.features.player.lyrics

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import app.liquidspot.xposed.features.player.Texts
import app.liquidspot.xposed.ui.glass.Spring
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class LyricsView(context: Context) : View(context) {
    sealed class State {
        data object Loading : State()
        data object NotFound : State()
        data object Failed : State()
        data class Ready(val lyrics: Lyrics) : State()
    }

    private class Item(
        var line: LyricLine,
        val main: StaticLayout?,
        val bg: StaticLayout?,
        val tr: StaticLayout?,
        val left: Float,
        val bgTop: Float,
        val trTop: Float,
        val height: Float,
    )

    var sidePadding = 0f
        set(value) {
            if (field != value) {
                field = value
                laidOutWidth = 0
                requestLayout()
                invalidate()
            }
        }

    var onSeek: (Long) -> Unit = {}
    var onTapEmpty: () -> Unit = {}
    var onInteract: () -> Unit = {}
    var tapInterceptor: (Float) -> Boolean = { false }

    var reduceMotion = false
        set(value) {
            field = value
            scrollSpring.reduceMotion = value
            activeSpring.reduceMotion = value
        }

    private var state: State = State.Loading
    private var lines: List<LyricLine> = emptyList()
    private var synced = false
    private var translations: Map<String, String> = emptyMap()
    private var items: List<Item> = emptyList()
    private var tops = FloatArray(0)
    private var contentHeight = 0f
    private var laidOutWidth = 0
    private var textWidth = 0f

    private var active = -1
    private var previousActive = -1
    private var activeProgress = 1f
    private val activeSpring = Spring(dampingRatio = 1f, stiffness = 220f) {
        activeProgress = it
        invalidate()
    }
    private var scroll = 0f
    private val scrollSpring = Spring(dampingRatio = 0.9f, stiffness = 120f) {
        scroll = it
        invalidate()
    }
    private var lastUserTouch = 0L
    private var downY = 0f
    private var downScroll = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val textPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = sp(26f)
    }
    private val bgPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = sp(18f)
    }
    private val trPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textSize = sp(16f)
    }
    private val statusPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = sp(18f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val blurCache = object : LinkedHashMap<Long, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) = size > BLUR_CACHE_SIZE
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var rendersLeft = 0
    private var position = 0L
    private var needsSnap = false

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun setState(newState: State) {
        state = newState
        val lyrics = (newState as? State.Ready)?.lyrics
        lines = prepared(lyrics)
        synced = lyrics?.synced == true
        translations = emptyMap()
        laidOutWidth = 0
        active = -1
        previousActive = -1
        activeSpring.snapTo(1f)
        needsSnap = true
        lastUserTouch = 0L
        scrollSpring.snapTo(if (synced) minScroll() else 0f)
        requestLayout()
        invalidate()
    }

    fun updateLyrics(lyrics: Lyrics) {
        if (state !is State.Ready) return
        state = State.Ready(lyrics)
        val next = prepared(lyrics)
        synced = lyrics.synced
        if (sameShape(lines, next) && items.size == next.size) {
            lines = next
            items.forEachIndexed { i, item -> item.line = next[i] }
            invalidate()
            return
        }
        lines = next
        relayoutKeepingPlace()
    }

    private fun sameShape(a: List<LyricLine>, b: List<LyricLine>): Boolean =
        a.size == b.size && a.indices.all { i ->
            val x = a[i]
            val y = b[i]
            x.text == y.text && x.background == y.background && x.gap == y.gap && x.right == y.right && x.timeMs == y.timeMs
        }

    fun translatableTexts(): List<String> = lines.filter { !it.gap && it.text != NOTE }.map { it.text }.distinct()

    fun setTranslations(map: Map<String, String>?) {
        translations = map.orEmpty()
        relayoutKeepingPlace()
    }

    private fun prepared(lyrics: Lyrics?): List<LyricLine> =
        lyrics?.let(LyricsShape::prepare).orEmpty().map { if (!it.gap && it.text.isBlank()) it.copy(text = NOTE, words = emptyList()) else it }

    private fun relayoutKeepingPlace() {
        laidOutWidth = 0
        if (active >= lines.size) active = -1
        ensureLayout()
        if (!userScrolling() && !dragging) scrollSpring.animateTo(targetScroll())
        invalidate()
    }

    fun snapToCurrentLine() {
        needsSnap = true
    }

    fun setPosition(positionMs: Long) {
        if (!synced || lines.isEmpty()) return
        if (positionMs < position - SEEK_BACK_MS) smoothLine = -1
        position = positionMs
        val index = LrcParser.activeIndex(lines, positionMs + LEAD_MS)
        if (needsSnap && height > 0 && tops.size == lines.size) {
            needsSnap = false
            active = index
            previousActive = -1
            activeSpring.snapTo(1f)
            scrollSpring.snapTo(targetScroll())
            invalidate()
            return
        }
        if (index != active) {
            previousActive = active
            active = index
            activeSpring.snapTo(0f)
            activeSpring.animateTo(1f)
            if (!userScrolling()) scrollSpring.animateTo(targetScroll())
            invalidate()
        } else if (!userScrolling() && !dragging && abs(scrollSpring.target - targetScroll()) > 1f) {
            scrollSpring.animateTo(targetScroll())
        }
        val current = lines.getOrNull(active)
        if (current != null && (current.gap || current.words.isNotEmpty() || current.backgroundWords.isNotEmpty())) invalidate()
    }

    private fun sungOffset(words: List<LyricWord>, positionMs: Long): Float {
        var offset = 0f
        for (word in words) {
            if (positionMs >= word.endMs) {
                offset = word.to.toFloat()
                continue
            }
            if (positionMs > word.startMs) {
                val progress = (positionMs - word.startMs).toFloat() / (word.endMs - word.startMs)
                offset = word.from + (word.to - word.from) * progress.coerceIn(0f, 1f)
            }
            break
        }
        return offset
    }

    private fun glowingWord(words: List<LyricWord>, positionMs: Long): Pair<LyricWord, Float>? {
        val word = words.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs } ?: return null
        if (word.endMs - word.startMs < GLOW_MIN_MS) return null
        val progress = (positionMs - word.startMs).toFloat() / (word.endMs - word.startMs)
        return word to sin(progress * PI.toFloat())
    }

    private fun xAt(layout: StaticLayout, lineIndex: Int, offset: Float): Float {
        val lineEnd = layout.getLineEnd(lineIndex)
        val whole = offset.toInt()
        val x0 = layout.getPrimaryHorizontal(whole)
        val x1 = if (whole + 1 >= lineEnd) layout.getLineRight(lineIndex) else layout.getPrimaryHorizontal(whole + 1)
        return x0 + (x1 - x0) * (offset - whole)
    }

    private var smoothLine = -1
    private val smoothValues = FloatArray(2)
    private val smoothTimes = LongArray(2)

    private fun smoothedSung(part: Int, words: List<LyricWord>): Float {
        val target = sungOffset(words, position + KARAOKE_LEAD_MS)
        val now = SystemClock.uptimeMillis()
        if (smoothLine != active) {
            smoothLine = active
            smoothValues.fill(0f)
            smoothTimes.fill(now)
        }
        val dt = ((now - smoothTimes[part]) / 1000f).coerceIn(0f, MAX_SMOOTH_DT)
        smoothTimes[part] = now
        val current = smoothValues[part]
        if (target <= current) return current
        val next = current + (target - current) * (1f - exp(-dt * FOLLOW_RATE))
        smoothValues[part] = if (target - next < SNAP_CHARS) target else next
        if (smoothValues[part] < target) postInvalidateOnAnimation()
        return smoothValues[part]
    }

    private fun drawKaraoke(canvas: Canvas, layout: StaticLayout, words: List<LyricWord>, sung: Float, paint: TextPaint, alpha: Float, top: Float) {
        val at = position + KARAOKE_LEAD_MS
        val glow = glowingWord(words, at)
        val edge = dp(KARAOKE_EDGE_DP)
        canvas.save()
        canvas.translate(0f, top)
        paint.alpha = (alpha * KARAOKE_DIM * 255).toInt()
        layout.draw(canvas)
        paint.alpha = (alpha * 255).toInt()
        val right = layout.width.toFloat()
        for (l in 0 until layout.lineCount) {
            val lineStart = layout.getLineStart(l)
            if (sung <= lineStart) break
            val rowTop = layout.getLineTop(l).toFloat()
            val rowBottom = layout.getLineBottom(l).toFloat()
            val x = if (sung >= layout.getLineEnd(l)) layout.getLineRight(l) + edge else xAt(layout, l, sung)
            val layer = canvas.saveLayer(-dp(GLOW_DP), rowTop, right + dp(GLOW_DP), rowBottom, null)
            canvas.clipRect(-dp(GLOW_DP), rowTop, right + dp(GLOW_DP), rowBottom)
            val glowing = glow != null && glow.first.from >= lineStart && glow.first.from < layout.getLineEnd(l)
            if (glowing) paint.setShadowLayer(dp(GLOW_DP) * glow.second, 0f, 0f, ((GLOW_ALPHA * glow.second * alpha * 255).toInt() shl 24) or 0xFFFFFF)
            layout.draw(canvas)
            if (glowing) paint.clearShadowLayer()
            maskPaint.shader = LinearGradient(x - edge, 0f, x, 0f, 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            canvas.drawRect(-dp(GLOW_DP), rowTop, right + dp(GLOW_DP), rowBottom, maskPaint)
            canvas.restoreToCount(layer)
        }
        paint.alpha = 255
        canvas.restore()
    }

    private fun userScrolling() = SystemClock.uptimeMillis() - lastUserTouch < USER_PAUSE_MS

    private fun targetScroll(): Float {
        if (tops.isEmpty()) return 0f
        return (tops[active.coerceIn(0, tops.lastIndex)] - height * ANCHOR).coerceIn(minScroll(), maxScroll())
    }

    private fun minScroll() = -height * ANCHOR
    private fun maxScroll() = (contentHeight - height * ANCHOR).coerceAtLeast(0f)

    private fun layoutOf(text: String, paint: TextPaint, width: Int, right: Boolean): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(if (right) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()

    private fun ensureLayout() {
        val full = width
        if (full <= 0 || full == laidOutWidth) return
        laidOutWidth = full
        val w = (full - 2 * sidePadding).roundToInt().coerceAtLeast(1)
        blurCache.clear()
        val duet = lines.any { it.right }
        textWidth = if (duet) w * DUET_WIDTH else w.toFloat()
        val tw = textWidth.toInt().coerceAtLeast(1)
        val gap = dp(LINE_GAP_DP)
        val inner = dp(INNER_GAP_DP)
        var y = 0f
        tops = FloatArray(lines.size)
        items = lines.mapIndexed { i, line ->
            tops[i] = y
            val left = if (line.right) w - textWidth else 0f
            val item = if (line.gap) {
                Item(line, null, null, null, left, 0f, 0f, dp(GAP_HEIGHT_DP))
            } else {
                val main = layoutOf(line.text, textPaint, tw, line.right)
                var h = main.height.toFloat()
                val bg = line.background?.let { layoutOf(it, bgPaint, tw, line.right) }
                val bgTop = h + inner
                if (bg != null) h = bgTop + bg.height
                val tr = translations[line.text]?.takeIf { it.isNotBlank() && it != line.text }?.let { layoutOf(it, trPaint, tw, line.right) }
                val trTop = h + inner
                if (tr != null) h = trTop + tr.height
                Item(line, main, bg, tr, left, bgTop, trTop, h)
            }
            y += item.height + gap
            item
        }
        contentHeight = y
    }

    private fun drawStatic(canvas: Canvas, item: Item, alpha: Float) {
        textPaint.alpha = (alpha * 255).toInt()
        item.main?.draw(canvas)
        item.bg?.let {
            canvas.save()
            canvas.translate(0f, item.bgTop)
            bgPaint.alpha = (alpha * BG_ALPHA * 255).toInt()
            it.draw(canvas)
            canvas.restore()
        }
        item.tr?.let {
            canvas.save()
            canvas.translate(0f, item.trTop)
            trPaint.alpha = (alpha * TR_ALPHA * 255).toInt()
            it.draw(canvas)
            canvas.restore()
        }
        textPaint.alpha = 255
        bgPaint.alpha = 255
        trPaint.alpha = 255
    }

    override fun onDraw(canvas: Canvas) {
        when (val s = state) {
            State.Loading -> drawStatus(canvas, Texts.lyricsLoading)
            State.NotFound -> drawStatus(canvas, Texts.lyricsNotFound)
            State.Failed -> drawStatus(canvas, Texts.lyricsFailed)
            is State.Ready -> if (s.lyrics.instrumental || lines.isEmpty()) drawStatus(canvas, Texts.lyricsInstrumental) else drawLines(canvas)
        }
    }

    private fun drawStatus(canvas: Canvas, text: String) {
        val inner = (width - 2 * sidePadding).roundToInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, statusPaint, inner)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        canvas.translate(sidePadding, (height - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun emphasis(i: Int): Float = when (i) {
        active -> activeProgress
        previousActive -> 1f - activeProgress
        else -> 0f
    }.coerceIn(0f, 1f)

    private fun drawLines(canvas: Canvas) {
        ensureLayout()
        rendersLeft = RENDERS_PER_FRAME
        canvas.save()
        canvas.translate(sidePadding, 0f)
        val h = height.toFloat()
        val fadeEdge = dp(56f)
        val userMode = userScrolling() || dragging
        for (i in items.indices) {
            val item = items[i]
            val y = tops[i] - scroll
            if (y + item.height < -dp(20f) || y > h + dp(20f)) continue

            val e = emphasis(i)
            var alpha = when {
                !synced -> 0.85f
                userMode -> maxOf(0.6f, INACTIVE_ALPHA + (1f - INACTIVE_ALPHA) * e)
                else -> INACTIVE_ALPHA + (1f - INACTIVE_ALPHA) * e
            }
            val center = y + min(item.height, h) / 2f
            alpha *= edgeFade(center, fadeEdge) * edgeFade(h - center, fadeEdge)
            val scale = if (synced && !reduceMotion) INACTIVE_SCALE + (1f - INACTIVE_SCALE) * e else 1f
            val pivotX = if (item.line.right) item.left + textWidth else item.left

            canvas.save()
            canvas.scale(scale, scale, pivotX, y)
            when {
                item.line.gap -> drawGap(canvas, item, y, alpha, i == active)
                i == active && synced && !userMode && !reduceMotion &&
                    (item.line.words.isNotEmpty() || item.line.backgroundWords.isNotEmpty()) -> drawKaraokeItem(canvas, item, y, alpha)
                else -> {
                    val distance = if (active < 0) i + 1 else abs(i - active)
                    val level = if (!synced || userMode || i == active || reduceMotion) 0 else min(distance, MAX_BLUR_LEVEL)
                    if (level == 0) {
                        canvas.translate(item.left, y)
                        drawStatic(canvas, item, alpha)
                    } else {
                        drawBlurred(canvas, i, item, level, alpha, y)
                    }
                }
            }
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawKaraokeItem(canvas: Canvas, item: Item, y: Float, alpha: Float) {
        canvas.translate(item.left, y)
        val main = item.main ?: return
        if (item.line.words.isNotEmpty()) {
            drawKaraoke(canvas, main, item.line.words, smoothedSung(0, item.line.words), textPaint, alpha, 0f)
        } else {
            textPaint.alpha = (alpha * 255).toInt()
            main.draw(canvas)
            textPaint.alpha = 255
        }
        item.bg?.let { bg ->
            if (item.line.backgroundWords.isNotEmpty()) {
                drawKaraoke(canvas, bg, item.line.backgroundWords, smoothedSung(1, item.line.backgroundWords), bgPaint, alpha * BG_ALPHA, item.bgTop)
            } else {
                canvas.save()
                canvas.translate(0f, item.bgTop)
                bgPaint.alpha = (alpha * BG_ALPHA * 255).toInt()
                bg.draw(canvas)
                bgPaint.alpha = 255
                canvas.restore()
            }
        }
        item.tr?.let { tr ->
            canvas.save()
            canvas.translate(0f, item.trTop)
            trPaint.alpha = (alpha * TR_ALPHA * 255).toInt()
            tr.draw(canvas)
            trPaint.alpha = 255
            canvas.restore()
        }
    }

    private fun drawGap(canvas: Canvas, item: Item, y: Float, alpha: Float, live: Boolean) {
        val line = item.line
        val r = dp(DOT_RADIUS_DP)
        val spacing = dp(DOT_SPACING_DP)
        val rowWidth = spacing * 2 + r * 2
        val startX = if (line.right) item.left + textWidth - rowWidth + r else item.left + r
        val cy = y + item.height / 2f
        var scale = 1f
        var progress = 0f
        if (live && synced) {
            val span = (line.endMs - line.timeMs).coerceAtLeast(1L)
            progress = ((position - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
            val phase = (SystemClock.uptimeMillis() % BREATH_MS).toFloat() / BREATH_MS
            scale = (1f + BREATH_SCALE * sin(phase * 2f * PI.toFloat())) *
                ((line.endMs - position).toFloat() / DOT_EXIT_MS).coerceIn(0f, 1f)
            postInvalidateOnAnimation()
        }
        for (k in 0 until 3) {
            val fill = (progress * 3f - k).coerceIn(0f, 1f)
            val dotAlpha = if (live) DOT_DIM + (1f - DOT_DIM) * fill else DOT_DIM
            dotPaint.alpha = (alpha * dotAlpha * 255).toInt()
            canvas.drawCircle(startX + k * spacing, cy, r * scale, dotPaint)
        }
    }

    private fun edgeFade(distanceFromEdge: Float, length: Float) = (distanceFromEdge / length).coerceIn(0f, 1f)

    private fun drawBlurred(canvas: Canvas, index: Int, item: Item, level: Int, alpha: Float, y: Float) {
        val key = (index.toLong() shl 8) or level.toLong()
        val bitmap = blurCache[key] ?: if (rendersLeft > 0) {
            rendersLeft--
            renderBlurred(item, level)?.also { blurCache[key] = it } ?: return
        } else {
            postInvalidateOnAnimation()
            nearestBlurred(index, level) ?: run {
                canvas.save()
                canvas.translate(item.left, y)
                drawStatic(canvas, item, alpha * FALLBACK_ALPHA)
                canvas.restore()
                return
            }
        }
        val pad = dp(BLUR_PAD_DP)
        canvas.save()
        canvas.translate(item.left - pad, y - pad)
        canvas.scale(1f / BLUR_SCALE, 1f / BLUR_SCALE)
        bitmapPaint.alpha = (alpha * 255).toInt()
        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        canvas.restore()
    }

    private fun nearestBlurred(index: Int, level: Int): Bitmap? {
        for (d in 1..MAX_BLUR_LEVEL) {
            for (l in intArrayOf(level - d, level + d)) {
                if (l in 1..MAX_BLUR_LEVEL) blurCache[(index.toLong() shl 8) or l.toLong()]?.let { return it }
            }
        }
        return null
    }

    private fun renderBlurred(item: Item, level: Int): Bitmap? {
        val pad = dp(BLUR_PAD_DP)
        val w = ((textWidth + 2 * pad) * BLUR_SCALE).toInt()
        val h = ((item.height + 2 * pad) * BLUR_SCALE).toInt()
        if (w <= 0 || h <= 0) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.scale(BLUR_SCALE, BLUR_SCALE)
        c.translate(pad, pad)
        drawStatic(c, item, 1f)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val alphaValues = IntArray(pixels.size) { pixels[it] ushr 24 }
        blurAlpha(alphaValues, w, h, (dp(level * BLUR_STEP_DP) * BLUR_SCALE).toInt().coerceAtLeast(1))
        for (i in pixels.indices) pixels[i] = (alphaValues[i].coerceIn(0, 255) shl 24) or 0xFFFFFF
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun blurAlpha(values: IntArray, w: Int, h: Int, radius: Int) {
        val tmp = IntArray(values.size)
        repeat(BLUR_PASSES) {
            boxBlur(values, tmp, w, h, radius, horizontal = true)
            boxBlur(tmp, values, w, h, radius, horizontal = false)
        }
    }

    private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val window = 2 * r + 1
        for (l in 0 until lines) {
            fun at(i: Int): Int = if (i < 0 || i >= length) 0 else if (horizontal) src[l * w + i] else src[i * w + l]
            var sum = 0
            for (i in -r..r) sum += at(i)
            for (i in 0 until length) {
                val v = sum / window
                if (horizontal) dst[l * w + i] = v else dst[i * w + l] = v
                sum += at(i + r + 1) - at(i - r)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onInteract()
        if (state !is State.Ready || lines.isEmpty()) {
            if (event.actionMasked == MotionEvent.ACTION_UP) onTapEmpty()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrollSpring.stop()
                downY = event.y
                downScroll = scroll
                dragging = false
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val dy = event.y - downY
                if (!dragging && abs(dy) > touchSlop) dragging = true
                if (dragging) {
                    scroll = (downScroll - dy).coerceIn(minScroll(), maxScroll() + height * 0.5f)
                    lastUserTouch = SystemClock.uptimeMillis()
                    onInteract()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val fling = -(velocity?.yVelocity ?: 0f) * 0.35f
                    lastUserTouch = SystemClock.uptimeMillis()
                    scrollSpring.snapTo(scroll)
                    scrollSpring.animateTo((scroll + fling).coerceIn(minScroll(), maxScroll()))
                    postDelayed({ if (!userScrolling()) scrollSpring.animateTo(targetScroll()) }, USER_PAUSE_MS + 50)
                } else if (!tapInterceptor(event.y)) {
                    val index = lineAt(event.x, event.y)
                    val line = index?.let(lines::getOrNull)
                    if (line == null || line.gap) {
                        onTapEmpty()
                    } else if (synced && line.timeMs >= 0) {
                        performClick()
                        onSeek(line.timeMs)
                        lastUserTouch = 0L
                    }
                }
                dragging = false
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun lineAt(viewX: Float, y: Float): Int? {
        val x = viewX - sidePadding
        val contentY = y + scroll
        for (i in items.indices) {
            val item = items[i]
            if (contentY < tops[i] || contentY > tops[i] + item.height + dp(LINE_GAP_DP) / 2f) continue
            if (x < item.left - dp(TAP_SLOP_DP) || x > item.left + textWidth + dp(TAP_SLOP_DP)) return null
            return i
        }
        return null
    }

    override fun onDetachedFromWindow() {
        scrollSpring.stop()
        activeSpring.stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val NOTE = "♪"
        const val ANCHOR = 0.3f
        const val KARAOKE_DIM = 0.4f
        const val KARAOKE_EDGE_DP = 22f
        const val KARAOKE_LEAD_MS = 60L
        const val FOLLOW_RATE = 14f
        const val MAX_SMOOTH_DT = 0.1f
        const val SNAP_CHARS = 0.05f
        const val SEEK_BACK_MS = 800L
        const val GLOW_MIN_MS = 900L
        const val GLOW_DP = 8f
        const val GLOW_ALPHA = 0.55f
        const val LINE_GAP_DP = 22f
        const val INNER_GAP_DP = 4f
        const val GAP_HEIGHT_DP = 28f
        const val DOT_RADIUS_DP = 5f
        const val DOT_SPACING_DP = 16f
        const val DOT_DIM = 0.3f
        const val DOT_EXIT_MS = 350f
        const val BREATH_MS = 1_600L
        const val BREATH_SCALE = 0.12f
        const val BG_ALPHA = 0.7f
        const val TR_ALPHA = 0.6f
        const val INACTIVE_ALPHA = 0.35f
        const val INACTIVE_SCALE = 0.95f
        const val DUET_WIDTH = 0.84f
        const val TAP_SLOP_DP = 24f
        const val LEAD_MS = 150L
        const val USER_PAUSE_MS = 3_000L
        const val MAX_BLUR_LEVEL = 3
        const val BLUR_STEP_DP = 0.9f
        const val BLUR_SCALE = 0.5f
        const val BLUR_PAD_DP = 20f
        const val BLUR_PASSES = 3
        const val BLUR_CACHE_SIZE = 40
        const val RENDERS_PER_FRAME = 2
        const val FALLBACK_ALPHA = 0.5f
    }
}
