package app.liquidspot.xposed.features.pages

import app.liquidspot.core.FeatureCatalog
import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.feature.Feature
import app.liquidspot.xposed.features.navbar.GlassUi

object LiquidPagesFeature : Feature {
    override val info = FeatureCatalog.LIQUID_PAGES

    override fun install(ctx: HookContext) {
        GlassUi.enablePages(ctx)
    }
}
