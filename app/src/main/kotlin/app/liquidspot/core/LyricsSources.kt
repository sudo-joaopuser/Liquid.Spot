package app.liquidspot.core

import androidx.annotation.StringRes
import app.liquidspot.R

enum class LyricsSource(
    val label: String,
    @param:StringRes val detail: Int,
    val requiresKey: Boolean = false,
    val defaultEnabled: Boolean = true,
) {
    SPOTIFY("Spotify", R.string.lyrics_source_spotify),
    LRC_RED("lrc.red", R.string.lyrics_source_lrc_red),
    BINI_LYRICS("BiniLyrics", R.string.lyrics_source_bini),
    BETTER_LYRICS("BetterLyrics", R.string.lyrics_source_better),
    BETTER_LYRICS_PORTATO("BetterLyrics Portato", R.string.lyrics_source_better_portato),
    PAXSENIX("PaxSenix", R.string.lyrics_source_paxsenix, requiresKey = true),
    PAXSENIX_SPOTIFY("PaxSenix: Spotify", R.string.lyrics_source_paxsenix_spotify, requiresKey = true),
    PAXSENIX_MUSIXMATCH("PaxSenix: Musixmatch", R.string.lyrics_source_paxsenix_musixmatch, requiresKey = true),
    LYRICS_PLUS("LyricsPlus", R.string.lyrics_source_lyrics_plus),
    UNISON("Unison", R.string.lyrics_source_unison),
    KUGOU("KuGou", R.string.lyrics_source_kugou),
    LRCLIB("LRCLIB", R.string.lyrics_source_lrclib),
    MUSIXMATCH("Musixmatch", R.string.lyrics_source_musixmatch, defaultEnabled = false),
    GENIUS("Genius", R.string.lyrics_source_genius),
    ;

    val prefKey: String get() = "lyrics.enabled.$name"

    companion object {
        const val ORDER_KEY = "lyrics.order"
        const val PAXSENIX_KEY = "lyrics.paxsenix_key"

        val DEFAULT_ORDER: List<LyricsSource> = entries.toList()

        fun parseOrder(value: String?): List<LyricsSource> {
            val saved = value.orEmpty().split(',').mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }.distinct()
            return saved + entries.filter { it !in saved }
        }

        fun formatOrder(order: List<LyricsSource>): String = order.joinToString(",") { it.name }
    }
}
