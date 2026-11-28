package app.liquidspot.xposed.features.navbar

import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.features.pages.ComposeSymbols
import app.liquidspot.xposed.util.Logger
import java.lang.reflect.Field
import java.lang.reflect.Modifier

object NavTabsHook {
    private var installed = false
    private val clicks = HashMap<Int, Any>()
    private lateinit var clickField: Field

    @Volatile
    var selectedTitle = 0
        private set

    @Volatile
    var failed = false
        private set

    fun install(ctx: HookContext) {
        if (installed) return
        installed = true
        ComposeSymbols.whenReady(ctx.appInfo) { symbols ->
            runCatching {
                val item = symbols.navItem ?: error("item das abas não encontrado")
                val action = Class.forName(symbols.action, false, ctx.classLoader)
                val function0 = action.declaredConstructors.single { it.parameterTypes.size == 2 }.parameterTypes[1]
                clickField = action.declaredFields.single { !Modifier.isStatic(it.modifiers) && it.type == function0 }.apply { isAccessible = true }
                val constructor = Class.forName(item, false, ctx.classLoader).declaredConstructors.single { it.parameterTypes.size == PARAMS && it.parameterTypes[7] == action }
                ctx.xposed.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    runCatching { onItem(chain.args[4] as Int, chain.args[6] as Boolean, chain.args[7]) }
                    result
                }
                Logger.i("Barra de abas: gancho nas abas do Spotify instalado")
            }.onFailure {
                failed = true
                Logger.e("Barra de abas: não foi possível instalar o gancho das abas", it)
            }
        }
        if (ComposeSymbols.isFailed) failed = true
    }

    private fun onItem(title: Int, selected: Boolean, action: Any?) {
        val click = action?.let { clickField.get(it) } ?: return
        synchronized(clicks) { clicks[title] = click }
        if (selected) selectedTitle = title
    }

    fun has(title: Int): Boolean = synchronized(clicks) { title in clicks }

    fun click(title: Int): Boolean {
        val click = synchronized(clicks) { clicks[title] } ?: return false
        return runCatching {
            click.javaClass.getMethod("invoke").invoke(click)
            true
        }.onFailure { Logger.w("Barra de abas: toque na aba falhou", it) }.getOrDefault(false)
    }

    private const val PARAMS = 8
}
