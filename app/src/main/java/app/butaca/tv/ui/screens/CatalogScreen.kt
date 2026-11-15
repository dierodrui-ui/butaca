package app.butaca.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import app.butaca.tv.ButacaApp
import app.butaca.tv.LocalNav
import app.butaca.tv.data.CategoryEntity
import app.butaca.tv.data.ContentRef
import app.butaca.tv.data.ContentType
import app.butaca.tv.data.ItemEntity
import app.butaca.tv.data.toRef
import app.butaca.tv.ui.C
import app.butaca.tv.ui.Ic
import app.butaca.tv.ui.components.AppDialog
import app.butaca.tv.ui.components.Artwork
import app.butaca.tv.ui.components.Badge
import app.butaca.tv.ui.components.EmptyState
import app.butaca.tv.ui.components.FocusSurface
import app.butaca.tv.ui.components.MenuRequest
import app.butaca.tv.ui.components.MenuRow
import app.butaca.tv.ui.components.PinDialog
import app.butaca.tv.ui.components.PosterCard
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.itemMeta
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val RECENT = "__recent"
private const val FAV = "__fav"

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CatalogScreen(type: String) {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()

    val allCats by remember(type) { repo.db.categories().observe(type) }.collectAsState(initial = emptyList())
    val showAdult by repo.showAdult.collectAsState(initial = false)
    val watched by remember() { repo.watchedKeys() }.collectAsState(initial = emptySet())
    val visibleCats = allCats.filter { !it.hidden && (showAdult || !it.isAdult) }
    val hasLockedAdult = !showAdult && allCats.any { it.isAdult && !it.hidden }
    val unlocked by repo.adultUnlocked.collectAsState()

    val firstKey = if (type == ContentType.LIVE) FAV else RECENT
    var selected by remember(type) { mutableStateOf(firstKey) }
    var pendingFocus by remember(type) { mutableStateOf(firstKey) }
    var catMenu by remember { mutableStateOf<CategoryEntity?>(null) }
    var askPin by remember { mutableStateOf(false) }

    // Cambia de categoría al pararte encima (con una pequeña espera para no saturar)
    LaunchedEffect(pendingFocus) { delay(220); selected = pendingFocus }

    val favId by produceState<Long?>(null) { value = repo.systemFolderId("fav") }

    val itemsFlow: Flow<List<RefOrItem>> = remember(selected, type, favId) {
        when (selected) {
            RECENT -> repo.recent(type, 150).map { l -> l.map { RefOrItem.Item(it) } }
            FAV -> favId?.let { id ->
                repo.folderContent(id).map { refs -> refs.filter { it.type == type }.map { RefOrItem.Ref(it) } }
            } ?: flowOf(emptyList())
            else -> repo.categoryItems(selected, type).map { l -> l.map { RefOrItem.Item(it) } }
        }
    }
    val entries by itemsFlow.collectAsState(initial = emptyList())

    val title = when (selected) {
        RECENT -> if (type == ContentType.SERIES) "Series actualizadas" else "Añadidas recientemente"
        FAV -> "Mis canales"
        else -> allCats.firstOrNull { it.key == selected }?.name ?: ""
    }
    val typeName = when (type) { ContentType.LIVE -> "Directo"; ContentType.SERIES -> "Series"; else -> "Películas" }

    Row(Modifier.fillMaxSize()) {
        // ---------- Categorías ----------
        Column(
            Modifier.width(250.dp).fillMaxHeight().background(C.Panel).padding(top = 24.dp, start = 12.dp, end = 12.dp),
        ) {
            T(typeName, size = 22, weight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
            LazyColumn(
                Modifier.weight(1f).focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 30.dp),
            ) {
                item(key = firstKey) {
                    MenuRow(
                        if (type == ContentType.LIVE) "Mis canales" else if (type == ContentType.SERIES) "Actualizadas" else "Recientes",
                        if (type == ContentType.LIVE) Ic.Heart else Ic.Clock,
                        onClick = { selected = firstKey },
                        selected = selected == firstKey,
                        iconTint = if (selected == firstKey) C.Accent else C.Muted,
                        modifier = Modifier.onFocusChanged { if (it.isFocused) pendingFocus = firstKey },
                    )
                }
                items(visibleCats, key = { it.key }) { cat ->
                    MenuRow(
                        cat.name, if (cat.isAdult) Ic.Lock else null,
                        onClick = { selected = cat.key },
                        onLongClick = { catMenu = cat },
                        selected = selected == cat.key,
                        iconTint = C.Muted,
                        modifier = Modifier.onFocusChanged { if (it.isFocused) pendingFocus = cat.key },
                    )
                }
                if (hasLockedAdult) {
                    item(key = "__locked") {
                        MenuRow("Contenido oculto", Ic.Lock, onClick = { askPin = true }, iconTint = C.Muted)
                    }
                } else if (unlocked) {
                    item(key = "__relock") {
                        MenuRow("Volver a ocultar", Ic.EyeOff, onClick = { repo.lockAdult() }, iconTint = C.Muted)
                    }
                }
            }
        }

        // ---------- Contenido ----------
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp, end = 28.dp, top = 24.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                T(title, size = 22, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                T(if (entries.size == 1) "1 título" else "${entries.size} títulos", size = 13, color = C.Muted, maxLines = 1)
            }
            if (entries.isEmpty()) {
                EmptyState(
                    if (selected == FAV) "Todavía no tienes canales favoritos" else "No hay nada aquí",
                    if (selected == FAV) "Mantén OK sobre un canal y elige «Añadir a favoritos»." else "Prueba con otra categoría.",
                )
            } else if (type == ContentType.LIVE) {
                LazyColumn(
                    Modifier.fillMaxSize().focusRestorer(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 40.dp, top = 4.dp, start = 4.dp, end = 4.dp),
                ) {
                    items(entries, key = { it.key }) { e -> ChannelRow(e) }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    modifier = Modifier.fillMaxSize().focusRestorer(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 40.dp, top = 6.dp, start = 6.dp, end = 6.dp),
                ) {
                    items(entries, key = { it.key }) { e ->
                        val ref = e.ref
                        val item = (e as? RefOrItem.Item)?.item
                        if (item != null) LaunchedEffect(item.key) { repo.requestGenre(item) }
                        PosterCard(
                            title = ref.title, icon = ref.icon, keySeed = ref.stableKey, width = null,
                            meta = if (item != null) itemMeta(item) else itemMeta(null, ref.year, ref.rating),
                            isSeries = ref.isSeries, watched = ref.stableKey in watched, unavailable = ref.itemKey == null,
                            onClick = { e.open(nav) },
                            onLongClick = { nav.showMenu(MenuRequest(ref)) },
                        )
                    }
                }
            }
        }
    }

    catMenu?.let { cat ->
        AppDialog(onDismiss = { catMenu = null }) {
            T(cat.name, size = 22, weight = FontWeight.SemiBold)
            T("Opciones de la categoría", size = 14, color = C.Muted)
            MenuRow("Ocultar esta categoría", Ic.EyeOff, onClick = {
                scope.launch { repo.setCategoryHidden(cat.key, true); catMenu = null }
            })
            if (cat.isAdult) {
                MenuRow("No es contenido para adultos", Ic.Check, onClick = {
                    scope.launch { repo.setCategoryAdult(cat, false); catMenu = null }
                })
            } else {
                MenuRow("Marcar como contenido para adultos", Ic.Lock, onClick = {
                    scope.launch { repo.setCategoryAdult(cat, true); catMenu = null }
                })
            }
            T("Puedes volver a mostrar las categorías ocultas en Ajustes › Categorías.", size = 13, color = C.Muted)
        }
    }

    if (askPin) AdultUnlockDialog(onDone = { askPin = false })
}

