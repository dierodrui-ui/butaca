package app.butaca.tv.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.butaca.tv.ButacaApp
import app.butaca.tv.LocalNav
import app.butaca.tv.data.Cond
import app.butaca.tv.data.ContentType
import app.butaca.tv.data.WishResult
import app.butaca.tv.data.parseWish
import app.butaca.tv.data.runWish
import app.butaca.tv.data.toRef
import app.butaca.tv.ui.C
import app.butaca.tv.ui.Ic
import app.butaca.tv.ui.components.ActionButton
import app.butaca.tv.ui.components.EmptyState
import app.butaca.tv.ui.components.MenuRequest
import app.butaca.tv.ui.components.Pill
import app.butaca.tv.ui.components.PivotScope
import app.butaca.tv.ui.components.PosterCard
import app.butaca.tv.ui.components.SectionTitle
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.TvTextField
import app.butaca.tv.ui.components.itemMeta
import kotlinx.coroutines.launch

private val EXAMPLES = listOf(
    "Película de niños tranquila y relajante",
    "Ciencia ficción futurista con inteligencia artificial",
    "Comedia española para reír",
    "Thriller con giro final muy bien valorado",
    "Serie de misterio corta",
    "Película de los 80 de aventuras",
    "Algo de terror con fantasmas",
    "Película romántica de Navidad",
)

/** «Te digo lo que ver»: dices (o escribes) lo que te apetece y la app lo traduce a etiquetas de TMDB. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WishScreen() {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var phrase by rememberSaveable { mutableStateOf("") }
    var typed by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<WishResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    val watched by remember { repo.watchedKeys() }.collectAsState(initial = emptySet())
    val micFocus = remember { FocusRequester() }

    fun run(conds: List<Cond>) {
        loading = true
        scope.launch {
            result = try { repo.runWish(conds) } catch (_: Exception) { WishResult(conds, emptyList()) }
            loading = false
        }
    }
    fun ask(text: String) {
        if (text.isBlank()) return
        phrase = text
        loading = true
        scope.launch {
            val conds = try { repo.parseWish(text) } catch (_: Exception) { emptyList() }
            if (conds.isEmpty()) { result = WishResult(emptyList(), emptyList()); loading = false } else run(conds)
        }
    }

    // Micrófono: el reconocimiento de voz de Google TV, en castellano
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) ask(text)
    }
    fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "¿Qué te apetece ver?")
        try { voice.launch(i) } catch (_: ActivityNotFoundException) {
            nav.toast("Este dispositivo no tiene reconocimiento de voz. Escríbelo en el cuadro de texto.")
        }
    }
    LaunchedEffect(Unit) { try { micFocus.requestFocus() } catch (_: Exception) { } }

    PivotScope(pivot = 60.dp) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                // El botón de búsqueda del mando (si lo tiene) también abre el micrófono
                if (e.type == KeyEventType.KeyDown && e.key == Key.Search) { listen(); true } else false
            },
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 28.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("Te digo lo que ver", size = 30, weight = FontWeight.Bold)
                    T("Escribe o dicta con el micrófono del mando lo que te apetece y lo busco por géneros, temas, época y estilo en TMDB, solo entre lo que tienes.", size = 14, color = C.Muted)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvTextField(
                            typed, { typed = it }, "Escribe o dicta con el micrófono del mando: «película de niños tranquila»",
                            modifier = Modifier.width(620.dp).focusRequester(micFocus), onSubmit = { ask(typed) },
                        )
                        ActionButton("Buscar", Ic.Search, onClick = { ask(typed) }, primary = true, height = 48.dp)
                        // Borrar la frase y empezar de nuevo
                        if (typed.isNotEmpty() || phrase.isNotEmpty()) ActionButton("Borrar", Ic.Close, onClick = {
                            typed = ""; phrase = ""; result = null
                            try { micFocus.requestFocus() } catch (_: Exception) { }
                        }, height = 48.dp)
                    }
                    T("PRUEBA CON", size = 11, weight = FontWeight.Bold, color = C.Faint, modifier = Modifier.padding(top = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EXAMPLES.forEach { ex -> Pill("«$ex»", false, { typed = ex; ask(ex) }, small = true) }
                    }
                }
            }
            val r = result
            if (phrase.isNotBlank()) {
                item(key = "understood", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        SectionTitle("«$phrase»")
                        when {
                            loading -> T("Pensando qué te puede encajar…", size = 14, color = C.Muted)
                            r != null && r.conds.isEmpty() -> T("No he sabido sacar etiquetas de esa frase. Prueba con un género, un tema o una época (por ejemplo «comedia de los 90»).", size = 14, color = C.Muted)
                            r != null -> {
                                T("LO HE ENTENDIDO ASÍ · pulsa una etiqueta para quitarla", size = 11, weight = FontWeight.Bold, color = C.Faint)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    r.conds.forEach { c ->
                                        val prefix = when (c) { is Cond.Topic -> "#"; is Cond.Person -> "👤 "; else -> "" }
                                        Pill("✕  $prefix${c.label}", true, { run(r.conds - c) }, small = true)
                                    }
                                }
                                r.note?.let { T(it, size = 13, color = C.AccentSoft) }
                                T(if (r.items.isEmpty()) "No tienes nada que encaje con todo eso." else "${r.items.size} títulos en tu servidor", size = 13, color = C.Muted)
                            }
                        }
                    }
                }
            }
            if (r != null && !loading) {
                if (r.items.isEmpty() && r.conds.isNotEmpty()) {
                    item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState("Nada con todas esas etiquetas", "Quita alguna de arriba para ampliar.")
                    }
                }
                items(r.items.size, key = { "w_" + r.items[it].key }) { i ->
                    val item = r.items[i]
                    LaunchedEffect(item.key) { repo.requestGenre(item) }
                    PosterCard(
                        title = item.name, icon = item.icon, keySeed = item.stableKey, width = null,
                        meta = itemMeta(item), isSeries = item.type == ContentType.SERIES, watched = item.stableKey in watched,
                        onClick = { nav.openItem(item) }, onLongClick = { nav.showMenu(MenuRequest(item.toRef())) },
                    )
                }
            }
        }
    }
}
