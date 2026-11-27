package app.liquidspot.xposed.feature

import app.liquidspot.xposed.features.background.KeepRunningFeature
import app.liquidspot.xposed.features.miniplayer.LiquidMiniPlayerFeature
import app.liquidspot.xposed.features.navbar.LiquidNavBarFeature
import app.liquidspot.xposed.features.pages.LiquidPagesFeature
import app.liquidspot.xposed.features.player.LiquidPlayerFeature
import app.liquidspot.xposed.features.sheets.GlassSheetsFeature
import app.liquidspot.xposed.theme.AccentColorFeature
import app.liquidspot.xposed.theme.AmoledThemeFeature

object Features {
    val all: List<Feature> = listOf(
        AmoledThemeFeature,
        AccentColorFeature,
        LiquidNavBarFeature,
        LiquidMiniPlayerFeature,
        LiquidPagesFeature,
        LiquidPlayerFeature,
        GlassSheetsFeature,
        KeepRunningFeature,
    )
}
