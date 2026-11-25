package app.liquidspot.xposed.features.player

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import app.liquidspot.core.Constants
import app.liquidspot.xposed.a11y.A11yBridge
import app.liquidspot.xposed.a11y.TextMatcher
import app.liquidspot.xposed.util.Logger

class PlayerTargets(private val res: Resources) {
    enum class Repeat { OFF, ALL, ONE }

    data class States(
        val shuffle: Boolean?,
        val smartShuffle: Boolean,
        val repeat: Repeat?,
        val added: Boolean?,
        val connectActive: Boolean?,
    )

    private fun strings(vararg names: String) = A11yBridge.spotifyStrings(res, *names)

    private val shuffle = TextMatcher.formats(
        strings(
            "np_content_desc_shuffle_active", "np_content_desc_shuffle_inactive",
            "np_content_desc_enhance_shuffle_inactive", "np_content_desc_enhance_shuffle_smart_shuffle_active",
        ),
    )
    private val shuffleOn = TextMatcher.formats(strings("np_content_desc_shuffle_active"))
    private val smartShuffleOn = TextMatcher.formats(strings("np_content_desc_enhance_shuffle_smart_shuffle_active"))

    private val repeat = TextMatcher.formats(strings("np_content_desc_repeat"))
    private val repeatAll = strings("np_state_desc_repeat_all")
    private val repeatOne = strings("np_state_desc_repeat_one")
    private val repeatOff = strings("np_state_desc_repeat_off")

    private val add = TextMatcher.formats(
        strings(
            "add_to_button_content_description_add", "add_to_button_content_description_added",
            "add_to_button_content_description_with_details_add", "add_to_button_content_description_with_details_added",
            "add_to_button_content_description_with_entity_add", "add_to_button_content_description_with_entity_added",
            "heart_button_content_description", "heart_active_button_content_description",
        ),
    )
    private val added = TextMatcher.formats(
        strings(
            "add_to_button_content_description_added", "add_to_button_content_description_with_details_added",
            "add_to_button_content_description_with_entity_added", "heart_active_button_content_description",
        ),
    )

    private val queue = TextMatcher.formats(strings("np_content_desc_queue", "npv_screen_queue_button"))
    private val more = TextMatcher.formats(
        strings(
            "show_context_menu_content_description_track", "action_context_menu_content_description",
            "media_context_menu_content_description", "entity_action_context_menu_default_content_description",
        ),
    )
    private val close = TextMatcher.formats(
        strings("np_content_desc_close", "collapse_button_with_title_content_description", "close_button_content_description"),
    )
    private val dj = TextMatcher.formats(
        strings("endless_dj_button_content_description", "endless_dj_button_content_description_with_interactivity"),
    )
    private val connectIdle = TextMatcher.formats(strings("connect_destination_button_normal_accessibility"))

    var shuffleTarget: A11yBridge.Target? = null
        private set
    var repeatTarget: A11yBridge.Target? = null
        private set
    var addTarget: A11yBridge.Target? = null
        private set
    var queueTarget: A11yBridge.Target? = null
        private set
    var moreTarget: A11yBridge.Target? = null
        private set
    var closeTarget: A11yBridge.Target? = null
        private set
    var connectTarget: A11yBridge.Target? = null
        private set
    var djTarget: A11yBridge.Target? = null
        private set

    val foundAll: Boolean
        get() = listOf(shuffleTarget, repeatTarget, addTarget, queueTarget, moreTarget, closeTarget, connectTarget).all { it != null }

    fun refresh(pageRoot: View, exclude: View) {
        val items = A11yBridge.collect(pageRoot, exclude = { it === exclude })
        fun pick(matcher: TextMatcher) = A11yBridge.find(items, matcher)?.clickableTarget()
        shuffleTarget = pick(shuffle)
        repeatTarget = pick(repeat)
        addTarget = pick(add)
        queueTarget = pick(queue)
        moreTarget = pick(more)
        closeTarget = pick(close)
        connectTarget = findConnect(pageRoot, exclude) ?: pick(connectIdle)
        djTarget = pick(dj)
        Logger.d(
            "Player: botões do Spotify — aleatório=${shuffleTarget != null} repetir=${repeatTarget != null} " +
                "+=${addTarget != null} fila=${queueTarget != null} …=${moreTarget != null} fechar=${closeTarget != null} " +
                "dispositivos=${connectTarget != null} (${items.size} elementos)",
        )
    }

    private fun findConnect(pageRoot: View, exclude: View): A11yBridge.Target? {
        val id = res.getIdentifier("connect_entry_point_container", "id", Constants.SPOTIFY_PACKAGE)
        val container = if (id != 0) pageRoot.findViewById<ViewGroup>(id) else null
        container ?: return null
        val items = A11yBridge.collect(container, exclude = { it === exclude })
        return items.firstOrNull { it.clickable }?.target ?: items.firstOrNull()?.clickableTarget()
    }

    fun readStates(): States {
        val shuffleLabel = shuffleTarget?.read()?.label
        val repeatSnap = repeatTarget?.read()
        val addLabel = addTarget?.read()?.label
        val connectLabel = connectTarget?.read()?.label
        return States(
            shuffle = shuffleLabel?.let { shuffleOn.matches(it) || smartShuffleOn.matches(it) },
            smartShuffle = shuffleLabel?.let(smartShuffleOn::matches) == true,
            repeat = repeatSnap?.let { snap ->
                val state = snap.state ?: snap.label
                when {
                    repeatOne.any { state.contains(it, ignoreCase = true) } -> Repeat.ONE
                    repeatAll.any { state.contains(it, ignoreCase = true) } -> Repeat.ALL
                    repeatOff.any { state.contains(it, ignoreCase = true) } -> Repeat.OFF
                    snap.selected -> Repeat.ALL
                    else -> Repeat.OFF
                }
            },
            added = addLabel?.let(added::matches),
            connectActive = connectLabel?.let { it.isNotEmpty() && !connectIdle.matches(it) },
        )
    }
}
