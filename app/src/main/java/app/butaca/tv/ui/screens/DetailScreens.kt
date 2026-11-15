package app.butaca.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import app.butaca.tv.data.RelatedRow
import app.butaca.tv.data.Related
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
import app.butaca.tv.ui.components.MenuRequest
import app.butaca.tv.ui.components.Pill
import app.butaca.tv.ui.components.PivotScope
import app.butaca.tv.ui.components.PosterCard
import app.butaca.tv.ui.components.Scrim
import app.butaca.tv.ui.components.SectionTitle
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.TvRow
import app.butaca.tv.ui.components.itemMeta
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
private fun Backdrop(url: String?, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Scrim(Modifier.fillMaxSize())
        BottomFade(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(220.dp))
    }
}

/** Carpetas en las que está el título; al pulsar una, te lleva a ella. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderChips(stableKey: String) {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    val folders by remember { repo.folders() }.collectAsState(initial = emptyList())
    val inFolders by remember(stableKey) { repo.foldersOf(stableKey) }.collectAsState(initial = emptySet())
    val mine = folders.filter { it.id in inFolders }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T("EN TUS CARPETAS", size = 11, weight = FontWeight.SemiBold, color = C.Muted)
        if (mine.isEmpty()) T("Todavía no está en ninguna carpeta.", size = 13, color = C.Muted)
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            mine.forEach { f ->
                FocusSurface(
                    onClick = { nav.openFolder(f.id) },
                    modifier = Modifier.height(34.dp),
                    radius = 999.dp, container = C.Surface3, focusedScale = 1.06f,
                ) {
                    Row(
                        Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        val icon = when (f.system) { "later" -> Ic.Clock; "fav" -> Ic.Heart; else -> Ic.Folder }
                        Icon(icon, null, tint = if (f.system == null) Color(f.color) else C.Accent, modifier = Modifier.size(14.dp))
                        T(f.name, size = 13, maxLines = 1)
                        Icon(Ic.Chevron, null, tint = C.Muted, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
    }
}

/** Botones comunes: ver más adelante, carpeta, favorito, vista. Todos a la vista. */
@Composable
private fun SecondaryActions(item: ItemEntity, onPicker: () -> Unit) {
    val repo = ButacaApp.repo
    val scope = rememberCoroutineScope()
    val folders by remember { repo.folders() }.collectAsState(initial = emptyList())
    val inFolders by remember(item.stableKey) { repo.foldersOf(item.stableKey) }.collectAsState(initial = emptySet())
    val progress by remember(item.stableKey) { repo.progressOf(item.stableKey) }.collectAsState(initial = null)
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
        ActionButton(
            if (inFav) "En favoritos" else "Favorito", if (inFav) Ic.HeartFilled else Ic.Heart,
            onClick = { scope.launch { repo.toggleFolder(favId, ref, inFav) } },
        )
    }
    ActionButton(
        if (watched) "Vista" else "Marcar vista", Ic.Check,
        onClick = { scope.launch { repo.markWatched(ref, !watched) } },
    )
}

/** Géneros y etiquetas de TMDB, para ver de un vistazo de qué va. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(related: Related?) {
    val genres = related?.genres.orEmpty()
    val tags = related?.tags.orEmpty()
    if (genres.isEmpty() && tags.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        genres.forEach { g ->
            Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0x33FFB020)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                T(g, size = 12, weight = FontWeight.SemiBold, color = C.Accent)
            }
        }
        tags.forEach { t ->
            Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0x22FFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                T("#$t", size = 12, color = C.TextSoft)
            }
        }
    }
}

/** Filas de relacionados (TMDB). Si TMDB no responde, al menos los del mismo género según tu servidor. */
private fun LazyListScope.relatedRows(related: List<RelatedRow>?, similar: List<ItemEntity>) {
    if (related == null) {
        item(key = "rel_loading") { T("Buscando títulos relacionados…", size = 13, color = C.Muted, modifier = Modifier.padding(top = 12.dp, start = 6.dp)) }
        return
    }
    related.forEachIndexed { i, row -> posterRow("rel_$i", row.title, row.items) }
    if (related.isEmpty()) posterRow("similar", "Del mismo estilo", similar)
}

private fun LazyListScope.posterRow(key: String, title: String, similar: List<ItemEntity>) {
    if (similar.isEmpty()) return
    item(key = key) {
        val nav = LocalNav.current
        val repo = ButacaApp.repo
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
            SectionTitle(title, Modifier.padding(start = 6.dp))
            TvRow {
                items(similar, key = { it.key }) { s ->
                    LaunchedEffect(s.key) { repo.requestGenre(s) }
                    PosterCard(
                        title = s.name, icon = s.icon, keySeed = s.stableKey, width = 112.dp,
                        meta = itemMeta(s), isSeries = s.type == ContentType.SERIES,
                        onClick = { nav.openItem(s) },
                        onLongClick = { nav.showMenu(MenuRequest(s.toRef())) },
                    )
                }
            }
        }
    }
}

