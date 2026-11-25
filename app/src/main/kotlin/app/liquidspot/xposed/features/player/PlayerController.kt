package app.liquidspot.xposed.features.player

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import app.liquidspot.core.Constants
import app.liquidspot.xposed.features.debug.UiDumper
import app.liquidspot.xposed.features.navbar.GlassUi
import app.liquidspot.xposed.features.player.lyrics.LrcParser
import app.liquidspot.xposed.features.player.lyrics.Lyrics
import app.liquidspot.xposed.features.player.lyrics.LyricsQuery
import app.liquidspot.xposed.features.player.lyrics.LyricsRepository
import app.liquidspot.xposed.features.player.lyrics.LyricsSettings
import app.liquidspot.xposed.features.player.lyrics.LyricsTranslator
import app.liquidspot.xposed.features.player.lyrics.LyricsView
import app.liquidspot.xposed.features.player.lyrics.ai.AiSync
import app.liquidspot.xposed.media.NowPlayingStore
import app.liquidspot.xposed.ui.PlayerIcon
import app.liquidspot.xposed.util.Logger

class PlayerController(private val activity: Activity, private val pageRoot: ViewGroup) {
    private val res = activity.resources
    val view = LiquidPlayerView(activity)
    private val targets = PlayerTargets(res)
    private val hiddenLayers = listOf("overlay_controls_layout", "widgets_container", "revised_template_sticky_header", "endless_feed_sticky_header")
        .map { res.getIdentifier(it, "id", Constants.SPOTIFY_PACKAGE) }
        .filter { it != 0 }
    private val overlayId = res.getIdentifier("overlay_controls_layout", "id", Constants.SPOTIFY_PACKAGE)
    private val carouselId = res.getIdentifier("track_carousel", "id", Constants.SPOTIFY_PACKAGE)
    private val videoId = res.getIdentifier("video_surface", "id", Constants.SPOTIFY_PACKAGE)
    private val djOverlayId = res.getIdentifier("player_overlay", "id", Constants.SPOTIFY_PACKAGE)
    private var loggedNarration: String? = null
    private val sheetId = res.getIdentifier("now_playing_view_container", "id", Constants.SPOTIFY_PACKAGE)
    private val carouselImageId = res.getIdentifier("image", "id", Constants.SPOTIFY_PACKAGE)
    private val dimmedOverlayChildren = ArrayList<View>()
    private val screenLocation = IntArray(2)
    private var canvasActive = false
    private var activeVideo: View? = null
    private val frameGrabber = CanvasFrameGrabber { frame -> view.backdropView.setVideoFrame(frame) }
    private var lastCanvasPoll = 0L

    private var trackKey: String? = null
    private var quietArtKey: String? = null
    private var awaitingArtKey: String? = null
    private var lyricsKey: String? = null
    private var shownLyrics: Lyrics? = null
    private var lyricsLoading = false
    private var lyricsOptions: List<Lyrics> = emptyList()
    private var lyricsComplete = false
    private val chosenSource = HashMap<String, String>()
    private var lastTargetsRefresh = 0L
    private var targetRefreshes = 0
    private var lastStatePoll = 0L
    private var failed = false
    private var running = false

    private val storeListener: () -> Unit = { guard("estado") { bindTrack() } }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            guard("frame") { tick() }
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val lyrics by lazy { LyricsRepository(activity.cacheDir) { LyricsSettings.config() } }
    private val translator by lazy { LyricsTranslator(activity.cacheDir) }
    private var translatedFor: Any? = null

