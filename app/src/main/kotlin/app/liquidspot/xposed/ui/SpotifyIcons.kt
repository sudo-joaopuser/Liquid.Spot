package app.liquidspot.xposed.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import app.liquidspot.core.Constants

enum class PlayerIcon(val spotifyName: String?) {
    BACK(null),
    SHARE(null),
    MORE("encore_icon_more_android_24"),
    MORE_HORIZONTAL(null),
    ADD("encore_icon_plus_alt_24"),
    ADDED("encore_icon_check_alt_fill_24"),
    SHUFFLE("encore_icon_shuffle_24"),
    SMART_SHUFFLE("encore_icon_shuffle_smart_24"),
    PLAY("encore_icon_play_24"),
    PAUSE("encore_icon_pause_24"),
    NEXT("encore_icon_skip_forward_24"),
    REWIND(null),
    FORWARD(null),
    REPEAT("encore_icon_repeat_24"),
    REPEAT_ONE("encore_icon_repeat_once_24"),
    LYRICS(null),
    DEVICES("encore_icon_devices_24"),
    HEADPHONES("encore_icon_headphones_24"),
    QUEUE("encore_icon_queue_24"),
    DJ(null),
    ALBUM("encore_icon_album_24"),
    ARTIST("encore_icon_artist_24"),
    TRANSLATE(null),
    AI("encore_icon_enhance_24"),
    ;

    fun load(context: Context): Drawable {
        val res = context.resources
        val id = spotifyName?.let { res.getIdentifier(it, "drawable", Constants.SPOTIFY_PACKAGE) } ?: 0
        val drawable = if (id != 0) runCatching { res.getDrawable(id, context.theme) }.getOrNull() else null
        return (drawable ?: Glyph(this)).mutate()
    }
}

