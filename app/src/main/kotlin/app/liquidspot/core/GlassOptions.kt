package app.liquidspot.core

enum class GlassStyle {
    LIQUID,
    FROSTED,
    MATTE,
    SOLID;

    companion object {
        val DEFAULT = LIQUID
        fun fromPref(value: String?): GlassStyle = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

enum class NavBarLayout {
    THREE_TABS,
    SEARCH_BUBBLE,
    CREATE_BUBBLE;

    companion object {
        val DEFAULT = THREE_TABS
        fun fromPref(value: String?): NavBarLayout = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
