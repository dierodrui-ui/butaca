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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import app.butaca.tv.data.heroExtra
import app.butaca.tv.data.homeRows
import app.butaca.tv.data.upcomingNotInServer
import app.butaca.tv.data.alerts
import app.butaca.tv.data.alertItem
import app.butaca.tv.data.setAlert
import app.butaca.tv.data.removeAlert
import app.butaca.tv.data.RelatedRow
import app.butaca.tv.data.TmdbTitle
import app.butaca.tv.data.AlertEntity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
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
import app.butaca.tv.data.ContentRef
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
import app.butaca.tv.ui.components.PivotScope
import app.butaca.tv.ui.components.PosterCard
import app.butaca.tv.ui.components.SectionTitle
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.TopFade
import app.butaca.tv.ui.components.TvRow
import app.butaca.tv.ui.components.WideCard
import app.butaca.tv.ui.components.itemMeta
import app.butaca.tv.ui.components.requestFocusSafely
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private data class HeroData(
    val backdrop: String?, val plot: String?, val genre: String?,
    val logo: String? = null, val trailer: String? = null,
)

private val SidePad = 36.dp

@Composable
fun HomeScreen(onGoFolders: () -> Unit) {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val novedades by remember() { repo.novedades() }.collectAsState(initial = emptyList())
    val continueList by remember() { repo.continueWatching() }.collectAsState(initial = emptyList())
    val newEps by remember() { repo.newEpisodes() }.collectAsState(initial = emptyList())
    val watched by remember() { repo.watchedKeys() }.collectAsState(initial = emptySet())
    val recentMovies by remember { repo.recent(ContentType.MOVIE, 30) }.collectAsState(initial = emptyList())
    val recentSeries by remember { repo.recent(ContentType.SERIES, 30) }.collectAsState(initial = emptyList())
    val sync by repo.sync.collectAsState()
    val laterId by produceState<Long?>(null) { value = repo.systemFolderId("later") }
    var laterFilter by remember { mutableStateOf("all") }
    val laterFlow = remember(laterId) { laterId?.let { repo.folderContent(it) } ?: flowOf(emptyList()) }
    val laterAll by laterFlow.collectAsState(initial = emptyList())
    val later = laterAll.filter { laterFilter == "all" || it.type == laterFilter }
    var heroFocused by remember { mutableStateOf(false) }
    val settings by repo.prefs.flow.collectAsState()
    // Filas de TMDB: «Porque viste…», tendencias, imprescindibles, de temporada…
    val discovery by produceState(emptyList<RelatedRow>(), settings.tmdbKey, repo.kidsLimit) { value = repo.homeRows() }
    val upcoming by produceState(emptyList<TmdbTitle>(), settings.tmdbKey, repo.kidsLimit) { value = repo.upcomingNotInServer() }
    val alerts by remember { repo.alerts() }.collectAsState(initial = emptyList())
    val alertKeys = alerts.map { it.key }.toSet()
    val ready by produceState(emptyList<Pair<AlertEntity, ItemEntity>>(), alerts) {
        value = alerts.filter { it.available }.mapNotNull { a -> repo.alertItem(a)?.let { a to it } }
    }

    val empty = novedades.isEmpty() && recentMovies.isEmpty() && recentSeries.isEmpty() && continueList.isEmpty()

    Box(Modifier.fillMaxSize()) {
        PivotScope(pivot = 58.dp, enabled = { !heroFocused }) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = 90.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                if (novedades.isNotEmpty()) {
                    item(key = "hero") {
                        Hero(novedades, onFocus = { f ->
                            heroFocused = f
                            if (f) scope.launch { listState.animateScrollToItem(0) }
                        })
                    }
                } else {
                    item(key = "top") { Box(Modifier.height(16.dp)) }
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
                        Section("Seguir viendo") {
                            TvRow {
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
                                                val ref = item?.toRef() ?: ContentRef(p.stableKey, p.type, p.title, p.icon, null, null, null)
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
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = SidePad),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SectionTitle("Ver más adelante", Modifier.weight(1f), icon = Ic.Clock, extra = "mantén OK para más opciones")
                                Pill("Todo", laterFilter == "all", { laterFilter = "all" }, small = true)
                                Pill("Películas", laterFilter == ContentType.MOVIE, { laterFilter = ContentType.MOVIE }, small = true)
                                Pill("Series", laterFilter == ContentType.SERIES, { laterFilter = ContentType.SERIES }, small = true)
                                Pill("Ver todo", false, onGoFolders, small = true)
                            }
                            Box(Modifier.padding(start = SidePad - 6.dp)) {
                                TvRow {
                                    items(later, key = { it.stableKey }) { ref ->
                                        PosterCard(
                                            title = ref.title, icon = ref.icon, keySeed = ref.stableKey,
                                            meta = itemMeta(null, ref.year, ref.rating), isSeries = ref.isSeries, unavailable = ref.itemKey == null,
                                            onClick = { nav.openRef(ref) },
                                            onLongClick = { nav.showMenu(MenuRequest(ref, folderId = laterId, folderIsSystem = true)) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (newEps.isNotEmpty()) {
                    item(key = "neweps") {
                        Section("Nuevos episodios") {
                            TvRow {
                                items(newEps, key = { it.stableKey }) { t ->
                                    val badge = when {
                                        t.newSeason -> "NUEVA TEMPORADA"
                                        t.newEpisodes == 1 -> "1 NUEVO"
                                        else -> "${t.newEpisodes} NUEVOS"
                                    }
                                    WideCard(
                                        title = t.title, subtitle = "Ver los episodios", image = t.icon, keySeed = t.stableKey, badge = badge,
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
                if (ready.isNotEmpty()) {
                    item(key = "ready") {
                        Section("¡Ya en tu servidor! · lo que esperabas") {
                            TvRow {
                                items(ready, key = { it.first.key }) { (a, item) ->
                                    PosterCard(
                                        title = item.name, icon = item.icon, keySeed = item.stableKey, meta = "Ha llegado",
                                        isSeries = item.type == ContentType.SERIES,
                                        onClick = { scope.launch { repo.removeAlert(a) }; nav.openItem(item) },
                                        onLongClick = { nav.showMenu(MenuRequest(item.toRef())) },
                                    )
                                }
                            }
                        }
                    }
                }
                discovery.forEachIndexed { i, row ->
                    item(key = "disc_$i") { ItemRow(row.title, row.items, watched) }
                }
                if (recentMovies.isNotEmpty()) {
                    item(key = "recentMovies") { ItemRow("Películas añadidas recientemente", recentMovies, watched) }
                }
                if (recentSeries.isNotEmpty()) {
                    item(key = "recentSeries") { ItemRow("Series actualizadas", recentSeries, watched) }
                }
                if (upcoming.isNotEmpty()) {
                    item(key = "upcoming") {
                        Section("Próximamente en cines · pulsa OK para que te avisemos cuando llegue") {
                            TvRow {
                                items(upcoming, key = { "u${it.id}" }) { t ->
                                    val key = "m${t.id}"
                                    val on = key in alertKeys
                                    PosterCard(
                                        title = t.title, icon = t.poster, keySeed = key,
                                        meta = if (on) "Te avisaremos" else (t.date?.let { formatDate(it) } ?: "Próximamente"),
                                        highlighted = on,
                                        onClick = {
                                            scope.launch { repo.setAlert(t, !on) }
                                            nav.toast(if (on) "Aviso quitado" else "Te avisaremos cuando «${t.title}» llegue a tu servidor")
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            TopFade(Modifier.align(Alignment.TopCenter), height = 40.dp)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(title, Modifier.padding(horizontal = SidePad))
        Box(Modifier.padding(start = SidePad - 6.dp)) { content() }
    }
}

@Composable
private fun ItemRow(title: String, items: List<ItemEntity>, watched: Set<String>) {
    val nav = LocalNav.current
    val repo = ButacaApp.repo
    Section(title) {
        TvRow {
            items(items, key = { it.key }) { item ->
                LaunchedEffect(item.key) { repo.requestGenre(item) }
                PosterCard(
                    title = item.name, icon = item.icon, keySeed = item.stableKey,
                    meta = itemMeta(item),
                    isSeries = item.type == ContentType.SERIES, watched = item.stableKey in watched,
                    onClick = { nav.openItem(item) },
                    onLongClick = { nav.showMenu(MenuRequest(item.toRef())) },
                )
            }
        }
    }
}

fun formatDate(iso: String): String = try {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso)
    java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("es", "ES")).format(d!!)
} catch (_: Exception) { iso }

fun addedLabel(addedSecs: Long): String {
    if (addedSecs <= 0) return "Novedad"
    val days = ((System.currentTimeMillis() / 1000 - addedSecs) / 86400).toInt()
    return when {
        days <= 0 -> "Añadida hoy"
        days == 1 -> "Añadida ayer"
        days < 7 -> "Hace $days días"
        else -> "Hace poco"
    }
}

/** Destacado de novedades a todo lo ancho, al estilo Google TV. */
@Composable
private fun Hero(items: List<ItemEntity>, onFocus: (Boolean) -> Unit) {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    var focusedBtn by remember { mutableIntStateOf(0) }
    val extras = remember { mutableStateMapOf<String, HeroData>() }
    val firstBtn = remember { FocusRequester() }
    val laterId by produceState<Long?>(null) { value = repo.systemFolderId("later") }

    val safeIndex = if (items.isEmpty()) 0 else index % items.size
    val item = items[safeIndex]
    val inFolders by remember(item.stableKey) { repo.foldersOf(item.stableKey) }.collectAsState(initial = emptySet())
    val lid = laterId
    val inLater = lid != null && lid in inFolders

    LaunchedEffect(Unit) { delay(300); requestFocusSafely(firstBtn) }

    // Pasa solo cada 10 s (también con el foco puesto). Si usas el mando, vuelve a empezar a contar.
    val progress = remember { Animatable(0f) }
    var nudge by remember { mutableIntStateOf(0) }
    LaunchedEffect(items.size, safeIndex, nudge) {
        progress.snapTo(0f)
        if (items.size > 1) {
            progress.animateTo(1f, tween(10_000, easing = LinearEasing))
            index = (safeIndex + 1) % items.size
        }
    }
    // Precarga del fondo y logo del siguiente, para que el cambio sea limpio
    LaunchedEffect(safeIndex, items.size) {
        if (items.size > 1) {
            val next = items[(safeIndex + 1) % items.size]
            if (extras[next.key] == null) {
                val e = try { repo.heroExtra(next) } catch (_: Exception) { null }
                if (e != null && extras[next.key] == null) extras[next.key] = HeroData(e.backdrop ?: next.backdrop, e.plot ?: next.plot, e.genres ?: next.genre, e.logo, e.trailer)
            }
        }
    }

    LaunchedEffect(item.key) {
        if (extras[item.key] != null) return@LaunchedEffect
        val extra = try {
            // Con TMDB: logo del título, tráiler, fondo y sinopsis en castellano
            repo.heroExtra(item)?.let { HeroData(it.backdrop ?: item.backdrop, it.plot ?: item.plot, it.genres ?: item.genre, it.logo, it.trailer) }
                ?: run {
                    val acc = repo.account() ?: return@run null
                    if (item.type == ContentType.MOVIE) repo.client.vodInfo(acc, item.streamId)?.let {
                        repo.saveVodInfo(item, it)
                        HeroData(it.backdrop ?: item.backdrop, it.plot ?: item.plot, it.genre ?: item.genre)
                    } else null
                }
        } catch (_: Exception) { null }
        extras[item.key] = extra ?: HeroData(item.backdrop, item.plot, item.genre)
    }
    val extra = extras[item.key]
    val backdrop = extra?.backdrop ?: item.backdrop

    Box(Modifier.fillMaxWidth().height(350.dp).background(C.Bg)) {
        // Imagen a la derecha, fundida con el fondo; al cambiar, fundido cruzado lento y un zoom muy suave
        androidx.compose.animation.AnimatedContent(
            targetState = safeIndex,
            transitionSpec = {
                androidx.compose.animation.fadeIn(tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) togetherWith
                    androidx.compose.animation.fadeOut(tween(1200, delayMillis = 150))
            },
            modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.74f),
            label = "heroBackdrop",
        ) { i ->
            val it2 = items[i % items.size]
            val bd = extras[it2.key]?.backdrop ?: it2.backdrop
            val zoom = remember { Animatable(1.0f) }
            LaunchedEffect(Unit) { zoom.animateTo(1.08f, tween(12_000, easing = LinearEasing)) }
            Box(Modifier.fillMaxSize().clipToBounds()) {
                if (bd != null) {
                    AsyncImage(
                        bd, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(C.toneFor(it2.stableKey))) {
                        Box(
                            Modifier.align(Alignment.Center).padding(start = 160.dp).height(250.dp).aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(12.dp))
                        ) { Artwork(it2.icon, it2.name, it2.stableKey, Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to C.Bg, 0.30f to C.Bg, 0.55f to Color(0xB307080C), 0.8f to Color(0x3307080C), 1f to Color(0x0007080C))
            )
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp)
                .background(Brush.verticalGradient(0f to Color(0x0007080C), 1f to C.Bg))
        )

        Column(
            Modifier.align(Alignment.CenterStart).padding(start = SidePad + 4.dp, top = 10.dp).width(540.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            androidx.compose.animation.AnimatedContent(
                targetState = safeIndex,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(tween(700, delayMillis = 350)) +
                        androidx.compose.animation.slideInVertically(tween(800, delayMillis = 350, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 10 }) togetherWith
                        androidx.compose.animation.fadeOut(tween(350))
                },
                label = "heroInfo",
                contentAlignment = Alignment.BottomStart,
                modifier = Modifier.height(215.dp),   // altura fija: los botones no se mueven al cambiar
            ) { i ->
                val item = items[i % items.size]
                val extra = extras[item.key]
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Badge("NUEVO EN TU SERVIDOR", accent = true)
                        val kind = if (item.type == ContentType.SERIES) "Serie" else "Película"
                        T("${addedLabel(item.added)} · $kind", size = 12, weight = FontWeight.SemiBold, color = C.TextSoft)
                    }
                    val logo = extra?.logo
                    if (logo != null) {
                        AsyncImage(
                            logo, item.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                            modifier = Modifier.heightIn(max = 96.dp).widthIn(max = 380.dp).padding(vertical = 4.dp),
                        )
                    } else {
                        T(item.name, size = 34, weight = FontWeight.Bold, maxLines = 2, lineHeight = 38, color = Color.White)
                    }
                    val meta = listOfNotNull(
                        item.year?.toString(),
                        (extra?.genre ?: item.genre)?.split(',')?.take(3)?.joinToString(", ") { it.trim() },
                        item.cert?.let { if (it == 0) "TP" else "+$it" },
                        item.rating?.let { "★ " + String.format("%.1f", it).replace('.', ',') },
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) T(meta, size = 14, color = C.TextSoft)
                    val plot = extra?.plot ?: item.plot
                    if (!plot.isNullOrBlank()) T(plot, size = 14, color = Color(0xFFC9CFD8), maxLines = 3, lineHeight = 20)
                }
            }
            Row(
                Modifier
                    .padding(top = 6.dp)
                    .onFocusChanged { focused = it.hasFocus; onFocus(it.hasFocus) }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown) nudge++
                        if (e.type != KeyEventType.KeyDown || items.size < 2) return@onPreviewKeyEvent false
                        when {
                            e.key == Key.DirectionRight && focusedBtn == (if (extra?.trailer != null) 2 else 1) -> { index = (safeIndex + 1) % items.size; true }
                            e.key == Key.DirectionLeft && focusedBtn == 0 && safeIndex > 0 -> { index = safeIndex - 1; true }
                            else -> false
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                    if (inLater) "En tu lista" else "Ver más adelante",
                    if (inLater) Ic.Check else Ic.Clock,
                    onClick = { if (lid != null) scope.launch { repo.toggleFolder(lid, item.toRef(), inLater) } },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) focusedBtn = 1 },
                )
                extra?.trailer?.let { key ->
                    ActionButton(
                        "Tráiler", Ic.Film, onClick = { nav.openTrailer(key) },
                        modifier = Modifier.onFocusChanged { if (it.isFocused) focusedBtn = 2 },
                    )
                }
            }
            if (items.size > 1) {
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.indices.forEach { i ->
                        if (i == safeIndex) {
                            Box(Modifier.height(4.dp).width(30.dp).clip(RoundedCornerShape(999.dp)).background(Color(0x40F4F5F8))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value.coerceIn(0.02f, 1f)).background(C.Accent))
                            }
                        } else {
                            Box(Modifier.height(4.dp).width(8.dp).clip(RoundedCornerShape(999.dp)).background(Color(0x55F4F5F8)))
                        }
                    }
                    T("   ◀ ▶ más novedades", size = 11, color = C.Muted)
                }
            }
        }
    }
}
