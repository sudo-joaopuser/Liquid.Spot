package app.liquidspot.xposed.features.pages

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import java.lang.ref.WeakReference
import kotlin.math.max

class VideoFrames(private val onFrame: () -> Unit) {
    private class Slot(view: SurfaceView) {
        val ref = WeakReference(view)
        var front: Bitmap? = null
        var back: Bitmap? = null
        var pending = false
    }

    private val slots = ArrayList<Slot>()
    private val handler = Handler(Looper.getMainLooper())
    private val location = IntArray(2)
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var running = false
    private val tick = Runnable { capture() }

    var limit = 0

    fun update(found: List<SurfaceView>) {
        slots.removeAll { slot -> slot.ref.get().let { view -> view == null || found.none { it === view } } }
        for (view in found) if (slots.none { it.ref.get() === view }) slots += Slot(view)
    }

    fun inside(group: android.view.ViewGroup): Boolean = slots.any { slot ->
        var parent = slot.ref.get()?.parent
        while (parent is android.view.View) {
            if (parent === group) return@any true
            parent = parent.parent
        }
        false
    }

    fun start() {
        if (running || slots.isEmpty()) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        slots.clear()
    }

    private fun covered(view: SurfaceView): Boolean {
        if (!view.isShown || view.width == 0 || view.height == 0 || !view.holder.surface.isValid) return false
        view.getLocationInWindow(location)
        return location[1] < limit && location[1] + view.height > 0
    }

    private fun capture() {
        if (!running) return
        var watching = false
        for (slot in slots) {
            val view = slot.ref.get()
            if (view == null || !covered(view)) {
                slot.front = null
                continue
            }
            watching = true
            if (slot.pending) continue
            val w = max(1, view.width / SCALE)
            val h = max(1, view.height / SCALE)
            val target = slot.back?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { slot.back = it }
            slot.pending = true
            runCatching {
                PixelCopy.request(view, target, { result ->
                    slot.pending = false
                    if (result == PixelCopy.SUCCESS) {
                        slot.back = slot.front
                        slot.front = target
                        onFrame()
                    }
                }, handler)
            }.onFailure { slot.pending = false }
        }
        if (watching) handler.postDelayed(tick, INTERVAL_MS) else running = false
    }

    fun draw(canvas: Canvas, originX: Int, originY: Int) {
        for (slot in slots) {
            val frame = slot.front ?: continue
            val view = slot.ref.get() ?: continue
            if (!view.isShown) continue
            view.getLocationInWindow(location)
            val x = (location[0] - originX).toFloat()
            val y = (location[1] - originY).toFloat()
            dst.set(x, y, x + view.width, y + view.height)
            canvas.drawBitmap(frame, null, dst, paint)
        }
    }

    private companion object {
        const val SCALE = 4
        const val INTERVAL_MS = 33L
    }
}
