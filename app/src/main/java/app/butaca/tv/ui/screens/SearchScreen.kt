package app.butaca.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.butaca.tv.ButacaApp
import app.butaca.tv.LocalNav
import app.butaca.tv.data.ContentType
import app.butaca.tv.data.ItemEntity
import app.butaca.tv.data.toRef
import app.butaca.tv.data.SearchExtras
import app.butaca.tv.data.searchExtras
import app.butaca.tv.ui.C
import app.butaca.tv.ui.components.EmptyState
import app.butaca.tv.ui.components.MenuRequest
import app.butaca.tv.ui.components.Pill
import app.butaca.tv.ui.components.PivotScope
import app.butaca.tv.ui.components.PosterCard
import app.butaca.tv.ui.components.SectionTitle
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.TvTextField
import app.butaca.tv.ui.components.itemMeta
import app.butaca.tv.ui.components.requestFocusSafely
import kotlinx.coroutines.delay

/** Buscador con resultados en cuadrícula vertical: nada se sale de la pantalla por los lados. */
@Composable
fun SearchScreen() {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ItemEntity>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("all") }
    var extras by remember { mutableStateOf<SearchExtras?>(null) }
    var decade by remember { mutableStateOf(0) }
    var minRating by remember { mutableStateOf(0) }
    val decades = listOf("Todas", "2020s", "2010s", "2000s", "90s", "80s", "Antes")
    val ratings = listOf(0, 6, 7, 8)
    fun passes(i: ItemEntity): Boolean {
        val y = i.year
        val dOk = when (decade) {
            0 -> true
            6 -> y != null && y < 1980
            else -> y != null && y / 10 * 10 == listOf(0, 2020, 2010, 2000, 1990, 1980)[decade]
        }
        val r = ratings[minRating]
        return dOk && (r == 0 || (i.rating ?: 0.0) >= r)
    }
    val watched by remember { repo.watchedKeys() }.collectAsState(initial = emptySet())
    val field = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { delay(200); requestFocusSafely(field) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); extras = null; return@LaunchedEffect }
        searching = true
        delay(300)
        results = repo.search(query)
        searching = false
        extras = try { repo.searchExtras(query) } catch (_: Exception) { null }
    }

    val filtered = results.filter { it.type == ContentType.LIVE || passes(it) }
    val movies = filtered.filter { it.type == ContentType.MOVIE }
    val series = filtered.filter { it.type == ContentType.SERIES }
    val live = filtered.filter { it.type == ContentType.LIVE }

    PivotScope(pivot = 60.dp) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 26.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "field", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("Buscar", size = 26, weight = FontWeight.SemiBold)
                    TvTextField(
                        value = query, onValue = { query = it },
                        hint = "Pulsa OK para escribir, o usa el micrófono del mando",
                        modifier = Modifier.fillMaxWidth(0.7f).focusRequester(field),
                        imeAction = ImeAction.Search,
                        onSubmit = { focusManager.moveFocus(FocusDirection.Down) },
                    )
                    T("Títulos en español o inglés, personas («Tom Hanks», «Almodóvar») o temas («zombis», «navidad»). Pulsa ✓ o Atrás para cerrar el teclado.", size = 13, color = C.Muted)
                }
            }
            if (query.trim().length >= 2) {
                item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Todo · ${results.size}", filter == "all", { filter = "all" })
                        Pill("Películas · ${movies.size}", filter == ContentType.MOVIE, { filter = ContentType.MOVIE })
                        Pill("Series · ${series.size}", filter == ContentType.SERIES, { filter = ContentType.SERIES })
                        Pill("Canales · ${live.size}", filter == ContentType.LIVE, { filter = ContentType.LIVE })
                        Pill("Década: ${decades[decade]}", decade != 0, { decade = (decade + 1) % decades.size })
                        Pill("Nota: ${if (ratings[minRating] == 0) "Todas" else "${ratings[minRating]}+"}", minRating != 0, { minRating = (minRating + 1) % ratings.size })
                    }
                }
                // Personas y temas (desde TMDB)
                extras?.let { ex ->
                    val person = ex.person
                    val pItems = ex.personItems.filter { passes(it) }
                    if (person != null && pItems.isNotEmpty() && (filter == "all" || filter == ContentType.MOVIE || filter == ContentType.SERIES)) {
                        item(key = "person_h", span = { GridItemSpan(maxLineSpan) }) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SectionTitle(person.name, Modifier.padding(top = 8.dp), extra = "${pItems.size} títulos en tu servidor")
                                Pill("Ver su página", false, { nav.openPerson(person.id) }, small = true)
                            }
                        }
                        items(pItems.take(14), key = { "p_" + it.key }) { item ->
                            LaunchedEffect(item.key) { repo.requestGenre(item) }
                            PosterCard(
                                title = item.name, icon = item.icon, keySeed = item.stableKey, width = null, meta = itemMeta(item),
                                isSeries = item.type == ContentType.SERIES, watched = item.stableKey in watched,
                                onClick = { nav.openItem(item) }, onLongClick = { nav.showMenu(MenuRequest(item.toRef())) },
                            )
                        }
                    }
                    ex.topics.forEachIndexed { ti, (label, list0) ->
                        val list = list0.filter { passes(it) }
                        if (list.isNotEmpty() && filter == "all") {
                            item(key = "topic_h$ti", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(label, Modifier.padding(top = 8.dp)) }
                            items(list.take(14), key = { "t${ti}_" + it.key }) { item ->
                                LaunchedEffect(item.key) { repo.requestGenre(item) }
                                PosterCard(
                                    title = item.name, icon = item.icon, keySeed = item.stableKey, width = null, meta = itemMeta(item),
                                    isSeries = item.type == ContentType.SERIES, watched = item.stableKey in watched,
                                    onClick = { nav.openItem(item) }, onLongClick = { nav.showMenu(MenuRequest(item.toRef())) },
                                )
                            }
                        }
                    }
                }
                if (results.isEmpty() && !searching && (extras?.personItems.isNullOrEmpty())) {
                    item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState("Sin resultados para «$query»", "Prueba con menos letras o con otra palabra del título.")
                    }
                }
            }
            listOf(
                Triple(ContentType.MOVIE, "Películas", movies),
                Triple(ContentType.SERIES, "Series", series),
                Triple(ContentType.LIVE, "Canales en directo", live),
            ).forEach { (type, label, list) ->
                if (list.isNotEmpty() && (filter == "all" || filter == type)) {
                    item(key = "h_$type", span = { GridItemSpan(maxLineSpan) }) {
                        SectionTitle(label, Modifier.padding(top = 8.dp))
                    }
                    // En «Todo» enseñamos las primeras; al elegir el filtro, todas
                    val shown = if (filter == "all") list.take(14) else list
                    items(shown, key = { it.key }) { item ->
                        LaunchedEffect(item.key) { repo.requestGenre(item) }
                        PosterCard(
                            title = item.name, icon = item.icon, keySeed = item.stableKey, width = null,
                            meta = itemMeta(item),
                            isSeries = type == ContentType.SERIES, watched = item.stableKey in watched,
                            onClick = { nav.openItem(item) },
                            onLongClick = { nav.showMenu(MenuRequest(item.toRef())) },
                        )
                    }
                    if (filter == "all" && list.size > 14) {
                        item(key = "more_$type", span = { GridItemSpan(maxLineSpan) }) {
                            Row { Pill("Ver las ${list.size} de $label", false, { filter = type }, small = true) }
                        }
                    }
                }
            }
        }
    }
}
