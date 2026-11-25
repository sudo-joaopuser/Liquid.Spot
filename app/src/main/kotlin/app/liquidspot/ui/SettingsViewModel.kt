package app.liquidspot.ui

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.liquidspot.core.AiFiles
import app.liquidspot.core.Constants
import app.liquidspot.core.FeatureCatalog
import app.liquidspot.core.FeatureInfo
import app.liquidspot.core.GlassStyle
import app.liquidspot.core.LyricsSource
import app.liquidspot.core.NavBarLayout
import app.liquidspot.core.OptionCatalog
import app.liquidspot.core.OptionInfo
import app.liquidspot.core.PrefKeys
import app.liquidspot.core.ThemePalette
import app.liquidspot.ui.ai.AiInstaller
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FrameworkInfo(val name: String, val version: String, val apiVersion: Int)

data class AiUiState(
    val modelSource: String? = null,
    val modelSize: Long = 0L,
    val runtimeInstalled: Boolean = false,
    val busy: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    val cacheEnabled: Boolean = AiFiles.CACHE_ENABLED_DEFAULT,
    val cacheLimitMb: Int = AiFiles.CACHE_LIMIT_DEFAULT_MB,
    val cacheCleared: Boolean = false,
) {
    val installed: Boolean get() = modelSource != null && runtimeInstalled
}

