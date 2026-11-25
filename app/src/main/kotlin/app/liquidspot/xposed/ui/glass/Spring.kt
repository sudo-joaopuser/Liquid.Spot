package app.liquidspot.xposed.ui.glass

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class Spring(
    private val dampingRatio: Float = 0.72f,
    private val stiffness: Float = 320f,
    private val onUpdate: (Float) -> Unit,
) {
    var value = 0f
        private set
    var velocity = 0f
        private set
    var target = 0f
        private set

    var reduceMotion = false

    private var running = false
    private var lastFrameNanos = 0L
    private val choreographer = Choreographer.getInstance()

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNanos == 0L) 1f / 120f else min((frameTimeNanos - lastFrameNanos) / 1e9f, 1f / 30f)
            lastFrameNanos = frameTimeNanos
            step(dt)
            onUpdate(value)
            if (running) choreographer.postFrameCallback(this)
        }
    }

    fun snapTo(newValue: Float) {
        stop()
        value = newValue
        target = newValue
        velocity = 0f
        onUpdate(value)
    }

    fun animateTo(newTarget: Float) {
        if (reduceMotion) {
            snapTo(newTarget)
            return
        }
        target = newTarget
        if (!running) {
            running = true
            lastFrameNanos = 0L
            choreographer.postFrameCallback(frame)
        }
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(frame)
    }

    private fun step(dt: Float) {
        val omega = sqrt(stiffness)
        val damping = 2f * dampingRatio * omega
        var remaining = dt
        while (remaining > 0f) {
            val h = min(remaining, 1f / 240f)
            val accel = -stiffness * (value - target) - damping * velocity
            velocity += accel * h
            value += velocity * h
            remaining -= h
        }
        if (abs(value - target) < 0.01f && abs(velocity) < 0.5f) {
            value = target
            velocity = 0f
            running = false
        }
    }
}
