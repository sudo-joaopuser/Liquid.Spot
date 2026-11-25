package app.liquidspot.xposed.feature

import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.util.Logger

class FeatureManager(private val features: List<Feature>) {
    fun installAll(ctx: HookContext) {
        var installed = 0
        var failed = 0
        for (feature in features) {
            val id = feature.info.id
            if (!ctx.prefs.isEnabled(feature.info)) {
                Logger.d("Função desligada: $id")
                continue
            }
            val started = System.nanoTime()
            runCatching { feature.install(ctx) }
                .onSuccess {
                    installed++
                    Logger.i("Função instalada: $id (${(System.nanoTime() - started) / 1_000_000} ms)")
                }
                .onFailure {
                    failed++
                    Logger.e("Função $id falhou e foi desligada", it)
                }
        }
        Logger.i("$installed funções instaladas, $failed com erro")
    }
}
