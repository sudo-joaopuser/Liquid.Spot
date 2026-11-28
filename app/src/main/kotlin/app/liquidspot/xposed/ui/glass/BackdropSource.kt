package app.liquidspot.xposed.ui.glass

import android.graphics.Canvas
import android.graphics.RenderNode
import android.view.View
import android.view.ViewTreeObserver

class BackdropSource(
    val source: View,
    private val autoRecord: Boolean = true,
    private val content: ((Canvas) -> Unit)? = null,
) {
    val node = RenderNode("LiquidSpotBackdrop")

    var isRecording = false
        private set

    var baseColor = 0xFF000000.toInt()

    private val clients = LinkedHashSet<View>()
    private val listeners = ArrayList<() -> Unit>()
    private val location = IntArray(2)
    private val onDraw = ViewTreeObserver.OnDrawListener {
        if (source.isShown) record()
    }
    private var observer: ViewTreeObserver? = null

    fun addClient(view: View) {
        if (clients.add(view) && clients.size == 1) {
            if (autoRecord) observer = source.viewTreeObserver.also { it.addOnDrawListener(onDraw) }
        }
    }

    fun removeClient(view: View) {
        if (clients.remove(view) && clients.isEmpty()) {
            observer?.takeIf { it.isAlive }?.removeOnDrawListener(onDraw)
            observer = null
        }
    }

    fun addListener(listener: () -> Unit) {
        if (listener !in listeners) listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
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
        val previous = activeCanvas
        activeCanvas = canvas
        try {
            canvas.drawColor(baseColor)
            content?.invoke(canvas) ?: source.draw(canvas)
        } finally {
            isRecording = false
            activeCanvas = previous
            node.endRecording()
        }
        for (listener in listeners) listener()
    }

    companion object {
        private var activeCanvas: Canvas? = null

        fun isBackdropCanvas(canvas: Any?): Boolean = canvas != null && canvas === activeCanvas
    }
}