    fun attach() {
        view.id = View.generateViewId()
        pageRoot.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = start()
            override fun onViewDetachedFromWindow(v: View) = stop()
        })
        wireActions()
        applySettings()
        if (view.isAttachedToWindow) start()
        restoreLyricsMode()
        layoutNow()
        UiDumper.dumpOnce(pageRoot, "player do Spotify")
        Logger.i("Player Liquid Glass instalado")
    }

    private fun restoreLyricsMode() {
        if (!lyricsModeOn || trackKey == null || view.isLyricsMode) return
        guard("letras restauradas") {
            view.setLyricsMode(true, animate = false)
            view.lyricsView.snapToCurrentLine()
            loadLyrics()
        }
    }

    private fun layoutNow() {
        val w = pageRoot.width
        val h = pageRoot.height
        if (w <= 0 || h <= 0) return
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    fun applySettings() {
        view.style = GlassUi.style
        view.reduceMotion = GlassUi.reduceMotion
    }

    private fun start() {
        if (running || failed) return
        running = true
        NowPlayingStore.addListener(storeListener)
        guard("início") { bindTrack() }
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stop() {
        running = false
        AiSync.stop()
        resetCanvasFit()
        NowPlayingStore.removeListener(storeListener)
        Choreographer.getInstance().removeFrameCallback(frame)
        canvasSwapPending = false
        carousel()?.let {
            it.animate().cancel()
            it.translationX = 0f
            it.alpha = 1f
        }
        setCanvasActive(false)
        setSpotifyLayersHidden(false)
    }

    private fun bindTrack() {
        val track = NowPlayingStore.track
        val narration = track != null && !track.isMusicTrack && !track.isAd && isDjPage()
        if (narration && loggedNarration != track.mediaId) {
            loggedNarration = track.mediaId
            Logger.d("DJ falando: mediaId=${track.mediaId}")
        }
        if (track == null || !shouldShow(track, narration)) {
            if (view.visibility == View.VISIBLE) {
                if (!hidePending) {
                    hidePending = true
                    view.postDelayed(hideIfStillGone, HIDE_DELAY_MS)
                }
            } else {
                setCanvasActive(false)
                setSpotifyLayersHidden(false)
            }
            return
        }
        if (hidePending) {
            hidePending = false
            view.removeCallbacks(hideIfStillGone)
        }
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
            view.resetDismiss()
        }
        setSpotifyLayersHidden(true)

        val changed = track.key != trackKey
        view.titleView.text = track.title.ifBlank { DJ_TITLE }
        view.artistView.text = track.artist
        view.headerTitle.text = track.album.ifBlank { DJ_TITLE.takeIf { narration }.orEmpty() }
        view.explicit = track.explicit
        view.seekBar.durationMs = track.durationMs
        view.seekBar.visibility = if (track.durationMs > 0) View.VISIBLE else View.INVISIBLE
        view.lyricsButton.isEnabled = !narration
        if (narration && view.isLyricsMode) {
            view.setLyricsMode(false)
            lyricsModeOn = false
        }
        view.setPlaying(NowPlayingStore.isPlaying, animate = !changed && trackKey != null)

        val hiRes = ArtworkLoader.cached(track.artUri)
        val art = hiRes ?: track.art
        val swiped = changed && view.takeSwipeCommit()
        if (swiped) quietArtKey = track.key else if (changed) quietArtKey = null
        val quiet = quietArtKey == track.key
        val fresh = (changed || awaitingArtKey == track.key) && !quiet
        if (art != null || (!quiet && track.artUri == null)) {
            view.artwork.setArtwork(art, animate = fresh)
            view.backdropView.setArtwork(art, animate = fresh, zoom = !quiet)
            awaitingArtKey = null
        } else if (changed) {
            awaitingArtKey = track.key
        }
        if (hiRes == null) {
            val key = track.key
            ArtworkLoader.load(track.artUri) { bitmap ->
                if (trackKey == key) {
                    val silent = quietArtKey == key
                    view.artwork.setArtwork(bitmap, animate = !silent)
                    view.backdropView.setArtwork(bitmap, animate = !silent, zoom = false)
                }
            }
        }

        if (changed && canvasSwapPending) view.post { guard("canvas trocado") { revealCanvas() } }
        if (changed) {
            preloadNeighbors()
            if (trackKey != null) aiOn = false
            trackKey = track.key
            lastStatePoll = 0L
            restoreLyricsMode()
            loadLyrics()
        }
    }

    private fun tick() {
        if (view.visibility != View.VISIBLE) return
        val now = SystemClock.uptimeMillis()
        val position = NowPlayingStore.positionMs()
        view.seekBar.setPosition(position)
        if (view.lyricsProgress > 0f) view.lyricsView.setPosition(position)
        if (aiOn && view.isLyricsMode && view.isShown) AiSync.heartbeat()
        if (!view.isLyricsMode) view.setLyricPreview(if (GlassUi.lyricLine) previewLine(position) else null)
        if (canvasSwapPending && now - canvasSwapStarted > CANVAS_SWAP_TIMEOUT_MS) revealCanvas()
        if (!view.isTrackDragging && !canvasSwapPending && now - lastCanvasPoll > CANVAS_POLL_MS) {
            lastCanvasPoll = now
            pollCanvas(now)
        }
        if (canvasActive && !view.isLyricsMode) frameGrabber.tick(now)
        setSpotifyLayersHidden(true)
        if (canvasActive) fitCanvas()

        if (!targets.foundAll && targetRefreshes < MAX_TARGET_REFRESHES && now - lastTargetsRefresh > TARGET_REFRESH_MS) {
            targetRefreshes++
            lastTargetsRefresh = now
            targets.refresh(pageRoot, view)
            updateAvailability()
        }
        if (now - lastStatePoll > STATE_POLL_MS) {
            lastStatePoll = now
            applyStates()
        }
    }

    private fun applyStates() {
        val s = targets.readStates()
        view.shuffleButton.icon = if (s.smartShuffle) PlayerIcon.SMART_SHUFFLE else PlayerIcon.SHUFFLE
        view.shuffleButton.active = s.shuffle == true
        view.repeatButton.icon = if (s.repeat == PlayerTargets.Repeat.ONE) PlayerIcon.REPEAT_ONE else PlayerIcon.REPEAT
        view.repeatButton.active = s.repeat == PlayerTargets.Repeat.ALL || s.repeat == PlayerTargets.Repeat.ONE
        view.addButton.icon = if (s.added == true) PlayerIcon.ADDED else PlayerIcon.ADD
        view.addButton.contentDescription = if (s.added == true) Texts.added else Texts.add
        val listening = s.connectActive == true || headphonesConnected()
        view.devicesButton.icon = if (listening) PlayerIcon.HEADPHONES else PlayerIcon.DEVICES
        view.devicesButton.active = listening
        view.setPlaying(NowPlayingStore.isPlaying)
    }

    private fun updateAvailability() {
        view.shuffleButton.isEnabled = targets.shuffleTarget != null
        view.repeatButton.isEnabled = targets.repeatTarget != null
        view.addButton.visibility = if (targets.addTarget != null) View.VISIBLE else View.INVISIBLE
        view.devicesButton.isEnabled = targets.connectTarget != null
        view.queueButton.isEnabled = targets.queueTarget != null
        val djVisibility = if (targets.djTarget != null) View.VISIBLE else View.GONE
        if (view.djButton.visibility != djVisibility) {
            view.djButton.visibility = djVisibility
            view.requestLayout()
        }
    }

    private fun wireActions() {
        view.playButton.setOnClickListener { guard("play") { NowPlayingStore.playPause() } }
        view.nextButton.setOnClickListener { guard("próxima") { if (!view.animateTrackChange(1)) NowPlayingStore.next() } }
        view.previousButton.setOnClickListener {
            guard("anterior") {
                val restart = NowPlayingStore.positionMs() > RESTART_THRESHOLD_MS
                if (restart || !view.animateTrackChange(-1)) NowPlayingStore.previous()
            }
        }
        view.seekBar.onSeek = { ms -> guard("seek") { NowPlayingStore.seekTo(ms) } }
        view.neighborArt = { dir -> runCatching { neighborArt(dir) }.getOrNull() }
        view.onTrackDragOffset = { dx -> guard("canvas deslizando") { moveCanvas(dx) } }
        view.onTrackDragEnd = { committed -> guard("canvas solto") { endCanvasDrag(committed) } }
        view.onSwipeTrack = { dir -> guard("deslizar") { if (dir > 0) NowPlayingStore.next() else NowPlayingStore.previous() } }

        view.shuffleButton.setOnClickListener { forward("aleatório") { targets.shuffleTarget } }
        view.repeatButton.setOnClickListener { forward("repetir") { targets.repeatTarget } }
        view.addButton.setOnClickListener { forward("+") { targets.addTarget } }
        view.moreButton.setOnClickListener { guard("menu") { showMenu() } }
        view.devicesButton.setOnClickListener {
            guard("dispositivos") {
                openDevices()
            }
        }
        view.queueButton.setOnClickListener { forward("fila") { targets.queueTarget } }
        view.djButton.setOnClickListener { forward("DJ") { targets.djTarget } }
        view.djButton.setOnLongClickListener {
            guard("DJ (segurar)") {
                if (targets.djTarget?.longClick() != true) Logger.w("Player: botão do DJ não respondeu ao toque longo")
            }
            true
        }
        view.closeButton.setOnClickListener { guard("fechar") { close() } }
        view.artistView.setOnClickListener { guard("artista") { openArtist() } }
        view.headerTitle.setOnClickListener { guard("álbum") { openAlbum() } }
        view.titleView.setOnClickListener { guard("álbum") { openAlbum() } }
        view.onDismiss = { guard("fechar") { close() } }
        view.sheetProvider = { findSheet() }
        view.sheetCompanions = { sheetSiblings(SHEET_COMPANION_IDS) }
        view.sheetBehind = { sheetSiblings(listOf("coordinator_layout")).firstOrNull() }

        view.lyricsButton.setOnClickListener {
            guard("letras") {
                val enable = !view.isLyricsMode
                view.setLyricsMode(enable)
                lyricsModeOn = enable
                if (enable) {
                    view.lyricsView.snapToCurrentLine()
                    loadLyrics()
                }
            }
        }
        view.aiButton.setOnClickListener {
            guard("IA") {
                if (!aiUsable()) return@guard
                aiOn = !aiOn
                if (aiOn) {
                    restartAi()
                } else {
                    AiSync.stop()
                    view.aiButton.ringProgress = null
                    updateAiButton()
                    shownLyrics?.let(view.lyricsView::updateLyrics)
                }
            }
        }
        view.lyricPreview.setOnClickListener { if (!view.isLyricsMode) view.lyricsButton.performClick() }
        view.translateButton.active = translationOn
        view.translateButton.setOnClickListener {
            guard("traduzir") {
                translationOn = !translationOn
                view.translateButton.active = translationOn
                if (translationOn) requestTranslation() else view.lyricsView.setTranslations(null)
            }
        }
        view.lyricsView.onSeek = { ms -> guard("seek letra") { NowPlayingStore.seekTo(ms) } }
        view.sourceChip.setOnClickListener {
            guard("fonte da letra") {
                if (lyricsOptions.isNotEmpty()) view.sourcePicker.show(lyricsOptions, shownLyrics, lyricsComplete)
            }
        }
        view.sourcePicker.onSelect = { chosen ->
            guard("escolher letra") {
                lyricsKey?.let { chosenSource[it] = chosen.source }
                showLyrics(chosen)
            }
        }
    }

    private fun forward(name: String, target: () -> app.liquidspot.xposed.a11y.A11yBridge.Target?) = guard(name) {
        var t = target()
        if (t == null || !t.click()) {
            targets.refresh(pageRoot, view)
            t = target()
            if (t?.click() != true) Logger.w("Player: botão '$name' do Spotify não encontrado")
        }
        lastStatePoll = SystemClock.uptimeMillis() - STATE_POLL_MS + 250
    }

    private fun openDevices() {
        if (targets.connectTarget == null) targets.refresh(pageRoot, view)
        val target = targets.connectTarget
        when {
            target != null && target.tap() -> Logger.d("Dispositivos: toque no botão do player")
            target != null && target.click() -> Logger.d("Dispositivos: clique de acessibilidade no botão do player")
            openDevicesFromMiniPlayer() -> Logger.d("Dispositivos: botão do mini player")
            else -> Logger.w("Player: botão Dispositivos do Spotify não encontrado")
        }
        lastStatePoll = SystemClock.uptimeMillis() - STATE_POLL_MS + 250
    }

    private fun openDevicesFromMiniPlayer(): Boolean {
        val id = res.getIdentifier("connect_destination_button", "id", Constants.SPOTIFY_PACKAGE)
        if (id == 0) return false
        val button = activity.window.decorView.findViewById<View>(id) ?: return false
        if (!button.isAttachedToWindow || !button.isShown || button.width == 0) return false
        return button.performClick()
    }

    @Suppress("DEPRECATION")
    private fun close() {
        if (targets.closeTarget?.click() == true) return
        activity.onBackPressed()
    }

    private fun loadLyrics() {
        val track = NowPlayingStore.track ?: return
        if (track.key == lyricsKey) return
        lyricsKey = track.key
        shownLyrics = null
        lyricsLoading = true
        AiSync.stop()
        updateAiButton()
        lyricsOptions = emptyList()
        lyricsComplete = false
        view.setLyricsSource(null, hasAlternatives = false)
        view.lyricsView.setState(LyricsView.State.Loading)
        val key = track.key
        val trackId = track.mediaId?.takeIf { it.startsWith("spotify:track:") }?.removePrefix("spotify:track:")
        lyrics.load(LyricsQuery(trackId, track.title, track.artist, track.album, track.durationMs)) { result ->
            if (lyricsKey != key) return@load
            when (result) {
                is LyricsRepository.Result.Found -> {
                    lyricsOptions = result.bundle.options
                    lyricsComplete = result.bundle.complete
                    if (result.bundle.complete) lyricsLoading = false
                    val choice = chosenSource[key]?.let { name -> lyricsOptions.firstOrNull { it.source == name } }
                    val target = choice ?: shownLyrics?.takeIf { shown -> lyricsOptions.any { it.source == shown.source } } ?: result.bundle.best
                    if (target != null) showLyrics(target)
                    view.sourcePicker.update(lyricsOptions, shownLyrics, lyricsComplete)
                }
                LyricsRepository.Result.NotFound -> {
                    lyricsLoading = false
                    view.lyricsView.setState(LyricsView.State.NotFound)
                }
                is LyricsRepository.Result.Failed -> {
                    lyricsLoading = false
                    lyricsKey = null
                    view.lyricsView.setState(LyricsView.State.Failed)
                }
            }
        }
    }

    private fun preloadNeighbors() {
        for (direction in intArrayOf(1, -1)) {
            NowPlayingStore.neighbor(direction)?.iconUri?.toString()?.let { uri -> ArtworkLoader.load(uri) {} }
        }
    }

    private fun neighborArt(direction: Int): android.graphics.Bitmap? {
        if (direction < 0 && NowPlayingStore.positionMs() > RESTART_THRESHOLD_MS) return view.artwork.bitmap
        carouselArt(direction)?.let { return it }
        val description = NowPlayingStore.neighbor(direction) ?: return null
        return description.iconBitmap ?: ArtworkLoader.cached(description.iconUri?.toString())
    }

    private fun carouselArt(direction: Int): android.graphics.Bitmap? {
        if (carouselImageId == 0) return null
        val carousel = pageRoot.findViewById<ViewGroup>(carouselId) ?: return null
        val pages = ArrayList<Pair<Int, android.widget.ImageView>>()
        collectImages(carousel, pages)
        if (pages.size < 2) return null
        pages.sortBy { it.first }
        val centerX = res.displayMetrics.widthPixels / 2
        val current = pages.indices.minByOrNull { kotlin.math.abs(pages[it].first - centerX) } ?: return null
        val image = pages.getOrNull(current + direction)?.second ?: return null
        return image.drawable?.toBitmap()
    }

    private fun collectImages(root: View, out: MutableList<Pair<Int, android.widget.ImageView>>) {
        if (root is android.widget.ImageView && root.id == carouselImageId && root.width > 0 && root.drawable != null) {
            root.getLocationOnScreen(screenLocation)
            out.add(screenLocation[0] + root.width / 2 to root)
            return
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) collectImages(root.getChildAt(i), out)
    }

    private fun android.graphics.drawable.Drawable.toBitmap(): android.graphics.Bitmap? {
        if (this is android.graphics.drawable.BitmapDrawable) return bitmap
        val w = intrinsicWidth.takeIf { it > 0 } ?: CAROUSEL_ART_PX
        val h = intrinsicHeight.takeIf { it > 0 } ?: CAROUSEL_ART_PX
        return runCatching {
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
                val saved = copyBounds()
                setBounds(0, 0, w, h)
                draw(android.graphics.Canvas(bitmap))
                bounds = saved
            }
        }.getOrNull()
    }

    private fun requestTranslation() {
        val lyrics = shownLyrics ?: return
        if (translatedFor === lyrics) return
        translatedFor = lyrics
        val texts = view.lyricsView.translatableTexts()
        if (texts.isEmpty()) return
        val key = (trackKey ?: lyrics.source) + "|" + lyrics.source
        translator.translate(key, texts) { result ->
            guard("tradução") {
                if (shownLyrics !== lyrics || !translationOn) return@guard
                if (result.isNullOrEmpty()) {
                    translatedFor = null
                    Toast.makeText(activity, Texts.translateFailed, Toast.LENGTH_SHORT).show()
                    return@guard
                }
                view.lyricsView.setTranslations(result)
            }
        }
    }

    private fun aiUsable(): Boolean {
        val lyrics = shownLyrics ?: return false
        return AiSync.installed && lyrics.synced && lyrics.lines.none { it.words.isNotEmpty() }
    }

    private fun previewLine(position: Long): String? {
        val shown = shownLyrics ?: return if (lyricsLoading) Texts.lyricsLoading else null
        val lyrics = shown.takeIf { it.synced && !it.instrumental } ?: return null
        val index = LrcParser.activeIndex(lyrics.lines, position)
        val text = lyrics.lines.getOrNull(index)?.text?.takeIf { it.isNotBlank() }
        return text ?: "(${Texts.lyricsInstrumental})"
    }

    private fun updateAiButton() {
        val usable = aiUsable()
        if (view.aiButton.visibility == View.GONE) {
            view.aiButton.visibility = View.VISIBLE
            view.refreshControls()
        }
        view.aiButton.dimmed = !usable
        view.aiButton.active = aiOn && usable
    }

    private fun restartAi() {
        AiSync.stop()
        view.aiButton.ringProgress = null
        updateAiButton()
        val lyrics = shownLyrics ?: return
        val key = trackKey ?: return
        if (!aiOn || !aiUsable()) return
        view.aiButton.ringProgress = 0f
        AiSync.start(
            activity,
            key,
            lyrics,
            onUpdate = { updated, progress ->
                guard("IA") {
                    if (shownLyrics !== lyrics) return@guard
                    view.lyricsView.updateLyrics(updated)
                    view.aiButton.ringProgress = progress
                }
            },
            onProblem = { problem ->
                guard("IA") {
                    aiOn = false
                    view.aiButton.active = false
                    view.aiButton.ringProgress = null
                    val text = when (problem) {
                        AiSync.Problem.UNSUPPORTED -> Texts.aiUnsupported
                        AiSync.Problem.NO_AUDIO -> Texts.aiNoAudio
                        AiSync.Problem.UNAVAILABLE -> Texts.aiUnavailable
                    }
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    private fun showLyrics(lyrics: Lyrics) {
        if (shownLyrics?.source != lyrics.source || shownLyrics?.lines != lyrics.lines) {
            shownLyrics = lyrics
            view.lyricsView.setState(LyricsView.State.Ready(lyrics))
            translatedFor = null
            if (translationOn) requestTranslation()
            restartAi()
        }
        view.setLyricsSource(lyrics.source, hasAlternatives = lyricsOptions.size > 1 || !lyricsComplete)
    }

    private val audioManager by lazy { activity.getSystemService(AudioManager::class.java) }

    private fun headphonesConnected(): Boolean = headphoneDevice() != null

    private fun headphoneDevice(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        val outputs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { manager.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES) }.getOrNull()?.takeIf { it.isNotEmpty() }
        } else {
            null
        } ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        return outputs.firstOrNull { it.type in HEADPHONE_TYPES }
    }

    private var hidePending = false

    private val hideIfStillGone = Runnable {
        hidePending = false
        guard("esconder") {
            val track = NowPlayingStore.track
            val narration = track != null && !track.isMusicTrack && !track.isAd && isDjPage()
            if (shouldShow(track, narration)) return@guard
            view.visibility = View.GONE
            view.resetDismiss()
            Logger.d("Player Liquid escondido: mediaId=${track?.mediaId} dj=${isDjPage()}")
            setCanvasActive(false)
            setSpotifyLayersHidden(false)
        }
    }

    private fun shouldShow(track: NowPlayingStore.Track?, narration: Boolean): Boolean =
        track != null && (track.isMusicTrack || narration) && NowPlayingStore.controller != null

    private fun isDjPage(): Boolean = djOverlayId != 0 && pageRoot.findViewById<View>(djOverlayId) != null

    private var canvasSwapPending = false
    private var canvasSwapStarted = 0L

    private fun carousel(): View? = if (carouselId == 0) null else pageRoot.findViewById(carouselId)

    private var canvasBaseX = 0f
    private var canvasDragX = 0f
    private val unclipped = ArrayList<ViewGroup>()
    private val fitLocation = IntArray(2)

    private fun fitCanvas() {
        val carousel = carousel() ?: return
        val video = activeVideo ?: return
        if (video.width <= 0 || video.height <= 0 || view.width <= 0) return
        var ox = 0f
        var oy = 0f
        var v: View = video
        while (v !== carousel) {
            val parent = v.parent as? ViewGroup ?: return
            ox += v.left - parent.scrollX
            oy += v.top - parent.scrollY
            v = parent
        }
        val cx = ox + video.width / 2f
        val cy = oy + video.height / 2f
        val holder = carousel.parent as? View ?: return
        holder.getLocationOnScreen(fitLocation)
        val originX = fitLocation[0] + carousel.left.toFloat()
        val originY = fitLocation[1] + carousel.top.toFloat()
        view.getLocationOnScreen(fitLocation)
        val screenCx = fitLocation[0] + view.width / 2f
        val screenCy = fitLocation[1] + view.height / 2f
        val scale = maxOf(view.width.toFloat() / video.width, view.height.toFloat() / video.height)
        if (carousel.pivotX != cx) carousel.pivotX = cx
        if (carousel.pivotY != cy) carousel.pivotY = cy
        if (carousel.scaleX != scale) {
            carousel.scaleX = scale
            carousel.scaleY = scale
        }
        canvasBaseX = screenCx - (originX + cx)
        val ty = screenCy - (originY + cy)
        if (carousel.translationY != ty) carousel.translationY = ty
        val tx = canvasBaseX + canvasDragX
        if (carousel.animation == null && carousel.translationX != tx) carousel.translationX = tx
        var parent = carousel.parent as? ViewGroup
        while (parent != null && parent !== pageRoot.parent) {
            if (parent.clipChildren) {
                parent.clipChildren = false
                unclipped += parent
            }
            parent = parent.parent as? ViewGroup
        }
    }

    private fun resetCanvasFit() {
        canvasBaseX = 0f
        canvasDragX = 0f
        carousel()?.let {
            it.scaleX = 1f
            it.scaleY = 1f
            it.translationY = 0f
            it.translationX = 0f
        }
        unclipped.forEach { it.clipChildren = true }
        unclipped.clear()
    }

    private fun moveCanvas(dx: Float) {
        if (!canvasActive) return
        canvasDragX = dx
        carousel()?.translationX = canvasBaseX + dx
    }

    private fun endCanvasDrag(committed: Boolean) {
        val target = carousel() ?: return
        if (!canvasActive) {
            target.translationX = 0f
            view.releaseDragBlend()
            return
        }
        if (!committed) {
            view.releaseDragBlend()
            canvasDragX = 0f
            target.translationX = canvasBaseX
            return
        }
        target.alpha = 0f
        target.translationX = 0f
        canvasSwapPending = true
        canvasSwapStarted = SystemClock.uptimeMillis()
    }

    private fun revealCanvas() {
        canvasSwapPending = false
        lastCanvasPoll = SystemClock.uptimeMillis()
        canvasCandidate = null
        canvasQuietUntil = SystemClock.uptimeMillis() + CANVAS_QUIET_MS
        setCanvasActive(false, animate = false)
        view.releaseDragBlend()
        carousel()?.let {
            it.animate().cancel()
            it.translationX = 0f
            it.alpha = 1f
        }
    }

    private fun currentTrackId(): String? =
        NowPlayingStore.track?.mediaId?.takeIf { it.startsWith(TRACK_PREFIX) }?.removePrefix(TRACK_PREFIX)

    private fun showMenu() {
        val shuffle = view.shuffleButton
        val repeat = view.repeatButton
        view.menu.show(
            listOf(
                PlayerMenu.Entry(Texts.shuffle, shuffle.icon, active = shuffle.active, enabled = targets.shuffleTarget != null) {
                    forward("aleatório") { targets.shuffleTarget }
                },
                PlayerMenu.Entry(Texts.repeat, repeat.icon, active = repeat.active, enabled = targets.repeatTarget != null) {
                    forward("repetir") { targets.repeatTarget }
                },
                PlayerMenu.Entry(Texts.goToAlbum, PlayerIcon.ALBUM) { guard("álbum") { openAlbum() } },
                PlayerMenu.Entry(Texts.goToArtist, PlayerIcon.ARTIST) { guard("artista") { openArtist() } },
                PlayerMenu.Entry(Texts.spotifyOptions, PlayerIcon.MORE, enabled = targets.moreTarget != null) {
                    forward("…") { targets.moreTarget }
                },
            ),
        )
    }

    private fun openAlbum() {
        val id = currentTrackId() ?: return
        TrackLinks.load(id) { links ->
            guard("abrir álbum") {
                links?.albumId?.let { openSpotify("spotify:album:$it") } ?: Logger.w("Player: álbum da música não encontrado")
            }
        }
    }

    private fun openArtist() {
        val id = currentTrackId() ?: return
        val index = tappedArtistIndex()
        TrackLinks.load(id) { links ->
            guard("abrir artista") {
                val artists = links?.artistIds.orEmpty()
                val artist = artists.getOrNull(index) ?: artists.firstOrNull()
                artist?.let { openSpotify("spotify:artist:$it") } ?: Logger.w("Player: artista da música não encontrado")
            }
        }
    }

    private fun tappedArtistIndex(): Int {
        val text = view.artistView.text?.toString().orEmpty()
        if (text.isEmpty()) return 0
        val offset = view.artistView.getOffsetForPosition(view.lastDownX - view.artistView.x, view.lastDownY - view.artistView.y)
        var index = 0
        var cursor = text.indexOf(ARTIST_SEPARATOR)
        while (cursor in 0 until offset) {
            index++
            cursor = text.indexOf(ARTIST_SEPARATOR, cursor + ARTIST_SEPARATOR.length)
        }
        return index
    }

    private fun openSpotify(uri: String) {
        close()
        view.postDelayed({
            runCatching {
                activity.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage(Constants.SPOTIFY_PACKAGE),
                )
            }.onFailure { Logger.w("Player: não foi possível abrir $uri", it) }
        }, OPEN_PAGE_DELAY_MS)
    }

    private fun findSheet(): View? {
        val chain = ArrayList<View>()
        var current: View? = view
        while (current != null) {
            chain.add(current)
            current = current.parent as? View
        }
        chain.firstOrNull { sheetId != 0 && it.id == sheetId }?.let { return it }
        chain.firstOrNull { child ->
            val parent = child.parent as? View
            parent?.javaClass?.name?.endsWith(MAIN_LAYOUT) == true && child.id != coordinatorId
        }?.let { return it }
        chain.firstOrNull { child ->
            (child.parent as? View)?.id == android.R.id.content && !child.javaClass.name.endsWith(MAIN_LAYOUT)
        }?.let { return it }
        return null
    }

    private val coordinatorId = res.getIdentifier("coordinator_layout", "id", Constants.SPOTIFY_PACKAGE)

    private fun sheetSiblings(names: List<String>): List<View> {
        val parent = findSheet()?.parent as? ViewGroup ?: return emptyList()
        return names.mapNotNull { name ->
            val id = res.getIdentifier(name, "id", Constants.SPOTIFY_PACKAGE)
            if (id == 0) null else (0 until parent.childCount).map(parent::getChildAt).firstOrNull { it.id == id }
        }
    }

    private var canvasCandidate: View? = null
    private var canvasQuietUntil = 0L

    private fun pollCanvas(now: Long) {
        if (GlassUi.canvasDisabled) {
            canvasCandidate = null
            setCanvasActive(false)
            return
        }
        val video = findCanvasVideo()
        when {
            video == null -> {
                canvasCandidate = null
                setCanvasActive(false)
            }
            canvasActive -> {
                activeVideo = video
                frameGrabber.attach(video)
            }
            now >= canvasQuietUntil && video === canvasCandidate -> {
                canvasCandidate = null
                activeVideo = video
                setCanvasActive(true)
                frameGrabber.attach(video)
            }
            else -> canvasCandidate = video
        }
    }

    private fun setCanvasActive(active: Boolean, animate: Boolean = true) {
        if (active == canvasActive) return
        canvasActive = active
        view.setCanvasMode(active, animate)
        if (!active) {
            activeVideo = null
            resetCanvasFit()
            frameGrabber.attach(null)
            view.backdropView.setVideoFrame(null)
        }
        Logger.d(if (active) "Canvas do Spotify visível no player" else "Canvas do Spotify oculto")
    }

    private fun findCanvasVideo(): View? {
        if (videoId == 0) return null
        val overlay = pageRoot.findViewById<View>(overlayId) ?: return null
        val centerX = pageRoot.resources.displayMetrics.widthPixels / 2
        return findVisible(overlay, videoId) { video ->
            video.getLocationOnScreen(screenLocation)
            video.width > 0 && video.height > 0 && centerX in screenLocation[0] until screenLocation[0] + video.width
        }
    }

    private fun findVisible(root: View, id: Int, accept: (View) -> Boolean): View? {
        if (root.visibility != View.VISIBLE || root === view) return null
        if (root.id == id && accept(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findVisible(root.getChildAt(i), id, accept)?.let { return it }
        }
        return null
    }

    private fun setSpotifyLayersHidden(hidden: Boolean) {
        val alpha = if (hidden) 0f else 1f
        for (id in hiddenLayers) {
            val layer = pageRoot.findViewById<View>(id) ?: continue
            if (id == overlayId) {
                applyOverlay(layer, hidden)
            } else if (layer.alpha != alpha) {
                layer.alpha = alpha
            }
        }
    }

    private fun applyOverlay(overlay: View, hidden: Boolean) {
        val keepCanvas = hidden && canvasActive && overlay is ViewGroup
        val alpha = if (hidden && !keepCanvas) 0f else 1f
        if (overlay.alpha != alpha) overlay.alpha = alpha
        if (!keepCanvas) {
            restoreOverlayChildren()
            return
        }
        val carousel = pageRoot.findViewById<View>(carouselId)
        for (i in 0 until overlay.childCount) {
            val child = overlay.getChildAt(i)
            if (carousel != null && child.isAncestorOf(carousel)) continue
            dim(child)
        }
        val video = activeVideo
        if (carousel != null && video != null && carousel.isAncestorOf(video)) {
            var keep: View = video
            while (keep !== carousel) {
                val parent = keep.parent as? ViewGroup ?: break
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChildAt(i)
                    if (sibling === keep) undim(sibling) else dim(sibling)
                }
                keep = parent
            }
        }
    }

    private fun undim(child: View) {
        if (dimmedOverlayChildren.remove(child)) child.alpha = 1f
    }

    private fun dim(child: View) {
        if (child.alpha != 0f) child.alpha = 0f
        if (child !in dimmedOverlayChildren) dimmedOverlayChildren.add(child)
    }

    private fun restoreOverlayChildren() {
        if (dimmedOverlayChildren.isEmpty()) return
        dimmedOverlayChildren.forEach { it.alpha = 1f }
        dimmedOverlayChildren.clear()
    }

    private fun View.isAncestorOf(other: View): Boolean {
        var current: View? = other
        while (current != null) {
            if (current === this) return true
            current = current.parent as? View
        }
        return false
    }

    private inline fun guard(where: String, block: () -> Unit) {
        if (failed) return
        try {
            block()
        } catch (t: Throwable) {
            failed = true
            Logger.e("Player Liquid Glass desativado após erro ($where)", t)
            runCatching {
                stop()
                (view.parent as? ViewGroup)?.removeView(view)
            }
        }
    }

    private companion object {
        var lyricsModeOn = false
        var aiOn = false
        var translationOn = false

        const val TARGET_REFRESH_MS = 1_500L
        const val MAX_TARGET_REFRESHES = 10
        const val STATE_POLL_MS = 400L
        const val RESTART_THRESHOLD_MS = 3_000L
        const val CANVAS_POLL_MS = 300L
        const val CANVAS_SWAP_TIMEOUT_MS = 1_500L
        const val CANVAS_QUIET_MS = 700L
        const val HIDE_DELAY_MS = 700L
        const val DJ_TITLE = "DJ"
        const val MAIN_LAYOUT = "MainLayout"
        const val TRACK_PREFIX = "spotify:track:"
        const val ARTIST_SEPARATOR = ", "
        const val OPEN_PAGE_DELAY_MS = 250L
        val SHEET_COMPANION_IDS = listOf("now_playing_view_attachments_container")
        val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val HEADPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        const val CAROUSEL_ART_PX = 640
    }
}
