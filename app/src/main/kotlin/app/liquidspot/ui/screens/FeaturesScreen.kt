package app.liquidspot.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.liquidspot.R
import app.liquidspot.core.FeatureCatalog
import app.liquidspot.core.FeatureCategory
import app.liquidspot.core.FeatureInfo
import app.liquidspot.core.GlassStyle
import app.liquidspot.core.NavBarLayout
import app.liquidspot.core.OptionCatalog
import app.liquidspot.core.OptionInfo
import app.liquidspot.ui.SettingsUiState
import app.liquidspot.ui.theme.SectionCard

class FeaturesActions(
    val onFeatureToggle: (FeatureInfo, Boolean) -> Unit,
    val onOptionToggle: (OptionInfo, Boolean) -> Unit,
    val onDebugLoggingToggle: (Boolean) -> Unit,
    val onDumpUiToggle: (Boolean) -> Unit,
    val onNavBarLayout: (NavBarLayout) -> Unit,
    val onGlassStyle: (GlassStyle) -> Unit,
    val onReduceMotion: (Boolean) -> Unit,
    val onAccentColor: (Int) -> Unit,
)

private val GLASS_CATEGORIES = setOf(FeatureCategory.BAR, FeatureCategory.PAGES, FeatureCategory.PLAYER)

private fun SettingsUiState.isOn(feature: FeatureInfo) = features[feature.id] ?: feature.defaultEnabled

private fun SettingsUiState.isOn(option: OptionInfo) = options[option.key] ?: option.defaultEnabled

private fun SettingsUiState.canUse(option: OptionInfo) =
    connected && isOn(option.feature) && (!option.liquidOnly || glassStyle == GlassStyle.LIQUID)

@Composable
fun FeaturesScreen(state: SettingsUiState, actions: FeaturesActions, modifier: Modifier = Modifier) {
    ScreenColumn(stringResource(R.string.tab_features), modifier) {
        if (!state.connected) {
            Text(
                stringResource(R.string.features_need_service),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        val byCategory = FeatureCatalog.all.groupBy { it.category }
        FeatureCategory.entries.forEach { category ->
            val features = byCategory[category].orEmpty()
            if (features.isNotEmpty() || category == FeatureCategory.DEBUG) {
                SectionCard {
                    CardTitle(category.title)
                    features.forEach { feature ->
                        SwitchRow(
                            title = stringResource(feature.title),
                            summary = stringResource(feature.summary),
                            checked = state.isOn(feature),
                            enabled = state.connected,
                            onCheckedChange = { actions.onFeatureToggle(feature, it) },
                        )
                    }
                    OptionCatalog.all.filter { it.category == category }.forEach { option ->
                        SwitchRow(
                            title = stringResource(option.title),
                            summary = stringResource(option.summary),
                            checked = state.isOn(option),
                            enabled = state.canUse(option),
                            onCheckedChange = { actions.onOptionToggle(option, it) },
                        )
                    }
                    when (category) {
                        FeatureCategory.BAR -> {
                            HorizontalDivider(Modifier.padding(vertical = 12.dp))
                            NavBarLayoutPicker(
                                state.navBarLayout,
                                enabled = state.connected && state.isOn(FeatureCatalog.LIQUID_NAVBAR),
                                onSelect = actions.onNavBarLayout,
                            )
                        }
                        FeatureCategory.THEME -> if (state.isOn(FeatureCatalog.ACCENT_COLOR)) {
                            HorizontalDivider(Modifier.padding(vertical = 12.dp))
                            AccentColorPicker(state.accentColor, enabled = state.connected, onSelect = actions.onAccentColor)
                        }
                        FeatureCategory.DEBUG -> DebugSwitches(state, actions)
                        else -> Unit
                    }
                }
            }
            if (category == FeatureCategory.PLAYER) SectionCard { GlassSettings(state, actions) }
        }
    }
}

@Composable
private fun CardTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun GlassSettings(state: SettingsUiState, actions: FeaturesActions) {
    val anyGlassOn = FeatureCatalog.all.filter { it.category in GLASS_CATEGORIES }.any { state.isOn(it) }
    CardTitle(R.string.category_glass)
    Spacer(Modifier.height(4.dp))
    GlassStylePicker(state.glassStyle, enabled = state.connected && anyGlassOn, onSelect = actions.onGlassStyle)
    Spacer(Modifier.height(8.dp))
    SwitchRow(
        title = stringResource(R.string.pref_reduce_motion_title),
        summary = stringResource(R.string.pref_reduce_motion_summary),
        checked = state.reduceMotion,
        enabled = state.connected && anyGlassOn,
        onCheckedChange = actions.onReduceMotion,
    )
}

@Composable
private fun DebugSwitches(state: SettingsUiState, actions: FeaturesActions) {
    SwitchRow(
        title = stringResource(R.string.pref_debug_logging_title),
        summary = stringResource(R.string.pref_debug_logging_summary),
        checked = state.debugLogging,
        enabled = state.connected,
        onCheckedChange = actions.onDebugLoggingToggle,
    )
    SwitchRow(
        title = stringResource(R.string.pref_dump_ui_title),
        summary = stringResource(R.string.pref_dump_ui_summary),
        checked = state.dumpUi,
        enabled = state.connected,
        onCheckedChange = actions.onDumpUiToggle,
    )
}
