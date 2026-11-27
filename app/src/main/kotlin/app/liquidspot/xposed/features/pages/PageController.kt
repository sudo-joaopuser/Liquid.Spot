package app.liquidspot.xposed.features.pages

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import app.liquidspot.core.GlassStyle
import app.liquidspot.xposed.a11y.A11yBridge
import app.liquidspot.xposed.a11y.TextMatcher
import app.liquidspot.xposed.features.debug.UiDumper
import app.liquidspot.xposed.features.player.IconButton
import app.liquidspot.xposed.features.player.Texts
import app.liquidspot.xposed.ui.PlayerIcon
import app.liquidspot.xposed.ui.glass.BackdropSource
import app.liquidspot.xposed.ui.glass.GlassDrawable
import app.liquidspot.xposed.theme.SpotifyTheme
import app.liquidspot.xposed.util.Logger
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.roundToInt

class PageController(
    private val activity: Activity,
    private val back: ImageView,
    private val page: ViewGroup,
    private val styleProvider: () -> GlassStyle,
) : PageStyler {
    private val res = activity.resources

    private fun id(name: String) = res.getIdentifier(name, "id", activity.packageName)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, res.displayMetrics)

    private val bar = back.parent as? ViewGroup
    private val backBg: View? = bar?.findViewById(id("back_button_bg"))
    private val appBar: ViewGroup? = findAncestor(back, id("app_bar_layout"))
    private val mediaSlotId = id("cwp_header_media_slot")
    private val artworkId = id("cwp_header_artwork")
    private val artworkBackgroundId = id("cwp_header_artwork_background")

    private val backdrop = BackdropSource(page) { recordBehindButtons(it) }
    private val backGlass = GlassDrawable(back).apply {
        surface.backdrop = backdrop
        surface.shadowEnabled = false
    }
    private val backChrome: Drawable = InsetDrawable(backGlass, dp(GLASS_INSET_DP).roundToInt())
    private val shareButton = IconButton(activity, PlayerIcon.SHARE, 21f).apply { contentDescription = Texts.share }
    private val moreButton = IconButton(activity, PlayerIcon.MORE_HORIZONTAL, 22f).apply { contentDescription = Texts.more }
    private val pill = GlassPillView(activity, shareButton, moreButton)
    private val pillGlass = GlassDrawable(pill).apply {
        surface.backdrop = backdrop
        surface.shadowEnabled = false
    }
    private val rightView: View? = bar?.findViewById(id("action_bar_right_view"))

    private val moreLabels = TextMatcher.formats(
        A11yBridge.spotifyStrings(
            res,
            "show_context_menu_content_description_album", "show_context_menu_content_description_playlist",
            "show_context_menu_content_description_artist", "show_context_menu_content_description_show",
            "show_context_menu_content_description_user", "content_description_context_menu",
            "entity_action_context_menu_default_content_description",
        ),
    )

    private var hero: PageHero? = null
    private val baseBackground = android.graphics.drawable.ColorDrawable()
    private var dividers: TrackDividers? = null
    private var list = WeakReference<View>(null)
    private val menuButtons = ArrayList<WeakReference<View>>()
    private val shareButtons = ArrayList<WeakReference<View>>()

    private var stopped = false
    private var failed = false
    private var listening = false
    private val origin = IntArray(2)
    private val location = IntArray(2)
    private val backLocation = IntArray(2)

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        guard("quadro") { keepStyled() }
        true
    }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { guard("layout") { scan() } }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = listen(true)
        override fun onViewDetachedFromWindow(v: View) = listen(false)
    }

    private var started = false

    override fun tryStart() = guard("início") {
        if (started) return@guard
        val parent = bar ?: return@guard
        if (parent is LinearLayout || back.width == 0) return@guard
        back.getLocationInWindow(backLocation)
        val top = parent.parent as? ViewGroup ?: parent
        collect(top, MENU_BUTTON, menuButtons, skipLists = true) { inTopRow(it) }
        if (appBar == null && menuButtons.isEmpty()) return@guard
        started = true
        back.setImageDrawable(SizedIcon(PlayerIcon.BACK.load(activity), dp(BACK_ICON_DP).roundToInt()))
        back.scaleType = ImageView.ScaleType.CENTER
        back.background = backChrome
        back.outlineProvider = RoundOutline(dp(GLASS_INSET_DP))
        back.clipToOutline = true
        pill.background = InsetDrawable(pillGlass, dp(GLASS_INSET_DP).roundToInt())
        pill.outlineProvider = RoundOutline(dp(GLASS_INSET_DP))
        pill.clipToOutline = true
        val height = back.height.takeIf { it > 0 } ?: dp(BUTTON_DP).roundToInt()
        parent.addView(pill, ViewGroup.LayoutParams(dp(PILL_W_DP).roundToInt(), height))
        applySettings()
        wire()
        scan()
        keepStyled()
        back.addOnAttachStateChangeListener(attach)
        if (back.isAttachedToWindow) listen(true)
        val header = if (appBar != null) "app_bar_layout" else parent.javaClass.simpleName
        Logger.i("Página: botões de vidro em ${page.javaClass.simpleName} (cabeçalho $header)")
        UiDumper.dumpOnce(page, "página com botão de voltar")
    }

    override fun stop() {
        stopped = true
        listen(false)
        back.removeOnAttachStateChangeListener(attach)
    }

    override fun applySettings() {
        val style = styleProvider()
        backGlass.surface.style = style
        pillGlass.surface.style = style
        back.invalidate()
        pill.invalidate()
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        val observer = back.viewTreeObserver
        if (!observer.isAlive) return
        listening = on
        if (on) {
            observer.addOnPreDrawListener(preDraw)
            observer.addOnGlobalLayoutListener(layoutListener)
            backdrop.addClient(back)
            backdrop.addClient(pill)
        } else {
            observer.removeOnPreDrawListener(preDraw)
            observer.removeOnGlobalLayoutListener(layoutListener)
            backdrop.removeClient(back)
            backdrop.removeClient(pill)
        }
    }

    private fun keepStyled() {
        if (back.background !== backChrome) back.background = backChrome
        backBg?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
        placePill()
        if (hero == null) appBar?.let { header ->
            if (header.background !== baseBackground) header.background = baseBackground
            baseBackground.color = if (SpotifyTheme.amoled) 0xFF000000.toInt() else 0xFF121212.toInt()
        }
        hero?.let { current ->
            val header = appBar ?: return@let
            if (header.background !== current) header.background = current
            current.refresh()
            header.findViewById<View>(mediaSlotId)?.let { if (it.alpha != 0f) it.alpha = 0f }
            header.findViewById<View>(artworkBackgroundId)?.let { if (it.alpha != 0f) it.alpha = 0f }
        }
        rightView?.let { if (it.alpha != 0f) it.alpha = 0f }
        for (ref in menuButtons) ref.get()?.let { if (it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE }
        for (ref in shareButtons) ref.get()?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
    }

    private fun placePill() {
        val parent = bar ?: return
        if (pill.width == 0 || back.height == 0) return
        if (pill.layoutParams.height != back.height) {
            pill.layoutParams = pill.layoutParams.apply { height = back.height }
            return
        }
        val x = parent.width - parent.paddingRight - dp(END_MARGIN_DP) - pill.width
        val y = back.top + back.translationY + (back.height - pill.height) / 2f
        if (pill.translationX != x - pill.left) pill.translationX = x - pill.left
        if (pill.translationY != y - pill.top) pill.translationY = y - pill.top
    }

    private fun scan() {
        if (hero == null) {
            val header = appBar
            val artwork = header?.findViewById<View>(artworkId) as? ImageView
            if (header != null && artwork != null && header.findViewById<View>(mediaSlotId) != null &&
                artwork.visibility == View.VISIBLE && artwork.width in 1 until (header.width * SQUARE_ART_MAX).toInt()
            ) {
                hero = PageHero(header, artwork)
            }
        }
        back.getLocationInWindow(backLocation)
        val top = bar?.parent as? ViewGroup ?: bar
        if (top != null) collect(top, MENU_BUTTON, menuButtons, skipLists = true) { inTopRow(it) }
        appBar?.let { header -> collect(header, MENU_BUTTON, menuButtons, skipLists = true) { true } }
        collect(page, SHARE_BUTTON, shareButtons, skipLists = false) { true }
        if (list.get()?.isAttachedToWindow != true) {
            (largestList(page) as? ViewGroup)?.let { found ->
                list = WeakReference(found)
                val drawable = dividers ?: TrackDividers(found).also { dividers = it }
                if (found.foreground !== drawable) found.foreground = drawable
            }
        }
    }

    private fun inTopRow(view: View): Boolean {
        view.getLocationInWindow(location)
        val center = location[1] + view.height / 2
        return abs(center - (backLocation[1] + back.height / 2)) < back.height
    }

    private fun isList(view: View) = view.javaClass.name.endsWith("RecyclerView")

    private fun collect(root: View, simpleName: String, out: MutableList<WeakReference<View>>, skipLists: Boolean, accept: (View) -> Boolean) {
        if (root === pill || (skipLists && isList(root))) return
        if (root.javaClass.simpleName == simpleName) {
            if (out.none { it.get() === root } && accept(root)) out += WeakReference(root)
            return
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) collect(root.getChildAt(i), simpleName, out, skipLists, accept)
    }

    private fun largestList(root: View): View? {
        var best: View? = null
        var bestArea = 0
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (isList(view) && view is ViewGroup) {
                val area = view.width * view.height
                if (area > bestArea) {
                    best = view
                    bestArea = area
                }
                return
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return best?.takeIf { it.height > page.height / 2 }
    }

    private fun recordBehindButtons(canvas: Canvas) {
        page.getLocationInWindow(origin)
        drawExcept(canvas, page, root = true)
    }

    private fun drawExcept(canvas: Canvas, group: ViewGroup, root: Boolean) {
        if (!root) group.background?.let { background -> drawAt(canvas, group) { background.draw(canvas) } }
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child === back || child === pill || child === backBg || child === rightView || menuButtons.any { it.get() === child }) continue
            if (child.visibility != View.VISIBLE || child.alpha <= 0f || child.width == 0) continue
            if (child is ViewGroup && contains(child, back)) {
                drawExcept(canvas, child, root = false)
            } else {
                drawAt(canvas, child) { child.draw(canvas) }
            }
        }
    }

    private inline fun drawAt(canvas: Canvas, view: View, block: () -> Unit) {
        view.getLocationInWindow(location)
        val x = (location[0] - origin[0]).toFloat()
        val y = (location[1] - origin[1]).toFloat()
        val save = if (view.alpha < 1f) {
            canvas.saveLayerAlpha(x, y, x + view.width, y + view.height, (view.alpha * 255).roundToInt())
        } else {
            canvas.save()
        }
        canvas.translate(x, y)
        block()
        canvas.restoreToCount(save)
    }

    private fun contains(group: ViewGroup, view: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent === group) return true
            parent = parent.parent
        }
        return false
    }

    private fun wire() {
        moreButton.setOnClickListener { guard("…") { openMenu() } }
        shareButton.setOnClickListener { guard("compartilhar") { share() } }
    }

    private fun openMenu() {
        for (ref in menuButtons) ref.get()?.takeIf { it.isAttachedToWindow }?.let { if (it.performClick()) return }
        rightView?.let { right ->
            val target = A11yBridge.collect(right, maxItems = 20).firstOrNull { it.clickable }?.clickableTarget()
            if (target?.click() == true) return
        }
        val items = A11yBridge.collect(page, maxItems = 400, exclude = { it === pill })
        if (A11yBridge.find(items, moreLabels)?.clickableTarget()?.click() == true) return
        Logger.w("Página: botão \"…\" do Spotify não encontrado")
    }

    private fun share() {
        for (ref in shareButtons) ref.get()?.takeIf { it.isAttachedToWindow }?.let { if (it.performClick()) return }
        val link = PageLink.find(back)
        if (link == null) {
            Logger.w("Página: link não encontrado; abrindo o menu do Spotify para compartilhar")
            openMenu()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }
        activity.startActivity(Intent.createChooser(send, null))
    }

    private fun findAncestor(view: View, targetId: Int): ViewGroup? {
        if (targetId == 0) return null
        var parent = view.parent
        while (parent is ViewGroup) {
            if (parent.id == targetId) return parent
            parent = parent.parent
        }
        return null
    }

    private inline fun guard(where: String, block: () -> Unit) {
        if (failed || stopped) return
        try {
            block()
        } catch (t: Throwable) {
            failed = true
            Logger.e("Página Liquid Glass desativada após erro ($where)", t)
            runCatching { stop() }
        }
    }

    private companion object {
        const val BUTTON_DP = 48f
        const val GLASS_INSET_DP = 2f
        const val BACK_ICON_DP = 22f
        const val PILL_W_DP = 100f
        const val END_MARGIN_DP = 12f
        const val SQUARE_ART_MAX = 0.8f
        const val MENU_BUTTON = "ContextMenuButton"
        const val SHARE_BUTTON = "ShareButton"
    }
}
