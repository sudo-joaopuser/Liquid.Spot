package app.liquidspot.xposed.features.navbar

import android.app.Activity
import android.view.View
import app.liquidspot.core.Constants

class SpotifyBars(activity: Activity) {
    private val res = activity.resources

    private fun id(name: String): Int = res.getIdentifier(name, "id", Constants.SPOTIFY_PACKAGE)

    val coordinator = id("coordinator_layout")

    val navigationBar = id("navigation_bar")

    val navigationInsets = id("navigation_bar_window_insets_space")

    val nowPlayingBar = id("now_playing_bar_layout")

    val bottomGradients = listOf("bottom_gradient", "bottom_gradient_window_insets", "bottom_gradient_black_fade")
        .map(::id)
        .filter { it != 0 }

    val isComplete: Boolean
        get() = coordinator != 0 && navigationBar != 0 && navigationInsets != 0

    fun <T : View> find(root: View, id: Int): T? = if (id == 0) null else root.findViewById(id)
}
