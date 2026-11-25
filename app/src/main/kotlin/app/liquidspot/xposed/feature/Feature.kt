package app.liquidspot.xposed.feature

import app.liquidspot.core.FeatureInfo
import app.liquidspot.xposed.HookContext

interface Feature {
    val info: FeatureInfo

    fun install(ctx: HookContext)
}
