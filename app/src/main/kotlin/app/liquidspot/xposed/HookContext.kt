package app.liquidspot.xposed

import android.content.pm.ApplicationInfo
import app.liquidspot.xposed.hook.Hooks
import io.github.libxposed.api.XposedInterface

class HookContext(
    val xposed: XposedInterface,
    val appInfo: ApplicationInfo,
    val classLoader: ClassLoader,
    val prefs: ModulePrefs,
) {
    val hooks = Hooks(xposed)

    fun findClass(name: String): Class<*> = Class.forName(name, false, classLoader)
}
