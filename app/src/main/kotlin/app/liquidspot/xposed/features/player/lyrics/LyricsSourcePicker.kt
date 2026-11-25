package app.liquidspot.xposed.features.player.lyrics

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.liquidspot.xposed.features.player.GlassPanel
import app.liquidspot.xposed.features.player.Texts
import app.liquidspot.xposed.theme.SpotifyTheme
import app.liquidspot.xposed.ui.glass.BackdropSource
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class LyricsSourcePicker(context: Context, backdrop: BackdropSource) : FrameLayout(context) {
    var onSelect: (Lyrics) -> Unit = {}

    private val panel = GlassPanel(context, backdrop)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val scroller = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    init {
        visibility = View.GONE
        setBackgroundColor(0x66000000)
        outlineProvider = null
        translationZ = dp(PICKER_Z_DP)
        addView(
            scroller,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                val m = dp(16f).roundToInt()
                setMargins(m, dp(TOP_MARGIN_DP).roundToInt(), m, dp(140f).roundToInt())
            },
        )
    }

    val isShowing: Boolean get() = visibility == View.VISIBLE

    fun show(options: List<Lyrics>, selected: Lyrics?, complete: Boolean) {
        fill(options, selected, complete)
        scroller.scrollTo(0, 0)
        visibility = View.VISIBLE
        requestLayout()
        alpha = 0f
        animate().alpha(1f).setDuration(180).start()
    }

    fun update(options: List<Lyrics>, selected: Lyrics?, complete: Boolean) {
        if (!isShowing) return
        fill(options, selected, complete)
        requestLayout()
    }

    private fun fill(options: List<Lyrics>, selected: Lyrics?, complete: Boolean) {
        panel.removeAllViews()
        panel.addView(text(Texts.lyricsPickerTitle, 17f, bold = true, alpha = 1f).apply { setPadding(0, 0, 0, dp(8f).roundToInt()) })
        options.forEach { lyrics ->
            val isSelected = lyrics.source == selected?.source
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val v = dp(10f).roundToInt()
                setPadding(0, v, 0, v)
                isClickable = true
                setOnClickListener {
                    hide()
                    onSelect(lyrics)
                }
            }
            row.addView(
                text((if (isSelected) "✓  " else "") + lyrics.source, 16f, bold = true, alpha = 1f).apply {
                    if (isSelected) setTextColor(SpotifyTheme.accent)
                },
            )
            val kind = if (lyrics.synced) Texts.lyricsSynced else Texts.lyricsPlain
            row.addView(text("$kind · ${Texts.lyricsLineCount(lyrics.lines.size)}", 13f, bold = false, alpha = 0.6f))
            panel.addView(row)
        }
        if (!complete) panel.addView(text(Texts.lyricsSearchingMore, 13f, bold = false, alpha = 0.6f).apply { setPadding(0, dp(8f).roundToInt(), 0, 0) })
    }

    fun hide() {
        if (visibility != View.VISIBLE) return
        animate().alpha(0f).setDuration(150).withEndAction { visibility = View.GONE }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) hide()
        return true
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean, alpha: Float) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(0xFFFFFFFF.toInt())
        this.alpha = alpha
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private companion object {
        const val PICKER_Z_DP = 40f
        const val TOP_MARGIN_DP = 96f
    }
}
