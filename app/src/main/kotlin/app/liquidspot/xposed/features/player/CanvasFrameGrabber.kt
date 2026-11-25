package app.liquidspot.xposed.features.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import app.liquidspot.xposed.util.Logger

class CanvasFrameGrabber(private val onFrame: (Bitmap) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var next = 0
    private var video: View? = null
    private var lastGrab = 0L
    private var pending = false
    private var failures = 0

    fun attach(surface: View?) {
        if (surface === video) return
        video = surface
        failures = 0
        pending = false
    }

    fun tick(now: Long) {
        val surface = video ?: return
        if (pending || failures >= MAX_FAILURES || now - lastGrab < INTERVAL_MS) return
        lastGrab = now
        val renderer = findRenderer(surface) ?: return
        if (renderer.width <= 0 || renderer.height <= 0) return
        val bitmap = buffer((renderer.width / SCALE).coerceAtLeast(1), (renderer.height / SCALE).coerceAtLeast(1))
        when (renderer) {
            is TextureView -> grabTexture(renderer, bitmap)
            is SurfaceView -> grabSurface(renderer, bitmap)
        }
    }

    private fun grabTexture(view: TextureView, bitmap: Bitmap) {
        if (!view.isAvailable) return
        val ok = runCatching { view.getBitmap(bitmap) }.isSuccess
        if (ok) deliver(bitmap) else fail("TextureView")
    }

    private fun grabSurface(view: SurfaceView, bitmap: Bitmap) {
        if (!view.holder.surface.isValid) return
        pending = true
        runCatching {
            PixelCopy.request(view, bitmap, { result ->
                pending = false
                if (result == PixelCopy.SUCCESS) deliver(bitmap) else fail("SurfaceView ($result)")
            }, handler)
        }.onFailure {
            pending = false
            fail("SurfaceView")
        }
    }

    private fun deliver(bitmap: Bitmap) {
        failures = 0
        next = 1 - next
        onFrame(bitmap)
    }

    private fun fail(kind: String) {
        failures++
        if (failures == MAX_FAILURES) Logger.w("Canvas: não foi possível capturar o vídeo ($kind); usando o blur da capa")
    }

    private fun buffer(w: Int, h: Int): Bitmap {
        val existing = buffers[next]
        if (existing != null && existing.width == w && existing.height == h) return existing
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { buffers[next] = it }
    }

    private fun findRenderer(root: View): View? {
        if (root.visibility != View.VISIBLE) return null
        if (root is TextureView || root is SurfaceView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findRenderer(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private companion object {
        const val INTERVAL_MS = 90L
        const val SCALE = 8
        const val MAX_FAILURES = 5
    }
}
