package app.liquidspot.xposed.ui.glass

import app.liquidspot.xposed.theme.SpotifyTheme
import android.graphics.Canvas
import android.graphics.RenderNode
import android.view.View
import android.view.ViewTreeObserver

class BackdropSource(val source: View, private val content: ((Canvas) -> Unit)? = null) {
    val node = RenderNode("LiquidSpotBackdrop")

    var isRecording = false
        private set

    var baseColor = if (SpotifyTheme.amoled) 0xFF000000.toInt() else 0xFF121212.toInt()

    private val clients = LinkedHashSet<View>()
    private val location = IntArray(2)
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        record()
        true
    }
    private var observer: ViewTreeObserver? = null

    fun addClient(view: View) {
        if (clients.add(view) && clients.size == 1) {
            observer = source.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
        }
    }

    fun removeClient(view: View) {
        if (clients.remove(view) && clients.isEmpty()) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
            observer = null
        }
    }

    fun locationInWindow(out: IntArray) {
        source.getLocationInWindow(location)
        out[0] = location[0]
        out[1] = location[1]
    }

    fun record() {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0 || !source.isLaidOut) return
        node.setPosition(0, 0, w, h)
        val canvas = node.beginRecording(w, h)
        isRecording = true
        try {
            canvas.drawColor(baseColor)
            content?.invoke(canvas) ?: source.draw(canvas)
        } finally {
            isRecording = false
            node.endRecording()
        }
        for (client in clients) client.invalidate()
    }
}
