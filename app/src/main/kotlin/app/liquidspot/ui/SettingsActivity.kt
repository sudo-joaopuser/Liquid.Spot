package app.liquidspot.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.liquidspot.R
import app.liquidspot.ui.screens.AboutScreen
import app.liquidspot.ui.screens.FeaturesActions
import app.liquidspot.ui.screens.FeaturesScreen
import app.liquidspot.ui.screens.HomeScreen
import app.liquidspot.ui.screens.LyricsActions
import app.liquidspot.ui.screens.LyricsScreen
import app.liquidspot.ui.theme.LiquidSpotTheme

class SettingsActivity : ComponentActivity() {
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LiquidSpotTheme { SettingsRoot(viewModel) }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSpotifyVersion()
    }
}

private enum class Tab(@param:StringRes val title: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Filled.Home),
    FEATURES(R.string.tab_features, Icons.Filled.Build),
    LYRICS(R.string.tab_lyrics, Icons.AutoMirrored.Filled.List),
    ABOUT(R.string.tab_about, Icons.Filled.Info),
}

@Composable
private fun SettingsRoot(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val featuresActions = remember(viewModel) {
        FeaturesActions(
            onFeatureToggle = viewModel::setFeatureEnabled,
            onOptionToggle = viewModel::setOptionEnabled,
            onDebugLoggingToggle = viewModel::setDebugLogging,
            onDumpUiToggle = viewModel::setDumpUi,
            onNavBarLayout = viewModel::setNavBarLayout,
            onGlassStyle = viewModel::setGlassStyle,
            onReduceMotion = viewModel::setReduceMotion,
            onAccentColor = viewModel::setAccentColor,
        )
    }
    val lyricsActions = remember(viewModel) {
        LyricsActions(
            onToggle = viewModel::setLyricsSourceEnabled,
            onMove = viewModel::moveLyricsSource,
            onResetOrder = viewModel::resetLyricsOrder,
            onPaxSenixKey = viewModel::setPaxSenixKey,
            onAiDownload = viewModel::downloadAi,
            onAiImport = viewModel::importAi,
            onAiCancel = viewModel::cancelAi,
            onAiRemove = viewModel::removeAi,
            onAiCacheEnabled = viewModel::setAiCacheEnabled,
            onAiCacheLimit = viewModel::setAiCacheLimit,
            onAiCacheClear = viewModel::clearAiCache,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.title)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.HOME -> HomeScreen(state, viewModel::requestSpotifyScope, modifier)
            Tab.FEATURES -> FeaturesScreen(state, featuresActions, modifier)
            Tab.LYRICS -> LyricsScreen(state, lyricsActions, modifier)
            Tab.ABOUT -> AboutScreen(modifier)
        }
    }
}
