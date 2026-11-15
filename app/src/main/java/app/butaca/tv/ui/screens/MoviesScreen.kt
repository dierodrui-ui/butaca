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

    PivotScope(pivot = 56.dp) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 18.dp, bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = Side), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        T("Películas", size = 26, weight = FontWeight.SemiBold)
                        Pill("Por categorías de TMDB", true, { }, small = true)
                        Pill("Categorías del servidor", false, onServer, small = true)
                    }
                    val h = hub
                    val p = prep
                    when {
                        !repo.tmdb.enabled -> T("Añade tu clave de TMDB en Ajustes para ordenar el catálogo por categorías.", size = 13, color = C.Muted)
                        p != null -> T("Clasificando tu catálogo con TMDB… ${p.first} de ${p.second}. Las filas se irán completando solas.", size = 13, color = C.Accent)
                        h != null -> T("${h.identified} películas clasificadas con TMDB" + (if (h.unclassified > 0) " · ${h.unclassified} sin identificar (en «Categorías del servidor»)" else ""), size = 13, color = C.Muted)
                    }
                }
            }
            val h = hub
            if (h == null) {
                item(key = "loading") { T("Preparando las filas…", size = 14, color = C.Muted, modifier = Modifier.padding(horizontal = Side)) }
            } else if (h.rows.isEmpty() && repo.tmdb.enabled) {
                item(key = "empty") { EmptyState("Todavía no hay películas clasificadas", "En unos minutos aparecerán aquí, ordenadas por categorías.") }
            }
            h?.rows?.forEach { row ->
                item(key = "row_" + row.id) { HubRowView(row, watched, onSeeAll = { nav.openHubRow(row.id) }) }
            }
        }
    }
}

@Composable
private fun HubRowView(row: HubRow, watched: Set<String>, onSeeAll: () -> Unit) {
    val nav = LocalNav.current
    val repo = ButacaApp.repo
    val shown = row.all.take(if (row.numbered) 10 else 30)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(row.title, Modifier.padding(horizontal = Side), extra = if (row.numbered) null else "${row.all.size}")
        Box(Modifier.padding(start = Side - 6.dp)) {
            TvRow {
                itemsIndexed(shown, key = { _, it -> it.key }) { i, item ->
                    LaunchedEffect(item.key) { repo.requestGenre(item) }
                    PosterCard(
                        title = item.name, icon = item.icon, keySeed = item.stableKey,
                        meta = itemMeta(item), watched = item.stableKey in watched,
                        position = if (row.numbered) i + 1 else null,
                        onClick = { nav.openItem(item) },
                        onLongClick = { nav.showMenu(MenuRequest(item.toRef())) },
                    )
                }
                if (row.all.size > shown.size) {
                    item(key = "more") {
                        FocusSurface(
                            onClick = onSeeAll,
                            modifier = Modifier.width(116.dp).aspectRatio(2f / 3f),
                            radius = 10.dp, container = C.Raised, focusedContainer = C.Accent,
                            content = C.Text, focusedContent = C.OnAccent, focusedScale = 1.07f,
                        ) {
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                T("Ver todo", size = 15, weight = FontWeight.SemiBold, color = Color.Unspecified)
                                T("${row.all.size}", size = 12, color = Color.Unspecified)
                            }
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
