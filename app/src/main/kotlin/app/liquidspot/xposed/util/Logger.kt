package app.liquidspot.xposed.util

import android.util.Log
import app.liquidspot.core.Constants
import io.github.libxposed.api.XposedInterface

object Logger {
    private const val TAG = Constants.LOG_TAG

    @Volatile
    private var xposed: XposedInterface? = null

    @Volatile
    var debugEnabled = false

    fun attach(xposed: XposedInterface) {
        this.xposed = xposed
    }

    fun d(msg: String) {
        if (debugEnabled) write(Log.DEBUG, msg, null)
    }

    fun i(msg: String) = write(Log.INFO, msg, null)

    fun w(msg: String, tr: Throwable? = null) = write(Log.WARN, msg, tr)

    fun e(msg: String, tr: Throwable? = null) = write(Log.ERROR, msg, tr)

    private fun write(priority: Int, msg: String, tr: Throwable?) {
        xposed?.let { runCatching { it.log(priority, TAG, msg, tr) } }
        if (tr == null) Log.println(priority, TAG, msg)
        else Log.println(priority, TAG, msg + '\n' + Log.getStackTraceString(tr))
    }
}
