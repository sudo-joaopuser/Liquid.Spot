package app.liquidspot.xposed.features.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.liquidspot.xposed.theme.SpotifyTheme
import app.liquidspot.xposed.ui.PlayerIcon
import app.liquidspot.xposed.ui.glass.BackdropSource
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class PlayerMenu(context: Context, backdrop: BackdropSource) : FrameLayout(context) {
    class Entry(
        val label: String,
        val icon: PlayerIcon,
        val active: Boolean = false,
        val enabled: Boolean = true,
        val action: () -> Unit,
    )

    private val panel = GlassPanel(context, backdrop)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        visibility = View.GONE
        setBackgroundColor(0x66000000)
        outlineProvider = null
        translationZ = dp(MENU_Z_DP)
        addView(
            panel,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                val m = dp(16f).roundToInt()
                setMargins(m, m, m, dp(BOTTOM_MARGIN_DP).roundToInt())
            },
        )
    }

    val isShowing: Boolean get() = visibility == View.VISIBLE

    fun show(entries: List<Entry>) {
        panel.removeAllViews()
        entries.forEach { panel.addView(row(it)) }
        visibility = View.VISIBLE
        alpha = 0f
        panel.translationY = dp(24f)
        animate().alpha(1f).setDuration(180).start()
        panel.animate().translationY(0f).setDuration(220).start()
    }

    fun hide() {
        if (visibility != View.VISIBLE) return
        animate().alpha(0f).setDuration(150).withEndAction { visibility = View.GONE }.start()
    }

    private fun row(entry: Entry): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val v = dp(12f).roundToInt()
        setPadding(0, v, 0, v)
        isClickable = entry.enabled
        alpha = if (entry.enabled) 1f else 0.4f
        setOnClickListener {
            hide()
            entry.action()
        }
        val color = if (entry.active) SpotifyTheme.accent else 0xFFFFFFFF.toInt()
        addView(
            ImageView(context).apply {
                setImageDrawable(entry.icon.load(context).apply { setTint(color) })
            },
            LinearLayout.LayoutParams(dp(24f).roundToInt(), dp(24f).roundToInt()).apply { rightMargin = dp(16f).roundToInt() },
        )
        addView(
            TextView(context).apply {
                text = entry.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(color)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) hide()
        return true
    }

    private companion object {
        const val MENU_Z_DP = 40f
        const val BOTTOM_MARGIN_DP = 32f
    }
}
