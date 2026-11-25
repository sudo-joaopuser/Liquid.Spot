package app.liquidspot.xposed.features.navbar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.annotation.RequiresApi
import app.liquidspot.core.GlassStyle
import app.liquidspot.core.NavBarLayout
import app.liquidspot.xposed.theme.SpotifyTheme
import app.liquidspot.xposed.ui.glass.BackdropSource
import app.liquidspot.xposed.ui.glass.GlassSurface
import app.liquidspot.xposed.ui.glass.HIGHLIGHT_SHADER
import app.liquidspot.xposed.ui.glass.LENS_WITH_DISPERSION_SHADER
import app.liquidspot.xposed.ui.glass.Spring
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class LiquidNavBarView(context: Context) : View(context) {
    var onTabClick: (NavTab) -> Unit = {}

    var layout: NavBarLayout = NavBarLayout.DEFAULT
        set(value) {
            if (field != value) {
                field = value
                relayout(animate = false)
            }
        }

    var style: GlassStyle
        get() = pillSurface.style
        set(value) {
            pillSurface.style = value
            bubbleSurface.style = value
            invalidate()
        }

    var reduceMotion: Boolean = false
        set(value) {
            field = value
            indicator.reduceMotion = value
            lensSpring.reduceMotion = value
        }

    var backdrop: BackdropSource?
        get() = pillSurface.backdrop
        set(value) {
            pillSurface.backdrop?.removeClient(this)
            pillSurface.backdrop = value
            bubbleSurface.backdrop = value
            if (isAttachedToWindow) value?.addClient(this)
        }

    var bottomInset: Int = 0
        set(value) {
            if (field != value) {
                field = value
                relayout(animate = false)
            }
        }

    var selectedTab: NavTab? = null
        private set

    private val pillSurface = GlassSurface(this)
    private val bubbleSurface = GlassSurface(this)

    private val pillRect = RectF()
    private val bubbleRect = RectF()
    private val indicatorRect = RectF()
    private var pillTabs: List<NavTab> = emptyList()
    private var bubbleTab: NavTab? = null

    private val icons = HashMap<Pair<NavTab, Boolean>, Drawable>()
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INDICATOR_COLOR }

    private val indicator = Spring { invalidate() }
    private var indicatorAlpha = 0f
    private val indicatorAlphaSpring = Spring(dampingRatio = 1f, stiffness = 600f) {
        indicatorAlpha = it
        invalidate()
    }

    var lensEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) setLens(false)
        }

    private var lensPresence = 0f
    private val lensSpring = Spring(dampingRatio = 0.62f, stiffness = 280f) {
        lensPresence = it
        invalidate()
    }
    private var lensActive = false
    private val lensRect = RectF()
    private val lensPath = Path()
    private val contentNode = RenderNode("LiquidSpotBarContent")
    private val lensNode = RenderNode("LiquidSpotBarLens")
    private var lensShader: RuntimeShader? = null
    private var rimShader: RuntimeShader? = null
    private var lensBroken = false
    private val viewLocation = IntArray(2)
    private val sourceLocation = IntArray(2)
    private val lensShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lensFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LENS_FILL }
    private val lensRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        blendMode = android.graphics.BlendMode.PLUS
    }
    private val startLens = Runnable { if (downInPill) setLens(true) }

    var onCollapsedTap: () -> Unit = {}

    var collapse = 0f
        private set
    private val collapseSpring = Spring(dampingRatio = 0.86f, stiffness = 380f) {
        collapse = it
        invalidate()
    }
    var collapsed = false
        private set
    private val drawPill = RectF()
    private val circleRect = RectF()
    private var downInCircle = false

    fun setCollapsed(value: Boolean, animate: Boolean = true) {
        if (collapsed == value) return
        collapsed = value
        if (value) setLens(false)
        val target = if (value) 1f else 0f
        if (animate && !reduceMotion) collapseSpring.animateTo(target) else collapseSpring.snapTo(target)
    }

    fun pillCenterY(): Float = pillRect.centerY()

    fun collapsedLeftInset(): Float = circleRect.right + dp(COLLAPSED_GAP_DP)

    fun collapsedRightInset(): Float =
        if (bubbleRect.isEmpty) dp(GUTTER_DP) else width - bubbleRect.left + dp(COLLAPSED_GAP_DP)

    private var dragging = false
    private var downX = 0f
    private var downInPill = false
    private var lastHapticIndex = -1

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    fun selectTab(tab: NavTab?, animate: Boolean = true) {
        if (selectedTab == tab) {
            val index = pillTabs.indexOf(tab)
            if (!dragging && index >= 0 && indicator.target != index.toFloat()) {
                if (animate) indicator.animateTo(index.toFloat()) else indicator.snapTo(index.toFloat())
            }
            return
        }
        selectedTab = tab
        val index = pillTabs.indexOf(tab)
        if (index >= 0) {
            if (animate && indicatorAlpha > 0f) indicator.animateTo(index.toFloat()) else indicator.snapTo(index.toFloat())
            animateAlpha(1f, animate)
        } else {
            animateAlpha(0f, animate)
        }
        invalidate()
    }

    private fun animateAlpha(target: Float, animate: Boolean) {
        if (animate && !reduceMotion) indicatorAlphaSpring.animateTo(target) else indicatorAlphaSpring.snapTo(target)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        backdrop?.addClient(this)
    }

    override fun onDetachedFromWindow() {
        backdrop?.removeClient(this)
        indicator.stop()
        indicatorAlphaSpring.stop()
        lensSpring.stop()
        collapseSpring.stop()
        removeCallbacks(startLens)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout(animate = false)
    }

    fun barHeight(): Int = (dp(PILL_HEIGHT_DP) + dp(LIFT_DP) + dp(TOP_SPACE_DP)).roundToInt()

    private fun relayout(animate: Boolean) {
        pillTabs = when (layout) {
            NavBarLayout.THREE_TABS, NavBarLayout.CREATE_BUBBLE -> listOf(NavTab.HOME, NavTab.SEARCH, NavTab.LIBRARY)
            NavBarLayout.SEARCH_BUBBLE -> listOf(NavTab.HOME, NavTab.LIBRARY, NavTab.CREATE)
        }
        bubbleTab = when (layout) {
            NavBarLayout.THREE_TABS -> null
            NavBarLayout.SEARCH_BUBBLE -> NavTab.SEARCH
            NavBarLayout.CREATE_BUBBLE -> NavTab.CREATE
        }

        val pillH = dp(PILL_HEIGHT_DP)
        val bottom = height - bottomInset - dp(LIFT_DP)
        val top = bottom - pillH
        val available = width - 2 * dp(GUTTER_DP)
        val bubbleSpace = if (bubbleTab != null) pillH + dp(BUBBLE_GAP_DP) else 0f
        val pillW = min(available - bubbleSpace, 2 * dp(PILL_INSET_DP) + pillTabs.size * dp(TAB_MAX_WIDTH_DP))
        val groupW = pillW + bubbleSpace
        val left = (width - groupW) / 2f
        pillRect.set(left, top, left + pillW, bottom)
        if (bubbleTab != null) {
            val bLeft = pillRect.right + dp(BUBBLE_GAP_DP)
            bubbleRect.set(bLeft, top, bLeft + pillH, bottom)
        } else {
            bubbleRect.setEmpty()
        }
        val circleLeft = dp(GUTTER_DP)
        circleRect.set(circleLeft, top, circleLeft + pillH, bottom)

        val sel = selectedTab
        selectedTab = null
        selectTab(sel, animate)
        invalidate()
    }

    private fun tabWidth() = (pillRect.width() - 2 * dp(PILL_INSET_DP)) / pillTabs.size.coerceAtLeast(1)

    private fun tabCenterX(index: Float) = pillRect.left + dp(PILL_INSET_DP) + tabWidth() * (index + 0.5f)

    private var drawFailed = false

    override fun onDraw(canvas: Canvas) {
        if (drawFailed) return
        try {
            drawBar(canvas)
        } catch (t: Throwable) {
            drawFailed = true
            app.liquidspot.xposed.util.Logger.e("Barra Liquid Glass: erro ao desenhar", t)
        }
    }

    private val lensAvailable: Boolean
        get() = lensEnabled && !lensBroken && !GlassSurface.shadersBroken &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            pillSurface.effectiveStyle == GlassStyle.LIQUID && canvasIsHardware

    private var canvasIsHardware = true

    private fun setLens(active: Boolean) {
        val wanted = active && lensAvailable
        if (lensActive == wanted) return
        lensActive = wanted
        if (wanted) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        lensSpring.animateTo(if (wanted) 1f else 0f)
    }

    private fun drawBar(canvas: Canvas) {
        if (pillRect.isEmpty) return
        canvasIsHardware = canvas.isHardwareAccelerated
        val lens = lensPresence > 0.01f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !lensBroken &&
            canvas.isHardwareAccelerated && backdrop?.isRecording != true
        drawContent(canvas, indicatorFade = if (lens) 1f - lensPresence.coerceIn(0f, 1f) else 1f)
        if (lens) {
            runCatching { drawLens(canvas) }.onFailure {
                lensBroken = true
                app.liquidspot.xposed.util.Logger.e("Barra Liquid Glass: lente desativada após erro", it)
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun updateDrawPill() {
        val c = collapse.coerceIn(0f, 1f)
        drawPill.set(
            lerp(pillRect.left, circleRect.left, c),
            pillRect.top,
            lerp(pillRect.right, circleRect.right, c),
            pillRect.bottom,
        )
    }

    private fun circleTab(): NavTab? = selectedTab?.takeIf { it in pillTabs } ?: pillTabs.firstOrNull()

    private fun drawContent(canvas: Canvas, indicatorFade: Float) {
        updateDrawPill()
        val c = collapse.coerceIn(0f, 1f)
        val radius = drawPill.height() / 2f
        pillSurface.draw(canvas, drawPill, radius)
        if (!bubbleRect.isEmpty) bubbleSurface.draw(canvas, bubbleRect, bubbleRect.height() / 2f)
        if (backdrop?.isRecording == true) return

        if (c > 0.001f) {
            val shown = circleTab()
            canvas.save()
            canvas.clipRect(drawPill)
            if (shown != null && shown == selectedTab && indicatorAlpha > 0.01f) {
                val inset = dp(PILL_INSET_DP)
                val fullH = pillRect.height() - 2 * inset
                val w = lerp(tabWidth(), fullH, c)
                val cx = lerp(tabCenterX(indicator.value), circleRect.centerX(), c)
                indicatorRect.set(cx - w / 2f, pillRect.centerY() - fullH / 2f, cx + w / 2f, pillRect.centerY() + fullH / 2f)
                indicatorPaint.alpha = (alphaOf(INDICATOR_COLOR) * indicatorAlpha).roundToInt()
                canvas.drawRoundRect(indicatorRect, fullH / 2f, fullH / 2f, indicatorPaint)
            }
            pillTabs.forEachIndexed { i, tab ->
                val isCircle = tab == shown
                val alpha = if (isCircle) 1f else (1f - c * 2f).coerceIn(0f, 1f)
                if (alpha <= 0f) return@forEachIndexed
                val cx = lerp(tabCenterX(i.toFloat()), circleRect.centerX(), c)
                drawIcon(canvas, tab, tab == selectedTab, cx, pillRect.centerY(), 1f, alpha)
            }
            canvas.restore()
            drawBubbleContent(canvas)
            return
        }

        if (indicatorAlpha * indicatorFade > 0.01f && pillTabs.isNotEmpty()) {
            val lag = min(abs(indicator.target - indicator.value), 1f)
            val inset = dp(PILL_INSET_DP)
            val w = tabWidth() * (1f + lag * STRETCH)
            val h = (pillRect.height() - 2 * inset) * (1f - lag * STRETCH * SQUASH)
            val cx = tabCenterX(indicator.value)
            val cy = pillRect.centerY()
            indicatorRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            indicatorPaint.alpha = (alphaOf(INDICATOR_COLOR) * indicatorAlpha * indicatorFade).roundToInt()
            canvas.drawRoundRect(indicatorRect, h / 2f, h / 2f, indicatorPaint)
        }
        pillTabs.forEachIndexed { i, tab ->
            val closeness = 1f - min(abs(indicator.value - i), 1f)
            val isSelected = tab == selectedTab
            val scale = if (isSelected) 1f + 0.08f * closeness else 1f
            drawIcon(canvas, tab, isSelected, tabCenterX(i.toFloat()), pillRect.centerY(), scale)
        }
        drawBubbleContent(canvas)
    }

    private fun drawBubbleContent(canvas: Canvas) {
        val tab = bubbleTab ?: return
        if (selectedTab == tab) {
            val inset = dp(PILL_INSET_DP)
            indicatorPaint.alpha = alphaOf(INDICATOR_COLOR)
            canvas.drawCircle(bubbleRect.centerX(), bubbleRect.centerY(), bubbleRect.height() / 2f - inset, indicatorPaint)
        }
        drawIcon(canvas, tab, tab == selectedTab, bubbleRect.centerX(), bubbleRect.centerY(), if (tab == selectedTab) 1.08f else 1f)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawLens(canvas: Canvas) {
        val presence = lensPresence.coerceAtLeast(0f)
        val shown = presence.coerceAtMost(1f)
        val inset = dp(PILL_INSET_DP)
        val grow = dp(LENS_GROW_DP) * presence
        val speed = abs(indicator.velocity) * tabWidth()
        val squash = (speed / dp(SQUASH_SPEED_DP)).coerceIn(0f, 1f) * SQUASH_MAX * shown
        val w = (tabWidth() + grow * 2f) * (1f + squash)
        val h = (pillRect.height() - 2 * inset + grow * 2f) * (1f - squash * 0.6f)
        val cx = tabCenterX(indicator.value)
        val cy = pillRect.centerY()
        lensRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val r = h / 2f

        contentNode.setPosition(0, 0, width, height)
        val content = contentNode.beginRecording(width, height)
        try {
            backdrop?.let { source ->
                getLocationInWindow(viewLocation)
                source.locationInWindow(sourceLocation)
                content.save()
                content.translate((sourceLocation[0] - viewLocation[0]).toFloat(), (sourceLocation[1] - viewLocation[1]).toFloat())
                content.drawRenderNode(source.node)
                content.restore()
            }
            drawContent(content, indicatorFade = 0f)
        } finally {
            contentNode.endRecording()
        }

        val pad = dp(LENS_PAD_DP)
        lensNode.setPosition(0, 0, (w + 2 * pad).roundToInt(), (h + 2 * pad).roundToInt())
        val lens = lensNode.beginRecording()
        try {
            lens.translate(pad - lensRect.left, pad - lensRect.top)
            val zoom = 1f + LENS_ZOOM * shown
            lens.scale(zoom, zoom, lensRect.centerX(), lensRect.centerY())
            lens.drawRenderNode(contentNode)
        } finally {
            lensNode.endRecording()
        }
        val shader = lensShader ?: RuntimeShader(LENS_WITH_DISPERSION_SHADER).also { lensShader = it }
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("offset", -pad, -pad)
        shader.setFloatUniform("cornerRadii", r, r, r, r)
        shader.setFloatUniform("refractionHeight", dp(LENS_HEIGHT_DP))
        shader.setFloatUniform("refractionAmount", -dp(LENS_AMOUNT_DP) * shown)
        shader.setFloatUniform("depthEffect", 1f)
        shader.setFloatUniform("chromaticAberration", 1f)
        lensNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))

        lensPath.reset()
        lensPath.addRoundRect(lensRect, r, r, Path.Direction.CW)
        val layer = canvas.saveLayerAlpha(lensRect.left - pad, lensRect.top - pad, lensRect.right + pad, lensRect.bottom + pad, (shown * 255).roundToInt())
        lensShadowPaint.color = 0x01000000
        lensShadowPaint.setShadowLayer(dp(LENS_SHADOW_DP), 0f, dp(3f), LENS_SHADOW)
        canvas.drawPath(lensPath, lensShadowPaint)
        canvas.save()
        canvas.clipPath(lensPath)
        canvas.translate(lensRect.left - pad, lensRect.top - pad)
        canvas.drawRenderNode(lensNode)
        canvas.restore()
        canvas.drawPath(lensPath, lensFillPaint)
        drawLensRim(canvas, r)
        canvas.restoreToCount(layer)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawLensRim(canvas: Canvas, radius: Float) {
        val shader = rimShader ?: RuntimeShader(HIGHLIGHT_SHADER).also { rimShader = it }
        shader.setFloatUniform("size", lensRect.width(), lensRect.height())
        shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
        shader.setColorUniform("color", android.graphics.Color.WHITE)
        shader.setFloatUniform("angle", Math.toRadians(45.0).toFloat())
        shader.setFloatUniform("falloff", 1f)
        lensRimPaint.shader = shader
        lensRimPaint.strokeWidth = dp(1.5f)
        lensRimPaint.alpha = 220
        val inset = lensRimPaint.strokeWidth / 2f
        canvas.save()
        canvas.translate(lensRect.left, lensRect.top)
        canvas.drawRoundRect(inset, inset, lensRect.width() - inset, lensRect.height() - inset, radius, radius, lensRimPaint)
        canvas.restore()
    }

    private fun drawIcon(canvas: Canvas, tab: NavTab, active: Boolean, cx: Float, cy: Float, scale: Float, alpha: Float = 1f) {
        val icon = icons.getOrPut(tab to active) { tab.icon(context, active) }
        val half = dp(ICON_SIZE_DP) / 2f
        icon.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
        icon.setTint(if (active) SpotifyTheme.accent else INACTIVE_COLOR)
        icon.alpha = (alpha * 255).roundToInt()
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        icon.draw(canvas)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (collapsed || downInCircle) return onCollapsedTouch(event)
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                dragging = false
                downInPill = hit(pillRect, x, y)
                lastHapticIndex = if (downInPill) indexAt(x) else -1
                if (downInPill) postDelayed(startLens, LENS_HOLD_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (downInPill && !dragging && abs(x - downX) > dp(8f)) {
                    dragging = true
                    removeCallbacks(startLens)
                    setLens(true)
                }
                if (dragging) {
                    val index = indexAt(x)
                    indicator.animateTo(((x - pillRect.left - dp(PILL_INSET_DP)) / tabWidth() - 0.5f).coerceIn(0f, pillTabs.size - 1f))
                    animateAlpha(1f, true)
                    if (index != lastHapticIndex) {
                        lastHapticIndex = index
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(startLens)
                setLens(false)
                val tab = when {
                    dragging -> pillTabs.getOrNull(indexAt(x))
                    downInPill && hit(pillRect, x, y) -> pillTabs.getOrNull(indexAt(x))
                    hit(bubbleRect, x, y) && hit(bubbleRect, downX, y) -> bubbleTab
                    else -> null
                }
                dragging = false
                if (tab != null) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (tab != NavTab.CREATE) {
                        selectTab(tab)
                    } else {
                        val sel = selectedTab
                        selectedTab = null
                        selectTab(sel)
                    }
                    onTabClick(tab)
                    performClick()
                } else {
                    val sel = selectedTab
                    selectedTab = null
                    selectTab(sel)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(startLens)
                setLens(false)
                dragging = false
                val sel = selectedTab
                selectedTab = null
                selectTab(sel)
                return true
            }
        }
        return true
    }

    private fun onCollapsedTouch(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                updateDrawPill()
                downX = x
                downInCircle = hit(drawPill, x, y)
                downInPill = false
                if (downInCircle || hit(bubbleRect, x, y)) return true
                return y < drawPill.top - dp(TOP_SPACE_DP) || y > drawPill.bottom
            }
            MotionEvent.ACTION_UP -> {
                val circle = downInCircle
                downInCircle = false
                when {
                    circle && hit(drawPill, x, y) -> {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onCollapsedTap()
                        performClick()
                    }
                    !circle && hit(bubbleRect, x, y) && hit(bubbleRect, downX, y) -> bubbleTab?.let { tab ->
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        if (tab != NavTab.CREATE) selectTab(tab)
                        onTabClick(tab)
                        performClick()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> downInCircle = false
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun indexAt(x: Float): Int =
        ((x - pillRect.left - dp(PILL_INSET_DP)) / tabWidth()).toInt().coerceIn(0, pillTabs.size - 1)

    private fun hit(rect: RectF, x: Float, y: Float): Boolean {
        if (rect.isEmpty) return false
        val slop = dp(6f)
        return x >= rect.left - slop && x <= rect.right + slop && y >= rect.top - slop && y <= rect.bottom + slop
    }

    private companion object {
        const val GUTTER_DP = 19f
        const val LIFT_DP = 6f
        const val TOP_SPACE_DP = 8f
        const val PILL_HEIGHT_DP = 58f
        const val PILL_INSET_DP = 4f
        const val TAB_MAX_WIDTH_DP = 92f
        const val BUBBLE_GAP_DP = 14f
        const val ICON_SIZE_DP = 26f
        const val STRETCH = 0.16f
        const val SQUASH = 0.5f
        const val COLLAPSED_GAP_DP = 10f
        const val LENS_HOLD_MS = 160L
        const val LENS_GROW_DP = 7f
        const val LENS_PAD_DP = 24f
        const val LENS_HEIGHT_DP = 12f
        const val LENS_AMOUNT_DP = 14f
        const val LENS_ZOOM = 0.12f
        const val LENS_SHADOW_DP = 10f
        const val SQUASH_SPEED_DP = 2400f
        const val SQUASH_MAX = 0.12f
        const val LENS_FILL = 0x12FFFFFF
        const val LENS_SHADOW = 0x38000000

        const val INACTIVE_COLOR = 0xFFFFFFFF.toInt()
        const val INDICATOR_COLOR = 0x24FFFFFF

        fun alphaOf(color: Int) = color ushr 24
    }
}
