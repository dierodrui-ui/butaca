package app.butaca.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import app.butaca.tv.ButacaApp
import app.butaca.tv.LocalNav
import app.butaca.tv.data.ContentType
import app.butaca.tv.data.Episode
import app.butaca.tv.data.ItemEntity
import app.butaca.tv.data.SeriesInfo
import app.butaca.tv.data.VodInfo
import app.butaca.tv.data.toRef
import app.butaca.tv.ui.C
import app.butaca.tv.ui.Ic
import app.butaca.tv.ui.components.ActionButton
import app.butaca.tv.ui.components.Artwork
import app.butaca.tv.ui.components.BottomFade
import app.butaca.tv.ui.components.EmptyState
import app.butaca.tv.ui.components.FocusSurface
import app.butaca.tv.ui.components.FolderPicker
import app.butaca.tv.ui.components.IconButtonTv
import app.butaca.tv.ui.components.Pill
import app.butaca.tv.ui.components.Scrim
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.requestFocusSafely
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun ratingText(r: Double?) = r?.let { "★ " + String.format("%.1f", it).replace('.', ',') }

private fun durationText(secs: Long): String? {
    if (secs <= 0) return null
    val h = secs / 3600
    val m = (secs % 3600) / 60
    return if (h > 0) "$h h $m min" else "$m min"
}

@Composable
private fun Backdrop(url: String?) {
    Box(Modifier.fillMaxSize()) {
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Scrim(Modifier.fillMaxSize())
        BottomFade(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp))
    }
}

@Composable
private fun FolderChips(stableKey: String) {
    val repo = ButacaApp.repo
    val folders by repo.folders().collectAsState(initial = emptyList())
    val inFolders by repo.foldersOf(stableKey).collectAsState(initial = emptySet())
    val names = folders.filter { it.id in inFolders }.map { it.name }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T("EN TUS CARPETAS", size = 12, weight = FontWeight.SemiBold, color = C.Muted)
        if (names.isEmpty()) T("Todavía no está en ninguna carpeta.", size = 14, color = C.Muted)
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEach { n ->
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(C.Surface3).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Ic.Folder, null, tint = C.Accent, modifier = Modifier.size(15.dp))
                    T(n, size = 14)
                }
            }
        }
    }
}

/** Botones comunes: ver más adelante, carpeta, favorito, vista. */
@Composable
private fun SecondaryActions(item: ItemEntity, onPicker: () -> Unit) {
    val repo = ButacaApp.repo
    val scope = rememberCoroutineScope()
    val folders by repo.folders().collectAsState(initial = emptyList())
    val inFolders by repo.foldersOf(item.stableKey).collectAsState(initial = emptySet())
    val progress by repo.progressOf(item.stableKey).collectAsState(initial = null)
    val laterId = folders.firstOrNull { it.system == "later" }?.id
    val favId = folders.firstOrNull { it.system == "fav" }?.id
    val inLater = laterId != null && laterId in inFolders
    val inFav = favId != null && favId in inFolders
    val watched = progress?.finished == true
    val ref = item.toRef()
    if (laterId != null) {
        ActionButton(
            if (inLater) "En tu lista" else "Ver más adelante", if (inLater) Ic.Check else Ic.Clock,
            onClick = { scope.launch { repo.toggleFolder(laterId, ref, inLater) } },
        )
    }
    ActionButton("Añadir a carpeta", Ic.FolderPlus, onClick = onPicker)
    if (favId != null) {
        IconButtonTv(
            if (inFav) Ic.HeartFilled else Ic.Heart, "Favorito",
            onClick = { scope.launch { repo.toggleFolder(favId, ref, inFav) } },
            tint = if (inFav) C.Accent else C.Text,
        )
    }
    IconButtonTv(
        Ic.Check, if (watched) "Marcar como no vista" else "Marcar como vista",
        onClick = { scope.launch { repo.markWatched(ref, !watched) } },
        tint = if (watched) C.Green else C.Text,
    )
}

// ============================ Película ============================

