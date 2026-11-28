package app.liquidspot.xposed.features.pages

import android.app.Activity
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import app.liquidspot.core.GlassStyle
import app.liquidspot.xposed.features.player.IconButton
import app.liquidspot.xposed.features.player.Texts
import app.liquidspot.xposed.ui.PlayerIcon
import app.liquidspot.xposed.ui.glass.BackdropSource
import app.liquidspot.xposed.ui.glass.GlassDrawable
import app.liquidspot.xposed.util.Logger
import kotlin.math.roundToInt

class ComposePageController(
    private val activity: Activity,
    private val compose: View,
    private val host: ViewGroup,
    private val styleProvider: () -> GlassStyle,
) : PageStyler {
    private val res = activity.resources

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, res.displayMetrics)

    private val page = ComposeHooks.register(compose)
    private val overlay = FrameLayout(activity)
    private val backdrop = BackdropSource(compose)
    private val bar = BlurBar(activity, backdrop)
    private val backButton = IconButton(activity, PlayerIcon.BACK, 22f, glass = true).apply { contentDescription = Texts.back }
    private val shareButton = IconButton(activity, PlayerIcon.SHARE, 21f).apply { contentDescription = Texts.share }
    private val moreButton = IconButton(activity, PlayerIcon.MORE_HORIZONTAL, 22f).apply { contentDescription = Texts.more }
    private val pill = GlassPillView(activity, shareButton, moreButton)
    private val pillGlass = GlassDrawable(pill).apply {
        surface.backdrop = backdrop
        surface.shadowEnabled = false
    }

    private var started = false
    private var stopped = false
    private var failed = false
    private var listening = false
    private var pillShown = true

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        guard("quadro") { onFrame() }
        true
    }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = listen(true)
        override fun onViewDetachedFromWindow(v: View) = listen(false)
    }

    override fun tryStart() = guard("início") {
        if (started || compose.width == 0 || !ComposeHooks.isReady) return@guard
        started = true
        backButton.backdrop = backdrop
        pill.background = InsetDrawable(pillGlass, dp(GLASS_INSET_DP).roundToInt())
        pill.outlineProvider = RoundOutline(dp(GLASS_INSET_DP))
        pill.clipToOutline = true
        val back = dp(BACK_DP).roundToInt()
        overlay.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusBar() + dp(TOOLBAR_DP).roundToInt()))
        overlay.addView(backButton, FrameLayout.LayoutParams(back, back))
        overlay.addView(pill, FrameLayout.LayoutParams(dp(PILL_W_DP).roundToInt(), dp(PILL_H_DP).roundToInt()))
        host.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ComposeHooks.configureBar(statusBar(), dp(TOOLBAR_DP).roundToInt(), compose.width, (compose.width * TOOLBAR_MIN_WIDTH).toInt())
        backButton.setOnClickListener { guard("voltar") { goBack() } }
        shareButton.setOnClickListener { guard("compartilhar") { share() } }
        moreButton.setOnClickListener { guard("…") { openMenu() } }
        applySettings()
        place()
        compose.addOnAttachStateChangeListener(attach)
        if (compose.isAttachedToWindow) listen(true)
        Logger.i("Página Compose: botões de vidro pelos ganchos do Compose")
    }

    override fun stop() {
        stopped = true
        listen(false)
        compose.removeOnAttachStateChangeListener(attach)
        ComposeHooks.unregister(compose)
    }

    override fun applySettings() {
        val style = styleProvider()
        backButton.glassSurface?.style = style
        pillGlass.surface.style = style
        backButton.invalidate()
        pill.invalidate()
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        val observer = compose.viewTreeObserver
        if (!observer.isAlive) return
        listening = on
        if (on) {
            observer.addOnPreDrawListener(preDraw)
            backdrop.addClient(pill)
            backdrop.addClient(bar)
        } else {
            observer.removeOnPreDrawListener(preDraw)
            backdrop.removeClient(pill)
            backdrop.removeClient(bar)
        }
    }

    private fun statusBar(): Int =
        compose.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0

    private fun place() {
        val center = statusBar() + dp(TOOLBAR_DP) / 2f
        val back = dp(BACK_DP)
        move(backButton, dp(BACK_START_DP) + (dp(SPOTIFY_BUTTON_DP) - back) / 2f, center - back / 2f)
        val width = if (overlay.width > 0) overlay.width else compose.width
        move(pill, width - dp(END_MARGIN_DP) - dp(PILL_W_DP), center - dp(PILL_H_DP) / 2f)
    }

    private fun move(view: View, x: Float, y: Float) {
        if (view.translationX != x) view.translationX = x
        if (view.translationY != y) view.translationY = y
    }

    private fun onFrame() {
        if (!compose.isShown) return
        place()
        val tracked = ComposeHooks.playBounds(page)
        val show = !(tracked && playCoversBar())
        if (show != pillShown) {
            pillShown = show
            pill.visibility = if (show) View.VISIBLE else View.INVISIBLE
        }
        bar.progress = if (tracked) collapseProgress() else 0f
        if (bar.progress > 0f) {
            bar.update(ComposeHooks.toolbarNode, ComposeHooks.toolbarX, ComposeHooks.toolbarY, ComposeHooks.currentPlayNode, page.playLeft, page.playTop)
        }
    }

    private fun playCoversBar(): Boolean {
        val center = (page.playTop + page.playBottom) / 2f
        val pillBottom = statusBar() + dp(TOOLBAR_DP) / 2f + dp(PILL_H_DP) / 2f
        return center - dp(PLAY_RADIUS_DP) < pillBottom
    }

    private fun collapseProgress(): Float {
        val center = (page.playTop + page.playBottom) / 2f
        val barBottom = statusBar() + dp(TOOLBAR_DP)
        return ((barBottom + dp(FADE_END_DP) + dp(FADE_DP) - center) / dp(FADE_DP)).coerceIn(0f, 1f)
    }

    private fun goBack() {
        if (ComposeHooks.click(page.back)) return
        @Suppress("DEPRECATION")
        activity.onBackPressed()
    }

    private fun openMenu() {
        if (!ComposeHooks.click(page.menu)) Logger.w("Página Compose: ação do \"…\" do Spotify ainda não capturada")
    }

    private fun share() {
        if (ComposeHooks.click(page.share)) return
        openMenu()
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

    private companion object {
        const val TOOLBAR_DP = 56f
        const val BACK_DP = 44f
        const val SPOTIFY_BUTTON_DP = 48f
        const val BACK_START_DP = 12f
        const val PILL_W_DP = 100f
        const val PILL_H_DP = 48f
        const val GLASS_INSET_DP = 2f
        const val END_MARGIN_DP = 12f
        const val PLAY_RADIUS_DP = 24f
        const val FADE_DP = 220f
        const val FADE_END_DP = 60f
        const val TOOLBAR_MIN_WIDTH = 0.8f
    }
}
