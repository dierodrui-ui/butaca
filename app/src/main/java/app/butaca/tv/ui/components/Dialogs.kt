package app.butaca.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import app.butaca.tv.ButacaApp
import app.butaca.tv.data.ContentRef
import app.butaca.tv.data.ContentType
import app.butaca.tv.ui.C
import app.butaca.tv.ui.Ic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppDialog(onDismiss: () -> Unit, width: Int = 460, content: @Composable ColumnScope.() -> Unit) {
    // Si el menú se abrió manteniendo OK, el «soltar» de ese botón no debe pulsar la primera opción.
    // Ignoramos las teclas hasta que se suelte el botón (o pase un momento sin pulsar nada).
    val opened = remember { System.currentTimeMillis() }
    var armed by remember { mutableStateOf(false) }
    var lastDown by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (!armed) {
            delay(120)
            val now = System.currentTimeMillis()
            if (now - opened > 450 && now - lastDown > 350) armed = true
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .onPreviewKeyEvent { e ->
                    if (armed) false
                    else {
                        if (e.type == KeyEventType.KeyDown) lastDown = System.currentTimeMillis()
                        if (e.type == KeyEventType.KeyUp) armed = true
                        e.key != Key.Back
                    }
                }
                .width(width.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(C.Surface)
                .border(1.dp, C.Border, RoundedCornerShape(18.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

fun requestFocusSafely(r: FocusRequester) {
    try { r.requestFocus() } catch (_: Exception) { }
}

/** Petición para abrir el menú rápido (mantener OK). */
data class MenuRequest(
    val ref: ContentRef,
    val inContinue: Boolean = false,
    val folderId: Long? = null,
    val folderIsSystem: Boolean = false,
    val onMove: (() -> Unit)? = null,
)

@Composable
fun QuickMenu(
    req: MenuRequest,
    onOpen: (ContentRef) -> Unit,
    onDismiss: () -> Unit,
) {
    val repo = ButacaApp.repo
    val scope = rememberCoroutineScope()
    val ref = req.ref
    val folders by remember(ref.stableKey) { repo.foldersOf(ref.stableKey) }.collectAsState(initial = emptySet())
    val allFolders by remember() { repo.folders() }.collectAsState(initial = emptyList())
    val progress by remember(ref.stableKey) { repo.progressOf(ref.stableKey) }.collectAsState(initial = null)
    val laterId = allFolders.firstOrNull { it.system == "later" }?.id
    val favId = allFolders.firstOrNull { it.system == "fav" }?.id
    val inLater = laterId != null && laterId in folders
    val inFav = favId != null && favId in folders
    val watched = progress?.finished == true
    var picker by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); requestFocusSafely(first) }

    if (picker) {
        FolderPicker(ref, onDismiss = { picker = false; onDismiss() })
        return
    }

    AppDialog(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Artwork(
                ref.icon, ref.title, ref.stableKey,
                Modifier.width(52.dp).height(78.dp).clip(RoundedCornerShape(7.dp)), showTitle = false,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T(ref.title, size = 20, weight = FontWeight.SemiBold, maxLines = 2)
                val kind = when (ref.type) { ContentType.SERIES -> "Serie"; ContentType.LIVE -> "Canal"; else -> "Película" }
                T(listOfNotNull(kind, ref.year?.toString()).joinToString(" · "), size = 13, color = C.Muted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ref.itemKey != null) {
                MenuRow(
                    when (ref.type) { ContentType.LIVE -> "Ver ahora"; ContentType.SERIES -> "Ver serie"; else -> "Ver ficha" },
                    if (ref.isLive) Ic.Play else Ic.Info,
                    onClick = { onDismiss(); onOpen(ref) },
                    modifier = Modifier.focusRequester(first),
                )
            } else {
                MenuRow("Ya no está en el servidor", Ic.Info, onClick = {}, modifier = Modifier.focusRequester(first), iconTint = C.Muted)
            }
            if (req.onMove != null) {
                MenuRow("Mover", Ic.Move, onClick = { onDismiss(); req.onMove.invoke() })
            }
            if (!ref.isLive && laterId != null) {
                MenuRow(
                    if (inLater) "Quitar de «Ver más adelante»" else "Ver más adelante",
                    if (inLater) Ic.Minus else Ic.Clock,
                    onClick = { scope.launch { repo.toggleFolder(laterId, ref, inLater); onDismiss() } },
                )
            }
            MenuRow("Añadir a carpeta…", Ic.FolderPlus, onClick = { picker = true })
            if (favId != null) {
                MenuRow(
                    if (inFav) "Quitar de favoritos" else "Añadir a favoritos",
                    if (inFav) Ic.HeartFilled else Ic.Heart,
                    onClick = { scope.launch { repo.toggleFolder(favId, ref, inFav); onDismiss() } },
                )
            }
            if (!ref.isLive) {
                MenuRow(
                    if (watched) "Marcar como no vista" else "Marcar como vista",
                    Ic.Check,
                    onClick = { scope.launch { repo.markWatched(ref, !watched); onDismiss() } },
                )
            }
            if (req.inContinue) {
                MenuRow("Quitar de «Seguir viendo»", Ic.Close, onClick = { scope.launch { repo.dismissContinue(ref.stableKey); onDismiss() } })
            }
            if (req.folderId != null && !req.folderIsSystem) {
                MenuRow("Quitar de esta carpeta", Ic.Minus, onClick = { scope.launch { repo.removeFromFolder(req.folderId, ref.stableKey); onDismiss() } })
            }
        }
        T("Atrás para cerrar", size = 13, color = C.Muted)
    }
}

@Composable
fun FolderPicker(ref: ContentRef, onDismiss: () -> Unit) {
    val repo = ButacaApp.repo
    val scope = rememberCoroutineScope()
    val folders by remember() { repo.folders() }.collectAsState(initial = emptyList())
    val inFolders by remember(ref.stableKey) { repo.foldersOf(ref.stableKey) }.collectAsState(initial = emptySet())
    var creating by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(folders.isNotEmpty()) { if (folders.isNotEmpty()) { delay(80); requestFocusSafely(first) } }

    if (creating) {
        TextInputDialog(
            title = "Nueva carpeta",
            hint = "Nombre, por ejemplo «Comedias»",
            initial = "",
            onDismiss = { creating = false },
            onDone = { name ->
                creating = false
                scope.launch {
                    val color = C.FolderColors[folders.size % C.FolderColors.size]
                    val id = repo.createFolder(name, color)
                    repo.addToFolder(id, ref)
                }
            },
        )
        return
    }

    AppDialog(onDismiss, width = 440) {
        T("Añadir a carpeta", size = 24, weight = FontWeight.SemiBold)
        T("Elige una o varias. Puedes cambiarlo cuando quieras.", size = 14, color = C.Muted)
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(folders.filter { it.system != "fav" }, key = { it.id }) { f ->
                val on = f.id in inFolders
                FocusSurface(
                    onClick = { scope.launch { repo.toggleFolder(f.id, ref, on) } },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                        .then(if (f.id == folders.firstOrNull { it.system != "fav" }?.id) Modifier.focusRequester(first) else Modifier),
                    radius = 12.dp, focusedScale = 1.02f,
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier.size(24.dp).clip(RoundedCornerShape(7.dp))
                                .background(if (on) C.Accent else Color.Transparent)
                                .border(2.dp, if (on) C.Accent else Color(0xFF4A5363), RoundedCornerShape(7.dp)),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(Ic.Check, null, tint = C.OnAccent, modifier = Modifier.size(16.dp)) }
                        T(f.name, size = 16, weight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }
            }
        }
        FocusSurface(
            onClick = { creating = true },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            radius = 12.dp, container = Color.Transparent, selectedBorder = true, focusedScale = 1.02f,
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Ic.Plus, null, tint = C.Muted, modifier = Modifier.size(22.dp))
                T("Crear carpeta nueva", size = 16, color = C.TextSoft)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ActionButton("Listo", null, onClick = onDismiss, primary = true, height = 46.dp)
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    hint: String,
    initial: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
    password: Boolean = false,
    allowEmpty: Boolean = false,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); requestFocusSafely(focus) }
    AppDialog(onDismiss) {
        T(title, size = 22, weight = FontWeight.SemiBold)
        TvTextField(
            value = text, onValue = { text = it }, hint = hint,
            modifier = Modifier.focusRequester(focus), password = password,
            onSubmit = { if (allowEmpty || text.isNotBlank()) onDone(text.trim()) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            ActionButton("Cancelar", null, onClick = onDismiss, height = 46.dp)
            ActionButton("Guardar", null, onClick = { if (allowEmpty || text.isNotBlank()) onDone(text.trim()) }, primary = true, height = 46.dp)
        }
    }
}

/**
 * Campo de texto para el mando: el teclado solo aparece al pulsar OK,
 * y al confirmar (✓) se esconde para dejar ver lo que hay debajo.
 */
@Composable
fun TvTextField(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    onSubmit: () -> Unit = {},
    focused: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
) {
    var hasFocus by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = TextStyle(color = C.Text, fontSize = 16.sp),
        cursorBrush = SolidColor(C.Accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = imeAction, showKeyboardOnFocus = false),
        keyboardActions = KeyboardActions(
            onDone = { keyboard?.hide(); onSubmit() },
            onSearch = { keyboard?.hide(); onSubmit() },
            onGo = { keyboard?.hide(); onSubmit() },
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                    keyboard?.show(); true
                } else false
            }
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0F1318))
            .border(if (hasFocus || focused) 2.dp else 1.5.dp, if (hasFocus || focused) C.Accent else C.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) T(hint, size = 15, color = Color(0xFF7D8696), maxLines = 1)
                inner()
            }
        },
    )
}

