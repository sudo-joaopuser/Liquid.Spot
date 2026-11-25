package app.liquidspot.xposed.util

import app.liquidspot.xposed.hook.Hooks
import app.liquidspot.xposed.hook.method

object CrashLogger {
    private var installed = false

    fun install(hooks: Hooks) {
        if (installed) return
        installed = true
        Thread.setDefaultUncaughtExceptionHandler(wrap(Thread.getDefaultUncaughtExceptionHandler()))
        hooks.before(Thread::class.java.method("setDefaultUncaughtExceptionHandler", Thread.UncaughtExceptionHandler::class.java)) {
            val handler = arg<Thread.UncaughtExceptionHandler?>(0)
            if (handler !is Logging) args[0] = wrap(handler)
        }
    }

    private fun wrap(next: Thread.UncaughtExceptionHandler?) = Logging(next)

    private class Logging(private val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching { Logger.e("Spotify fechou por um erro na thread '${thread.name}'", error) }
            next?.uncaughtException(thread, error)
        }
    }
}
