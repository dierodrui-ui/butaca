package app.butaca.tv.player

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.butaca.tv.ButacaApp
import app.butaca.tv.data.AudioPrefEntity
import app.butaca.tv.data.ContentType
import app.butaca.tv.data.PlayItem
import app.butaca.tv.data.PlayRequest
import app.butaca.tv.data.Settings
import app.butaca.tv.data.XtreamClient
import app.butaca.tv.ui.components.ActionButton
import app.butaca.tv.ui.components.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.butaca.tv.ui.C as UiC

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(req: PlayRequest, onExit: () -> Unit) {
    val context = LocalContext.current
    val repo = ButacaApp.repo
    val settings = remember { repo.prefs.value }
    val scope = rememberCoroutineScope()

    var controlsVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentIndex by remember { mutableStateOf(req.startIndex) }

    val state = remember { PlayerState(req.items.size) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var scrubDuration by remember { mutableStateOf(0L) }
    var backHint by remember { mutableStateOf(false) }
    var lastBack by remember { mutableStateOf(0L) }
    var playerView by remember { mutableStateOf<TvPlayerView?>(null) }

    val player = remember {
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                if (settings.compat) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            )
            .setEnableDecoderFallback(true)
        val http = OkHttpDataSource.Factory(ButacaApp.instance.http).setUserAgent(XtreamClient.USER_AGENT)
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 2_500, 5_000).build()
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(load)
            .build()
            .also { applyPreferences(it, settings) }
    }

    fun mediaItem(item: PlayItem, url: String, index: Int): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaId(index.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setSubtitle(item.subtitle).build())
            .build()

    fun saveProgress(index: Int, positionMs: Long, durationMs: Long) {
        val item = req.items.getOrNull(index) ?: return
        if (item.type == ContentType.LIVE || durationMs <= 0 || positionMs <= 0) return
        scope.launch {
            if (item.episodeId != null) repo.saveEpisodeProgress(item, item.title, item.icon, positionMs, durationMs, req.seriesLastEpisodeId)
            else repo.saveMovieProgress(item, positionMs, durationMs)
        }
    }

    /** Pasa a la siguiente versión (otra calidad) del mismo título. */
    fun tryNextVariant(): Boolean {
        val idx = player.currentMediaItemIndex
        val item = req.items.getOrNull(idx) ?: return false
        val next = state.variant[idx] + 1
        if (next >= item.urls.size) return false
        state.variant[idx] = next
        val pos = player.currentPosition
        player.replaceMediaItem(idx, mediaItem(item, item.urls[next], idx))
        player.prepare()
        if (item.type != ContentType.LIVE && pos > 0) player.seekTo(idx, pos)
        player.playWhenReady = true
        state.rebuffers.clear()
        return true
    }

    LaunchedEffect(Unit) {
        state.audioPref = req.seriesKey?.let { repo.audioPref(it) }
        val items = req.items.mapIndexed { i, it -> mediaItem(it, it.urls.first(), i) }
        player.setMediaItems(items, req.startIndex, req.startPositionMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                handleAudio(player, tracks, settings, req, state, scope)
            }

            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                if (tryNextVariant()) return
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "El servidor no deja reproducir este contenido ahora mismo. Puede que hayas llegado al límite de conexiones."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No hay conexión con el servidor."
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Este dispositivo no puede reproducir el formato de este archivo."
                    else -> "No se ha podido reproducir. (${e.errorCodeName})"
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) state.started = true
                if (playbackState == Player.STATE_BUFFERING && state.started && settings.autoDown) {
                    val now = System.currentTimeMillis()
                    state.rebuffers.add(now)
                    state.rebuffers.removeAll { now - it > 120_000 }
                    if (state.rebuffers.size >= 4) tryNextVariant()
                }
                if (playbackState == Player.STATE_ENDED) {
                    saveProgress(player.currentMediaItemIndex, player.duration, player.duration)
                    onExit()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val prev = currentIndex
                val newIdx = player.currentMediaItemIndex
                if (prev != newIdx && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // El anterior se terminó entero
                    val d = state.lastDuration
                    if (d > 0) saveProgress(prev, d, d)
                }
                currentIndex = newIdx
                state.started = false
                state.rebuffers.clear()
            }
        }
        player.addListener(listener)
        onDispose {
            saveProgress(player.currentMediaItemIndex, player.currentPosition, player.duration)
            player.removeListener(listener)
            player.release()
        }
    }

    // Guarda el progreso cada 10 segundos
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            if (player.duration > 0) state.lastDuration = player.duration
            if (player.isPlaying) saveProgress(player.currentMediaItemIndex, player.currentPosition, player.duration)
        }
    }

    // Atrás: primero oculta los controles; para salir hay que pulsarlo dos veces
    BackHandler {
        val now = System.currentTimeMillis()
        when {
            scrubTarget != null -> scrubTarget = null
            controlsVisible -> playerView?.hideController()
            now - lastBack < 2500 -> onExit()
            else -> { lastBack = now; backHint = true }
        }
    }
    LaunchedEffect(lastBack) { if (backHint) { delay(2500); backHint = false } }

    // Al soltar la flecha, salta al punto elegido
    LaunchedEffect(scrubTarget) {
        val t = scrubTarget ?: return@LaunchedEffect
        delay(650)
        if (scrubTarget == t) {
            player.seekTo(t)
            scrubTarget = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                TvPlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                    useController = true
                    // Menos sombra sobre la imagen
                    findViewById<View>(androidx.media3.ui.R.id.exo_controls_background)?.setBackgroundColor(0x00000000)
                    findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)?.setBackgroundColor(0x80000000.toInt())
                    canScrub = { player.isCurrentMediaItemSeekable && !player.isCurrentMediaItemLive && player.duration > 0 }
                    onScrub = { delta ->
                        val dur = player.duration
                        val base = scrubTarget ?: player.currentPosition
                        scrubDuration = dur
                        scrubTarget = (base + delta).coerceIn(0L, (dur - 1000).coerceAtLeast(0L))
                    }
                    playerView = this
                    keepScreenOn = true
                    setShowSubtitleButton(true)
                    setShowNextButton(req.items.size > 1)
                    setShowPreviousButton(req.items.size > 1)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == View.VISIBLE })
                    post { requestFocus() }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        scrubTarget?.let { target ->
            val d = scrubDuration.coerceAtLeast(1L)
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0x99000000)))
                    .padding(horizontal = 48.dp, vertical = 30.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                T("${formatTime(target)}  /  ${formatTime(d)}", size = 26, weight = FontWeight.Bold)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxWidth((target.toFloat() / d).coerceIn(0f, 1f)).height(6.dp).background(UiC.Accent))
                }
                T("Mantén la flecha para ir más rápido · suelta para saltar", size = 13, color = UiC.TextSoft)
            }
        }

        if (backHint) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(28.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xE6171B22)).padding(horizontal = 18.dp, vertical = 12.dp)
            ) { T("Pulsa Atrás otra vez para salir", size = 15, weight = FontWeight.SemiBold) }
        }

        if (controlsVisible && scrubTarget == null) {
            val item = req.items.getOrNull(currentIndex)
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color(0x80000000), 1f to Color.Transparent))
                    .padding(horizontal = 48.dp, vertical = 30.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                T(item?.title ?: "", size = 26, weight = FontWeight.Bold, maxLines = 1)
                item?.subtitle?.let { T(it, size = 16, color = UiC.TextSoft, maxLines = 1) }
            }
        }

        error?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.width(560.dp).clip(RoundedCornerShape(18.dp)).background(UiC.Surface).padding(30.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    T("No se puede reproducir", size = 24, weight = FontWeight.SemiBold)
                    T(msg, size = 16, color = UiC.TextSoft)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton("Reintentar", null, onClick = {
                            error = null
                            val idx = player.currentMediaItemIndex
                            state.variant[idx] = 0
                            val item = req.items[idx]
                            player.replaceMediaItem(idx, mediaItem(item, item.urls.first(), idx))
                            player.prepare(); player.playWhenReady = true
                        }, primary = true)
                        ActionButton("Volver", null, onClick = onExit)
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

private class PlayerState(size: Int) {
    val variant = IntArray(size)
    val audioDone = HashSet<String>()
    val autoChoice = HashMap<String, String>()
    var audioPref: AudioPrefEntity? = null
    var started = false
    val rebuffers = ArrayList<Long>()
    var lastDuration = 0L
}

@OptIn(UnstableApi::class)
private fun applyPreferences(player: ExoPlayer, s: Settings) {
    val b = player.trackSelectionParameters.buildUpon()
    when (s.audioLang) {
        0 -> b.setPreferredAudioLanguages("es", "spa")
        1 -> b.setPreferredAudioLanguages("en", "eng")
        else -> {}
    }
    when (s.subs) {
        // Solo forzados: no se eligen subtítulos normales, pero sí los forzados del idioma del audio
        0 -> b.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT).setSelectUndeterminedTextLanguage(false)
        1 -> b.setPreferredTextLanguages("es", "spa")
        else -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    }
    when (s.quality) {
        0 -> b.setForceHighestSupportedBitrate(true)
        1 -> b.setMaxVideoSize(1920, 1080)
        else -> b.setMaxVideoSize(1280, 720)
    }
    player.trackSelectionParameters = b.build()
}

