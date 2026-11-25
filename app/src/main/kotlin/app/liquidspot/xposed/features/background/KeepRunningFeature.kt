package app.liquidspot.xposed.features.background

import android.app.Service
import android.content.Intent
import app.liquidspot.core.FeatureCatalog
import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.feature.Feature
import app.liquidspot.xposed.hook.Hooks
import app.liquidspot.xposed.util.Logger

object KeepRunningFeature : Feature {
    override val info = FeatureCatalog.KEEP_RUNNING

    private val hookedClasses = HashSet<Class<*>>()

    override fun install(ctx: HookContext) {
        val hooks = ctx.hooks
        hookClass(hooks, Service::class.java)
        hooks.after(Service::class.java.getDeclaredConstructor()) {
            thisObject?.javaClass?.let { hookHierarchy(hooks, it) }
        }
    }

    private fun hookHierarchy(hooks: Hooks, start: Class<*>) {
        var current: Class<*>? = start
        while (current != null && current != Service::class.java) {
            hookClass(hooks, current)
            current = current.superclass
        }
    }

    private fun hookClass(hooks: Hooks, cls: Class<*>) {
        synchronized(hookedClasses) {
            if (!hookedClasses.add(cls)) return
        }
        declared(cls, "onTaskRemoved", Intent::class.java)?.let { method ->
            hooks.before(method) { returnEarly() }
            Logger.d("Manter rodando: onTaskRemoved interceptado em ${cls.name}")
        }
        declared(cls, "onStartCommand", Intent::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)?.let { method ->
            hooks.after(method) {
                if (result == Service.START_NOT_STICKY) result = Service.START_STICKY
            }
        }
    }

    private fun declared(cls: Class<*>, name: String, vararg params: Class<*>) =
        runCatching { cls.getDeclaredMethod(name, *params) }.getOrNull()
            ?.takeUnless { java.lang.reflect.Modifier.isAbstract(it.modifiers) }
}
