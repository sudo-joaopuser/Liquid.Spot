package app.liquidspot.xposed.features.navbar

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import app.liquidspot.core.GlassStyle
import app.liquidspot.core.NavBarLayout
import app.liquidspot.core.OptionCatalog
import app.liquidspot.core.PrefKeys
import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.hook.method
import app.liquidspot.xposed.util.Logger
import java.util.WeakHashMap

object GlassUi {
    var navBarEnabled = false
        private set
    var miniPlayerEnabled = false
        private set
    var playerEnabled = false
        private set
    var pagesEnabled = false
        private set

    var layout = NavBarLayout.DEFAULT
        private set
    var style = GlassStyle.DEFAULT
        private set
    var reduceMotion = PrefKeys.GLASS_REDUCE_MOTION_DEFAULT
        private set
    var lensEnabled = OptionCatalog.NAVBAR_LENS.defaultEnabled
        private set
    var minimizeEnabled = OptionCatalog.NAVBAR_MINIMIZE.defaultEnabled
        private set
    var compactMiniPlayer = OptionCatalog.MINIPLAYER_COMPACT.defaultEnabled
        private set
    var canvasDisabled = OptionCatalog.PLAYER_CANVAS_OFF.defaultEnabled
        private set
    var lyricLine = OptionCatalog.PLAYER_LYRIC_LINE.defaultEnabled
        private set

    private var installed = false
    private val controllers = WeakHashMap<Activity, GlassUiController>()

    fun enableNavBar(ctx: HookContext) {
        navBarEnabled = true
        install(ctx)
    }

    fun enableMiniPlayer(ctx: HookContext) {
        miniPlayerEnabled = true
        install(ctx)
    }

    fun enablePlayer(ctx: HookContext) {
        playerEnabled = true
        install(ctx)
    }

    fun enablePages(ctx: HookContext) {
        pagesEnabled = true
        install(ctx)
    }

    private fun install(ctx: HookContext) {
        if (installed) return
        installed = true

        val prefs = ctx.prefs
        fun reload() {
            layout = NavBarLayout.fromPref(prefs.getString(PrefKeys.NAVBAR_LAYOUT, null))
            style = GlassStyle.fromPref(prefs.getString(PrefKeys.GLASS_STYLE, null))
            reduceMotion = prefs.getBoolean(PrefKeys.GLASS_REDUCE_MOTION, PrefKeys.GLASS_REDUCE_MOTION_DEFAULT)
            lensEnabled = prefs.getBoolean(OptionCatalog.NAVBAR_LENS.key, OptionCatalog.NAVBAR_LENS.defaultEnabled)
            minimizeEnabled = prefs.getBoolean(OptionCatalog.NAVBAR_MINIMIZE.key, OptionCatalog.NAVBAR_MINIMIZE.defaultEnabled)
            compactMiniPlayer = prefs.getBoolean(OptionCatalog.MINIPLAYER_COMPACT.key, OptionCatalog.MINIPLAYER_COMPACT.defaultEnabled)
            canvasDisabled = prefs.getBoolean(OptionCatalog.PLAYER_CANVAS_OFF.key, OptionCatalog.PLAYER_CANVAS_OFF.defaultEnabled)
            lyricLine = prefs.getBoolean(OptionCatalog.PLAYER_LYRIC_LINE.key, OptionCatalog.PLAYER_LYRIC_LINE.defaultEnabled)
        }
        reload()
        for (key in listOf(PrefKeys.NAVBAR_LAYOUT, PrefKeys.GLASS_STYLE, PrefKeys.GLASS_REDUCE_MOTION) + OptionCatalog.all.map { it.key }) {
            prefs.onChange(key) {
                reload()
                Logger.d("Vidro: layout=$layout estilo=$style reduzirAnimações=$reduceMotion")
                synchronized(controllers) { controllers.values.toList() }.forEach { it.post { it.applySettings() } }
            }
        }

        ctx.hooks.after(Activity::class.java.method("onCreate", Bundle::class.java)) {
            val activity = thisObject as? Activity ?: return@after
            val controller = GlassUiController(activity)
            synchronized(controllers) { controllers[activity] = controller }
            controller.start()
        }
        ctx.hooks.before(Activity::class.java.method("dispatchTouchEvent", MotionEvent::class.java)) {
            val activity = thisObject as? Activity ?: return@before
            val event = arg<MotionEvent?>(0) ?: return@before
            synchronized(controllers) { controllers[activity] }?.onTouch(event)
        }
        ctx.hooks.before(Activity::class.java.method("onDestroy")) {
            val activity = thisObject as? Activity ?: return@before
            synchronized(controllers) { controllers.remove(activity) }?.stop()
        }
    }
}
