package app.liquidspot.xposed.features.navbar

import android.app.Activity
import android.graphics.Outline
import android.graphics.Rect
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import app.liquidspot.xposed.features.debug.UiDumper
import app.liquidspot.xposed.features.pages.ComposePageController
import app.liquidspot.xposed.features.pages.PageController
import app.liquidspot.xposed.features.pages.PageStyler
import app.liquidspot.xposed.features.player.IconButton
import app.liquidspot.xposed.features.player.Texts
import app.liquidspot.xposed.features.player.PlayerController
import app.liquidspot.xposed.media.NowPlayingStore
import app.liquidspot.xposed.ui.PlayerIcon
import app.liquidspot.xposed.ui.glass.BackdropSource
import app.liquidspot.xposed.ui.glass.GlassDrawable
import app.liquidspot.xposed.util.Logger
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

class GlassUiController(private val activity: Activity) {
    private val bars = SpotifyBars(activity)
    private val decor: View = activity.window.decorView

    private var backdrop: BackdropSource? = null
    private var navBar: View? = null
    private var insetsSpace: View? = null
    private var overlay: LiquidNavBarView? = null
    private var tabs: ComposeTabs? = null

    private var navAttempts = 0
    private var navGaveUp = false
    private var lastNavAttempt = 0L
    private var lastPoll = 0L

    private val miniPlayers = WeakHashMap<View, MiniPlayerState>()

    private class MiniPlayerState(val drawable: GlassDrawable) {
        var marginFixes = 0
        var left = -1
        var right = -1
        var next: IconButton? = null
        var compact = false
        var minimal = false
        val hidden = ArrayList<View>()
        val concealed = ArrayList<WeakReference<View>>()
        val parts = HashMap<Int, WeakReference<View>>()
        val textSizes = WeakHashMap<android.widget.TextView, Float>()
    }