@Composable
fun MovieScreen(key: String, onBack: () -> Unit) {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }

    val item by produceState<ItemEntity?>(null, key) { value = repo.itemByKey(key) }
    val info by produceState<VodInfo?>(null, item?.streamId) {
        val it = item ?: return@produceState
        val acc = repo.account() ?: return@produceState
        value = try { repo.client.vodInfo(acc, it.streamId) } catch (_: Exception) { null }
    }
    val current = item
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T("Cargando…", color = C.Muted) }
        return
    }
    val progress by repo.progressOf(current.stableKey).collectAsState(initial = null)
    var picker by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); requestFocusSafely(playFocus) }

    val resumeMs = progress?.takeIf { !it.finished && it.positionMs > 60_000 }?.positionMs ?: 0L

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Backdrop(info?.backdrop ?: current.backdrop)
        Column(Modifier.fillMaxSize().padding(horizontal = 60.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Ic.Back, null, tint = C.Muted, modifier = Modifier.size(18.dp))
                T("Atrás para volver", size = 14, color = C.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Box(Modifier.width(240.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp))) {
                    Artwork(info?.poster ?: current.icon, current.name, current.stableKey, Modifier.fillMaxSize(), titleSize = 24)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val genre = info?.genre ?: current.genre
                    T(listOfNotNull("Película", genre).joinToString(" · "), size = 14, color = C.TextSoft, maxLines = 1)
                    T(current.name, size = 40, weight = FontWeight.Bold, maxLines = 2, lineHeight = 44)
                    val meta = listOfNotNull(
                        current.year?.toString() ?: info?.releaseDate?.take(4),
                        durationText(info?.durationSecs ?: 0),
                        ratingText(info?.rating ?: current.rating),
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) T(meta, size = 15, color = C.Muted)
                    val plot = info?.plot ?: current.plot
                    if (!plot.isNullOrBlank()) T(plot, size = 16, color = C.TextSoft, maxLines = 5, modifier = Modifier.fillMaxWidth(0.85f))
                    info?.cast?.let { T("Reparto: $it", size = 13, color = C.Muted, maxLines = 1, modifier = Modifier.fillMaxWidth(0.85f)) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        item {
                            ActionButton(
                                if (resumeMs > 0) "Continuar (${resumeMs / 60000} min)" else "Reproducir", Ic.Play,
                                onClick = { scope.launch { repo.playRequestFor(current, resumeMs)?.let { nav.play(it) } } },
                                primary = true, modifier = Modifier.focusRequester(playFocus),
                            )
                        }
                        if (resumeMs > 0) item {
                            ActionButton("Desde el principio", null, onClick = {
                                scope.launch { repo.playRequestFor(current, 0)?.let { nav.play(it) } }
                            })
                        }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { SecondaryActions(current) { picker = true } } }
                    }
                    FolderChips(current.stableKey)
                }
            }
        }
    }
    if (picker) FolderPicker(current.toRef(), onDismiss = { picker = false })
}

