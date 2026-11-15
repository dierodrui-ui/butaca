package app.butaca.tv.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.butaca.tv.ButacaApp
import app.butaca.tv.LocalNav
import app.butaca.tv.data.ContentType
import app.butaca.tv.data.ItemEntity
import app.butaca.tv.data.toRef
import app.butaca.tv.ui.C
import app.butaca.tv.ui.Ic
import app.butaca.tv.ui.components.ActionButton
import app.butaca.tv.ui.components.Artwork
import app.butaca.tv.ui.components.Badge
import app.butaca.tv.ui.components.EmptyState
import app.butaca.tv.ui.components.MenuRequest
import app.butaca.tv.ui.components.Pill
import app.butaca.tv.ui.components.PosterCard
import app.butaca.tv.ui.components.Scrim
import app.butaca.tv.ui.components.SectionTitle
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.WideCard
import app.butaca.tv.ui.components.requestFocusSafely
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class HeroExtra(val backdrop: String?, val plot: String?, val genre: String?)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(onGoFolders: () -> Unit) {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()

    val novedades by repo.novedades().collectAsState(initial = emptyList())
    val continueList by repo.continueWatching().collectAsState(initial = emptyList())
    val newEps by repo.newEpisodes().collectAsState(initial = emptyList())
    val watched by repo.watchedKeys().collectAsState(initial = emptySet())
    val recentMovies by repo.recent(ContentType.MOVIE, 30).collectAsState(initial = emptyList())
    val recentSeries by repo.recent(ContentType.SERIES, 30).collectAsState(initial = emptyList())
    val sync by repo.sync.collectAsState()
    val laterId by produceState<Long?>(null) { value = repo.systemFolderId("later") }
    var laterFilter by remember { mutableStateOf("all") }
    val laterFlow = remember(laterId) { laterId?.let { repo.folderContent(it) } }
    val laterAll by (laterFlow ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())
    val later = laterAll.filter { laterFilter == "all" || it.type == laterFilter }

    val empty = novedades.isEmpty() && recentMovies.isEmpty() && recentSeries.isEmpty() && continueList.isEmpty()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        if (novedades.isNotEmpty()) {
            item(key = "hero") { Hero(novedades) }
        }
        if (empty) {
            item(key = "empty") {
                EmptyState(
                    if (sync.running) "Preparando tu catálogo…" else "Tu catálogo está vacío",
                    if (sync.running) "La primera vez tarda un poco: estamos descargando los canales, películas y series de tu servidor."
                    else "Ve a Ajustes › Cuenta y pulsa «Actualizar catálogo ahora».",
                )
            }
        }
        if (continueList.isNotEmpty()) {
            item(key = "continue") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("Seguir viendo")
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(continueList, key = { it.stableKey }) { p ->
                            val left = ((p.durationMs - p.positionMs) / 60000).coerceAtLeast(1)
                            val sub = if (p.type == ContentType.SERIES)
                                listOfNotNull(p.season?.let { "T$it" }, p.episode?.let { "E$it" }).joinToString(" · ").ifBlank { "Serie" }
                            else "Película · quedan $left min"
                            WideCard(
                                title = p.title, subtitle = sub, image = p.icon, keySeed = p.stableKey,
                                progress = if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else null,
                                onClick = {
                                    scope.launch {
                                        val item = repo.bestItem(p.stableKey)
                                        if (item == null) nav.toast("«${p.title}» ya no está en tu servidor")
                                        else if (item.type == ContentType.SERIES) nav.openItem(item)
                                        else repo.playRequestFor(item, p.positionMs)?.let { nav.play(it) }
                                    }
                                },
                                onLongClick = {
                                    scope.launch {
                                        val item = repo.bestItem(p.stableKey)
                                        val ref = item?.toRef() ?: app.butaca.tv.data.ContentRef(p.stableKey, p.type, p.title, p.icon, null, null, null)
                                        nav.showMenu(MenuRequest(ref, inContinue = true))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        if (laterAll.isNotEmpty()) {
            item(key = "later") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Ver más adelante", Modifier.weight(1f), icon = Ic.Clock, extra = "mantén OK para más opciones")
                        Pill("Todo", laterFilter == "all", { laterFilter = "all" }, small = true)
                        Pill("Películas", laterFilter == ContentType.MOVIE, { laterFilter = ContentType.MOVIE }, small = true)
                        Pill("Series", laterFilter == ContentType.SERIES, { laterFilter = ContentType.SERIES }, small = true)
                        Pill("Ver todo", false, onGoFolders, small = true)
                    }
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(later, key = { it.stableKey }) { ref ->
                            PosterCard(
                                title = ref.title, icon = ref.icon, keySeed = ref.stableKey,
                                meta = ref.year?.toString(), isSeries = ref.isSeries, unavailable = ref.itemKey == null,
                                onClick = { nav.openRef(ref) },
                                onLongClick = { nav.showMenu(MenuRequest(ref, folderId = laterId, folderIsSystem = true)) },
                            )
                        }
                    }
                }
            }
        }
        if (newEps.isNotEmpty()) {
            item(key = "neweps") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("Nuevos episodios")
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(newEps, key = { it.stableKey }) { t ->
                            val badge = when {
                                t.newSeason -> "NUEVA TEMPORADA"
                                t.newEpisodes == 1 -> "1 NUEVO"
                                else -> "${t.newEpisodes} NUEVOS"
                            }
                            WideCard(
                                title = t.title, subtitle = "Toca para ver los episodios", image = t.icon, keySeed = t.stableKey, badge = badge,
                                onClick = {
                                    scope.launch {
                                        val item = repo.bestItem(t.stableKey)
                                        if (item == null) nav.toast("La serie ya no está en tu servidor") else nav.openItem(item)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        if (recentMovies.isNotEmpty()) {
            item(key = "recentMovies") { ItemRow("Películas añadidas recientemente", recentMovies, watched) }
        }
        if (recentSeries.isNotEmpty()) {
            item(key = "recentSeries") { ItemRow("Series actualizadas", recentSeries, watched) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ItemRow(title: String, items: List<ItemEntity>, watched: Set<String>) {
    val nav = LocalNav.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle(title)
        LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items, key = { it.key }) { item ->
                PosterCard(
                    title = item.name, icon = item.icon, keySeed = item.stableKey,
                    meta = listOfNotNull(item.year?.toString(), item.rating?.let { "★ " + String.format("%.1f", it).replace('.', ',') }).joinToString(" · ").ifBlank { null },
                    isSeries = item.type == ContentType.SERIES, watched = item.stableKey in watched,
                    onClick = { nav.openItem(item) },
                    onLongClick = { nav.showMenu(MenuRequest(item.toRef())) },
                )
            }
        }
    }
}

fun addedLabel(addedSecs: Long): String {
    if (addedSecs <= 0) return "Novedad"
    val days = ((System.currentTimeMillis() / 1000 - addedSecs) / 86400).toInt()
    return when {
        days <= 0 -> "Añadida hoy"
        days == 1 -> "Añadida ayer"
        days < 7 -> "Hace $days días"
        else -> "Esta semana"
    }
}

/** Carrusel de novedades, al estilo Google TV. */
@Composable
private fun Hero(items: List<ItemEntity>) {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    var focusedBtn by remember { mutableIntStateOf(0) }
    val extras = remember { mutableStateMapOf<String, HeroExtra>() }
    val firstBtn = remember { FocusRequester() }
    val laterId by produceState<Long?>(null) { value = repo.systemFolderId("later") }

    val safeIndex = if (items.isEmpty()) 0 else index % items.size
    val item = items[safeIndex]
    val inFolders by repo.foldersOf(item.stableKey).collectAsState(initial = emptySet())
    val lid = laterId
    val inLater = lid != null && lid in inFolders

    LaunchedEffect(Unit) { delay(300); requestFocusSafely(firstBtn) }

    // Pasa sola cada 8 segundos mientras no estés encima
    LaunchedEffect(items.size, focused) {
        if (!focused && items.size > 1) while (true) {
            delay(8000)
            index = (index + 1) % items.size
        }
    }

    // Fondo y sinopsis: las películas los piden aparte al servidor
    LaunchedEffect(item.key) {
        if (extras[item.key] != null) return@LaunchedEffect
        val acc = repo.account() ?: return@LaunchedEffect
        val extra = try {
            if (item.type == ContentType.MOVIE) repo.client.vodInfo(acc, item.streamId)?.let { HeroExtra(it.backdrop, it.plot, it.genre) }
            else HeroExtra(item.backdrop, item.plot, item.genre)
        } catch (_: Exception) { null }
        extras[item.key] = extra ?: HeroExtra(item.backdrop, item.plot, item.genre)
    }
    val extra = extras[item.key]
    val backdrop = extra?.backdrop ?: item.backdrop

    Box(
        Modifier.fillMaxWidth().height(330.dp).clip(RoundedCornerShape(18.dp)).background(C.toneFor(item.stableKey))
    ) {
        if (backdrop != null) {
            AsyncImage(backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 56.dp).width(170.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
                Artwork(item.icon, item.name, item.stableKey, Modifier.fillMaxSize())
            }
        }
        Scrim(Modifier.fillMaxSize())

        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 40.dp, end = 40.dp).fillMaxWidth(0.58f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Badge("NUEVO EN TU SERVIDOR", accent = true)
                val kind = if (item.type == ContentType.SERIES) "Serie" else "Película"
                T("${addedLabel(item.added)} · $kind", size = 13, weight = FontWeight.SemiBold, color = Color(0xFFE3E7ED))
            }
            T(item.name, size = 40, weight = FontWeight.Bold, maxLines = 2, lineHeight = 44, color = Color.White)
            val meta = listOfNotNull(
                item.year?.toString(),
                (extra?.genre ?: item.genre)?.split(',')?.take(2)?.joinToString(", ") { it.trim() },
                item.rating?.let { "★ " + String.format("%.1f", it).replace('.', ',') },
            ).joinToString(" · ")
            if (meta.isNotBlank()) T(meta, size = 15, color = Color(0xFFE3E7ED))
            val plot = extra?.plot ?: item.plot
            if (!plot.isNullOrBlank()) T(plot, size = 15, color = Color(0xFFD5DAE2), maxLines = 2)
            Row(
                Modifier
                    .padding(top = 6.dp)
                    .onFocusChanged { focused = it.hasFocus }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown || items.size < 2) return@onPreviewKeyEvent false
                        when {
                            e.key == Key.DirectionRight && focusedBtn == 1 -> { index = (safeIndex + 1) % items.size; true }
                            e.key == Key.DirectionLeft && focusedBtn == 0 && safeIndex > 0 -> { index = safeIndex - 1; true }
                            else -> false
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionButton(
                    if (item.type == ContentType.SERIES) "Ver serie" else "Reproducir", Ic.Play,
                    onClick = {
                        if (item.type == ContentType.SERIES) nav.openItem(item)
                        else scope.launch { repo.playRequestFor(item)?.let { nav.play(it) } }
                    },
                    primary = true,
                    modifier = Modifier.focusRequester(firstBtn).onFocusChanged { if (it.isFocused) focusedBtn = 0 },
                )
                ActionButton(
                    if (inLater) "En «Ver más adelante»" else "Ver más adelante",
                    if (inLater) Ic.Check else Ic.Clock,
                    onClick = { val id = laterId ?: return@ActionButton; scope.launch { repo.toggleFolder(id, item.toRef(), inLater) } },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) focusedBtn = 1 },
                )
            }
        }

        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 30.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.indices.forEach { i ->
                Box(
                    Modifier.height(8.dp).width(if (i == safeIndex) 24.dp else 8.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (i == safeIndex) C.Accent else Color(0x73F2F4F7))
                )
            }
        }
        if (items.size > 1) {
            T("◀ ▶ para ver más novedades", size = 12, color = Color(0xB3F2F4F7), modifier = Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 16.dp))
        }
    }
}
