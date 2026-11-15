package app.butaca.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.focus.focusRestorer
import app.butaca.tv.ui.Ic
import app.butaca.tv.ui.components.MenuRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.butaca.tv.ButacaApp
import app.butaca.tv.LocalNav
import app.butaca.tv.data.ContentType
import app.butaca.tv.data.Hub
import app.butaca.tv.data.HubCache
import app.butaca.tv.data.HubRow
import app.butaca.tv.data.ItemEntity
import app.butaca.tv.data.KidsPrep
import app.butaca.tv.data.movieHub
import app.butaca.tv.data.toRef
import app.butaca.tv.ui.C
import app.butaca.tv.ui.components.EmptyState
import app.butaca.tv.ui.components.FocusSurface
import app.butaca.tv.ui.components.MenuRequest
import app.butaca.tv.ui.components.Pill
import app.butaca.tv.ui.components.PivotScope
import app.butaca.tv.ui.components.PosterCard
import app.butaca.tv.ui.components.SectionTitle
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.TvRow
import app.butaca.tv.ui.components.itemMeta
import kotlinx.coroutines.delay

private val Side = 36.dp

/** Pestaña Películas: catálogo ordenado por TMDB (por defecto) o las categorías del servidor. */
@Composable
fun MoviesScreen() {
    var serverView by rememberSaveable { mutableStateOf(false) }
    if (serverView) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 24.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Por categorías de TMDB", false, { serverView = false }, small = true)
                Pill("Categorías del servidor", true, { }, small = true)
            }
            Box(Modifier.weight(1f)) { CatalogScreen(ContentType.MOVIE) }
        }
    } else {
        MovieHubView(onServer = { serverView = true })
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MovieHubView(onServer: () -> Unit) {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    val settings by repo.prefs.flow.collectAsState()
    val prep by KidsPrep.progress.collectAsState()
    val watched by remember { repo.watchedKeys() }.collectAsState(initial = emptySet())
    // Mientras se leen géneros de TMDB, recalculamos de vez en cuando para ir completando
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(prep != null) { while (KidsPrep.progress.value != null) { delay(60_000); tick++ } ; tick++ }
    val hub by produceState<Hub?>(HubCache.hub, settings.kidsLimit, settings.tmdbKey, tick) {
        value = try { repo.movieHub() } catch (_: Exception) { value }
    }
    val rows = hub?.rows.orEmpty()
    var selected by rememberSaveable { mutableStateOf("") }
    var pendingFocus by remember { mutableStateOf("") }
    LaunchedEffect(pendingFocus) { if (pendingFocus.isNotEmpty()) { delay(220); selected = pendingFocus } }
    val row = rows.firstOrNull { it.id == selected } ?: rows.firstOrNull()
    var sort by rememberSaveable { mutableStateOf(0) }   // 0 populares, 1 nota, 2 más recientes, 3 A-Z
    val list: List<ItemEntity> = remember(row, sort) {
        val l = row?.all.orEmpty()
        if (row?.numbered == true) l else when (sort) {
            1 -> l.sortedByDescending { it.rating ?: 0.0 }
            2 -> l.sortedByDescending { it.year ?: 0 }
            3 -> l.sortedBy { it.normName }
            else -> l
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 24.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Por categorías de TMDB", true, { }, small = true)
            Pill("Categorías del servidor", false, onServer, small = true)
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // ---------- Categorías de TMDB ----------
            Column(Modifier.width(250.dp).fillMaxHeight().background(C.Panel).padding(top = 24.dp, start = 12.dp, end = 12.dp)) {
                T("Películas", size = 22, weight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
                LazyColumn(
                    Modifier.weight(1f).focusRestorer(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 30.dp),
                ) {
                    items(rows, key = { it.id }) { r ->
                        MenuRow(
                            r.title, if (r.id == "foryou") Ic.Heart else if (r.numbered) Ic.Star else null,
                            onClick = { selected = r.id },
                            selected = row?.id == r.id,
                            iconTint = if (row?.id == r.id) C.Accent else C.Muted,
                            modifier = Modifier.onFocusChanged { if (it.isFocused) pendingFocus = r.id },
                        )
                    }
                }
            }
            // ---------- Contenido ----------
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp, end = 28.dp, top = 24.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    T(row?.title ?: "Películas", size = 22, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (row != null) T(if (list.size == 1) "1 título" else "${list.size} títulos", size = 13, color = C.Muted, maxLines = 1)
                }
                val h = hub
                val p = prep
                when {
                    !repo.tmdb.enabled -> T("Añade tu clave de TMDB en Ajustes para ordenar el catálogo por categorías.", size = 13, color = C.Muted)
                    p != null -> T("Clasificando tu catálogo con TMDB… ${p.first} de ${p.second}. Las categorías se irán completando solas.", size = 13, color = C.Accent)
                    h != null && h.unclassified > 0 -> T("${h.unclassified} películas sin identificar en TMDB: están en «Categorías del servidor».", size = 12, color = C.Faint)
                }
                if (row != null && !row.numbered) {
                    Row(Modifier.padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Más populares", "Mejor nota", "Más recientes", "A-Z").forEachIndexed { i, s ->
                            Pill(s, sort == i, { sort = i }, small = true)
                        }
                    }
                }
                when {
                    hub == null -> T("Preparando las categorías…", size = 14, color = C.Muted, modifier = Modifier.padding(top = 12.dp))
                    rows.isEmpty() -> EmptyState("Todavía no hay películas clasificadas", "En unos minutos aparecerán aquí, ordenadas por categorías.")
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(104.dp),
                        modifier = Modifier.fillMaxSize().focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 40.dp, top = 6.dp, start = 6.dp, end = 6.dp),
                    ) {
                        items(list.size, key = { list[it].key }) { i ->
                            val item = list[i]
                            PosterCard(
                                title = item.name, icon = item.icon, keySeed = item.stableKey, width = null,
                                meta = itemMeta(item), watched = item.stableKey in watched,
                                position = if (row?.numbered == true) i + 1 else null,
                                onClick = { nav.openItem(item) },
                                onLongClick = { nav.showMenu(MenuRequest(item.toRef())) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** «Ver todo» de una fila: la lista completa en cuadrícula. */
@Composable
fun HubRowScreen(id: String, onBack: () -> Unit) {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    BackHandler { onBack() }
    val row by produceState(HubCache.row(id)) { if (value == null) value = repo.movieHub().rows.firstOrNull { it.id == id } }
    val watched by remember { repo.watchedKeys() }.collectAsState(initial = emptySet())
    var sort by rememberSaveable { mutableStateOf(0) }   // 0 populares, 1 nota, 2 más recientes, 3 A-Z
    val r = row
    val list: List<ItemEntity> = remember(r, sort) {
        val l = r?.all.orEmpty()
        when (sort) {
            1 -> l.sortedByDescending { it.rating ?: 0.0 }
            2 -> l.sortedByDescending { it.year ?: 0 }
            3 -> l.sortedBy { it.normName }
            else -> l
        }
    }
    PivotScope(pivot = 60.dp) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            modifier = Modifier.fillMaxSize().background(C.Bg),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(r?.title ?: "Películas", size = 28, weight = FontWeight.SemiBold)
                    T("${list.size} películas · Atrás para volver", size = 13, color = C.Muted)
                    if (r?.numbered != true) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Más populares", "Mejor nota", "Más recientes", "A-Z").forEachIndexed { i, s ->
                            Pill(s, sort == i, { sort = i }, small = true)
                        }
                    }
                }
            }
            items(list.size, key = { list[it].key }) { i ->
                val item = list[i]
                PosterCard(
                    title = item.name, icon = item.icon, keySeed = item.stableKey, width = null,
                    meta = itemMeta(item), watched = item.stableKey in watched,
                    position = if (r?.numbered == true) i + 1 else null,
                    onClick = { nav.openItem(item) },
                    onLongClick = { nav.showMenu(MenuRequest(item.toRef())) },
                )
            }
        }
    }
}