class Glyph(private val icon: PlayerIcon) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        path.reset()
        when (icon) {
            PlayerIcon.BACK -> {
                path.moveTo(15f, 4.5f); path.lineTo(7.5f, 12f); path.lineTo(15f, 19.5f)
                canvas.drawPath(path, stroke)
            }
            PlayerIcon.SHARE -> {
                path.moveTo(12f, 14f); path.lineTo(12f, 3f)
                path.moveTo(8f, 7f); path.lineTo(12f, 3f); path.lineTo(16f, 7f)
                path.moveTo(9f, 10f); path.lineTo(6.5f, 10f); path.lineTo(6.5f, 20.5f); path.lineTo(17.5f, 20.5f); path.lineTo(17.5f, 10f); path.lineTo(15f, 10f)
                canvas.drawPath(path, stroke)
            }
            PlayerIcon.MORE, PlayerIcon.MORE_HORIZONTAL -> for (x in listOf(5f, 12f, 19f)) canvas.drawCircle(x, 12f, 1.8f, fill)
            PlayerIcon.ADD -> {
                canvas.drawCircle(12f, 12f, 9.5f, stroke)
                canvas.drawLine(12f, 7.5f, 12f, 16.5f, stroke)
                canvas.drawLine(7.5f, 12f, 16.5f, 12f, stroke)
            }
            PlayerIcon.ADDED -> {
                canvas.drawCircle(12f, 12f, 10f, fill)
                val dark = Paint(stroke).apply { color = 0xFF000000.toInt() }
                path.moveTo(7.5f, 12.5f); path.lineTo(10.5f, 15.5f); path.lineTo(16.5f, 9f)
                canvas.drawPath(path, dark)
            }
            PlayerIcon.SHUFFLE, PlayerIcon.SMART_SHUFFLE -> {
                path.moveTo(3f, 7f); path.lineTo(7f, 7f); path.cubicTo(12f, 7f, 12f, 17f, 17f, 17f); path.lineTo(21f, 17f)
                path.moveTo(3f, 17f); path.lineTo(7f, 17f); path.cubicTo(12f, 17f, 12f, 7f, 17f, 7f); path.lineTo(21f, 7f)
                path.moveTo(18f, 4f); path.lineTo(21f, 7f); path.lineTo(18f, 10f)
                path.moveTo(18f, 14f); path.lineTo(21f, 17f); path.lineTo(18f, 20f)
                canvas.drawPath(path, stroke)
            }
            PlayerIcon.NEXT -> {
                path.moveTo(4f, 5f); path.lineTo(13f, 12f); path.lineTo(4f, 19f); path.close()
                path.moveTo(12f, 5f); path.lineTo(21f, 12f); path.lineTo(12f, 19f); path.close()
                canvas.drawPath(path, fill)
            }
            PlayerIcon.REWIND, PlayerIcon.FORWARD -> {
                val rewind = icon == PlayerIcon.REWIND
                for (start in floatArrayOf(1.5f, 11.5f)) {
                    path.reset()
                    if (rewind) {
                        path.moveTo(start + 10f, 5f); path.lineTo(start, 12f); path.lineTo(start + 10f, 19f)
                    } else {
                        path.moveTo(start, 5f); path.lineTo(start + 10f, 12f); path.lineTo(start, 19f)
                    }
                    path.close()
                    canvas.drawPath(path, fill)
                }
            }
            PlayerIcon.PLAY -> {
                path.moveTo(7f, 4f); path.lineTo(20f, 12f); path.lineTo(7f, 20f); path.close()
                canvas.drawPath(path, fill)
            }
            PlayerIcon.PAUSE -> {
                canvas.drawRoundRect(6f, 4f, 10f, 20f, 1f, 1f, fill)
                canvas.drawRoundRect(14f, 4f, 18f, 20f, 1f, 1f, fill)
            }
            PlayerIcon.REPEAT, PlayerIcon.REPEAT_ONE -> {
                path.moveTo(4f, 11f); path.lineTo(4f, 9f); path.quadTo(4f, 7f, 6f, 7f); path.lineTo(19f, 7f)
                path.moveTo(16f, 4f); path.lineTo(19f, 7f); path.lineTo(16f, 10f)
                path.moveTo(20f, 13f); path.lineTo(20f, 15f); path.quadTo(20f, 17f, 18f, 17f); path.lineTo(5f, 17f)
                path.moveTo(8f, 14f); path.lineTo(5f, 17f); path.lineTo(8f, 20f)
                canvas.drawPath(path, stroke)
                if (icon == PlayerIcon.REPEAT_ONE) canvas.drawLine(12f, 10f, 12f, 14f, stroke)
            }
            PlayerIcon.LYRICS -> {
                path.addRoundRect(3f, 4f, 21f, 17f, 3f, 3f, Path.Direction.CW)
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(8f, 17f); path.lineTo(7f, 21f); path.lineTo(12f, 17f)
                canvas.drawPath(path, stroke)
                canvas.drawCircle(9.5f, 10.5f, 1.4f, fill)
                canvas.drawCircle(14.5f, 10.5f, 1.4f, fill)
            }
            PlayerIcon.DEVICES -> {
                canvas.drawRoundRect(3f, 5f, 15f, 19f, 2f, 2f, stroke)
                canvas.drawRoundRect(17f, 9f, 21f, 19f, 1.5f, 1.5f, stroke)
            }
            PlayerIcon.HEADPHONES -> {
                path.moveTo(4f, 15f); path.lineTo(4f, 12f); path.cubicTo(4f, 7f, 8f, 4f, 12f, 4f); path.cubicTo(16f, 4f, 20f, 7f, 20f, 12f); path.lineTo(20f, 15f)
                canvas.drawPath(path, stroke)
                canvas.drawRoundRect(3f, 14f, 8f, 20f, 2f, 2f, stroke)
                canvas.drawRoundRect(16f, 14f, 21f, 20f, 2f, 2f, stroke)
            }
            PlayerIcon.DJ -> {
                val text = Paint(fill).apply {
                    textSize = 11f
                    textAlign = Paint.Align.CENTER
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                }
                canvas.drawRoundRect(2f, 5f, 22f, 19f, 4f, 4f, stroke)
                canvas.drawText("DJ", 12f, 16f, text)
            }
            PlayerIcon.ALBUM -> {
                canvas.drawCircle(12f, 12f, 9.5f, stroke)
                canvas.drawCircle(12f, 12f, 2.5f, fill)
            }
            PlayerIcon.ARTIST -> {
                canvas.drawCircle(12f, 8f, 4f, stroke)
                path.moveTo(4f, 21f); path.cubicTo(4f, 15f, 20f, 15f, 20f, 21f)
                canvas.drawPath(path, stroke)
            }
            PlayerIcon.TRANSLATE -> {
                path.moveTo(3f, 6f); path.lineTo(13f, 6f); path.moveTo(8f, 4f); path.lineTo(8f, 6f)
                path.moveTo(11f, 6f); path.cubicTo(10f, 10f, 7f, 13f, 4f, 14f)
                path.moveTo(6f, 9f); path.cubicTo(7f, 11f, 9f, 13f, 12f, 14f)
                path.moveTo(12f, 21f); path.lineTo(16.5f, 10f); path.lineTo(21f, 21f); path.moveTo(13.6f, 17f); path.lineTo(19.4f, 17f)
                canvas.drawPath(path, stroke)
            }
            PlayerIcon.AI -> {
                path.moveTo(12f, 3f); path.quadTo(13f, 11f, 21f, 12f); path.quadTo(13f, 13f, 12f, 21f); path.quadTo(11f, 13f, 3f, 12f); path.quadTo(11f, 11f, 12f, 3f); path.close()
                canvas.drawPath(path, fill)
            }
            PlayerIcon.QUEUE -> {
                canvas.drawLine(4f, 6f, 20f, 6f, stroke)
                canvas.drawLine(4f, 12f, 20f, 12f, stroke)
                canvas.drawLine(4f, 18f, 14f, 18f, stroke)
            }
        }
        canvas.restore()
    }

    override fun setTint(tintColor: Int) {
        stroke.color = tintColor
        fill.color = tintColor
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 24
    override fun getIntrinsicHeight() = 24
}
