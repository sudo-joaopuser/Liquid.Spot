package app.liquidspot.xposed.features.navbar

import android.view.View
import app.liquidspot.xposed.a11y.A11yBridge
import app.liquidspot.xposed.util.Logger

class ComposeTabs(private val navBar: View) {
    private var targets: Map<NavTab, A11yBridge.Target> = emptyMap()
    private var labels: Map<NavTab, A11yBridge.Target> = emptyMap()

    val tabs: Set<NavTab> get() = targets.keys

    fun refresh(titles: Map<NavTab, List<String>>): Boolean {
        val items = A11yBridge.collect(navBar)
        val found = LinkedHashMap<NavTab, A11yBridge.Target>()
        val foundLabels = LinkedHashMap<NavTab, A11yBridge.Target>()
        for (item in items) {
            if (item.label.isEmpty()) continue
            for ((tab, names) in titles) {
                if (tab in found) continue
                if (names.any { it.isNotEmpty() && item.label.contains(it, ignoreCase = true) }) {
                    found[tab] = item.clickableTarget()
                    foundLabels[tab] = item.target
                    break
                }
            }
        }
        targets = found
        labels = foundLabels
        Logger.d("ComposeTabs: ${items.size} elementos, abas encontradas ${found.keys}")
        return NavTab.HOME in found && NavTab.SEARCH in found && NavTab.LIBRARY in found
    }

    fun click(tab: NavTab): Boolean = targets[tab]?.click() == true

    fun selected(): NavTab? {
        var missing = 0
        for ((tab, target) in targets) {
            val snapshot = target.read()
            if (snapshot == null) {
                missing++
                continue
            }
            if (snapshot.selected || labels[tab]?.takeIf { it !== target }?.read()?.selected == true) return tab
        }
        check(missing < targets.size) { "elementos das abas sumiram" }
        return null
    }
}
