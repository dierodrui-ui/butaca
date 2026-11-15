package app.butaca.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.butaca.tv.AppNav
import app.butaca.tv.ButacaApp
import app.butaca.tv.LocalNav
import app.butaca.tv.data.ContentType
import app.butaca.tv.data.ItemEntity
import app.butaca.tv.data.Repo
import app.butaca.tv.data.Tag
import app.butaca.tv.data.TagGroup
import app.butaca.tv.data.TagKind
import app.butaca.tv.data.explore
import app.butaca.tv.data.explorerGroups
import app.butaca.tv.data.toRef
import app.butaca.tv.ui.C
import app.butaca.tv.ui.components.EmptyState
import app.butaca.tv.ui.components.MenuRequest
import app.butaca.tv.ui.components.Pill
import app.butaca.tv.ui.components.PivotScope
import app.butaca.tv.ui.components.PosterCard
import app.butaca.tv.ui.components.SectionTitle
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.itemMeta
import kotlinx.coroutines.delay

/** Estado del explorador: etiquetas elegidas, grupos desplegados y resultados. */
class ExplorerState(initial: List<Tag>) {
    val selected = mutableStateListOf<Tag>().apply { addAll(initial) }
    val expanded = mutableStateMapOf<String, Boolean>()
    var results by mutableStateOf<List<ItemEntity>?>(null)
    var loading by mutableStateOf(false)
    var typeFilter by mutableStateOf("all")

    fun toggle(t: Tag) {
        val i = selected.indexOfFirst { it.key == t.key }
        if (i >= 0) selected.removeAt(i) else selected.add(t)
    }

    fun isOn(t: Tag) = selected.any { it.key == t.key }
}

@Composable
fun rememberExplorer(initial: List<Tag>): ExplorerState {
    val repo = ButacaApp.repo
    val state = remember { ExplorerState(initial) }
    val keys = state.selected.joinToString("|") { it.key }
    LaunchedEffect(keys) {
        if (state.selected.isEmpty()) { state.results = null; state.loading = false; return@LaunchedEffect }
        state.loading = true
        delay(350)   // por si marcas varias seguidas
        state.results = try { repo.explore(state.selected.toList()) } catch (_: Exception) { emptyList() }
        state.loading = false
    }
    return state
}

private fun label(t: Tag) = if (t.kind == TagKind.KEYWORD && t.movieId != null && t.name.firstOrNull()?.isLowerCase() == true) "#${t.name}" else t.name

/** Bloques del explorador para meter en cualquier cuadrícula (buscador o pantalla propia). */
@OptIn(ExperimentalLayoutApi::class)
fun LazyGridScope.explorerItems(
    state: ExplorerState,
    groups: List<TagGroup>,
    watched: Set<String>,
    nav: AppNav,
    repo: Repo,
) {
    // Selección actual
    if (state.selected.isNotEmpty()) {
        item(key = "ex_sel", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("TU SELECCIÓN", size = 11, weight = FontWeight.Bold, color = C.Faint)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.selected.forEach { t -> Pill("✕  " + label(t), true, { state.toggle(t) }, small = true) }
                    Pill("Borrar todo", false, { state.selected.clear() }, small = true)
                }
            }
        }
    }
    // Grupos: primero las más importantes; «Ver más» para el resto
    groups.forEach { g ->
        item(key = "ex_g_${g.title}", span = { GridItemSpan(maxLineSpan) }) {
            val open = state.expanded[g.title] == true
            val shown = if (open) g.tags else g.tags.take(g.visible)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                T(g.title.uppercase(), size = 11, weight = FontWeight.Bold, color = C.Faint)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { t -> Pill(label(t), state.isOn(t), { state.toggle(t) }, small = true) }
                    if (g.tags.size > g.visible) {
                        Pill(if (open) "Ver menos" else "Ver más (${g.tags.size - g.visible})", false, { state.expanded[g.title] = !open }, small = true)
                    }
                }
            }
        }
    }
    // Resultados
    val res = state.results
    item(key = "ex_res_h", span = { GridItemSpan(maxLineSpan) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            when {
                state.selected.isEmpty() -> T("Marca una o varias etiquetas y aquí aparecerá lo que tienes que encaja con todas.", size = 14, color = C.Muted)
                state.loading -> T("Buscando en TMDB lo que encaja…", size = 14, color = C.Muted)
                res != null -> {
                    val movies = res.count { it.type == ContentType.MOVIE }
                    val series = res.count { it.type == ContentType.SERIES }
                    SectionTitle("Resultados", extra = "${res.size} en tu servidor")
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Todo · ${res.size}", state.typeFilter == "all", { state.typeFilter = "all" }, small = true)
                        Pill("Películas · $movies", state.typeFilter == ContentType.MOVIE, { state.typeFilter = ContentType.MOVIE }, small = true)
                        Pill("Series · $series", state.typeFilter == ContentType.SERIES, { state.typeFilter = ContentType.SERIES }, small = true)
                    }
                }
            }
        }
    }
    if (res != null && !state.loading) {
        val list = res.filter { state.typeFilter == "all" || it.type == state.typeFilter }
        if (list.isEmpty() && state.selected.isNotEmpty()) {
            item(key = "ex_none", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState("No hay nada con todas esas etiquetas", "Quita alguna para ampliar la búsqueda.")
            }
        }
        items(list, key = { "ex_" + it.key }) { item ->
            LaunchedEffect(item.key) { repo.requestGenre(item) }
            PosterCard(
                title = item.name, icon = item.icon, keySeed = item.stableKey, width = null,
                meta = itemMeta(item), isSeries = item.type == ContentType.SERIES, watched = item.stableKey in watched,
                onClick = { nav.openItem(item) }, onLongClick = { nav.showMenu(MenuRequest(item.toRef())) },
            )
        }
    }
}

/** Pantalla abierta al pulsar una etiqueta de una ficha (p. ej. «Comedia» o «Pixar»). */
@Composable
fun ExploreScreen(initial: List<Tag>, onBack: () -> Unit) {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    BackHandler { onBack() }
    val state = rememberExplorer(initial)
    val groups by produceState(emptyList<TagGroup>()) { value = repo.explorerGroups(false) }
    // Si la etiqueta viene de una ficha y no está en las listas (p. ej. un tema en inglés), la añadimos arriba
    val extra = initial.filter { t -> groups.none { g -> g.tags.any { it.key == t.key } } }
    val allGroups = if (extra.isEmpty()) groups else listOf(TagGroup("De la ficha", extra, extra.size)) + groups
    val watched by remember { repo.watchedKeys() }.collectAsState(initial = emptySet())

    PivotScope(pivot = 60.dp) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            modifier = Modifier.fillMaxSize().background(C.Bg),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    T("Explorar", size = 28, weight = FontWeight.SemiBold)
                    T("Combina etiquetas para afinar: solo verás lo que encaja con todas. Atrás para volver.", size = 13, color = C.Muted)
                }
            }
            explorerItems(state, allGroups, watched, nav, repo)
        }
    }
}
