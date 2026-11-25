package app.liquidspot.xposed.features.player

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.widget.TextView
import app.liquidspot.core.GlassStyle
import app.liquidspot.xposed.features.player.lyrics.LyricsSourcePicker
import app.liquidspot.xposed.features.player.lyrics.LyricsView
import app.liquidspot.xposed.ui.PlayerIcon
import app.liquidspot.xposed.ui.glass.BackdropSource
import app.liquidspot.xposed.ui.glass.Spring
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

@SuppressLint("ViewConstructor")
class LiquidPlayerView(context: Context) : ViewGroup(context) {
    val backdropView = ArtworkBackdropView(context)
    private val canvasScrim = CanvasScrim(context).apply { alpha = 0f }
    val artwork = ArtworkView(context)
    private val nextArtwork = ArtworkView(context).apply { visibility = View.INVISIBLE }
    val closeButton: View = Grabber(context).apply { contentDescription = Texts.close }
    val moreButton = IconButton(context, PlayerIcon.MORE_HORIZONTAL, 22f).apply { contentDescription = Texts.more }
    val headerTitle = text(14f, bold = true, alpha = 0.95f).apply { gravity = android.view.Gravity.CENTER }
    val titleView = text(22f, bold = true, alpha = 1f)
    val artistView = text(16f, bold = false, alpha = 0.7f)
    val addButton = IconButton(context, PlayerIcon.ADD, 22f).apply { contentDescription = Texts.add }
    private val actionPill = GlassPill(context)
    val seekBar = LiquidSeekBar(context)
    val shuffleButton = IconButton(context, PlayerIcon.SHUFFLE, 26f).apply { showActiveDot = true; contentDescription = Texts.shuffle }
    val previousButton = IconButton(context, PlayerIcon.REWIND, 44f).apply { contentDescription = Texts.previous }
    val playButton = IconButton(context, PlayerIcon.PLAY, 52f).apply { contentDescription = Texts.play }
    val nextButton = IconButton(context, PlayerIcon.FORWARD, 44f).apply { contentDescription = Texts.next }
    val repeatButton = IconButton(context, PlayerIcon.REPEAT, 26f).apply { showActiveDot = true; contentDescription = Texts.repeat }
    val lyricsButton = IconButton(context, PlayerIcon.LYRICS, 24f).apply { contentDescription = Texts.lyrics }
    val devicesButton = IconButton(context, PlayerIcon.DEVICES, 24f).apply { contentDescription = Texts.devices }
    val queueButton = IconButton(context, PlayerIcon.QUEUE, 24f).apply { contentDescription = Texts.queue }
    val djButton = IconButton(context, PlayerIcon.DJ, 26f).apply {
        contentDescription = Texts.dj
        visibility = View.GONE
    }
    val volumeBar = VolumeBar(context)
    val lyricPreview = LyricTicker(context)
    val translateButton = IconButton(context, PlayerIcon.TRANSLATE, 20f, glass = true).apply { contentDescription = Texts.translate }
    val aiButton = IconButton(context, PlayerIcon.AI, 20f, glass = true).apply { contentDescription = Texts.aiSync }
    val lyricsView = LyricsView(context)

    val backdrop = BackdropSource(backdropView)

