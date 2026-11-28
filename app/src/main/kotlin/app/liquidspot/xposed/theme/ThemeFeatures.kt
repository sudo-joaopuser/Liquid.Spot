package app.liquidspot.xposed.theme

import app.liquidspot.core.FeatureCatalog
import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.feature.Feature

object AmoledTheme {
    fun install(ctx: HookContext) {
        SpotifyTheme.install(ctx)
        AmoledGradients.install(ctx.hooks)
    }
}

object AccentColorFeature : Feature {
    override val info = FeatureCatalog.ACCENT_COLOR

    override fun install(ctx: HookContext) = SpotifyTheme.enableAccent(ctx)
}