/** Puntúa una pista de audio: castellano de España primero, latino después. */
private fun audioScore(f: Format, groupIdx: Int, s: Settings, pref: AudioPrefEntity?): Int {
    val lang = (f.language ?: "").lowercase()
    val label = (f.label ?: "").lowercase()
    val isEs = lang.startsWith("es") || lang.startsWith("spa") || listOf("español", "espanol", "castellano", "spanish", "spa").any { label.contains(it) }
    val isLatin = lang.contains("419") || lang.endsWith("-mx") || lang.endsWith("-us") || lang.endsWith("-ar") ||
        listOf("latino", "latam", "latin", "mexic", "lat.").any { label.contains(it) }
    val isSpain = lang == "es-es" || listOf("castellano", "españa", "espana", "spain", "es-es", "europe").any { label.contains(it) }
    val isEn = lang.startsWith("en") || listOf("english", "inglés", "ingles").any { label.contains(it) }
    var score = 0
    when (s.audioLang) {
        0 -> {
            if (isEs) score += 100
            if (s.preferSpain) { if (isSpain) score += 30; if (isLatin) score -= 45 }
        }
        1 -> if (isEn) score += 100
    }
    if (pref != null) {
        if (pref.label != null && pref.label == f.label) score += 200
        else if (pref.groupIndex == groupIdx && pref.language == f.language) score += 150
    }
    if ((f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) score -= 60   // audiodescripción
    score += f.channelCount.coerceIn(0, 8)
    return score
}

@OptIn(UnstableApi::class)
private fun handleAudio(
    player: ExoPlayer, tracks: Tracks, s: Settings, req: PlayRequest, state: PlayerState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (groups.isEmpty()) return
    val mediaId = player.currentMediaItem?.mediaId ?: return

    if (mediaId !in state.audioDone) {
        state.audioDone.add(mediaId)
        if (s.audioLang == 2 && state.audioPref == null) return
        var best: Pair<Int, Int>? = null
        var bestScore = Int.MIN_VALUE
        groups.forEachIndexed { gi, g ->
            for (t in 0 until g.length) {
                if (!g.isTrackSupported(t)) continue
                val sc = audioScore(g.getTrackFormat(t), gi, s, state.audioPref)
                if (sc > bestScore) { bestScore = sc; best = gi to t }
            }
        }
        val (gi, t) = best ?: return
        val group = groups[gi]
        state.autoChoice[mediaId] = "$gi:$t"
        if (!group.isTrackSelected(t)) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t))
                .build()
        }
        return
    }

    // Si eliges otra pista a mano en una serie, la recordamos para los siguientes episodios
    val seriesKey = req.seriesKey ?: return
    if (!s.rememberTrack) return
    groups.forEachIndexed { gi, g ->
        for (t in 0 until g.length) {
            if (g.isTrackSelected(t)) {
                val choice = "$gi:$t"
                if (state.autoChoice[mediaId] != choice) {
                    state.autoChoice[mediaId] = choice
                    val f = g.getTrackFormat(t)
                    val pref = AudioPrefEntity(seriesKey, f.language, f.label, gi)
                    state.audioPref = pref
                    scope.launch { ButacaApp.repo.saveAudioPref(pref) }
                }
                return
            }
        }
    }
}
