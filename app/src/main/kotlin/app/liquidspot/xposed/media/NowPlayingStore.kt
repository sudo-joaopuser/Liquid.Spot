package app.liquidspot.xposed.media

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.hook.method
import app.liquidspot.xposed.util.Logger
import java.util.concurrent.CopyOnWriteArrayList

object NowPlayingStore {
    data class Track(
        val mediaId: String?,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val art: Bitmap?,
        val artUri: String?,
        val explicit: Boolean,
    ) {
        val isMusicTrack: Boolean
            get() = mediaId == null || mediaId.startsWith("spotify:track:") || mediaId.startsWith("spotify:local:") ||
                !mediaId.startsWith("spotify:")

        val isAd: Boolean get() = mediaId?.startsWith("spotify:ad:") == true

        val key: String get() = mediaId ?: "$title|$artist|$album"
    }

    @Volatile
    var track: Track? = null
        private set

    @Volatile
    var state: PlaybackState? = null
        private set

    @Volatile
    var controller: MediaController? = null
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var installed = false

    val isPlaying: Boolean
        get() = state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING

    fun positionMs(): Long {
        val s = state ?: return 0L
        return PositionMath.interpolate(
            positionMs = s.position,
            lastUpdateMs = s.lastPositionUpdateTime,
            speed = s.playbackSpeed,
            playing = s.state == PlaybackState.STATE_PLAYING,
            nowMs = SystemClock.elapsedRealtime(),
            durationMs = track?.durationMs ?: 0L,
        )
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun install(ctx: HookContext) {
        if (installed) return
        installed = true
        val cls = MediaSession::class.java
        ctx.hooks.after(cls.method("setMetadata", MediaMetadata::class.java)) {
            val session = thisObject as? MediaSession ?: return@after
            val metadata = arg<MediaMetadata?>(0) ?: return@after
            onMetadata(session, metadata)
        }
        ctx.hooks.after(cls.method("setPlaybackState", PlaybackState::class.java)) {
            val session = thisObject as? MediaSession ?: return@after
            val playback = arg<PlaybackState?>(0) ?: return@after
            if (controller == null || session.sessionToken == controller?.sessionToken) {
                adopt(session)
                state = playback
                notifyListeners()
            }
        }
        Logger.i("NowPlayingStore: hooks da MediaSession instalados")
    }

    private fun onMetadata(session: MediaSession, metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        adopt(session)
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val explicit = EXPLICIT_KEYS.any { key -> metadata.containsKey(key) && metadata.getLong(key) == 1L }
        val next = Track(
            mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            title = title,
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            art = art,
            artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
            explicit = explicit,
        )
        val previous = track
        track = if (previous != null && previous.key == next.key && next.art == null) next.copy(art = previous.art) else next
        if (previous?.key != next.key) Logger.d("Tocando: ${next.title} — ${next.artist} (${next.mediaId})")
        notifyListeners()
    }

    private fun adopt(session: MediaSession) {
        if (controller?.sessionToken != session.sessionToken) {
            controller = runCatching { session.controller }.getOrNull()
        }
    }

    private fun notifyListeners() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { runCatching(it).onFailure { t -> Logger.e("Listener do NowPlayingStore falhou", t) } }
        } else {
            main.post { notifyListeners() }
        }
    }

    fun playPause() {
        val c = controller?.transportControls ?: return
        if (isPlaying) c.pause() else c.play()
    }

    fun next() = controller?.transportControls?.skipToNext()

    fun previous() = controller?.transportControls?.skipToPrevious()

    fun seekTo(ms: Long) = controller?.transportControls?.seekTo(ms)

    fun neighbor(direction: Int): android.media.MediaDescription? = runCatching {
        val c = controller ?: return null
        val queue = c.queue ?: return null
        val active = state?.activeQueueItemId ?: return null
        val index = queue.indexOfFirst { it.queueId == active }
        if (index < 0) return null
        queue.getOrNull(index + direction)?.description
    }.getOrNull()

    private val EXPLICIT_KEYS = listOf(
        "android.media.IS_EXPLICIT",
        "com.google.android.gms.car.media.metadata.IS_EXPLICIT",
    )
}
