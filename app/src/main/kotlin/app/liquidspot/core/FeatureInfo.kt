package app.liquidspot.core

import androidx.annotation.StringRes
import app.liquidspot.R

enum class FeatureCategory(@param:StringRes val title: Int) {
    BAR(R.string.category_bar),
    PAGES(R.string.category_pages),
    PLAYER(R.string.category_player),
    THEME(R.string.category_theme),
    DEBUG(R.string.category_debug),
}

data class FeatureInfo(
    val id: String,
    val category: FeatureCategory,
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    val defaultEnabled: Boolean,
) {
    val prefKey: String get() = "feature.$id"
}

object FeatureCatalog {
    val LIQUID_NAVBAR = FeatureInfo(
        id = "liquid_navbar",
        category = FeatureCategory.BAR,
        title = R.string.feature_liquid_navbar_title,
        summary = R.string.feature_liquid_navbar_summary,
        defaultEnabled = true,
    )

    val LIQUID_MINIPLAYER = FeatureInfo(
        id = "liquid_miniplayer",
        category = FeatureCategory.BAR,
        title = R.string.feature_liquid_miniplayer_title,
        summary = R.string.feature_liquid_miniplayer_summary,
        defaultEnabled = true,
    )

    val LIQUID_PLAYER = FeatureInfo(
        id = "liquid_player",
        category = FeatureCategory.PLAYER,
        title = R.string.feature_liquid_player_title,
        summary = R.string.feature_liquid_player_summary,
        defaultEnabled = true,
    )

    val LIQUID_PAGES = FeatureInfo(
        id = "liquid_pages",
        category = FeatureCategory.PAGES,
        title = R.string.feature_liquid_pages_title,
        summary = R.string.feature_liquid_pages_summary,
        defaultEnabled = true,
    )

    val GLASS_SHEETS = FeatureInfo(
        id = "glass_sheets",
        category = FeatureCategory.PLAYER,
        title = R.string.feature_glass_sheets_title,
        summary = R.string.feature_glass_sheets_summary,
        defaultEnabled = true,
    )

    val ACCENT_COLOR = FeatureInfo(
        id = "accent_color",
        category = FeatureCategory.THEME,
        title = R.string.feature_accent_title,
        summary = R.string.feature_accent_summary,
        defaultEnabled = false,
    )

    val KEEP_RUNNING = FeatureInfo(
        id = "keep_running",
        category = FeatureCategory.PLAYER,
        title = R.string.feature_keep_running_title,
        summary = R.string.feature_keep_running_summary,
        defaultEnabled = false,
    )

    val all: List<FeatureInfo> = listOf(
        LIQUID_NAVBAR, LIQUID_MINIPLAYER, LIQUID_PAGES, LIQUID_PLAYER, GLASS_SHEETS, KEEP_RUNNING,
        ACCENT_COLOR,
    )
}