    private var collapseShift = 0f
    private var tracking = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchLastY = 0f
    private var travel = 0f
    private val location = IntArray(2)

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { guard("layout") { onLayout() } }
    private var skipNextDraw = false

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        guard("frame") { onPreDraw() }
        if (skipNextDraw) {
            skipNextDraw = false
            false
        } else {
            true
        }
    }

    private var failed = false

    private inline fun guard(where: String, block: () -> Unit) {
        if (failed) return
        try {
            block()
        } catch (t: Throwable) {
            failed = true
            Logger.e("Vidro desativado nesta tela após erro ($where)", t)
            runCatching { restoreOriginal() }
            decor.post { stop() }
        }
    }

    private fun restoreOriginal() {
        navBar?.let { nav ->
            nav.alpha = 1f
            nav.layoutParams = nav.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        }
        for (id in bars.bottomGradients) bars.find<View>(decor, id)?.alpha = 1f
        overlay?.visibility = View.GONE
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics)

    fun start() {
        if (!bars.isComplete) return
        decor.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        decor.viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    fun stop() {
        decor.removeCallbacks(pageScan)
        decor.removeCallbacks(navRetry)
        pages.values.toList().forEach { it.stop() }
        pages.clear()
        decor.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        decor.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
    }

    fun post(block: () -> Unit) {
        decor.post(block)
    }

    fun applySettings() = guard("settings") {
        overlay?.apply {
            layout = GlassUi.layout
            style = GlassUi.style
            reduceMotion = GlassUi.reduceMotion
            lensEnabled = GlassUi.lensEnabled
        }
        for (state in miniPlayers.values) state.drawable.surface.style = GlassUi.style
        for (player in players.values) player.applySettings()
        for (page in pages.values) page.applySettings()
        decor.invalidate()
    }

    private val players = WeakHashMap<ViewGroup, PlayerController>()
    private val playerOverlayId = activity.resources.getIdentifier("revised_template_overlay", "id", activity.packageName)
    private val playerScrollId = activity.resources.getIdentifier("scroll_container", "id", activity.packageName)
    private val overlayControlsId = activity.resources.getIdentifier("overlay_controls_layout", "id", activity.packageName)
    private val djOverlayId = activity.resources.getIdentifier("player_overlay", "id", activity.packageName)

    private fun detectPlayer() {
        if (playerScrollId == 0 || overlayControlsId == 0) return
        val scroll = decor.findViewById<View>(playerScrollId) ?: return
        if (!scroll.isAttachedToWindow) return
        val page = scroll.parent as? ViewGroup ?: return
        if (players.containsKey(page)) return
        if (page.findViewById<View>(overlayControlsId) == null) return
        val music = playerOverlayId != 0 && page.findViewById<View>(playerOverlayId) != null
        val dj = djOverlayId != 0 && page.findViewById<View>(djOverlayId) != null
        if (!music && !dj) return
        players[page] = PlayerController(activity, page).also { it.attach() }
        skipNextDraw = true
    }

    private val pages = WeakHashMap<View, PageStyler>()
    private val listEntityId = activity.resources.getIdentifier("list_entity_root", "id", activity.packageName)
    private val listContainerId = activity.resources.getIdentifier("container", "id", activity.packageName)
    private val listComposeId = activity.resources.getIdentifier("compose_view", "id", activity.packageName)
    private var pageScanQueued = false
    private val pageScan = Runnable {
        pageScanQueued = false
        if (!failed) runCatching { detectPages() }.onFailure { Logger.e("Páginas Liquid Glass: erro ao procurar a página", it) }
    }

    private fun detectPages() {
        val backs = ArrayList<ImageView>()
        val hosts = ArrayList<ViewGroup>()
        val lists = ArrayList<ViewGroup>()
        collectPageViews(decor, backs, hosts, lists)
        for (host in hosts) {
            for (i in 0 until host.childCount) host.getChildAt(i).takeIf { it.visibility == View.VISIBLE }?.let { UiDumper.dumpOnce(it, "página aberta") }
        }
        for (back in backs) {
            val controller = pages[back] ?: pageRootOf(back)?.let { page ->
                PageController(activity, back, page) { GlassUi.style }.also { pages[back] = it }
            } ?: continue
            controller.tryStart()
        }
        for (root in lists) {
            val compose = root.findViewById<View>(listComposeId) ?: continue
            val host = root.findViewById<ViewGroup>(listContainerId) ?: root
            pages.getOrPut(compose) { ComposePageController(activity, compose, host) { GlassUi.style } }.tryStart()
        }
    }

    private fun collectPageViews(view: View, backs: MutableList<ImageView>, hosts: MutableList<ViewGroup>, lists: MutableList<ViewGroup>) {
        if (view.visibility != View.VISIBLE || view === overlay) return
        if (view is ImageView && view.javaClass.simpleName == BACK_BUTTON_CLASS) {
            backs += view
            return
        }
        if (view is ViewGroup) {
            if (view.javaClass.name == FRAGMENT_HOST_CLASS) hosts += view
            if (listEntityId != 0 && view.id == listEntityId) lists += view
            for (i in 0 until view.childCount) collectPageViews(view.getChildAt(i), backs, hosts, lists)
        }
    }

    private fun pageRootOf(view: View): ViewGroup? {
        var child = view
        var parent = view.parent
        while (parent is ViewGroup) {
            if (parent.javaClass.name == FRAGMENT_HOST_CLASS) return child as? ViewGroup
            child = parent
            parent = parent.parent
        }
        return null
    }

    private fun ensureBackdrop(): BackdropSource? {
        backdrop?.let { if (it.source.isAttachedToWindow) return it }
        val source = bars.find<View>(decor, bars.coordinator) ?: return null
        return BackdropSource(source).also { backdrop = it }
    }

    private fun onLayout() {
        if (GlassUi.navBarEnabled && !navGaveUp) {
            val current = navBar
            if (current == null || !current.isAttachedToWindow) setupNavBar()
        }
        if (GlassUi.miniPlayerEnabled) styleMiniPlayer()
        if (GlassUi.pagesEnabled && !pageScanQueued) {
            pageScanQueued = true
            decor.postDelayed(pageScan, PAGE_SCAN_MS)
        }
        if (GlassUi.playerEnabled) {
            runCatching { detectPlayer() }.onFailure { Logger.e("Player Liquid Glass: erro ao procurar a página do player", it) }
        }
    }

    private var navRetryQueued = false
    private val startedAt = SystemClock.uptimeMillis()
    private val navRetry = Runnable {
        navRetryQueued = false
        guard("barra") { if (GlassUi.navBarEnabled && !navGaveUp && navBar?.isAttachedToWindow != true) setupNavBar() }
    }

    private fun queueNavRetry() {
        if (navRetryQueued || SystemClock.uptimeMillis() - startedAt > NAV_RETRY_WINDOW_MS) return
        navRetryQueued = true
        decor.postDelayed(navRetry, RETRY_INTERVAL_MS)
    }

    private fun setupNavBar() {
        val now = SystemClock.uptimeMillis()
        if (now - lastNavAttempt < RETRY_INTERVAL_MS) {
            queueNavRetry()
            return
        }
        lastNavAttempt = now
        queueNavRetry()

        val nav = bars.find<View>(decor, bars.navigationBar) ?: return
        val main = nav.parent as? ViewGroup ?: return
        if (nav.width == 0 || nav.height == 0) return
        val source = ensureBackdrop() ?: return

        UiDumper.dumpOnce(nav, "barra de abas do Spotify")
        val reader = ComposeTabs(activity.resources)
        if (!reader.refresh()) {
            if (NavTabsHook.failed && ++navAttempts >= MAX_NAV_ATTEMPTS) {
                navGaveUp = true
                Logger.w("Barra Liquid Glass: abas do Spotify não encontradas, mantendo a barra original")
            }
            return
        }

        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }

        val bar = LiquidNavBarView(activity).apply {
            id = View.generateViewId()
            backdrop = source
            layout = GlassUi.layout
            style = GlassUi.style
            reduceMotion = GlassUi.reduceMotion
            lensEnabled = GlassUi.lensEnabled
            onTabClick = { tab -> post { guard("click") { onTabClicked(tab) } } }
            onCollapsedTap = { setCollapsed(false) }
        }
        main.addView(bar, main.indexOfChild(nav) + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, nav.height))

        nav.layoutParams = nav.layoutParams.apply { height = bar.barHeight() }
        nav.alpha = 0f

        navBar = nav
        insetsSpace = bars.find(decor, bars.navigationInsets)
        overlay = bar
        tabs = reader
        bar.selectTab(reader.runCatching { selected() }.getOrNull() ?: NavTab.HOME, animate = false)
        Logger.i("Barra Liquid Glass instalada (abas: ${reader.tabs})")
    }

    private fun onTabClicked(tab: NavTab) {
        val reader = tabs ?: return
        if (!reader.click(tab)) Logger.w("Não foi possível abrir a aba $tab")
        lastPoll = 0L
    }

    private fun onPreDraw() {
        if (GlassUi.navBarEnabled && !navGaveUp && navBar?.isAttachedToWindow != true) setupNavBar()
        if (GlassUi.miniPlayerEnabled && miniPlayers.isEmpty()) styleMiniPlayer()
        val bar = overlay
        val nav = navBar
        if (bar != null && nav != null) followNavBar(bar, nav)
        if (bar != null && bar.collapsed && (!GlassUi.minimizeEnabled || miniPlayerView() == null || playerShown())) setCollapsed(false)
        for ((view, state) in miniPlayers) {
            fixMiniPlayerMargins(view, state)
            applyCompact(view, state)
        }
    }

    private val found = HashMap<Int, WeakReference<View>>()
    private val missedAt = HashMap<Int, Long>()

    private fun cached(id: Int): View? {
        if (id == 0) return null
        found[id]?.get()?.let { if (it.isAttachedToWindow) return it }
        val now = SystemClock.uptimeMillis()
        if (now - (missedAt[id] ?: 0L) < LOOKUP_RETRY_MS) return null
        val view = bars.find<View>(decor, id)
        if (view == null) missedAt[id] = now else found[id] = WeakReference(view)
        return view
    }

    private fun miniPlayerView(): View? =
        cached(bars.nowPlayingBar)?.takeIf { it.isShown && it.height > 0 && miniPlayers.containsKey(it) }

    private fun playerShown(): Boolean = players.keys.any { it.isShown }

    private fun setCollapsed(value: Boolean) {
        val bar = overlay ?: return
        if (bar.collapsed == value) return
        bar.setCollapsed(value)
        decor.invalidate()
    }

    fun onTouch(event: MotionEvent) = guard("touch") {
        val bar = overlay ?: return@guard
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = canTrack(bar, event)
                touchStartX = event.rawX
                touchStartY = event.rawY
                touchLastY = event.rawY
                travel = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return@guard
                val dy = event.rawY - touchLastY
                touchLastY = event.rawY
                if (abs(event.rawX - touchStartX) > abs(event.rawY - touchStartY) * HORIZONTAL_RATIO) return@guard
                if (dy == 0f) return@guard
                if (travel != 0f && (dy > 0f) != (travel > 0f)) travel = 0f
                travel += dy
                val threshold = dp(SCROLL_TRAVEL_DP)
                if (travel < -threshold) {
                    travel = 0f
                    if (!bar.collapsed && miniPlayerView() != null) setCollapsed(true)
                } else if (travel > threshold) {
                    travel = 0f
                    setCollapsed(false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
    }

    private fun canTrack(bar: LiquidNavBarView, event: MotionEvent): Boolean {
        if (!GlassUi.minimizeEnabled || bar.visibility != View.VISIBLE || playerShown()) return false
        val npb = miniPlayerView() ?: return false
        npb.getLocationOnScreen(location)
        return event.rawY < location[1] - dp(8f)
    }

    private fun followCollapse(bar: LiquidNavBarView, nav: View) {
        val c = bar.collapse.coerceIn(0f, 1f)
        val npb = miniPlayerView()
        if (c == 0f && npb != null && !bar.collapsed) {
            bar.getLocationInWindow(location)
            val pillCenter = location[1] + bar.pillCenterY()
            npb.getLocationInWindow(location)
            collapseShift = (pillCenter - (location[1] + npb.height / 2f)).coerceAtLeast(0f)
        }
        val wanted = (bar.barHeight() - collapseShift * c).roundToInt().coerceAtLeast(1)
        if (nav.layoutParams.height != wanted) nav.layoutParams = nav.layoutParams.apply { height = wanted }
    }

    private fun followNavBar(bar: LiquidNavBarView, nav: View) {
        followCollapse(bar, nav)
        if (nav.alpha != 0f) nav.alpha = 0f
        for (id in bars.bottomGradients) {
            cached(id)?.let { if (it.alpha != 0f) it.alpha = 0f }
        }

        val visible = nav.isAttachedToWindow && nav.visibility == View.VISIBLE
        val wanted = if (visible) View.VISIBLE else View.GONE
        if (bar.visibility != wanted) bar.visibility = wanted
        if (!visible) return

        val inset = insetsSpace?.height ?: 0
        bar.bottomInset = inset
        val barHeight = maxOf(nav.height, bar.barHeight())
        val height = barHeight + inset
        if (bar.layoutParams.height != height) bar.layoutParams = bar.layoutParams.apply { this.height = height }
        val top = nav.top + nav.translationY + nav.height - barHeight
        if (bar.translationY != top) bar.translationY = top

        val now = SystemClock.uptimeMillis()
        if (now - lastPoll >= POLL_INTERVAL_MS) {
            lastPoll = now
            tabs?.selected()?.let { bar.selectTab(it) }
        }
    }

    private fun styleMiniPlayer() {
        val npb = cached(bars.nowPlayingBar) ?: return
        if (miniPlayers.containsKey(npb)) return
        val source = ensureBackdrop() ?: return

        val drawable = GlassDrawable(npb).apply {
            surface.backdrop = source
            surface.style = GlassUi.style
        }
        npb.background = drawable
        npb.outlineProvider = PILL_OUTLINE
        npb.clipToOutline = true
        source.addClient(npb)

        val coverId = activity.resources.getIdentifier("cover_image", "id", activity.packageName)
        bars.find<View>(npb, coverId)?.let {
            it.outlineProvider = OVAL_OUTLINE
            it.clipToOutline = true
        }

        (npb.parent as? ViewGroup)?.let { parent ->
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child !== npb && child is FrameLayout && child.childCount == 0 && child.background != null) {
                    child.visibility = View.INVISIBLE
                }
            }
        }

        miniPlayers[npb] = MiniPlayerState(drawable)
        Logger.i("Mini player Liquid Glass aplicado")
    }

    private fun spotifyId(name: String) = activity.resources.getIdentifier(name, "id", activity.packageName)

    private val connectId = spotifyId("connect_destination_button")
    private val addId = spotifyId("add_to_button")
    private val playPauseId = spotifyId("play_pause_button")
    private val carouselId = spotifyId("tracks_carousel_view")

    private fun part(group: ViewGroup, state: MiniPlayerState, id: Int): View? {
        if (id == 0) return null
        state.parts[id]?.get()?.let { if (it.isAttachedToWindow) return it }
        val view = bars.find<View>(group, id) ?: return null
        state.parts[id] = WeakReference(view)
        return view
    }

    private fun applyCompact(npb: View, state: MiniPlayerState) {
        val group = npb as? ViewGroup ?: return
        val connect = part(group, state, connectId)
        val add = part(group, state, addId)
        val play = part(group, state, playPauseId)
        val parent = npb.parent as? ViewGroup
        if ((overlay?.collapse ?: 0f) > MINIMAL_AT) {
            enterMinimal(group, state, connect, add, play)
            return
        }
        if (state.minimal) leaveMinimal(state)
        val usable = GlassUi.compactMiniPlayer && add != null && play != null &&
            parent != null && (parent is FrameLayout || parent.javaClass.name.contains("ConstraintLayout"))
        if (!usable) {
            if (state.compact) leaveCompact(state, connect, add, play)
            return
        }
        state.compact = true
        if (connect != null) {
            if (connect.visibility == View.VISIBLE) connect.visibility = View.INVISIBLE
            conceal(connect, state)
        }
        if (add.visibility == View.VISIBLE) add.visibility = View.INVISIBLE
        conceal(add, state)
        if (add.width == 0 || play.width == 0) return
        val shift = (add.left - play.left).toFloat()
        if (play.translationX != shift) play.translationX = shift

        val next = state.next ?: IconButton(activity, PlayerIcon.NEXT, NEXT_ICON_DP).apply {
            id = View.generateViewId()
            contentDescription = Texts.next
            setOnClickListener { NowPlayingStore.next() }
            parent.addView(this, ViewGroup.LayoutParams(play.width, play.height))
            state.next = this
        }
        if (next.parent !== parent) {
            (next.parent as? ViewGroup)?.removeView(next)
            parent.addView(next, ViewGroup.LayoutParams(play.width, play.height))
        }
        if (next.layoutParams.width != play.width || next.layoutParams.height != play.height) {
            next.layoutParams = next.layoutParams.apply {
                width = play.width
                height = play.height
            }
        }
        val x = npb.left + npb.translationX + play.left - next.left
        val y = npb.top + npb.translationY + play.top - next.top
        if (next.translationX != x) next.translationX = x
        if (next.translationY != y) next.translationY = y
        val alpha = npb.alpha
        if (next.alpha != alpha) next.alpha = alpha
        val wanted = if (npb.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        if (next.visibility != wanted) next.visibility = wanted
    }

    private fun enterMinimal(npb: ViewGroup, state: MiniPlayerState, connect: View?, add: View?, play: View?) {
        if (state.compact) leaveCompact(state, connect, add, play)
        state.minimal = true
        for (v in listOfNotNull(connect, add)) {
            if (v.visibility != View.GONE) {
                v.visibility = View.GONE
                if (v !in state.hidden) state.hidden += v
            }
            conceal(v, state)
        }
        if (play != null && play.translationX != 0f) play.translationX = 0f
        val carousel = bars.find<ViewGroup>(npb, carouselId) ?: return
        shrinkTexts(carousel, state)
    }

    private fun shrinkTexts(view: View, state: MiniPlayerState) {
        if (view is android.widget.TextView) {
            if (!state.textSizes.containsKey(view)) {
                state.textSizes[view] = view.textSize
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * MINIMAL_TEXT_SCALE)
            }
            return
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) shrinkTexts(view.getChildAt(i), state)
    }

    private fun conceal(view: View, state: MiniPlayerState) {
        if (state.concealed.any { it.get() === view }) return
        view.clipBounds = Rect()
        state.concealed += WeakReference(view)
    }

    private fun reveal(state: MiniPlayerState) {
        for (ref in state.concealed) ref.get()?.clipBounds = null
        state.concealed.clear()
    }

    private fun leaveMinimal(state: MiniPlayerState) {
        state.minimal = false
        reveal(state)
        state.hidden.forEach { if (it.visibility == View.GONE) it.visibility = View.VISIBLE }
        state.hidden.clear()
        for ((text, size) in state.textSizes) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        state.textSizes.clear()
    }

    private fun leaveCompact(state: MiniPlayerState, connect: View?, add: View?, play: View?) {
        state.compact = false
        reveal(state)
        if (connect?.visibility == View.INVISIBLE) connect.visibility = View.VISIBLE
        if (add?.visibility == View.INVISIBLE) add.visibility = View.VISIBLE
        play?.translationX = 0f
        state.next?.let { (it.parent as? ViewGroup)?.removeView(it) }
        state.next = null
    }

    private fun fixMiniPlayerMargins(npb: View, state: MiniPlayerState) {
        if (npb.width == 0 || !npb.isAttachedToWindow) return
        val gutter = dp(GUTTER_DP)
        val bar = overlay
        val c = bar?.collapse?.coerceIn(0f, 1f) ?: 0f
        val leftTarget = (gutter + c * ((bar?.collapsedLeftInset() ?: gutter) - gutter)).roundToInt()
        val rightTarget = (gutter + c * ((bar?.collapsedRightInset() ?: gutter) - gutter)).roundToInt()
        if (leftTarget != state.left || rightTarget != state.right) {
            state.left = leftTarget
            state.right = rightTarget
            state.marginFixes = 0
        }
        if (state.marginFixes >= 3) return
        val lp = npb.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        npb.getLocationInWindow(location)
        val left = location[0]
        val right = decor.width - (left + npb.width)
        val dl = leftTarget - left
        val dr = rightTarget - right
        if (abs(dl) <= 1 && abs(dr) <= 1) {
            state.marginFixes = 3
            return
        }
        state.marginFixes++
        lp.leftMargin = (lp.leftMargin + dl).coerceAtLeast(0)
        lp.rightMargin = (lp.rightMargin + dr).coerceAtLeast(0)
        lp.marginStart = lp.leftMargin
        lp.marginEnd = lp.rightMargin
        npb.layoutParams = lp
    }

    private companion object {
        const val GUTTER_DP = 19f
        const val SCROLL_TRAVEL_DP = 36f
        const val NEXT_ICON_DP = 24f
        const val MINIMAL_AT = 0.5f
        const val MINIMAL_TEXT_SCALE = 0.85f
        const val HORIZONTAL_RATIO = 1.2f
        const val RETRY_INTERVAL_MS = 32L
        const val NAV_RETRY_WINDOW_MS = 15_000L
        const val PAGE_SCAN_MS = 150L
        const val BACK_BUTTON_CLASS = "BackButtonView"
        const val FRAGMENT_HOST_CLASS = "androidx.fragment.app.FragmentContainerView"
        const val MAX_NAV_ATTEMPTS = 20
        const val POLL_INTERVAL_MS = 200L
        const val LOOKUP_RETRY_MS = 32L

        val PILL_OUTLINE = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        val OVAL_OUTLINE = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }
}
