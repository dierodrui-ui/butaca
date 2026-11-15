package app.butaca.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.tv.material3.Icon
import app.butaca.tv.ButacaApp
import app.butaca.tv.LocalNav
import app.butaca.tv.data.ContentRef
import app.butaca.tv.data.ContentType
import app.butaca.tv.data.FolderEntity
import app.butaca.tv.ui.C
import app.butaca.tv.ui.Ic
import app.butaca.tv.ui.components.ActionButton
import app.butaca.tv.ui.components.AppDialog
import app.butaca.tv.ui.components.ConfirmDialog
import app.butaca.tv.ui.components.EmptyState
import app.butaca.tv.ui.components.FocusSurface
import app.butaca.tv.ui.components.MenuRequest
import app.butaca.tv.ui.components.MenuRow
import app.butaca.tv.ui.components.Pill
import app.butaca.tv.ui.components.PosterCard
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.TextInputDialog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private const val CONTINUE = "continue"
private const val WATCHED = "watched"
private const val COLS = 6

private enum class SortMode(val label: String) { MINE("Mi orden"), ADDED("Añadido recientemente"), NAME("Nombre (A–Z)"), RATING("Valoración") }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FoldersScreen() {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()

    val folders by repo.folders().collectAsState(initial = emptyList())
    val counts by repo.folderCounts().collectAsState(initial = emptyMap())
    val continueRefs by repo.continueRefs().collectAsState(initial = emptyList())
    val watchedRefs by repo.watchedRefs().collectAsState(initial = emptyList())
    val watchedKeys by repo.watchedKeys().collectAsState(initial = emptySet())

    val later = folders.firstOrNull { it.system == "later" }
    val fav = folders.firstOrNull { it.system == "fav" }
    val userFolders = folders.filter { it.system == null }

    var selected by remember { mutableStateOf<String?>(null) }
    val sel = selected ?: later?.let { "f:${it.id}" } ?: CONTINUE
    val selFolder: FolderEntity? = if (sel.startsWith("f:")) folders.firstOrNull { "f:${it.id}" == sel } else null

    var filter by remember(sel) { mutableStateOf("all") }
    var sort by remember(sel) { mutableStateOf(SortMode.MINE) }
    var moving by remember(sel) { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FolderEntity?>(null) }

    val flow: Flow<List<ContentRef>> = remember(sel, selFolder?.id) {
        when {
            sel == CONTINUE -> repo.continueRefs()
            sel == WATCHED -> repo.watchedRefs()
            selFolder != null -> repo.folderContent(selFolder.id)
            else -> flowOf(emptyList())
        }
    }
    val content by flow.collectAsState(initial = emptyList())
    val filtered = content.filter { filter == "all" || it.type == filter }
    val sorted = when (sort) {
        SortMode.MINE -> filtered
        SortMode.ADDED -> filtered.sortedByDescending { it.addedAt }
        SortMode.NAME -> filtered.sortedBy { it.title.lowercase() }
        SortMode.RATING -> filtered.sortedByDescending { it.rating ?: 0.0 }
    }
    val canMove = selFolder != null && sort == SortMode.MINE && filter == "all"

    val title = when {
        sel == CONTINUE -> "Seguir viendo"
        sel == WATCHED -> "Vistas"
        else -> selFolder?.name ?: ""
    }
    val subtitle = when {
        sel == CONTINUE -> "Lo que has empezado y no has terminado."
        sel == WATCHED -> "Tu historial. Lo que terminas aparece aquí solo."
        selFolder?.system == "later" -> "Lo que terminas de ver sale solo de esta lista y pasa a «Vistas»."
        selFolder?.system == "fav" -> "Tus películas, series y canales favoritos."
        else -> "Tu carpeta. Mantén OK sobre un título para moverlo o quitarlo."
    }

