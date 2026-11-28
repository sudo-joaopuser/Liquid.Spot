package app.liquidspot.xposed.features.navbar

import android.content.res.Resources

class ComposeTabs(res: Resources) {
    private val ids: Map<NavTab, List<Int>> = NavTab.entries.associateWith { it.titleIds(res) }

    val tabs: Set<NavTab> get() = ids.filterValues { titles -> titles.any(NavTabsHook::has) }.keys

    fun refresh(): Boolean = tabs.containsAll(listOf(NavTab.HOME, NavTab.SEARCH, NavTab.LIBRARY))

    fun click(tab: NavTab): Boolean = ids[tab]?.firstOrNull(NavTabsHook::has)?.let(NavTabsHook::click) == true

    fun selected(): NavTab? {
        val title = NavTabsHook.selectedTitle
        if (title == 0) return null
        return ids.entries.firstOrNull { title in it.value }?.key
    }
}
