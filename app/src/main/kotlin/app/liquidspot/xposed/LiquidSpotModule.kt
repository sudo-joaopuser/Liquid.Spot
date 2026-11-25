package app.liquidspot.xposed

import app.liquidspot.BuildConfig
import app.liquidspot.core.Constants
import app.liquidspot.core.PrefKeys
import app.liquidspot.xposed.feature.FeatureManager
import app.liquidspot.xposed.feature.Features
import app.liquidspot.xposed.features.debug.UiDumper
import app.liquidspot.xposed.util.CrashLogger
import app.liquidspot.xposed.util.Logger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class LiquidSpotModule : XposedModule() {
    private var installed = false

    private var mainProcess = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Logger.attach(this)
        if (param.processName != Constants.SPOTIFY_PACKAGE) {
            if (apiVersion >= XposedInterface.API_102) detach()
            return
        }
        mainProcess = true
        Logger.i(
            "LiquidSpot ${BuildConfig.VERSION_NAME} carregado em $frameworkName $frameworkVersion (API $apiVersion)",
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!mainProcess || installed || param.packageName != Constants.SPOTIFY_PACKAGE || !param.isFirstPackage) return
        installed = true

        val prefs = ModulePrefs(
            runCatching { getRemotePreferences(Constants.PREFS_GROUP) }
                .onFailure { Logger.w("Preferências remotas indisponíveis, usando valores padrão", it) }
                .getOrNull(),
        )
        Logger.debugEnabled = prefs.getBoolean(PrefKeys.DEBUG_LOGGING, PrefKeys.DEBUG_LOGGING_DEFAULT)
        prefs.onChange(PrefKeys.DEBUG_LOGGING) {
            Logger.debugEnabled = prefs.getBoolean(PrefKeys.DEBUG_LOGGING, PrefKeys.DEBUG_LOGGING_DEFAULT)
        }
        UiDumper.enabled = prefs.getBoolean(PrefKeys.DEBUG_DUMP_UI, PrefKeys.DEBUG_DUMP_UI_DEFAULT)
        prefs.onChange(PrefKeys.DEBUG_DUMP_UI) {
            UiDumper.enabled = prefs.getBoolean(PrefKeys.DEBUG_DUMP_UI, PrefKeys.DEBUG_DUMP_UI_DEFAULT)
        }

        val appInfo = param.applicationInfo
        val ctx = HookContext(this, appInfo, param.classLoader, prefs)
        runCatching { CrashLogger.install(ctx.hooks) }.onFailure { Logger.w("Não foi possível instalar o registro de travamentos", it) }

        try {
            FeatureManager(Features.all).installAll(ctx)
        } catch (t: Throwable) {
            Logger.e("Erro inesperado ao instalar as funções", t)
        }
    }
}