// ============================ Película ============================

@OptIn(ExperimentalLayoutApi::class)
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
        value?.let { v -> repo.saveVodInfo(it, v) }
    }
    val current = item
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T("Cargando…", color = C.Muted) }
        return
    }
    val progress by remember(current.stableKey) { repo.progressOf(current.stableKey) }.collectAsState(initial = null)
    val genre = info?.genre ?: current.genre
    val similar by produceState(emptyList<ItemEntity>(), current.key, genre) { value = repo.similar(current, genre) }
    val related by produceState<Related?>(null, current.key) {
        val tid = withTimeoutOrNull(4000) { snapshotFlow { info }.filterNotNull().first() }?.tmdbId
        value = repo.related(current, tid)
    }
    var picker by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); requestFocusSafely(playFocus) }

    val resumeMs = progress?.takeIf { !it.finished && it.positionMs > 60_000 }?.positionMs ?: 0L

    var headerFocused by remember { mutableStateOf(true) }
    val movieList = rememberLazyListState()
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Backdrop(info?.backdrop ?: current.backdrop, Modifier.fillMaxWidth().height(520.dp))
        PivotScope(pivot = 80.dp, enabled = { !headerFocused }) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = movieList,
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 30.dp, bottom = 60.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item(key = "back") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Ic.Back, null, tint = C.Muted, modifier = Modifier.size(16.dp))
                        T("Atrás para volver", size = 13, color = C.Muted)
                    }
                }
                item(key = "header") {
                    Row(
                        Modifier.onFocusChanged {
                            headerFocused = it.hasFocus
                            if (it.hasFocus) scope.launch { movieList.animateScrollToItem(0) }
                        },
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        Box(Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
                            Artwork(info?.poster ?: current.icon, current.name, current.stableKey, Modifier.fillMaxSize(), titleSize = 20)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            val genreText = related?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: genre
                            T(listOfNotNull("Película", genreText).joinToString(" · "), size = 13, color = C.TextSoft, maxLines = 1)
                            T(current.name, size = 32, weight = FontWeight.Bold, maxLines = 2, lineHeight = 36)
                            val meta = listOfNotNull(
                                current.year?.toString() ?: info?.releaseDate?.take(4),
                                durationText(info?.durationSecs ?: 0),
                                ratingText(info?.rating ?: current.rating),
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) T(meta, size = 14, color = C.Muted)
                            TagChips(related)
                            val plot = info?.plot ?: current.plot
                            if (!plot.isNullOrBlank()) T(plot, size = 14, color = C.TextSoft, maxLines = 4, lineHeight = 20, modifier = Modifier.fillMaxWidth(0.9f))
                            info?.director?.let { T("Dirección: $it", size = 12, color = C.Muted, maxLines = 1) }
                            info?.cast?.let { T("Reparto: $it", size = 12, color = C.Muted, maxLines = 1, modifier = Modifier.fillMaxWidth(0.9f)) }
                            FlowRow(
                                Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                ActionButton(
                                    if (resumeMs > 0) "Continuar · min ${resumeMs / 60000}" else "Reproducir", Ic.Play,
                                    onClick = { scope.launch { repo.playRequestFor(current, resumeMs)?.let { nav.play(it) } } },
                                    primary = true, modifier = Modifier.focusRequester(playFocus),
                                )
                                if (resumeMs > 0) {
                                    ActionButton("Desde el principio", Ic.Refresh, onClick = {
                                        scope.launch { repo.playRequestFor(current, 0)?.let { nav.play(it) } }
                                    })
                                }
                                SecondaryActions(current) { picker = true }
                            }
                            Box(Modifier.padding(top = 6.dp)) { FolderChips(current.stableKey) }
                        }
                    }
                }
                relatedRows(related?.rows, similar)
            }
        }
    }
    if (picker) FolderPicker(current.toRef(), onDismiss = { picker = false })
}

