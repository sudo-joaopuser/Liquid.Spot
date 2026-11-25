package app.liquidspot.xposed.features.miniplayer

import app.liquidspot.core.FeatureCatalog
import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.feature.Feature
import app.liquidspot.xposed.features.navbar.GlassUi
import app.liquidspot.xposed.media.NowPlayingStore

object LiquidMiniPlayerFeature : Feature {
    override val info = FeatureCatalog.LIQUID_MINIPLAYER

    override fun install(ctx: HookContext) {
        NowPlayingStore.install(ctx)
        GlassUi.enableMiniPlayer(ctx)
    }
}
