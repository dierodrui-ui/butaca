package app.butaca.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.butaca.tv.ButacaApp
import app.butaca.tv.LocalNav
import app.butaca.tv.data.ContentType
import app.butaca.tv.data.ItemEntity
import app.butaca.tv.data.toRef
import app.butaca.tv.ui.C
import app.butaca.tv.ui.components.EmptyState
import app.butaca.tv.ui.components.MenuRequest
import app.butaca.tv.ui.components.Pill
import app.butaca.tv.ui.components.PosterCard
import app.butaca.tv.ui.components.SectionTitle
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.PivotScope
import app.butaca.tv.ui.components.TvRow
import app.butaca.tv.ui.components.itemMeta
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import app.butaca.tv.ui.components.TvTextField
import app.butaca.tv.ui.components.requestFocusSafely
import kotlinx.coroutines.delay

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen() {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ItemEntity>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("all") }
    val watched by remember() { repo.watchedKeys() }.collectAsState(initial = emptySet())
    val field = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { delay(200); requestFocusSafely(field) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        searching = true
        delay(300)
        results = repo.search(query)
        searching = false
    }

    val movies = results.filter { it.type == ContentType.MOVIE }
    val series = results.filter { it.type == ContentType.SERIES }
    val live = results.filter { it.type == ContentType.LIVE }

    PivotScope(pivot = 70.dp) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 26.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                T("Buscar", size = 26, weight = FontWeight.SemiBold)
                TvTextField(
                    value = query, onValue = { query = it },
                    hint = "Pulsa OK para escribir, o usa el micrófono del mando",
                    modifier = Modifier.fillMaxWidth(0.7f).focusRequester(field),
                    imeAction = ImeAction.Search,
                    onSubmit = { focusManager.moveFocus(FocusDirection.Down) },
                )
                T("Busca en canales, películas y series a la vez, en español y en inglés. Pulsa ✓ o Atrás para cerrar el teclado.", size = 13, color = C.Muted)
            }
        }
        if (query.trim().length >= 2) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Todo · ${results.size}", filter == "all", { filter = "all" })
                    Pill("Películas · ${movies.size}", filter == ContentType.MOVIE, { filter = ContentType.MOVIE })
                    Pill("Series · ${series.size}", filter == ContentType.SERIES, { filter = ContentType.SERIES })
                    Pill("Canales · ${live.size}", filter == ContentType.LIVE, { filter = ContentType.LIVE })
                }
            }
            if (results.isEmpty() && !searching) {
                item { EmptyState("Sin resultados para «$query»", "Prueba con menos letras o con otra palabra del título.") }
            }
        }
        listOf(
            Triple(ContentType.MOVIE, "Películas", movies),
            Triple(ContentType.SERIES, "Series", series),
            Triple(ContentType.LIVE, "Canales en directo", live),
        ).forEach { (type, label, list) ->
            if (list.isNotEmpty() && (filter == "all" || filter == type)) {
                item(key = type) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle(label)
                        TvRow {
                            items(list.take(60), key = { it.key }) { item ->
                                LaunchedEffect(item.key) { repo.requestGenre(item) }
                                PosterCard(
                                    title = item.name, icon = item.icon, keySeed = item.stableKey,
                                    width = 112.dp,
                                    meta = itemMeta(item),
                                    isSeries = type == ContentType.SERIES, watched = item.stableKey in watched,
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
    }
}
