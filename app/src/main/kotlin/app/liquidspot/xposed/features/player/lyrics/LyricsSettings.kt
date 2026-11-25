package app.liquidspot.xposed.features.player.lyrics

import app.liquidspot.core.LyricsSource
import app.liquidspot.xposed.ModulePrefs

object LyricsSettings {
    @Volatile
    private var prefs: ModulePrefs? = null

    fun attach(prefs: ModulePrefs) {
        this.prefs = prefs
    }

    fun config(): LyricsConfig = prefs?.let(LyricsConfig::from)
        ?: LyricsConfig(LyricsSource.DEFAULT_ORDER, LyricsSource.entries.filter { it.defaultEnabled }.toSet(), "")
}