// ============================ Serie ============================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeriesScreen(key: String, onBack: () -> Unit) {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
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
    val progress by remember(current.stableKey) { repo.progressOf(current.stableKey) }.collectAsState(initial = null)
    val epProgressList by remember(current.stableKey) { repo.episodeProgress(current.stableKey) }.collectAsState(initial = emptyList())
    val epProgress = epProgressList.associateBy { it.episodeId }
    val genre = info?.genre ?: current.genre
    val similar by produceState(emptyList<ItemEntity>(), current.key, genre) { value = repo.similar(current, genre) }
    val related by produceState<Related?>(null, current.key) {
        val tid = withTimeoutOrNull(5000) { snapshotFlow { info }.filterNotNull().first() }?.tmdbId
        value = repo.related(current, tid)
    }
    var picker by remember { mutableStateOf(false) }
    var inList by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val episodes = info?.episodes.orEmpty()
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
        Backdrop(info?.backdrop ?: current.backdrop, Modifier.fillMaxWidth().height(440.dp))
        // La cabecera queda fija arriba; solo se desplaza la lista de episodios
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 26.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth(0.72f)) {
                val genreText = related?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: genre
                T(listOfNotNull("Serie", genreText).joinToString(" · "), size = 13, color = C.TextSoft, maxLines = 1)
                T(current.name, size = if (inList) 24 else 32, weight = FontWeight.Bold, maxLines = if (inList) 1 else 2, lineHeight = if (inList) 28 else 36)
                val seasons = info?.seasons?.size ?: 0
                val meta = listOfNotNull(
                    current.year?.toString() ?: info?.releaseDate?.take(4),
                    if (seasons > 0) (if (seasons == 1) "1 temporada" else "$seasons temporadas") else null,
                    ratingText(info?.rating ?: current.rating),
                ).joinToString(" · ")
                if (meta.isNotBlank()) T(meta, size = 14, color = C.Muted)
                if (!inList) TagChips(related)
                if (!inList) {
                    val plot = info?.plot ?: current.plot
                    if (!plot.isNullOrBlank()) T(plot, size = 14, color = C.TextSoft, maxLines = 3, lineHeight = 20)
                    info?.cast?.let { T("Reparto: $it", size = 12, color = C.Muted, maxLines = 1) }
                }
            }
            FlowRow(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val label = when {
                    info == null && !failed -> "Cargando episodios…"
                    nextEp == null -> "Sin episodios"
                    progress == null -> "Empezar · T${nextEp.season} E${nextEp.number}"
                    else -> "Continuar · T${nextEp.season} E${nextEp.number}"
                }
                ActionButton(label, Ic.Play, onClick = { nextEp?.let { play(it, nextResume) } }, primary = true, modifier = Modifier.focusRequester(playFocus))
                SecondaryActions(current) { picker = true }
            }
            if (!inList) Box(Modifier.padding(top = 12.dp)) { FolderChips(current.stableKey) }

            PivotScope(pivot = 12.dp) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp)
                        .onFocusChanged { inList = it.hasFocus },
                    state = listState,
                    contentPadding = PaddingValues(bottom = 40.dp, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (failed) item(key = "failed") { EmptyState("No se han podido cargar los episodios", "Comprueba la conexión y vuelve a entrar.") }
                    val seasons = info?.seasons.orEmpty()
                    if (seasons.size > 1) {
                        item(key = "seasons") {
                            TvRow(gap = 8.dp) {
                                items(seasons) { s -> Pill("Temporada $s", s == season, { season = s }, small = true) }
                            }
                        }
                    }
                    items(episodes.filter { it.season == season }, key = { it.id }) { ep ->
                        val p = epProgress[ep.id]
                        EpisodeRow(ep, p?.finished == true, p?.takeIf { !it.finished && it.durationMs > 0 }?.let { it.positionMs.toFloat() / it.durationMs },
                            onClick = { play(ep, p?.takeIf { !it.finished }?.positionMs ?: 0L) })
                    }
                    relatedRows(related?.rows, similar)
                }
            }
        }
    }
    if (picker) FolderPicker(current.toRef(), onDismiss = { picker = false })
}

@Composable
private fun EpisodeRow(ep: Episode, watched: Boolean, progress: Float?, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(0.9f).height(82.dp),
        radius = 12.dp, container = C.Surface, focusedContainer = C.Raised, focusedScale = 1.01f,
    ) {
        Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                Artwork(ep.image, "E${ep.number}", ep.id, Modifier.fillMaxSize(), titleSize = 16)
                if (progress != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x2EFFFFFF))) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(C.Accent))
                    }
                }
                if (watched) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape).background(C.Green),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Ic.Check, "Visto", tint = C.Bg, modifier = Modifier.size(13.dp)) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                T("${ep.number}. ${ep.title}", size = 15, weight = FontWeight.SemiBold, maxLines = 1)
                if (!ep.plot.isNullOrBlank()) T(ep.plot, size = 12, color = C.Muted, maxLines = 2)
                durationText(ep.durationSecs)?.let { T(it, size = 11, color = C.Muted) }
            }
        }
    }
}
