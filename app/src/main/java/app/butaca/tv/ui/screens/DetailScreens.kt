package app.butaca.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
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
import app.butaca.tv.data.Related
import app.butaca.tv.data.TagKind
import app.butaca.tv.data.TmdbTitle
import app.butaca.tv.data.TmdbPerson
import app.butaca.tv.data.TmdbEpisode
import app.butaca.tv.data.setAlert
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import app.butaca.tv.data.RelatedRow
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
            active = if (inLater) C.Gold else null,
        )
    }
    ActionButton("Añadir a carpeta", Ic.FolderPlus, onClick = onPicker)
    if (favId != null) {
        ActionButton(
            if (inFav) "En favoritos" else "Favorito", if (inFav) Ic.HeartFilled else Ic.Heart,
            onClick = { scope.launch { repo.toggleFolder(favId, ref, inFav) } },
            active = if (inFav) C.Accent else null,
        )
    }
    var rating by remember { mutableStateOf(false) }
    ActionButton(
        if (watched) "Vista" else "Marcar vista", Ic.Check, active = if (watched) C.Green else null,
        onClick = {
            scope.launch { repo.markWatched(ref, !watched) }
            if (!watched) rating = true   // y, ya que la has visto, ¿qué te pareció?
        },
    )
    if (rating) app.butaca.tv.ui.components.RatingDialog(ref, onDismiss = { rating = false })
}