    val sourceChip = text(12f, bold = true, alpha = 0.9f).apply {
        gravity = android.view.Gravity.CENTER
        val h = dp(12f).roundToInt()
        setPadding(h, 0, h, 0)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14f)
            setColor(0x26FFFFFF)
        }
        visibility = View.INVISIBLE
    }
    val sourcePicker = LyricsSourcePicker(context, backdrop)
    val menu = PlayerMenu(context, backdrop)

    var explicit = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var style: GlassStyle = GlassStyle.DEFAULT
        set(value) {
            field = value
            actionPill.surface.style = value
            actionPill.invalidate()
            for (button in lyricsControls) {
                button.glassSurface?.style = value
                button.invalidate()
            }
        }

    var reduceMotion = false
        set(value) {
            field = value
            lyricsView.reduceMotion = value
            lyricPreview.reduceMotion = value
        }

    var onDismiss: () -> Unit = {}

    var onSwipeTrack: (Int) -> Unit = {}

    var neighborArt: (Int) -> android.graphics.Bitmap? = { null }

    private var swipeDirection = 0
    private var swipeAnimator: ValueAnimator? = null

    var lyricsProgress = 0f
        private set
    private var lyricsAnimator: ValueAnimator? = null
    private var canvasProgress = 0f
    private var canvasAnimator: ValueAnimator? = null
    val isLyricsMode: Boolean get() = lyricsAnimator?.let { (it.animatedValue as Float) > 0.5f } ?: (lyricsProgress > 0.5f)

    private var insetTop = 0
    private var insetBottom = 0

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt() }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val badgeRect = RectF()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragMode = DRAG_NONE
    private var velocity: VelocityTracker? = null
    private val dismissSpring = Spring(dampingRatio = 0.86f, stiffness = 380f) { setContentOffset(it) }
    private var contentOffset = 0f
    private var sheet: View? = null
    private var dismissAnimator: ValueAnimator? = null
    private val resetDismissRunnable = Runnable { resetDismiss() }

    var sheetProvider: () -> View? = { null }
    var sheetCompanions: () -> List<View> = { emptyList() }
    var sheetBehind: () -> View? = { null }
    private var companions: List<View> = emptyList()
    private var behind: View? = null
    private var behindVisibility = View.VISIBLE
    private var behindAlpha = 1f

    private var savedOutline: ViewOutlineProvider? = null
    private var savedClip = false
    private var sheetCornerRadius = 0f
    private var collapseStarted = 0L

    private val sheetOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val r = sheetCornerRadius
            outline.setRoundRect(0, 0, view.width, view.height + r.roundToInt(), r)
        }
    }

    private val waitForCollapse = object : Runnable {
        override fun run() {
            val target = sheet
            val collapsed = target == null || !target.isAttachedToWindow || !target.isShown || !isShown
            if (collapsed || SystemClock.uptimeMillis() - collapseStarted > COLLAPSE_TIMEOUT_MS) {
                resetDismiss()
            } else {
                postOnAnimation(this)
            }
        }
    }

    fun resetDismiss() {
        removeCallbacks(resetDismissRunnable)
        removeCallbacks(waitForCollapse)
        dismissAnimator?.cancel()
        dismissAnimator = null
        dismissSpring.stop()
        setContentOffset(0f)
        releaseSheet()
    }

    private fun adoptSheet(target: View) {
        sheet = target
        companions = sheetCompanions()
        behind = sheetBehind()?.also {
            behindVisibility = it.visibility
            behindAlpha = it.alpha
            if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE
            if (it.alpha < 1f) it.alpha = 1f
        }
        savedOutline = target.outlineProvider
        savedClip = target.clipToOutline
        target.outlineProvider = sheetOutline
        target.clipToOutline = true
    }

    private fun releaseSheet() {
        val target = sheet ?: return
        target.translationY = 0f
        companions.forEach { it.translationY = 0f }
        companions = emptyList()
        behind?.let {
            if (it.visibility != behindVisibility) it.visibility = behindVisibility
            if (it.alpha != behindAlpha) it.alpha = behindAlpha
        }
        behind = null
        target.outlineProvider = savedOutline
        target.clipToOutline = savedClip
        sheet = null
        savedOutline = null
    }

    private fun setContentOffset(offset: Float) {
        contentOffset = offset
        sheet?.let {
            it.translationY = offset
            for (companion in companions) companion.translationY = offset
            val radius = (offset * SHEET_RADIUS_FACTOR).coerceAtMost(dp(SHEET_RADIUS_DP))
            if (radius != sheetCornerRadius) {
                sheetCornerRadius = radius
                it.invalidateOutline()
            }
            return
        }
        val progress = (offset / (height.coerceAtLeast(1) * 0.5f)).coerceIn(0f, 1f)
        val scale = 1f - 0.06f * progress
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === backdropView || child === canvasScrim || child === sourcePicker || child === menu) continue
            child.translationY = offset
            child.scaleX = scale
            child.scaleY = scale
            child.pivotX = width / 2f - child.left
            child.pivotY = -child.top.toFloat()
        }
        backdropView.alpha = 1f - 0.15f * progress
        invalidate()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        setWillNotDraw(false)
        isClickable = true
        listOf(
            backdropView, canvasScrim, nextArtwork, artwork, headerTitle, closeButton, actionPill, moreButton, titleView, artistView, lyricPreview, addButton,
            seekBar, shuffleButton, previousButton, playButton, nextButton, repeatButton,
            lyricsView, volumeBar, lyricsButton, devicesButton, djButton, queueButton, sourceChip, sourcePicker, menu,
        ).forEach { addView(it) }
        lyricsControls.forEach {
            addView(it)
            it.visibility = View.INVISIBLE
        }
        actionPill.surface.backdrop = backdrop
        actionPill.surface.tintColor = 0x33FFFFFF
        for (button in lyricsControls) {
            button.backdrop = backdrop
            button.glassSurface?.tintColor = 0x33FFFFFF
        }
        headerTitle.visibility = View.GONE
        shuffleButton.visibility = View.GONE
        repeatButton.visibility = View.GONE
        backdropView.isRecordingBackdrop = { backdrop.isRecording }
        lyricsView.alpha = 0f
        lyricsView.visibility = View.INVISIBLE
        lyricsView.onTapEmpty = { if (controlsHide > 0f) setControlsHidden(false) }
        lyricsView.tapInterceptor = { y -> revealTap(y + lyricsView.top) }
        dismissSpring.snapTo(0f)
    }

    private fun text(sizeSp: Float, bold: Boolean, alpha: Float) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(0xFFFFFFFF.toInt())
        this.alpha = alpha
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    fun setPlaying(playing: Boolean, animate: Boolean = true) {
        playButton.icon = if (playing) PlayerIcon.PAUSE else PlayerIcon.PLAY
        playButton.contentDescription = if (playing) Texts.pause else Texts.play
    }

    fun setLyricsMode(enabled: Boolean, animate: Boolean = true) {
        lyricsButton.active = enabled
        val target = if (enabled) 1f else 0f
        lyricsAnimator?.cancel()
        if (!animate || reduceMotion) {
            applyLyricsProgress(target)
            return
        }
        lyricsAnimator = ValueAnimator.ofFloat(lyricsProgress, target).apply {
            duration = 350
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { applyLyricsProgress(it.animatedValue as Float) }
            start()
        }
    }

    fun setCanvasMode(enabled: Boolean, animate: Boolean = true) {
        val target = if (enabled) 1f else 0f
        canvasAnimator?.cancel()
        if (reduceMotion || !animate) {
            applyCanvasProgress(target)
            return
        }
        canvasAnimator = ValueAnimator.ofFloat(canvasProgress, target).apply {
            duration = 550
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { applyCanvasProgress(it.animatedValue as Float) }
            start()
        }
    }

    private fun applyCanvasProgress(t: Float) {
        canvasProgress = t
        updateCanvasBlend()
    }

    private var dragBlend = 0f
    private var blendAnimator: ValueAnimator? = null

    private fun updateCanvasBlend() {
        val shown = max(1f - canvasProgress * (1f - lyricsProgress), dragBlend)
        artwork.alpha = shown
        backdropView.artAlpha = shown
        canvasScrim.alpha = 1f - shown
    }

    fun releaseDragBlend() {
        blendAnimator?.cancel()
        if (dragBlend == 0f) return
        blendAnimator = ValueAnimator.ofFloat(dragBlend, 0f).apply {
            duration = BLEND_RELEASE_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                dragBlend = it.animatedValue as Float
                updateCanvasBlend()
            }
            start()
        }
    }

    fun setLyricsSource(label: String?, hasAlternatives: Boolean) {
        sourceChip.text = label?.let { Texts.lyricsSource(it) + if (hasAlternatives) "  ▾" else "" }.orEmpty()
        applyControlsHide()
    }

    private fun applyLyricsProgress(t: Float) {
        lyricsProgress = t
        lyricsView.visibility = if (t > 0f) View.VISIBLE else View.INVISIBLE
        lyricsView.alpha = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
        if (t < 1f) sourcePicker.hide()
        if (t <= 0f) {
            removeCallbacks(autoHide)
            setControlsHidden(false, animate = false)
        } else if (t >= 1f) {
            scheduleAutoHide()
        }
        applyControlsHide()
        updateCanvasBlend()
        requestLayout()
    }

    var controlsHide = 0f
        private set
    private var hideAnimator: ValueAnimator? = null
    private var revealLine = 0f
    private val autoHide = Runnable { if (isLyricsMode && !sourcePicker.isShowing && !menu.isShowing) setControlsHidden(true) }

    private val hideable: List<View>
        get() = listOf(
            seekBar, previousButton, playButton, nextButton, volumeBar,
            lyricsButton, devicesButton, djButton, queueButton, sourceChip,
        )

    private val lyricsControls: List<IconButton> get() = listOf(translateButton, aiButton)

    private fun hideAmount() = controlsHide * lyricsProgress

    fun scheduleAutoHide() {
        removeCallbacks(autoHide)
        if (lyricsProgress >= 1f) postDelayed(autoHide, AUTO_HIDE_MS)
    }

    fun setControlsHidden(hidden: Boolean, animate: Boolean = true) {
        val target = if (hidden) 1f else 0f
        if (!hidden) scheduleAutoHide()
        hideAnimator?.cancel()
        if (!animate || reduceMotion) {
            controlsHide = target
            applyControlsHide()
            return
        }
        hideAnimator = ValueAnimator.ofFloat(controlsHide, target).apply {
            duration = HIDE_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                controlsHide = it.animatedValue as Float
                applyControlsHide()
            }
            start()
        }
    }

    fun revealTap(y: Float): Boolean {
        if (hideAmount() < 0.5f || y < revealLine) return false
        setControlsHidden(false)
        return true
    }

    fun refreshControls() = applyControlsHide()

    fun setLyricPreview(text: String?) {
        val value = text?.let { "$it  ›" }.orEmpty()
        lyricPreview.setLine(value)
        val wanted = if (value.isEmpty()) View.INVISIBLE else View.VISIBLE
        if (lyricPreview.visibility != wanted) lyricPreview.visibility = wanted
    }

    private fun applyControlsHide() {
        val shown = 1f - hideAmount()
        for (v in hideable) {
            if (v.visibility == View.GONE) continue
            v.alpha = shown
            val wanted = if (shown > 0.01f) View.VISIBLE else View.INVISIBLE
            if (v !== sourceChip && v.visibility != wanted) v.visibility = wanted
        }
        sourceChip.alpha = lyricsView.alpha * 0.9f * shown
        for (b in lyricsControls) {
            if (b.visibility == View.GONE) continue
            b.alpha = lyricsView.alpha * shown
            val wanted = if (lyricsProgress > 0f && shown > 0.01f) View.VISIBLE else View.INVISIBLE
            if (b.visibility != wanted) b.visibility = wanted
        }
        sourceChip.visibility = if (lyricsProgress > 0f && shown > 0.01f && !sourceChip.text.isNullOrEmpty()) View.VISIBLE else View.INVISIBLE
        requestLayout()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        if (insetTop != bars.top || insetBottom != bars.bottom) {
            insetTop = bars.top
            insetBottom = bars.bottom
            requestLayout()
        }
        return insets
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resetDismiss()
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        lyricsAnimator?.cancel()
        hideAnimator?.cancel()
        removeCallbacks(autoHide)
        canvasAnimator?.cancel()
        blendAnimator?.cancel()
        resetDismiss()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val textWidth = MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST)
        val wrap = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        listOf(headerTitle, titleView, artistView).forEach { it.measure(textWidth, wrap) }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val pad = dp(PAD_DP)
        val p = lyricsProgress

        if (insetTop == 0 && insetBottom == 0) {
            rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())?.let {
                insetTop = it.top
                insetBottom = it.bottom
            }
        }

        place(backdropView, 0f, 0f, w.toFloat(), h.toFloat())
        place(canvasScrim, 0f, 0f, w.toFloat(), h.toFloat())

        val grabW = dp(GRABBER_TOUCH_W_DP)
        val grabH = dp(GRABBER_TOUCH_H_DP)
        place(closeButton, (w - grabW) / 2f, insetTop + dp(4f), grabW, grabH)
        val headerBottom = insetTop + dp(4f) + grabH

        val small = dp(44f)
        val rowCenter = h * ROW_AT
        val iconsTop = rowCenter - small / 2f
        val row = listOf(lyricsButton, devicesButton, djButton, queueButton).filter { it.visibility != View.GONE }
        val inner = w - 2 * pad
        row.forEachIndexed { i, button ->
            val cx = w * (i + 1f) / (row.size + 1f)
            place(button, cx - small / 2f, iconsTop, small, small)
        }

        val volH = volumeBar.preferredHeight().toFloat()
        val volCenter = h * VOLUME_AT
        val volTop = volCenter - volH / 2f
        place(volumeBar, pad, volTop, inner, volH)

        val playSize = dp(76f)
        val skipSize = dp(64f)
        val controlsCenter = h * CONTROLS_AT
        place(previousButton, w / 2f - inner * SKIP_OFFSET - skipSize / 2f, controlsCenter - skipSize / 2f, skipSize, skipSize)
        place(playButton, (w - playSize) / 2f, controlsCenter - playSize / 2f, playSize, playSize)
        place(nextButton, w / 2f + inner * SKIP_OFFSET - skipSize / 2f, controlsCenter - skipSize / 2f, skipSize, skipSize)

        val seekH = seekBar.preferredHeight().toFloat()
        val seekTop = h * SEEK_AT
        place(seekBar, pad, seekTop, inner, seekH)

        val roundSize = dp(ROUND_BUTTON_DP)
        val infoH = titleView.measuredHeight + dp(4f) + artistView.measuredHeight
        val previewH = lyricPreview.lineHeight().toFloat()
        val previewSlot = seekTop - dp(PREVIEW_GAP_DP) - previewH
        place(lyricPreview, pad, seekTop - dp(PREVIEW_NEAR_DP) - previewH, inner, previewH)
        lyricPreview.alpha = (1f - p * 2f).coerceIn(0f, 1f) * PREVIEW_ALPHA
        val playerInfoTop = previewSlot - dp(INFO_GAP_DP) - infoH
        val smallSize = dp(56f)
        val smallLeft = pad
        val smallTop = headerBottom + dp(10f)

        val heroHeight = (playerInfoTop + dp(HERO_OVERLAP_DP)).coerceIn(w.toFloat(), w * HERO_MAX_RATIO)
        val artW = lerp(w.toFloat(), smallSize, p)
        val artH = lerp(heroHeight, smallSize, p)
        val artLeft = lerp(0f, smallLeft, p)
        val artTop = lerp(0f, smallTop, p)
        place(artwork, artLeft, artTop, artW, artH)
        place(nextArtwork, artLeft, artTop, artW, artH)
        for (art in listOf(artwork, nextArtwork)) {
            art.cornerRadius = lerp(0f, dp(8f), p)
            art.fadeBottom = lerp(HERO_FADE, 0f, p)
            art.elevation = lerp(0f, dp(12f), p)
        }

        val lyricsInfoLeft = smallLeft + smallSize + dp(12f)
        val lyricsInfoTop = smallTop + (smallSize - infoH) / 2f
        val infoLeft = lerp(pad, lyricsInfoLeft, p)
        val infoTop = lerp(playerInfoTop, lyricsInfoTop, p)
        val badgeSpace = if (explicit) dp(22f) else 0f
        val pillW = dp(PILL_W_DP)
        val pillH = dp(PILL_H_DP)
        val pillLeft = w - pad - pillW
        val infoW = pillLeft - dp(10f) - infoLeft
        layoutText(titleView, infoLeft, infoTop, infoW)
        layoutText(artistView, infoLeft + badgeSpace, infoTop + titleView.measuredHeight + dp(4f), infoW - badgeSpace)
        val pillTop = infoTop + (infoH - pillH) / 2f
        place(actionPill, pillLeft, pillTop, pillW, pillH)
        place(addButton, pillLeft, pillTop, pillW / 2f, pillH)
        place(moreButton, pillLeft + pillW / 2f, pillTop, pillW / 2f, pillH)

        val chipH = dp(28f)
        sourceChip.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(chipH.roundToInt(), MeasureSpec.EXACTLY))
        val chipW = min(sourceChip.measuredWidth.toFloat(), inner - 2 * (roundSize + dp(8f)))
        val chipTop = seekTop - dp(12f) - chipH
        place(sourceChip, (w - chipW) / 2f, chipTop, chipW, chipH)
        val chipCenter = chipTop + chipH / 2f
        place(translateButton, w - pad - roundSize, chipCenter - roundSize / 2f, roundSize, roundSize)
        place(aiButton, pad, chipCenter - roundSize / 2f, roundSize, roundSize)

        val lyricsTop = smallTop + smallSize + dp(18f)
        val lyricsBottom = lerp(chipTop - dp(10f), h - insetBottom - dp(24f), hideAmount())
        val bleed = dp(LYRICS_BLEED_DP)
        lyricsView.sidePadding = pad
        place(lyricsView, 0f, lyricsTop - bleed, w.toFloat(), max(0f, lyricsBottom - lyricsTop) + 2 * bleed)
        revealLine = seekTop
        place(sourcePicker, 0f, 0f, w.toFloat(), h.toFloat())
        place(menu, 0f, 0f, w.toFloat(), h.toFloat())
    }

    private fun place(v: View, x: Float, y: Float, w: Float, h: Float) {
        val left = x.roundToInt()
        val top = y.roundToInt()
        val width = w.roundToInt().coerceAtLeast(0)
        val height = h.roundToInt().coerceAtLeast(0)
        if (v.isLayoutRequested || v.measuredWidth != width || v.measuredHeight != height) {
            v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
        v.layout(left, top, left + width, top + height)
    }

    private fun layoutText(v: TextView, x: Float, y: Float, maxWidth: Float) {
        val width = maxWidth.roundToInt().coerceAtLeast(0)
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        v.layout(x.roundToInt(), y.roundToInt(), x.roundToInt() + width, y.roundToInt() + v.measuredHeight)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!explicit || backdrop.isRecording) return
        val size = dp(16f)
        val left = artistView.x - dp(22f)
        val top = artistView.y + (artistView.height - size) / 2f
        badgeRect.set(left, top, left + size, top + size)
        canvas.drawRoundRect(badgeRect, dp(3f), dp(3f), badgePaint)
        badgeText.textSize = dp(11f)
        canvas.drawText("E", badgeRect.centerX(), badgeRect.centerY() + badgeText.textSize * 0.36f, badgeText)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragMode = DRAG_NONE
            }
            MotionEvent.ACTION_MOVE -> if (detectDrag(ev)) return true
        }
        return false
    }

    private fun detectDrag(ev: MotionEvent): Boolean {
        if (dragMode != DRAG_NONE) return false
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (dy > touchSlop && dy > abs(dx) * 1.5f && !isInside(lyricsView, downX, downY) && !isInside(seekBar, downX, downY) && !isInside(volumeBar, downX, downY)) {
            dragMode = DRAG_DISMISS
            removeCallbacks(resetDismissRunnable)
            dismissAnimator?.cancel()
            val found = sheetProvider()
            if (found !== sheet) {
                setContentOffset(0f)
                releaseSheet()
                found?.let(::adoptSheet)
            }
        } else if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f && !isLyricsMode && isInside(artwork, downX, downY)) {
            dragMode = DRAG_TRACK
        }
        if (dragMode == DRAG_NONE) return false
        velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragMode = DRAG_NONE
            }
            MotionEvent.ACTION_MOVE -> if (dragMode == DRAG_NONE) detectDrag(event) else when (dragMode) {
                DRAG_DISMISS -> {
                    dismissSpring.stop()
                    val raw = max(0f, event.rawY - rawDown)
                    setContentOffset(if (sheet != null) raw else raw * 0.85f)
                }
                DRAG_TRACK -> setTrackDrag(event.x - downX)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                val vx = velocity?.xVelocity ?: 0f
                when (dragMode) {
                    DRAG_DISMISS -> {
                        val dismiss = contentOffset > dp(120f) || vy > 2200f
                        if (dismiss) {
                            animateDismiss()
                        } else {
                            dismissSpring.snapTo(contentOffset)
                            dismissSpring.animateTo(0f)
                        }
                    }
                    DRAG_TRACK -> {
                        val dx = event.x - downX
                        val commit = abs(dx) > dp(90f) || (abs(vx) > 1500f && sign(vx) == sign(dx))
                        finishTrackDrag(dx, commit && dx != 0f)
                    }
                }
                dragMode = DRAG_NONE
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    private fun animateDismiss() {
        dismissSpring.stop()
        val from = contentOffset
        val to = ((sheet ?: this).height.takeIf { it > 0 } ?: height).toFloat()
        var canceled = false
        dismissAnimator?.cancel()
        dismissAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = (DISMISS_MS * (1f - from / to)).toLong().coerceAtLeast(DISMISS_MIN_MS)
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { setContentOffset(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (canceled) return
                    dismissAnimator = null
                    onDismiss()
                    if (sheet != null) {
                        collapseStarted = SystemClock.uptimeMillis()
                        postOnAnimation(waitForCollapse)
                    } else {
                        postDelayed(resetDismissRunnable, DISMISS_RESET_MS)
                    }
                }
            })
            start()
        }
    }

    private fun trackSpacing() = artwork.width + dp(24f)

    private fun setTrackDrag(dx: Float) {
        swipeAnimator?.cancel()
        applyTrackDrag(dx)
    }

    private fun applyTrackDrag(dx: Float) {
        val direction = if (dx < 0) 1 else -1
        if (dx != 0f && direction != swipeDirection) {
            swipeDirection = direction
            nextArtwork.setArtwork(neighborArt(direction), animate = false)
        }
        artwork.translationX = dx
        nextArtwork.translationX = dx + direction * trackSpacing()
        nextArtwork.visibility = if (dx != 0f) View.VISIBLE else View.INVISIBLE
        if (dx == 0f) {
            backdropView.clearSwipe()
        } else {
            backdropView.setSwipe(nextArtwork.bitmap, dx * width / trackSpacing().coerceAtLeast(1f), direction)
        }
        onTrackDragOffset(dx)
    }

    private var swipeCommitted = false

    fun animateTrackChange(direction: Int): Boolean {
        if (reduceMotion || lyricsProgress > 0f || dragMode != DRAG_NONE || swipeDirection != 0) return false
        val art = neighborArt(direction) ?: return false
        swipeAnimator?.cancel()
        swipeDirection = direction
        nextArtwork.setArtwork(art, animate = false)
        finishTrackDrag(0f, commit = true)
        return true
    }

    fun takeSwipeCommit(): Boolean = swipeCommitted.also { swipeCommitted = false }

    var onTrackDragOffset: (Float) -> Unit = {}
    var onTrackDragEnd: (Boolean) -> Unit = {}
    val isTrackDragging: Boolean get() = swipeDirection != 0 || dragMode == DRAG_TRACK

    private fun finishTrackDrag(dx: Float, commit: Boolean) {
        val direction = swipeDirection
        val target = if (commit) -direction * trackSpacing() else 0f
        swipeAnimator?.cancel()
        swipeAnimator = ValueAnimator.ofFloat(dx, target).apply {
            duration = if (commit) 260 else 220
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { applyTrackDrag(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (commit) {
                        artwork.showFrom(nextArtwork)
                        backdropView.commitSwipe()
                        if (canvasProgress > 0f) {
                            blendAnimator?.cancel()
                            dragBlend = 1f
                            updateCanvasBlend()
                        }
                        swipeCommitted = true
                        onSwipeTrack(direction)
                    } else {
                        backdropView.clearSwipe()
                    }
                    artwork.translationX = 0f
                    nextArtwork.translationX = 0f
                    nextArtwork.visibility = View.INVISIBLE
                    swipeDirection = 0
                    onTrackDragEnd(commit)
                }
            })
            start()
        }
    }

    private var rawDown = 0f

    var lastDownX = 0f
        private set
    var lastDownY = 0f
        private set

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (controlsHide == 0f) scheduleAutoHide()
            rawDown = ev.rawY
            lastDownX = ev.x
            lastDownY = ev.y
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isInside(v: View, x: Float, y: Float) =
        v.visibility == View.VISIBLE && x >= v.left && x <= v.right && y >= v.top && y <= v.bottom

    private companion object {
        const val PAD_DP = 24f
        const val AUTO_HIDE_MS = 5_000L
        const val HIDE_MS = 320L
        const val GRABBER_TOUCH_W_DP = 96f
        const val GRABBER_TOUCH_H_DP = 28f
        const val ROUND_BUTTON_DP = 36f
        const val SKIP_OFFSET = 0.3f
        const val DISMISS_RESET_MS = 700L
        const val DISMISS_MS = 320L
        const val HERO_FADE = 0.32f
        const val HERO_OVERLAP_DP = 40f
        const val HERO_MAX_RATIO = 1.12f
        const val ROW_AT = 0.91f
        const val VOLUME_AT = 0.8385f
        const val CONTROLS_AT = 0.7425f
        const val SEEK_AT = 0.628f
        const val LYRICS_BLEED_DP = 24f
        const val PREVIEW_GAP_DP = 18f
        const val PREVIEW_NEAR_DP = 6f
        const val INFO_GAP_DP = 16f
        const val PREVIEW_ALPHA = 0.75f
        const val PILL_W_DP = 92f
        const val PILL_H_DP = 38f
        const val BLEND_RELEASE_MS = 450L
        const val COLLAPSE_TIMEOUT_MS = 1_500L
        const val SHEET_RADIUS_DP = 32f
        const val SHEET_RADIUS_FACTOR = 0.25f
        const val DISMISS_MIN_MS = 120L
        const val DRAG_NONE = 0
        const val DRAG_DISMISS = 1
        const val DRAG_TRACK = 2
    }
}

private class Grabber(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt() }

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val w = 36f * density
        val h = 5f * density
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        canvas.drawRoundRect(left, top, left + w, top + h, h / 2f, h / 2f, paint)
    }
}

private class CanvasScrim(context: Context) : View(context) {
    private val paint = Paint()
    private var shaderHeight = 0

    override fun onDraw(canvas: Canvas) {
        if (height != shaderHeight) {
            shaderHeight = height
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(0x59000000, 0x00000000, 0x00000000, 0x8C000000.toInt(), 0xD9000000.toInt()),
                floatArrayOf(0f, 0.16f, 0.45f, 0.72f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

private class GlassPill(context: Context) : View(context) {
    val surface = app.liquidspot.xposed.ui.glass.GlassSurface(this).apply { shadowEnabled = false }
    private val rect = RectF()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        surface.backdrop?.addClient(this)
    }

    override fun onDetachedFromWindow() {
        surface.backdrop?.removeClient(this)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        surface.draw(canvas, rect, height / 2f)
    }
}