/** Teclado numérico para el PIN, manejable con las flechas del mando. */
@Composable
fun PinDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onPin: (String) -> Boolean,   // devuelve false si el PIN es incorrecto
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); requestFocusSafely(first) }
    AppDialog(onDismiss, width = 400) {
        T(title, size = 22, weight = FontWeight.SemiBold)
        T(if (error) "PIN incorrecto. Prueba otra vez." else text, size = 14, color = if (error) C.Red else C.Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
            repeat(4) { i ->
                Box(
                    Modifier.size(18.dp).clip(CircleShape)
                        .background(if (i < pin.length) C.Accent else Color.Transparent)
                        .border(2.dp, if (i < pin.length) C.Accent else Color(0xFF4A5363), CircleShape)
                )
            }
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            keys.chunked(3).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { c, k ->
                        FocusSurface(
                            onClick = {
                                error = false
                                when (k) {
                                    "⌫" -> pin = pin.dropLast(1)
                                    "OK" -> if (pin.length == 4) { if (!onPin(pin)) { error = true; pin = "" } }
                                    else -> if (pin.length < 4) {
                                        pin += k
                                        if (pin.length == 4) { if (!onPin(pin)) { error = true; pin = "" } }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).height(52.dp).then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier),
                            radius = 10.dp, container = if (k == "OK") C.Accent else C.Surface3,
                            focusedContainer = if (k == "OK") C.Accent else C.Raised,
                            content = if (k == "OK") C.OnAccent else C.Text,
                            focusedContent = if (k == "OK") C.OnAccent else C.Text,
                        ) {
                            T(k, size = 18, weight = FontWeight.SemiBold, color = androidx.tv.material3.LocalContentColor.current, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); requestFocusSafely(first) }
    AppDialog(onDismiss, width = 440) {
        T(title, size = 22, weight = FontWeight.SemiBold)
        T(text, size = 15, color = C.Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            ActionButton("Cancelar", null, onClick = onDismiss, height = 46.dp, modifier = Modifier.focusRequester(first))
            ActionButton(confirm, null, onClick = { onConfirm(); onDismiss() }, primary = true, height = 46.dp)
        }
    }
}
