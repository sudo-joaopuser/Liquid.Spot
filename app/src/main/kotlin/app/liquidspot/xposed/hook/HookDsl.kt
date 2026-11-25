package app.liquidspot.xposed.hook

import app.liquidspot.xposed.util.Logger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.reflect.Executable
import java.lang.reflect.Method

class Hooks(private val xposed: XposedInterface) {
    fun before(
        target: Executable,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        block: BeforeCall.() -> Unit,
    ): HookHandle = xposed.hook(target).setPriority(priority).intercept { chain ->
        val call = BeforeCall(chain)
        if (guard(target) { call.block() } && call.skipOriginal) call.result
        else chain.proceed(call.args)
    }

    fun after(
        target: Executable,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        block: AfterCall.() -> Unit,
    ): HookHandle = xposed.hook(target).setPriority(priority).intercept { chain ->
        val call = AfterCall(chain, chain.proceed())
        guard(target) { call.block() }
        call.result
    }

    fun replace(
        target: Executable,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        block: ReplaceCall.() -> Any?,
    ): HookHandle = xposed.hook(target).setPriority(priority).intercept { chain ->
        val call = ReplaceCall(chain)
        var result: Any? = null
        if (guard(target) { result = call.block() }) result else chain.proceed()
    }

    private inline fun guard(target: Executable, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (t: Throwable) {
        Logger.e("Hook falhou em $target", t)
        false
    }
}

open class HookCall internal constructor(protected val chain: Chain) {
    val thisObject: Any? get() = chain.thisObject
    val method: Executable get() = chain.executable
}

class BeforeCall internal constructor(chain: Chain) : HookCall(chain) {
    val args: Array<Any?> = chain.args.toTypedArray()
    internal var skipOriginal = false
    internal var result: Any? = null

    @Suppress("UNCHECKED_CAST")
    fun <T> arg(index: Int): T = args[index] as T

    fun returnEarly(value: Any? = null) {
        skipOriginal = true
        result = value
    }
}

class AfterCall internal constructor(chain: Chain, var result: Any?) : HookCall(chain) {
    val args: List<Any?> get() = chain.args

    @Suppress("UNCHECKED_CAST")
    fun <T> arg(index: Int): T = chain.args[index] as T
}

class ReplaceCall internal constructor(chain: Chain) : HookCall(chain) {
    val args: List<Any?> get() = chain.args

    @Suppress("UNCHECKED_CAST")
    fun <T> arg(index: Int): T = chain.args[index] as T

    fun callOriginal(): Any? = chain.proceed()

    fun callOriginal(vararg newArgs: Any?): Any? = chain.proceed(arrayOf(*newArgs))
}

fun Class<*>.method(name: String, vararg params: Class<*>): Method =
    getDeclaredMethod(name, *params).apply { isAccessible = true }
