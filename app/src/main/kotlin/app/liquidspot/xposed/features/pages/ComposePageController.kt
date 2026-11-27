package app.liquidspot.xposed.features.pages

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RenderNode
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import app.liquidspot.core.GlassStyle
import app.liquidspot.xposed.a11y.A11yBridge
import app.liquidspot.xposed.a11y.TextMatcher
import app.liquidspot.xposed.features.player.IconButton
import app.liquidspot.xposed.features.player.Texts
import app.liquidspot.xposed.ui.PlayerIcon
import app.liquidspot.xposed.ui.glass.BackdropSource
import app.liquidspot.xposed.util.Logger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class ComposePageController(
    private val activity: Activity,
    private val compose: View,
    private val host: ViewGroup,
    private val styleProvider: () -> GlassStyle,
) : PageStyler {
    private val res = activity.resources

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, res.displayMetrics)

    private val backLabels = TextMatcher.formats(A11yBridge.spotifyStrings(res, "back_button_content_description"))
    private val headerMenuLabels = TextMatcher.formats(A11yBridge.spotifyStrings(res, "standard_header_context_menu_element_accessibility"))
    private val rowMenuLabels = TextMatcher.formats(A11yBridge.spotifyStrings(res, "standard_context_menu_element_accessibility"))
    private val playLabels = TextMatcher.formats(
        A11yBridge.spotifyStrings(
            res,
            "play_button_default_play_content_description", "play_button_default_pause_content_description",
            "omni_play_button_play_content_description", "omni_play_button_pause_content_description",
        ),
    )

    private val overlay = Chrome(activity)
    private val page = RenderNode("LiquidSpotComposePage")
    private val backdrop = BackdropSource(compose) { recordBehindButtons(it) }
    private val backButton = IconButton(activity, PlayerIcon.BACK, 22f, glass = true).apply { contentDescription = Texts.back }
    private val moreButton = IconButton(activity, PlayerIcon.MORE_HORIZONTAL, 22f, glass = true).apply { contentDescription = Texts.more }

    private var backTarget: A11yBridge.Target? = null
    private var menuTarget: A11yBridge.Target? = null
    private val playTargets = ArrayList<A11yBridge.Target>()
    private var playLost = false
    private val backRect = Rect()
    private val menuRect = Rect()
    private val lastMenuRect = Rect()
    private val playRect = Rect()
    private var menuCovered = false

    private val known = HashMap<String, Int>()
    private val lines = ArrayList<Int>()
    private class Anchor(val target: A11yBridge.Target, val content: Int, val height: Int)

    private val anchors = ArrayList<Anchor>()
    private var rowStep = 0
    private var scroll = 0
    private var scrollAtScan = 0
    private var tracking = false

    private val composeLocation = IntArray(2)
    private val overlayLocation = IntArray(2)

    private var started = false
    private var startedAt = 0L
    private var stopped = false
    private var failed = false
    private var listening = false
    private var lastScan = 0L

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        guard("quadro") { onFrame() }
        true
    }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = listen(true)
        override fun onViewDetachedFromWindow(v: View) = listen(false)
    }

    override fun tryStart() = guard("início") {
        if (started || compose.width == 0) return@guard
        started = true
        startedAt = SystemClock.uptimeMillis()
        backButton.backdrop = backdrop
        moreButton.backdrop = backdrop
        val size = dp(BUTTON_DP).roundToInt()
        overlay.addView(backButton, FrameLayout.LayoutParams(size, size))
        overlay.addView(moreButton, FrameLayout.LayoutParams(size, size))
        backButton.visibility = View.INVISIBLE
        moreButton.visibility = View.INVISIBLE
        host.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        backButton.setOnClickListener { guard("voltar") { goBack() } }
        moreButton.setOnClickListener { guard("…") { openMenu() } }
        applySettings()
        compose.addOnAttachStateChangeListener(attach)
        if (compose.isAttachedToWindow) listen(true)
        Logger.i("Página Compose: botões de vidro e linhas entre as músicas")
    }

    override fun stop() {
        stopped = true
        listen(false)
        compose.removeOnAttachStateChangeListener(attach)
    }

    override fun applySettings() {
        val style = styleProvider()
        backButton.glassSurface?.style = style
        moreButton.glassSurface?.style = style
        backButton.invalidate()
        moreButton.invalidate()
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        val observer = compose.viewTreeObserver
        if (!observer.isAlive) return
        listening = on
        if (on) observer.addOnPreDrawListener(preDraw) else observer.removeOnPreDrawListener(preDraw)
    }

    private fun onFrame() {
        if (!compose.isShown) return
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastScan
        val starting = now - startedAt < FAST_SCAN_WINDOW_MS && (backRect.isEmpty || known.isEmpty())
        val due = when {
            starting || playLost || (!tracking && known.isNotEmpty()) -> elapsed >= FAST_SCAN_MS
            elapsed < SCAN_MS -> false
            backRect.isEmpty || !tracking || menuTarget?.read() == null -> true
            playTargets.isEmpty() && elapsed >= PLAY_SCAN_MS -> true
            needsRows() -> true
            else -> elapsed >= IDLE_SCAN_MS
        }
        if (due) {
            lastScan = now
            scan()
        }
        overlay.invalidate()
    }

    private fun needsRows(): Boolean {
        if (rowStep <= 0) return known.isEmpty()
        if (abs(scroll - scrollAtScan) < rowStep / 2) return false
        compose.getLocationOnScreen(composeLocation)
        val top = composeLocation[1] + scroll
        val bottom = composeLocation[1] + compose.height + scroll
        val covered = lines.isNotEmpty() && lines.first() <= top + rowStep && lines.last() >= bottom - rowStep
        return !covered
    }

    private fun scan() {
        val items = A11yBridge.collect(compose, maxItems = MAX_ITEMS)
        backTarget = items.firstOrNull { backLabels.matches(it.label) }?.clickableTarget()
        items.firstOrNull { headerMenuLabels.matches(it.label) }?.clickableTarget()?.let { menuTarget = it }
        playTargets.clear()
        items.filter { playLabels.matches(it.label) }.mapNotNullTo(playTargets) { it.clickableTarget() }
        playLost = false
        readRect(backTarget, backRect)
        if (readRect(menuTarget, menuRect)) lastMenuRect.set(menuRect)

        val rows = ArrayList<Triple<String, A11yBridge.Target, Rect>>()
        for (item in items) {
            if (!rowMenuLabels.matches(item.label)) continue
            val bounds = item.target.read()?.boundsOnScreen ?: continue
            if (bounds.isEmpty) continue
            rows += Triple(item.label, item.target, Rect(bounds))
        }
        if (rows.isEmpty()) return
        val fullHeight = rows.maxOf { it.third.height() }
        val found = rows.filter { it.third.height() >= fullHeight - 1 }.map { Triple(it.first, it.second, it.third.centerY()) }
        if (found.isEmpty()) return

        val offsets = found.mapNotNull { (label, _, y) -> known[label]?.let { it - y } }.sorted()
        when {
            offsets.isNotEmpty() -> scroll = offsets[offsets.size / 2]
            !tracking -> {
                known.clear()
                scroll = 0
            }
        }
        anchors.clear()
        for ((label, target, y) in found) {
            known[label] = y + scroll
            anchors += Anchor(target, y + scroll, fullHeight)
        }
        compose.getLocationOnScreen(composeLocation)
        val middle = composeLocation[1] + compose.height / 2 + scroll
        anchors.sortBy { abs(it.content - middle) }
        scrollAtScan = scroll
        tracking = true
        rebuildLines()
    }

    private fun rebuildLines() {
        val centers = known.values.sorted()
        val gaps = centers.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        rowStep = if (gaps.isEmpty()) 0 else gaps[gaps.size / 2]
        lines.clear()
        if (rowStep <= 0) return
        for (i in 0 until centers.size - 1) {
            val a = centers[i]
            val b = centers[i + 1]
            if (abs(b - a - rowStep) > rowStep / 4) continue
            lines += (a + b) / 2
        }
    }

    private fun readRect(target: A11yBridge.Target?, out: Rect): Boolean {
        val bounds = target?.read()?.boundsOnScreen
        if (bounds == null || bounds.isEmpty || bounds.width() > dp(MAX_TARGET_DP)) {
            out.setEmpty()
            return false
        }
        out.set(bounds)
        return true
    }

    private fun trackRows() {
        val iterator = anchors.iterator()
        while (iterator.hasNext()) {
            val anchor = iterator.next()
            val bounds = anchor.target.read()?.boundsOnScreen
            if (bounds == null || bounds.isEmpty) {
                iterator.remove()
                continue
            }
            if (bounds.height() < anchor.height - 1) continue
            scroll = anchor.content - bounds.centerY()
            return
        }
        tracking = false
    }

    private fun updatePlay(size: Float) {
        if (playTargets.isEmpty()) {
            playRect.setEmpty()
            menuCovered = false
            return
        }
        var nearest: Rect? = null
        var covered = false
        for (target in playTargets) {
            val bounds = target.read()?.boundsOnScreen ?: continue
            if (bounds.isEmpty) continue
            val covers = !lastMenuRect.isEmpty &&
                abs(bounds.centerX() - lastMenuRect.centerX()) < size &&
                bounds.exactCenterY() - dp(PLAY_RADIUS_DP) < lastMenuRect.exactCenterY() + size / 2f
            if (covers && !covered) {
                covered = true
                nearest = bounds
            } else if (nearest == null || (!covered && abs(bounds.centerY() - lastMenuRect.centerY()) < abs(nearest.centerY() - lastMenuRect.centerY()))) {
                nearest = bounds
            }
        }
        if (nearest == null) {
            playLost = true
            return
        }
        playRect.set(nearest)
        menuCovered = covered
    }

    private fun recordBehindButtons(canvas: Canvas) {
        val w = compose.width
        val h = compose.height
        page.setPosition(0, 0, w, h)
        val pageCanvas = page.beginRecording(w, h)
        try {
            compose.draw(pageCanvas)
        } finally {
            page.endRecording()
        }
        canvas.drawRenderNode(page)
        compose.getLocationOnScreen(composeLocation)
        val half = (dp(BUTTON_DP) / 2f + dp(PATCH_MARGIN_DP)).roundToInt()
        if (!backRect.isEmpty) {
            val x = backRect.centerX() - composeLocation[0]
            patchFromColumn(canvas, x, backRect.centerY() - composeLocation[1], half, x + half)
        }
        if (!lastMenuRect.isEmpty && !menuCovered) {
            val x = lastMenuRect.centerX() - composeLocation[0]
            patchFromColumn(canvas, x, lastMenuRect.centerY() - composeLocation[1], half, x + dp(MENU_SOURCE_DP).roundToInt())
        }
    }

    private fun patchFromColumn(canvas: Canvas, x: Int, y: Int, half: Int, source: Int) {
        val left = (x - half).toFloat()
        val src = source.coerceIn(0, compose.width - 1).toFloat()
        val save = canvas.save()
        canvas.clipRect(left, (y - half).toFloat(), (x + half).toFloat(), (y + half).toFloat())
        canvas.translate(left, 0f)
        canvas.scale(half * 2f, 1f)
        canvas.translate(-src, 0f)
        canvas.clipRect(src, (y - half).toFloat(), src + 1f, (y + half).toFloat())
        canvas.drawRenderNode(page)
        canvas.restoreToCount(save)
    }

    private fun goBack() {
        if (backTarget?.click() == true) return
        @Suppress("DEPRECATION")
        activity.onBackPressed()
    }

    private fun openMenu() {
        if (menuTarget?.click() == true) return
        scan()
        if (menuTarget?.click() != true) Logger.w("Página Compose: botão \"…\" do Spotify não encontrado")
    }

    private inline fun guard(where: String, block: () -> Unit) {
        if (failed || stopped) return
        try {
            block()
        } catch (t: Throwable) {
            failed = true
            Logger.e("Página Compose desativada após erro ($where)", t)
            runCatching {
                stop()
                overlay.visibility = View.GONE
            }
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class Chrome(context: Context) : FrameLayout(context) {
        private val line = Paint().apply {
            color = LINE_COLOR
            strokeWidth = max(1f, dp(LINE_DP))
        }
        private val playCircle = Path()

        override fun dispatchDraw(canvas: Canvas) {
            if (failed || stopped) return
            guard("desenho") { update() }
            if (backRect.isEmpty) return
            drawLines(canvas)
            guard("máscara") { maskSpotifyMenu(canvas) }
            super.dispatchDraw(canvas)
        }

        private fun update() {
            readRect(backTarget, backRect)
            if (readRect(menuTarget, menuRect)) lastMenuRect.set(menuRect)
            trackRows()
            if (backRect.isEmpty) {
                show(backButton, false)
                show(moreButton, false)
                return
            }
            getLocationOnScreen(overlayLocation)
            val size = dp(BUTTON_DP)
            show(backButton, true)
            move(backButton, backRect.exactCenterX() - overlayLocation[0] - size / 2f, backRect.exactCenterY() - overlayLocation[1] - size / 2f)
            updatePlay(size)
            show(moreButton, !lastMenuRect.isEmpty && !menuCovered)
            if (!lastMenuRect.isEmpty) {
                move(moreButton, lastMenuRect.exactCenterX() - overlayLocation[0] - size / 2f, lastMenuRect.exactCenterY() - overlayLocation[1] - size / 2f)
            }
        }

        private fun maskSpotifyMenu(canvas: Canvas) {
            if (lastMenuRect.isEmpty || !page.hasDisplayList() || !canvas.isHardwareAccelerated) return
            compose.getLocationOnScreen(composeLocation)
            val dx = (composeLocation[0] - overlayLocation[0]).toFloat()
            val dy = (composeLocation[1] - overlayLocation[1]).toFloat()
            val half = dp(MASK_DP).roundToInt()
            val save = canvas.save()
            canvas.translate(dx, dy)
            if (!playRect.isEmpty) {
                playCircle.rewind()
                playCircle.addCircle(
                    playRect.exactCenterX() - composeLocation[0],
                    playRect.exactCenterY() - composeLocation[1],
                    dp(PLAY_RADIUS_DP + PLAY_CLEARANCE_DP),
                    Path.Direction.CW,
                )
                canvas.clipOutPath(playCircle)
            }
            val x = lastMenuRect.centerX() - composeLocation[0]
            patchFromColumn(canvas, x, lastMenuRect.centerY() - composeLocation[1], half, x + dp(MENU_SOURCE_DP).roundToInt())
            canvas.restoreToCount(save)
        }

        private fun show(view: View, visible: Boolean) {
            val wanted = if (visible) View.VISIBLE else View.INVISIBLE
            if (view.visibility != wanted) view.visibility = wanted
        }

        private fun move(view: View, x: Float, y: Float) {
            if (view.translationX != x) view.translationX = x
            if (view.translationY != y) view.translationY = y
        }

        private fun drawLines(canvas: Canvas) {
            if (!tracking || lines.isEmpty()) return
            val inset = dp(INSET_DP)
            val right = width - inset
            val clipTop = backRect.bottom - overlayLocation[1] + dp(CLIP_GAP_DP)
            for (content in lines) {
                val y = (content - scroll - overlayLocation[1]).toFloat()
                if (y < clipTop) continue
                if (y > height) break
                canvas.drawLine(inset, y, right, y, line)
            }
        }
    }

    private companion object {
        const val BUTTON_DP = 44f
        const val MAX_TARGET_DP = 72f
        const val PLAY_RADIUS_DP = 24f
        const val PLAY_CLEARANCE_DP = 2f
        const val PATCH_MARGIN_DP = 12f
        const val MENU_SOURCE_DP = 15f
        const val MASK_DP = 14f
        const val CLIP_GAP_DP = 12f
        const val SCAN_MS = 250L
        const val PLAY_SCAN_MS = 500L
        const val FAST_SCAN_MS = 100L
        const val FAST_SCAN_WINDOW_MS = 2_500L
        const val IDLE_SCAN_MS = 2_000L
        const val MAX_ITEMS = 400
        const val LINE_COLOR = 0x26FFFFFF
        const val LINE_DP = 0.6f
        const val INSET_DP = 16f
    }
}
