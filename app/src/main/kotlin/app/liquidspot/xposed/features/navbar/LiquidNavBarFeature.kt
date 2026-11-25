package app.liquidspot.xposed.features.navbar

import app.liquidspot.core.FeatureCatalog
import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.feature.Feature

object LiquidNavBarFeature : Feature {
    override val info = FeatureCatalog.LIQUID_NAVBAR

    override fun install(ctx: HookContext) = GlassUi.enableNavBar(ctx)
}