/** Géneros, estudio y temas de TMDB. Al pulsar uno, todo lo que tienes de esa etiqueta. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(related: Related?) {
    val tags = related?.tagObjs.orEmpty()
    if (tags.isEmpty()) return
    val nav = LocalNav.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { t ->
            val (bg, fg, label) = when (t.kind) {
                TagKind.GENRE -> Triple(Color(0x33E8344B), C.AccentSoft, t.name)
                TagKind.COMPANY, TagKind.NETWORK -> Triple(Color(0x337FA8FF), Color(0xFF9DBBFF), t.name)
                TagKind.KEYWORD -> Triple(Color(0x22FFFFFF), C.TextSoft, "#" + t.name)
            }
            FocusSurface(
                onClick = { nav.openExplore(listOf(t)) },
                modifier = Modifier.height(28.dp),
                radius = 999.dp, container = bg, focusedContainer = bg, content = fg, focusedContent = fg,
                focusedScale = 1.08f, borderWidth = 2.dp,
            ) {
                T(label, size = 12, weight = FontWeight.SemiBold, color = fg, maxLines = 1, modifier = Modifier.align(Alignment.Center).padding(horizontal = 10.dp))
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
    related.forEachIndexed { i, row -> posterRow("rel_$i", row.title, row.items, row.missing) }
    if (related.isEmpty()) posterRow("similar", "Del mismo estilo", similar)
}

private fun LazyListScope.posterRow(key: String, title: String, similar: List<ItemEntity>, missing: List<TmdbTitle> = emptyList()) {
    if (similar.isEmpty() && missing.isEmpty()) return
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
                // De la saga, lo que no tienes: en gris; al pulsar, te avisamos cuando llegue
                items(missing, key = { "miss_${it.id}" }) { t ->
                    val scope = rememberCoroutineScope()
                    PosterCard(
                        title = t.title, icon = t.poster, keySeed = "m${t.id}", width = 112.dp,
                        meta = listOfNotNull(t.year?.toString(), "No disponible").joinToString(" · "), unavailable = true,
                        onClick = {
                            scope.launch { repo.setAlert(t, true) }
                            nav.toast("«${t.title}» no está en tu servidor. Te avisaremos si llega.")
                        },
                    )
                }
            }
        }
    }
}

/** Reparto y dirección: al pulsar, la página de la persona. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleChips(people: List<TmdbPerson>) {
    if (people.isEmpty()) return
    val nav = LocalNav.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T("DIRECCIÓN Y REPARTO", size = 11, weight = FontWeight.SemiBold, color = C.Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            people.take(8).forEach { p ->
                FocusSurface(
                    onClick = { nav.openPerson(p.id) },
                    modifier = Modifier.height(34.dp),
                    radius = 999.dp, container = C.Surface3, focusedScale = 1.06f,
                ) {
                    Row(
                        Modifier.align(Alignment.Center).padding(start = 4.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(C.Raised)) {
                            if (p.photo != null) AsyncImage(p.photo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        T(p.name, size = 13, maxLines = 1)
                        p.role?.let { T(it, size = 11, color = C.Muted, maxLines = 1) }
                    }
                }
            }
        }
    }
}

private fun endsAt(minutes: Int): String {
    val c = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, minutes) }
    return String.format("termina a las %02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

private fun certText(c: Int?) = c?.let { if (it == 0) "TP" else "+$it" }

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

    // La cabecera (imagen, logo, categorías y descripción) queda fija; debajo se desplaza el resto
    var inList by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Backdrop(info?.backdrop ?: current.backdrop, Modifier.fillMaxWidth().height(520.dp))
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 26.dp)) {
            Row(Modifier.animateContentSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Box(Modifier.width(if (inList) 112.dp else 180.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
                    Artwork(info?.poster ?: current.icon, current.name, current.stableKey, Modifier.fillMaxSize(), titleSize = 20)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    val genreText = related?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: genre
                    T(listOfNotNull("Película", genreText).joinToString(" · "), size = 13, color = C.TextSoft, maxLines = 1)
                    val logo = related?.logo
                    if (logo != null) AsyncImage(
                        logo, current.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                        modifier = Modifier.heightIn(max = if (inList) 56.dp else 84.dp).widthIn(max = 420.dp),
                    ) else T(current.name, size = if (inList) 26 else 32, weight = FontWeight.Bold, maxLines = if (inList) 1 else 2, lineHeight = 36)
                    val meta = listOfNotNull(
                        current.year?.toString() ?: info?.releaseDate?.take(4),
                        (related?.runtime?.let { durationText(it * 60L) } ?: durationText(info?.durationSecs ?: 0)),
                        (related?.runtime ?: ((info?.durationSecs ?: 0) / 60).toInt().takeIf { it > 0 })?.let { endsAt(it) },
                        ratingText(info?.rating ?: current.rating),
                        certText(related?.cert ?: current.cert),
                    ).joinToString(" · ")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        app.butaca.tv.ui.components.MatchLabel(app.butaca.tv.ui.components.LocalTaste.current?.match(related?.genres?.joinToString(",") ?: current.genre, current.rating ?: info?.rating, related?.tmdbId ?: current.tmdbId))
                        if (meta.isNotBlank()) T(meta, size = 14, color = C.Muted)
                    }
                    TagChips(related)
                    val plot = info?.plot ?: current.plot
                    if (!plot.isNullOrBlank()) T(plot, size = 14, color = C.TextSoft, maxLines = if (inList) 2 else 4, lineHeight = 20, modifier = Modifier.fillMaxWidth(0.92f))
                    if (!inList) {
                        info?.director?.let { T("Dirección: $it", size = 12, color = C.Muted, maxLines = 1) }
                        info?.cast?.let { T("Reparto: $it", size = 12, color = C.Muted, maxLines = 1, modifier = Modifier.fillMaxWidth(0.9f)) }
                    }
                    FlowRow(
                        Modifier.padding(top = 4.dp),
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
                        related?.trailer?.let { k -> ActionButton("Tráiler", Ic.Film, onClick = { nav.openTrailer(k) }) }
                        app.butaca.tv.ui.components.RateButton(current.toRef())
                        SecondaryActions(current) { picker = true }
                    }
                }
            }
            PivotScope(pivot = 12.dp) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp).onFocusChanged { inList = it.hasFocus },
                    contentPadding = PaddingValues(bottom = 50.dp, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "chips") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            FolderChips(current.stableKey)
                            PeopleChips(related?.people.orEmpty())
                        }
                    }
                    relatedRows(related?.rows, similar)
                }
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
    val tmdbEpisodes by produceState(emptyMap<Int, TmdbEpisode>(), related?.tmdbId, season) {
        val id = related?.tmdbId ?: return@produceState
        value = try { repo.tmdb.season(id, season).associateBy { it.number } } catch (_: Exception) { emptyMap() }
    }

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
                val logo = related?.logo
                if (logo != null) AsyncImage(
                    logo, current.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                    modifier = Modifier.heightIn(max = if (inList) 52.dp else 80.dp).widthIn(max = 400.dp),
                ) else T(current.name, size = if (inList) 24 else 32, weight = FontWeight.Bold, maxLines = if (inList) 1 else 2, lineHeight = if (inList) 28 else 36)
                val seasons = info?.seasons?.size ?: 0
                val meta = listOfNotNull(
                    current.year?.toString() ?: info?.releaseDate?.take(4),
                    if (seasons > 0) (if (seasons == 1) "1 temporada" else "$seasons temporadas") else null,
                    ratingText(info?.rating ?: current.rating),
                    certText(related?.cert ?: current.cert),
                ).joinToString(" · ")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    app.butaca.tv.ui.components.MatchLabel(app.butaca.tv.ui.components.LocalTaste.current?.match(related?.genres?.joinToString(",") ?: current.genre, current.rating ?: info?.rating, related?.tmdbId ?: current.tmdbId))
                    if (meta.isNotBlank()) T(meta, size = 14, color = C.Muted)
                }
                // Estado de la serie según TMDB
                related?.tv?.let { tv ->
                    val state = when (tv.status) {
                        "Returning Series" -> "En emisión"
                        "Ended" -> "Finalizada"
                        "Canceled" -> "Cancelada"
                        "In Production" -> "En producción"
                        else -> null
                    }
                    val next = tv.nextAirDate?.let { d -> "próximo episodio el ${formatDate(d)}" + (if (tv.nextSeason != null) " (T${tv.nextSeason} E${tv.nextEpisode})" else "") }
                    val missing = if (tv.seasons > seasons && seasons > 0) "tu servidor tiene $seasons de ${tv.seasons} temporadas" else null
                    val line = listOfNotNull(state, next, missing).joinToString(" · ")
                    if (line.isNotBlank()) T(line.replaceFirstChar { it.uppercase() }, size = 13, weight = FontWeight.SemiBold, color = if (missing != null) C.Accent else C.Green)
                }
                TagChips(related)
                val plot = info?.plot ?: current.plot
                if (!plot.isNullOrBlank()) T(plot, size = 14, color = C.TextSoft, maxLines = if (inList) 2 else 3, lineHeight = 20)
                if (!inList) info?.cast?.let { T("Reparto: $it", size = 12, color = C.Muted, maxLines = 1) }
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
                related?.trailer?.let { k -> ActionButton("Tráiler", Ic.Film, onClick = { nav.openTrailer(k) }) }
                app.butaca.tv.ui.components.RateButton(current.toRef())
                SecondaryActions(current) { picker = true }
            }
            if (!inList) Box(Modifier.padding(top = 12.dp)) { FolderChips(current.stableKey) }
            if (!inList) Box(Modifier.padding(top = 10.dp)) { PeopleChips(related?.people.orEmpty().take(6)) }

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
                    items(episodes.filter { it.season == season }, key = { it.id }) { ep0 ->
                        // Si el servidor no trae sinopsis o imagen del episodio, la ponemos desde TMDB
                        val t = tmdbEpisodes[ep0.number]
                        val ep = ep0.copy(
                            plot = ep0.plot ?: t?.overview,
                            image = ep0.image ?: t?.still,
                            title = if (t?.name != null && Regex("(?i)(episod|cap[ií]tulo|s\\d+e\\d+|^\\d+$)").containsMatchIn(ep0.title)) t.name else ep0.title,
                        )
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
