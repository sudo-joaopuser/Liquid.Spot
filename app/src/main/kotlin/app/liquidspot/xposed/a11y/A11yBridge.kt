package app.liquidspot.xposed.a11y

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.TextView
import app.liquidspot.core.Constants
import app.liquidspot.xposed.util.Logger
import java.lang.reflect.Method
import org.lsposed.hiddenapibypass.HiddenApiBypass

object A11yBridge {
    class Item(
        val target: Target,
        val label: String,
        val state: String?,
        val selected: Boolean,
        val clickable: Boolean,
        val parent: Item?,
    ) {
        fun clickableTarget(): Target {
            var item: Item? = this
            while (item != null) {
                if (item.clickable) return item.target
                item = item.parent
            }
            return target
        }

        override fun toString() = "'$label'" + (state?.let { " [$it]" } ?: "") + (if (selected) " (selecionado)" else "")
    }

    data class Snapshot(
        val label: String,
        val state: String?,
        val selected: Boolean,
        val boundsOnScreen: Rect = Rect(),
    )

    sealed class Target {
        abstract fun click(): Boolean
        abstract fun longClick(): Boolean
        abstract fun read(): Snapshot?
        protected abstract val touchHost: View

        fun tap(): Boolean {
            val host = touchHost
            if (!host.isAttachedToWindow) return false
            val bounds = read()?.boundsOnScreen?.takeUnless { it.isEmpty } ?: return false
            val origin = IntArray(2).also(host::getLocationOnScreen)
            val x = bounds.exactCenterX() - origin[0]
            val y = bounds.exactCenterY() - origin[1]
            val downTime = SystemClock.uptimeMillis()
            val handled = dispatch(host, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
            host.postDelayed({ dispatch(host, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y) }, TAP_MS)
            return handled
        }

        private fun dispatch(host: View, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): Boolean {
            val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            return try {
                host.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    class ViewTarget(val view: View) : Target() {
        override val touchHost: View get() = view

        override fun click(): Boolean {
            if (!view.isAttachedToWindow) return false
            return view.performClick() || view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
        }

        override fun longClick(): Boolean =
            view.isAttachedToWindow && (view.performLongClick() || view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))

        override fun read(): Snapshot? {
            if (!view.isAttachedToWindow) return null
            val loc = IntArray(2).also(view::getLocationOnScreen)
            val rect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
            return Snapshot(labelOf(view), view.stateDescription?.toString(), view.isSelected, rect)
        }
    }

    class VirtualTarget(val host: View, val provider: AccessibilityNodeProvider, val id: Int) : Target() {
        override val touchHost: View get() = host

        override fun click(): Boolean =
            host.isAttachedToWindow && provider.performAction(id, AccessibilityNodeInfo.ACTION_CLICK, null)

        override fun longClick(): Boolean =
            host.isAttachedToWindow && provider.performAction(id, AccessibilityNodeInfo.ACTION_LONG_CLICK, null)

        override fun read(): Snapshot? {
            if (!host.isAttachedToWindow) return null
            val info = provider.createAccessibilityNodeInfo(id) ?: return null
            val rect = Rect().also(info::getBoundsInScreen)
            return Snapshot(labelOf(info), info.stateDescription?.toString(), info.isSelected, rect)
        }
    }

    fun collect(root: View, maxItems: Int = 600, exclude: (View) -> Boolean = { false }): List<Item> {
        val out = ArrayList<Item>()
        val queue = ArrayDeque<Pair<View, Item?>>()
        queue += root to null
        while (queue.isNotEmpty() && out.size < maxItems) {
            val (view, parentItem) = queue.removeFirst()
            if (exclude(view) || view.visibility != View.VISIBLE) continue

            val provider = view.accessibilityNodeProvider
            val clickable = view.isClickable
            val label = labelOf(view)
            val item = if (label.isNotEmpty() || clickable) {
                Item(ViewTarget(view), label, view.stateDescription?.toString(), view.isSelected, clickable, parentItem).also { out += it }
            } else {
                parentItem
            }
            if (provider != null) collectVirtual(view, provider, item, out, maxItems)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) queue += view.getChildAt(i) to item
            }
        }
        return out
    }

    private fun collectVirtual(host: View, provider: AccessibilityNodeProvider, hostItem: Item?, out: MutableList<Item>, maxItems: Int) {
        if (GET_CHILD_ID == null) {
            for (id in 0 until PROBE_LIMIT) {
                if (out.size >= maxItems) return
                val info = provider.createAccessibilityNodeInfo(id) ?: continue
                out += itemOf(host, provider, id, info, hostItem)
            }
            return
        }
        val queue = ArrayDeque<Pair<Int, Item?>>()
        queue += View.NO_ID to hostItem
        val visited = HashSet<Int>()
        while (queue.isNotEmpty() && out.size < maxItems) {
            val (id, parent) = queue.removeFirst()
            if (!visited.add(id)) continue
            val info = provider.createAccessibilityNodeInfo(id) ?: continue
            val item = if (id == View.NO_ID) parent else itemOf(host, provider, id, info, parent).also { out += it }
            for (i in 0 until info.childCount) {
                val child = childVirtualId(info, i) ?: continue
                queue += child to item
            }
        }
    }

    private fun itemOf(host: View, provider: AccessibilityNodeProvider, id: Int, info: AccessibilityNodeInfo, parent: Item?): Item {
        val clickable = info.isClickable || info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        return Item(VirtualTarget(host, provider, id), labelOf(info), info.stateDescription?.toString(), info.isSelected, clickable, parent)
    }

    fun labelOf(view: View): String {
        val parts = ArrayList<CharSequence>(2)
        view.contentDescription?.let { parts += it }
        if (view is TextView && !view.text.isNullOrEmpty()) parts += view.text
        return parts.joinToString(" ").trim()
    }

    fun labelOf(info: AccessibilityNodeInfo): String =
        listOfNotNull(info.text, info.contentDescription).joinToString(" ").trim()

    fun find(items: List<Item>, matcher: TextMatcher): Item? =
        if (matcher.isEmpty) null else items.firstOrNull { matcher.matches(it.label) }

    fun spotifyStrings(res: Resources, vararg names: String): List<String> = names.mapNotNull { name ->
        val id = res.getIdentifier(name, "string", Constants.SPOTIFY_PACKAGE)
        if (id != 0) runCatching { res.getString(id) }.getOrNull() else null
    }

    private const val PROBE_LIMIT = 3000
    private const val TAP_MS = 60L

    @SuppressLint("SoonBlockedPrivateApi", "DiscouragedPrivateApi")
    private val GET_CHILD_ID: Method? = runCatching {
        AccessibilityNodeInfo::class.java.getDeclaredMethod("getChildId", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }.recoverCatching {
        HiddenApiBypass.getDeclaredMethod(AccessibilityNodeInfo::class.java, "getChildId", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }.onSuccess { Logger.d("A11yBridge: getChildId disponível") }
        .onFailure { Logger.e("A11yBridge: getChildId indisponível; elementos do Compose podem não ser encontrados", it) }
        .getOrNull()

    private fun childVirtualId(info: AccessibilityNodeInfo, index: Int): Int? =
        (runCatching { GET_CHILD_ID?.invoke(info, index) }.getOrNull() as? Long)?.let { (it shr 32).toInt() }
}
