package app.liquidspot.core

import androidx.annotation.StringRes
import app.liquidspot.R

data class OptionInfo(
    val key: String,
    val category: FeatureCategory,
    val feature: FeatureInfo,
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    val defaultEnabled: Boolean,
    val liquidOnly: Boolean = false,
)

object OptionCatalog {
    val NAVBAR_LENS = OptionInfo(
        key = "navbar.lens",
        category = FeatureCategory.BAR,
        feature = FeatureCatalog.LIQUID_NAVBAR,
        title = R.string.option_navbar_lens_title,
        summary = R.string.option_navbar_lens_summary,
        defaultEnabled = true,
        liquidOnly = true,
    )

    val NAVBAR_MINIMIZE = OptionInfo(
        key = "navbar.minimize",
        category = FeatureCategory.BAR,
        feature = FeatureCatalog.LIQUID_NAVBAR,
        title = R.string.option_navbar_minimize_title,
        summary = R.string.option_navbar_minimize_summary,
        defaultEnabled = true,
    )

    val MINIPLAYER_COMPACT = OptionInfo(
        key = "miniplayer.compact",
        category = FeatureCategory.BAR,
        feature = FeatureCatalog.LIQUID_MINIPLAYER,
        title = R.string.option_miniplayer_compact_title,
        summary = R.string.option_miniplayer_compact_summary,
        defaultEnabled = false,
    )

    val PLAYER_CANVAS_OFF = OptionInfo(
        key = "player.canvas_off",
        category = FeatureCategory.PLAYER,
        feature = FeatureCatalog.LIQUID_PLAYER,
        title = R.string.option_player_canvas_off_title,
        summary = R.string.option_player_canvas_off_summary,
        defaultEnabled = false,
    )

    val PLAYER_LYRIC_LINE = OptionInfo(
        key = "player.lyric_line",
        category = FeatureCategory.PLAYER,
        feature = FeatureCatalog.LIQUID_PLAYER,
        title = R.string.option_player_lyric_line_title,
        summary = R.string.option_player_lyric_line_summary,
        defaultEnabled = false,
    )

    val all: List<OptionInfo> = listOf(NAVBAR_LENS, NAVBAR_MINIMIZE, MINIPLAYER_COMPACT, PLAYER_CANVAS_OFF, PLAYER_LYRIC_LINE)
}