/** Elemento de una lista: o un título del catálogo o una referencia guardada en una carpeta. */
sealed class RefOrItem {
    abstract val ref: ContentRef
    abstract val key: String
    abstract fun open(nav: app.butaca.tv.AppNav)

    data class Item(val item: ItemEntity) : RefOrItem() {
        override val ref get() = item.toRef()
        override val key get() = item.key
        override fun open(nav: app.butaca.tv.AppNav) = nav.openItem(item)
    }

    data class Ref(val r: ContentRef) : RefOrItem() {
        override val ref get() = r
        override val key get() = r.stableKey
        override fun open(nav: app.butaca.tv.AppNav) = nav.openRef(r)
    }
}

@Composable
private fun ChannelRow(e: RefOrItem) {
    val nav = LocalNav.current
    val ref = e.ref
    val quality = (e as? RefOrItem.Item)?.item?.quality
    FocusSurface(
        onClick = { e.open(nav) },
        onLongClick = { nav.showMenu(MenuRequest(ref)) },
        modifier = Modifier.fillMaxWidth().height(56.dp),
        radius = 12.dp, container = C.Surface, focusedContainer = C.Raised, focusedScale = 1.02f,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(width = 72.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A303B))) {
                if (ref.icon != null) {
                    coil.compose.AsyncImage(
                        model = ref.icon, contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                } else {
                    T(ref.title.take(2).uppercase(), size = 14, weight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                }
            }
            T(ref.title, size = 15, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            when (quality) {
                4 -> Badge("4K")
                3 -> Badge("FHD")
                1 -> Badge("SD")
                else -> {}
            }
            if (ref.itemKey == null) T("No disponible", size = 13, color = C.Muted)
        }
    }
}

/** Desbloquear (o crear el PIN la primera vez). */
@Composable
fun AdultUnlockDialog(onDone: () -> Unit) {
    val repo = ButacaApp.repo
    val hasPin = repo.prefs.value.pin.isNotEmpty()
    var firstPin by remember { mutableStateOf<String?>(null) }
    if (hasPin) {
        PinDialog("Contenido oculto", "Escribe tu PIN para mostrarlo.", onDismiss = onDone, onPin = { pin ->
            val ok = repo.unlockAdult(pin)
            if (ok) onDone()
            ok
        })
    } else if (firstPin == null) {
        PinDialog("Crea un PIN", "Elige 4 cifras. Te lo pediremos para mostrar el contenido oculto.", onDismiss = onDone, onPin = { pin ->
            firstPin = pin; true
        })
    } else {
        PinDialog("Repite el PIN", "Vuelve a escribir las mismas 4 cifras.", onDismiss = onDone, onPin = { pin ->
            if (pin == firstPin) {
                repo.prefs.update { it.copy(pin = pin) }
                repo.unlockAdult(pin)
                onDone(); true
            } else { firstPin = null; false }
        })
    }
}