data class SettingsUiState(
    val connected: Boolean = false,
    val framework: FrameworkInfo? = null,
    val spotifyInScope: Boolean = false,
    val spotifyVersion: String? = null,
    val features: Map<String, Boolean> = FeatureCatalog.all.associate { it.id to it.defaultEnabled },
    val options: Map<String, Boolean> = OptionCatalog.all.associate { it.key to it.defaultEnabled },
    val debugLogging: Boolean = PrefKeys.DEBUG_LOGGING_DEFAULT,
    val dumpUi: Boolean = PrefKeys.DEBUG_DUMP_UI_DEFAULT,
    val navBarLayout: NavBarLayout = NavBarLayout.DEFAULT,
    val glassStyle: GlassStyle = GlassStyle.DEFAULT,
    val reduceMotion: Boolean = PrefKeys.GLASS_REDUCE_MOTION_DEFAULT,
    val accentColor: Int = ThemePalette.SPOTIFY_GREEN,
    val lyricsOrder: List<LyricsSource> = LyricsSource.DEFAULT_ORDER,
    val lyricsEnabled: Set<LyricsSource> = LyricsSource.entries.filter { it.defaultEnabled }.toSet(),
    val paxSenixKey: String = "",
    val ai: AiUiState = AiUiState(),
    val error: String? = null,
) {
    val spotifyTested: Boolean get() = spotifyVersion in Constants.TESTED_SPOTIFY_VERSIONS
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var service: XposedService? = null
    private var prefs: SharedPreferences? = null

    init {
        refreshSpotifyVersion()
        viewModelScope.launch {
            XposedServiceState.service.collect { onService(it) }
        }
    }

    private fun onService(svc: XposedService?) {
        service = svc
        if (svc == null) {
            prefs = null
            _state.update { it.copy(connected = false, framework = null, spotifyInScope = false) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = svc.getRemotePreferences(Constants.PREFS_GROUP)
                prefs = p
                _state.update {
                    it.copy(
                        connected = true,
                        framework = FrameworkInfo(svc.frameworkName, svc.frameworkVersion, svc.apiVersion),
                        spotifyInScope = Constants.SPOTIFY_PACKAGE in svc.scope,
                        features = FeatureCatalog.all.associate { f -> f.id to p.getBoolean(f.prefKey, f.defaultEnabled) },
                        options = OptionCatalog.all.associate { o -> o.key to p.getBoolean(o.key, o.defaultEnabled) },
                        debugLogging = p.getBoolean(PrefKeys.DEBUG_LOGGING, PrefKeys.DEBUG_LOGGING_DEFAULT),
                        dumpUi = p.getBoolean(PrefKeys.DEBUG_DUMP_UI, PrefKeys.DEBUG_DUMP_UI_DEFAULT),
                        navBarLayout = NavBarLayout.fromPref(p.getString(PrefKeys.NAVBAR_LAYOUT, null)),
                        glassStyle = GlassStyle.fromPref(p.getString(PrefKeys.GLASS_STYLE, null)),
                        reduceMotion = p.getBoolean(PrefKeys.GLASS_REDUCE_MOTION, PrefKeys.GLASS_REDUCE_MOTION_DEFAULT),
                        accentColor = p.getInt(PrefKeys.ACCENT_COLOR, ThemePalette.SPOTIFY_GREEN),
                        lyricsOrder = LyricsSource.parseOrder(p.getString(LyricsSource.ORDER_KEY, null)),
                        lyricsEnabled = LyricsSource.entries.filter { src -> p.getBoolean(src.prefKey, src.defaultEnabled) }.toSet(),
                        paxSenixKey = p.getString(LyricsSource.PAXSENIX_KEY, null).orEmpty(),
                        ai = aiState(AiInstaller(svc, p)),
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                prefs = null
                _state.update { it.copy(connected = false, error = t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    fun setFeatureEnabled(feature: FeatureInfo, enabled: Boolean) {
        _state.update { it.copy(features = it.features + (feature.id to enabled)) }
        write { putBoolean(feature.prefKey, enabled) }
    }

    fun setOptionEnabled(option: OptionInfo, enabled: Boolean) {
        _state.update { it.copy(options = it.options + (option.key to enabled)) }
        write { putBoolean(option.key, enabled) }
    }

    fun setDumpUi(enabled: Boolean) {
        _state.update { it.copy(dumpUi = enabled) }
        write { putBoolean(PrefKeys.DEBUG_DUMP_UI, enabled) }
    }

    fun setDebugLogging(enabled: Boolean) {
        _state.update { it.copy(debugLogging = enabled) }
        write { putBoolean(PrefKeys.DEBUG_LOGGING, enabled) }
    }

    fun setNavBarLayout(layout: NavBarLayout) {
        _state.update { it.copy(navBarLayout = layout) }
        write { putString(PrefKeys.NAVBAR_LAYOUT, layout.name) }
    }

    fun setGlassStyle(style: GlassStyle) {
        _state.update { it.copy(glassStyle = style) }
        write { putString(PrefKeys.GLASS_STYLE, style.name) }
    }

    fun setLyricsSourceEnabled(source: LyricsSource, enabled: Boolean) {
        _state.update { it.copy(lyricsEnabled = if (enabled) it.lyricsEnabled + source else it.lyricsEnabled - source) }
        write { putBoolean(source.prefKey, enabled) }
    }

    fun moveLyricsSource(source: LyricsSource, delta: Int) {
        val order = _state.value.lyricsOrder.toMutableList()
        val from = order.indexOf(source)
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (from < 0 || from == to) return
        order.add(to, order.removeAt(from))
        setLyricsOrder(order)
    }

    fun resetLyricsOrder() = setLyricsOrder(LyricsSource.DEFAULT_ORDER)

    private fun setLyricsOrder(order: List<LyricsSource>) {
        _state.update { it.copy(lyricsOrder = order) }
        write { putString(LyricsSource.ORDER_KEY, LyricsSource.formatOrder(order)) }
    }

    fun setPaxSenixKey(key: String) {
        _state.update { it.copy(paxSenixKey = key.trim()) }
        write { putString(LyricsSource.PAXSENIX_KEY, key.trim()) }
    }

    fun setAccentColor(color: Int) {
        _state.update { it.copy(accentColor = color) }
        write { putInt(PrefKeys.ACCENT_COLOR, color) }
    }

    fun setReduceMotion(enabled: Boolean) {
        _state.update { it.copy(reduceMotion = enabled) }
        write { putBoolean(PrefKeys.GLASS_REDUCE_MOTION, enabled) }
    }

    private var aiJob: Job? = null

    private fun aiState(installer: AiInstaller, base: AiUiState = AiUiState()): AiUiState {
        val s = installer.status()
        val p = prefs
        return base.copy(
            modelSource = s.modelSource,
            modelSize = s.modelSize,
            runtimeInstalled = s.runtimeInstalled,
            cacheEnabled = p?.getBoolean(AiFiles.PREF_CACHE_ENABLED, AiFiles.CACHE_ENABLED_DEFAULT) ?: AiFiles.CACHE_ENABLED_DEFAULT,
            cacheLimitMb = p?.getInt(AiFiles.PREF_CACHE_LIMIT_MB, AiFiles.CACHE_LIMIT_DEFAULT_MB) ?: AiFiles.CACHE_LIMIT_DEFAULT_MB,
        )
    }

    private fun installer(): AiInstaller? {
        val svc = service ?: return null
        val p = prefs ?: return null
        return AiInstaller(svc, p)
    }

    fun setAiCacheEnabled(enabled: Boolean) {
        _state.update { it.copy(ai = it.ai.copy(cacheEnabled = enabled)) }
        write { putBoolean(AiFiles.PREF_CACHE_ENABLED, enabled) }
    }

    fun setAiCacheLimit(megabytes: Int) {
        _state.update { it.copy(ai = it.ai.copy(cacheLimitMb = megabytes)) }
        write { putInt(AiFiles.PREF_CACHE_LIMIT_MB, megabytes) }
    }

    fun clearAiCache() {
        _state.update { it.copy(ai = it.ai.copy(cacheCleared = true)) }
        write { putLong(AiFiles.PREF_CACHE_CLEAR, System.currentTimeMillis()) }
    }

    fun downloadAi() = runAi { it.download(::onAiProgress) }

    fun importAi(uri: Uri) = runAi { it.import(getApplication<Application>().contentResolver, uri, ::onAiProgress) }

    fun cancelAi() {
        aiJob?.cancel()
    }

    fun removeAi() {
        val installer = installer() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { installer.remove() }
            _state.update { it.copy(ai = aiState(installer)) }
        }
    }

    private fun onAiProgress(value: Float) {
        _state.update { it.copy(ai = it.ai.copy(progress = value)) }
    }

    private fun runAi(block: suspend (AiInstaller) -> Unit) {
        val installer = installer() ?: return
        if (aiJob?.isActive == true) return
        _state.update { it.copy(ai = it.ai.copy(busy = true, progress = 0f, error = null)) }
        aiJob = viewModelScope.launch(Dispatchers.IO) {
            val error = try {
                block(installer)
                null
            } catch (e: CancellationException) {
                null
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
            if (error != null || !installer.status().let { it.modelInstalled && it.runtimeInstalled }) runCatching { installer.discardPartial() }
            _state.update { it.copy(ai = aiState(installer, AiUiState(error = error))) }
        }
    }

    fun requestSpotifyScope() {
        val svc = service ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                svc.requestScope(
                    listOf(Constants.SPOTIFY_PACKAGE),
                    object : XposedService.OnScopeEventListener {
                        override fun onScopeRequestApproved(approved: List<String>) {
                            _state.update { it.copy(spotifyInScope = Constants.SPOTIFY_PACKAGE in approved || it.spotifyInScope) }
                        }

                        override fun onScopeRequestFailed(message: String) {
                            _state.update { it.copy(error = message) }
                        }
                    },
                )
            }.onFailure { t -> _state.update { it.copy(error = t.message) } }
        }
    }

    fun refreshSpotifyVersion() {
        val version = try {
            getApplication<Application>().packageManager
                .getPackageInfo(Constants.SPOTIFY_PACKAGE, 0).versionName ?: "?"
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        _state.update { it.copy(spotifyVersion = version) }
    }

    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        val p = prefs ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { p.edit().apply(block).apply() }
                .onFailure { t -> _state.update { it.copy(error = t.message) } }
        }
    }
}
