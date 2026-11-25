package app.liquidspot.xposed.features.debug

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import app.liquidspot.xposed.a11y.A11yBridge
import app.liquidspot.xposed.util.Logger

object UiDumper {
    @Volatile
    var enabled = false

    private val dumped = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    fun dumpOnce(root: View, title: String) {
        if (!enabled || !dumped.add(root)) return
        dump(root, title)
    }

    fun dump(root: View, title: String) {
        Logger.i("===== Árvore: $title =====")
        dumpView(root, 0, root.resources)
        Logger.i("----- Elementos de acessibilidade: $title -----")
        A11yBridge.collect(root, maxItems = 400).forEach { item ->
            val kind = if (item.target is A11yBridge.VirtualTarget) "compose" else "view"
            Logger.i("  [$kind]${if (item.clickable) "[clicável]" else ""} $item")
        }
        Logger.i("===== Fim: $title =====")
    }

    private fun dumpView(view: View, depth: Int, res: Resources) {
        if (depth > 40) return
        val id = if (view.id != View.NO_ID) runCatching { res.getResourceEntryName(view.id) }.getOrDefault("0x" + view.id.toString(16)) else "-"
        val desc = A11yBridge.labelOf(view).take(60)
        val vis = when (view.visibility) {
            View.VISIBLE -> "V"
            View.INVISIBLE -> "I"
            else -> "G"
        }
        Logger.i(
            "  ".repeat(depth) + "${view.javaClass.name} #$id ${view.width}x${view.height} $vis a=${"%.2f".format(view.alpha)}" +
                (if (view.accessibilityNodeProvider != null) " [compose]" else "") +
                (if (desc.isNotEmpty()) " '$desc'" else ""),
        )
        if (view is ViewGroup) for (i in 0 until view.childCount) dumpView(view.getChildAt(i), depth + 1, res)
    }
}
