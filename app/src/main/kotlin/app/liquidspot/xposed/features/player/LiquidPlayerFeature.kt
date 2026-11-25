package app.liquidspot.xposed.features.player

import app.liquidspot.core.FeatureCatalog
import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.feature.Feature
import app.liquidspot.xposed.features.navbar.GlassUi
import app.liquidspot.xposed.features.player.lyrics.LyricsSettings
import app.liquidspot.xposed.features.player.lyrics.ai.AiSync
import app.liquidspot.xposed.media.NowPlayingStore

object LiquidPlayerFeature : Feature {
    override val info = FeatureCatalog.LIQUID_PLAYER

    override fun install(ctx: HookContext) {
        NowPlayingStore.install(ctx)
        LyricsSettings.attach(ctx.prefs)
        AiSync.install(ctx)
        GlassUi.enablePlayer(ctx)
    }
}