    Row(Modifier.fillMaxSize()) {
        // ---------- Listas ----------
        Column(Modifier.width(280.dp).fillMaxHeight().background(C.Panel).padding(top = 28.dp, start = 14.dp, end = 14.dp)) {
            T("Mis carpetas", size = 24, weight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
            LazyColumn(Modifier.weight(1f).focusRestorer(), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
                item { T("AUTOMÁTICAS", size = 11, weight = FontWeight.Bold, color = C.Faint, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp)) }
                item {
                    MenuRow("Seguir viendo", Ic.Play, onClick = { selected = CONTINUE }, selected = sel == CONTINUE,
                        trailing = "${continueRefs.size}", iconTint = C.Muted)
                }
                if (later != null) item {
                    MenuRow(later.name, Ic.Clock, onClick = { selected = "f:${later.id}" }, selected = sel == "f:${later.id}",
                        trailing = "${counts[later.id] ?: 0}", iconTint = C.Accent)
                }
                item {
                    MenuRow("Vistas", Ic.Check, onClick = { selected = WATCHED }, selected = sel == WATCHED,
                        trailing = "${watchedRefs.size}", iconTint = C.Muted)
                }
                if (fav != null) item {
                    MenuRow(fav.name, Ic.Heart, onClick = { selected = "f:${fav.id}" }, selected = sel == "f:${fav.id}",
                        trailing = "${counts[fav.id] ?: 0}", iconTint = C.Muted)
                }
                item { T("TUS CARPETAS", size = 11, weight = FontWeight.Bold, color = C.Faint, modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp)) }
                items(userFolders, key = { it.id }) { f ->
                    MenuRow(
                        f.name, Ic.Folder, onClick = { selected = "f:${f.id}" }, selected = sel == "f:${f.id}",
                        trailing = "${counts[f.id] ?: 0}", iconTint = Color(f.color),
                        onLongClick = { editing = f },
                    )
                }
                item {
                    FocusSurface(
                        onClick = { creating = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(48.dp),
                        radius = 10.dp, container = Color.Transparent, selectedBorder = true, focusedScale = 1.02f,
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Ic.Plus, null, tint = C.Muted, modifier = Modifier.size(20.dp))
                            T("Nueva carpeta", size = 15, color = C.Muted)
                        }
                    }
                }
            }
        }

        // ---------- Contenido ----------
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 32.dp, end = 32.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        T(title, size = 30, weight = FontWeight.SemiBold, maxLines = 1)
                        T(if (filtered.size == 1) "1 título" else "${filtered.size} títulos", size = 14, color = C.Muted)
                    }
                    T(subtitle, size = 14, color = C.Muted)
                }
                if (selFolder != null && selFolder.system == null) {
                    ActionButton("Editar carpeta", Ic.Edit, onClick = { editing = selFolder }, height = 42.dp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Todo", filter == "all", { filter = "all" })
                Pill("Películas", filter == ContentType.MOVIE, { filter = ContentType.MOVIE })
                Pill("Series", filter == ContentType.SERIES, { filter = ContentType.SERIES })
                if (selFolder?.system == "fav") Pill("Canales", filter == ContentType.LIVE, { filter = ContentType.LIVE })
                Spacer(Modifier.weight(1f))
                if (selFolder != null) {
                    ActionButton("Ordenar: ${sort.label}", Ic.Sort, onClick = {
                        sort = SortMode.entries[(sort.ordinal + 1) % SortMode.entries.size]
                    }, height = 40.dp)
                }
            }
            if (moving != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(C.Accent))
                    T("Moviendo «${content.firstOrNull { it.stableKey == moving }?.title ?: ""}» · usa ◀ ▶ ▲ ▼ y pulsa OK para soltar", size = 14, color = C.TextSoft)
                }
            }
            if (sorted.isEmpty()) {
                EmptyState(
                    "Aquí no hay nada todavía",
                    when (sel) {
                        CONTINUE -> "Lo que empieces a ver aparecerá aquí."
                        WATCHED -> "Lo que termines de ver aparecerá aquí."
                        else -> "Mantén OK sobre cualquier carátula y elige dónde guardarla."
                    },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(COLS),
                    modifier = Modifier.fillMaxSize().focusRestorer().onPreviewKeyEvent { e ->
                        val key = moving ?: return@onPreviewKeyEvent false
                        val folderId = selFolder?.id ?: return@onPreviewKeyEvent false
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                        val delta = when (e.key) {
                            Key.DirectionLeft -> -1
                            Key.DirectionRight -> 1
                            Key.DirectionUp -> -COLS
                            Key.DirectionDown -> COLS
                            Key.DirectionCenter, Key.Enter, Key.Back, Key.Escape -> { moving = null; return@onPreviewKeyEvent true }
                            else -> return@onPreviewKeyEvent false
                        }
                        scope.launch { repo.moveInFolder(folderId, key, delta) }
                        true
                    },
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 40.dp, top = 6.dp, start = 6.dp, end = 6.dp),
                ) {
                    itemsIndexed(sorted, key = { _, r -> r.stableKey }) { i, ref ->
                        PosterCard(
                            title = ref.title, icon = ref.icon, keySeed = ref.stableKey, width = 150.dp,
                            meta = if (ref.itemKey == null) "Ya no está en el servidor"
                            else listOfNotNull(ref.year?.toString(), ref.rating?.let { "★ " + String.format("%.1f", it).replace('.', ',') }).joinToString(" · ").ifBlank { null },
                            isSeries = ref.isSeries,
                            watched = sel != WATCHED && ref.stableKey in watchedKeys,
                            unavailable = ref.itemKey == null,
                            position = if (canMove) i + 1 else null,
                            highlighted = moving == ref.stableKey,
                            onClick = { if (moving == null) nav.openRef(ref) },
                            onLongClick = {
                                nav.showMenu(
                                    MenuRequest(
                                        ref,
                                        inContinue = sel == CONTINUE,
                                        folderId = selFolder?.id,
                                        folderIsSystem = selFolder?.system != null,
                                        onMove = if (canMove) ({ moving = ref.stableKey }) else null,
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        TextInputDialog("Nueva carpeta", "Nombre, por ejemplo «Comedias»", "", onDismiss = { creating = false }, onDone = { name ->
            creating = false
            scope.launch {
                val id = repo.createFolder(name, C.FolderColors[userFolders.size % C.FolderColors.size])
                selected = "f:$id"
            }
        })
    }
    editing?.let { f -> EditFolderDialog(f, onDismiss = { editing = null }, onDeleted = { selected = null }) }
}

@Composable
private fun EditFolderDialog(folder: FolderEntity, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val repo = ButacaApp.repo
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    when {
        renaming -> TextInputDialog("Renombrar carpeta", "Nombre", folder.name, onDismiss = { renaming = false }, onDone = { name ->
            scope.launch { repo.updateFolder(folder.id, name, folder.color); onDismiss() }
        })
        deleting -> ConfirmDialog(
            "¿Eliminar «${folder.name}»?",
            "Se borra la carpeta, no las películas ni las series.",
            "Eliminar",
            onDismiss = { deleting = false },
            onConfirm = { scope.launch { repo.deleteFolder(folder.id); onDeleted(); onDismiss() } },
        )
        else -> AppDialog(onDismiss, width = 420) {
            T(folder.name, size = 22, weight = FontWeight.SemiBold)
            MenuRow("Renombrar", Ic.Edit, onClick = { renaming = true })
            MenuRow("Cambiar color", Ic.Folder, iconTint = Color(folder.color), onClick = {
                val i = C.FolderColors.indexOf(folder.color)
                val next = C.FolderColors[(i + 1) % C.FolderColors.size]
                scope.launch { repo.updateFolder(folder.id, folder.name, next); onDismiss() }
            })
            MenuRow("Eliminar carpeta", Ic.Trash, onClick = { deleting = true })
        }
    }
}
