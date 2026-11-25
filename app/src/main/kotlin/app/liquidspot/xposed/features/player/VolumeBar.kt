package app.liquidspot.xposed.features.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioManager
import android.media.session.MediaController
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import app.liquidspot.xposed.media.NowPlayingStore
import app.liquidspot.xposed.ui.glass.Spring
import kotlin.math.roundToInt

class VolumeBar(context: Context) : View(context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    private var fraction = 0f
    private var dragging = false
    private var lastStep = -1

    private var thickness = 0f
    private val thicknessSpring = Spring(dampingRatio = 0.8f, stiffness = 600f) {
        thickness = it
        invalidate()
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt() }
    private val path = Path()

    private val poll = object : Runnable {
        override fun run() {
            if (!dragging) refresh()
            postDelayed(this, POLL_MS)
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        thicknessSpring.snapTo(dp(NORMAL_DP))
        iconPaint.strokeWidth = dp(1.6f)
        contentDescription = Texts.volume
    }

    fun preferredHeight(): Int = dp(HEIGHT_DP).roundToInt()

    private fun remote(): MediaController.PlaybackInfo? = runCatching {
        NowPlayingStore.controller?.playbackInfo?.takeIf {
            it.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE && it.volumeControl != 0 && it.maxVolume > 0
        }
    }.getOrNull()

    private fun read(): Float {
        remote()?.let { return it.currentVolume.toFloat() / it.maxVolume }
        val manager = audio ?: return 0f
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun write(value: Float) {
        val info = remote()
        if (info != null) {
            val step = (value * info.maxVolume).roundToInt()
            if (step != lastStep) {
                lastStep = step
                runCatching { NowPlayingStore.controller?.setVolumeTo(step, 0) }
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            return
        }
        val manager = audio ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val step = (value * max).roundToInt()
        if (step != lastStep) {
            lastStep = step
            runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0) }
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun refresh() {
        val value = read().coerceIn(0f, 1f)
        if (value != fraction) {
            fraction = value
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
        post(poll)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(poll)
        thicknessSpring.stop()
        super.onDetachedFromWindow()
    }

    private fun trackLeft() = dp(ICON_DP + ICON_GAP_DP)
    private fun trackRight() = width - dp(ICON_DP + ICON_GAP_DP)

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val left = trackLeft()
        val right = trackRight()
        val r = thickness / 2f
        canvas.drawRoundRect(left, cy - r, right, cy + r, r, r, trackPaint)
        val x = left + (right - left) * fraction
        if (x > left) canvas.drawRoundRect(left, cy - r, x, cy + r, r, r, fillPaint)
        drawSpeaker(canvas, 0f, cy, waves = 0)
        drawSpeaker(canvas, width - dp(ICON_DP), cy, waves = 2)
    }

    private fun drawSpeaker(canvas: Canvas, x: Float, cy: Float, waves: Int) {
        val s = dp(ICON_DP) / 24f
        canvas.save()
        canvas.translate(x, cy - 12f * s)
        canvas.scale(s, s)
        iconPaint.strokeWidth = 1.8f
        path.reset()
        path.moveTo(3f, 9f); path.lineTo(7f, 9f); path.lineTo(12f, 5f); path.lineTo(12f, 19f); path.lineTo(7f, 15f); path.lineTo(3f, 15f); path.close()
        canvas.drawPath(path, iconFill)
        if (waves >= 1) {
            path.reset()
            path.moveTo(15.5f, 9f); path.quadTo(17.5f, 12f, 15.5f, 15f)
            canvas.drawPath(path, iconPaint)
        }
        if (waves >= 2) {
            path.reset()
            path.moveTo(18.5f, 6f); path.quadTo(22.5f, 12f, 18.5f, 18f)
            canvas.drawPath(path, iconPaint)
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val left = trackLeft()
        val right = trackRight()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                lastStep = -1
                thicknessSpring.animateTo(dp(ACTIVE_DP))
            }
            MotionEvent.ACTION_MOVE -> Unit
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                thicknessSpring.animateTo(dp(NORMAL_DP))
                postDelayed({ refresh() }, 250)
                return true
            }
        }
        fraction = ((event.x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
        write(fraction)
        invalidate()
        return true
    }

    private companion object {
        const val HEIGHT_DP = 32f
        const val NORMAL_DP = 6f
        const val ACTIVE_DP = 12f
        const val ICON_DP = 20f
        const val ICON_GAP_DP = 12f
        const val POLL_MS = 500L
    }
}