// ============================ Serie ============================

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeriesScreen(key: String, onBack: () -> Unit) {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }

    val item by produceState<ItemEntity?>(null, key) { value = repo.itemByKey(key) }
    var info by remember { mutableStateOf<SeriesInfo?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(item?.streamId) {
        val it = item ?: return@LaunchedEffect
        val acc = repo.account() ?: return@LaunchedEffect
        val res = try { repo.client.seriesInfo(acc, it.streamId) } catch (_: Exception) { null }
        if (res == null) failed = true else {
            info = res
            repo.markSeriesSeen(it.stableKey, res, it)
        }
    }
    val current = item
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T("Cargando…", color = C.Muted) }
        return
    }
    val progress by repo.progressOf(current.stableKey).collectAsState(initial = null)
    val epProgressList by repo.episodeProgress(current.stableKey).collectAsState(initial = emptyList())
    val epProgress = epProgressList.associateBy { it.episodeId }
    var picker by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val episodes = info?.episodes.orEmpty()
    // Siguiente episodio a ver
    val nextIdx = run {
        val lastId = progress?.episodeId ?: return@run 0
        val i = episodes.indexOfFirst { it.id == lastId }
        if (i < 0) 0 else if (epProgress[lastId]?.finished == true) (i + 1).coerceAtMost(episodes.lastIndex.coerceAtLeast(0)) else i
    }
    val nextEp: Episode? = episodes.getOrNull(nextIdx)
    val nextResume = nextEp?.let { epProgress[it.id] }?.takeIf { !it.finished }?.positionMs ?: 0L
    var season by remember(info) { mutableIntStateOf(nextEp?.season ?: info?.seasons?.firstOrNull() ?: 1) }

    LaunchedEffect(info != null) { delay(200); requestFocusSafely(playFocus) }

    fun play(ep: Episode, resume: Long) {
        val i = info ?: return
        repo.episodesRequest(current, i, ep.id, resume)?.let { nav.play(it) }
    }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Box(Modifier.fillMaxWidth().height(460.dp)) { Backdrop(info?.backdrop ?: current.backdrop) }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 60.dp, end = 60.dp, top = 40.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth(0.7f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Ic.Back, null, tint = C.Muted, modifier = Modifier.size(18.dp))
                        T("Atrás para volver", size = 14, color = C.Muted)
                    }
                    val genre = info?.genre ?: current.genre
                    T(listOfNotNull("Serie", genre).joinToString(" · "), size = 14, color = C.TextSoft, maxLines = 1)
                    T(current.name, size = 40, weight = FontWeight.Bold, maxLines = 2, lineHeight = 44)
                    val seasons = info?.seasons?.size ?: 0
                    val meta = listOfNotNull(
                        current.year?.toString() ?: info?.releaseDate?.take(4),
                        if (seasons > 0) (if (seasons == 1) "1 temporada" else "$seasons temporadas") else null,
                        ratingText(info?.rating ?: current.rating),
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) T(meta, size = 15, color = C.Muted)
                    val plot = info?.plot ?: current.plot
                    if (!plot.isNullOrBlank()) T(plot, size = 16, color = C.TextSoft, maxLines = 3)
                }
            }
            item(key = "actions") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    item {
                        val label = when {
                            info == null && !failed -> "Cargando episodios…"
                            nextEp == null -> "Sin episodios"
                            progress == null -> "Empezar · T${nextEp.season} E${nextEp.number}"
                            else -> "Continuar · T${nextEp.season} E${nextEp.number}"
                        }
                        ActionButton(label, Ic.Play, onClick = { nextEp?.let { play(it, nextResume) } }, primary = true, modifier = Modifier.focusRequester(playFocus))
                    }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { SecondaryActions(current) { picker = true } } }
                }
            }
            item(key = "chips") { FolderChips(current.stableKey) }
            if (failed) item(key = "failed") { EmptyState("No se han podido cargar los episodios", "Comprueba la conexión y vuelve a entrar.") }
            val seasons = info?.seasons.orEmpty()
            if (seasons.size > 1) {
                item(key = "seasons") {
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(seasons) { s -> Pill("Temporada $s", s == season, { season = s }) }
                    }
                }
            }
            items(episodes.filter { it.season == season }, key = { it.id }) { ep ->
                val p = epProgress[ep.id]
                EpisodeRow(ep, p?.finished == true, p?.takeIf { !it.finished && it.durationMs > 0 }?.let { it.positionMs.toFloat() / it.durationMs },
                    onClick = { play(ep, p?.takeIf { !it.finished }?.positionMs ?: 0L) })
            }
        }
    }
    if (picker) FolderPicker(current.toRef(), onDismiss = { picker = false })
}

@Composable
private fun EpisodeRow(ep: Episode, watched: Boolean, progress: Float?, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(110.dp),
        radius = 12.dp, container = C.Surface, focusedContainer = C.Raised, focusedScale = 1.01f,
    ) {
        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                Artwork(ep.image, "E${ep.number}", ep.id, Modifier.fillMaxSize(), titleSize = 18)
                if (progress != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x2EFFFFFF))) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(C.Accent))
                    }
                }
                if (watched) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(C.Green),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Ic.Check, "Visto", tint = C.Bg, modifier = Modifier.size(14.dp)) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T("${ep.number}. ${ep.title}", size = 16, weight = FontWeight.SemiBold, maxLines = 1)
                if (!ep.plot.isNullOrBlank()) T(ep.plot, size = 13, color = C.Muted, maxLines = 2)
                durationText(ep.durationSecs)?.let { T(it, size = 12, color = C.Muted) }
            }
        }
    }
}
