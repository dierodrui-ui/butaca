package app.butaca.tv.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.butaca.tv.ButacaApp
import app.butaca.tv.data.ProfileEntity
import app.butaca.tv.ui.Bebas
import app.butaca.tv.ui.C
import app.butaca.tv.ui.Ic
import app.butaca.tv.ui.LogoBrush
import app.butaca.tv.ui.components.ActionButton
import app.butaca.tv.ui.components.AppDialog
import app.butaca.tv.ui.components.ConfirmDialog
import app.butaca.tv.ui.components.FocusSurface
import app.butaca.tv.ui.components.MenuRow
import app.butaca.tv.ui.components.PinDialog
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.TextInputDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val PROFILE_COLORS = listOf(0xFFE8344B, 0xFF4F8CFF, 0xFF2EC4A6, 0xFFFFB020, 0xFFB46CFF, 0xFFFF7A45, 0xFF4CC2FF, 0xFFFF5FA2)
private val TIMER_OPTIONS = listOf(0, 15, 20, 30, 45, 60, 90, 120)
private val ADS_OPTIONS = listOf(15, 20, 30, 0)
private val AGE_OPTIONS = listOf(3, 7, 12)

fun ageLabel(limit: Int) = when (limit) { 3 -> "Solo para todos los públicos"; 7 -> "Hasta 7 años"; else -> "Hasta 12 años" }
fun timerLabel(min: Int) = if (min <= 0) "Sin límite" else if (min < 60) "$min minutos" else if (min % 60 == 0) "${min / 60} h" else "${min / 60} h ${min % 60} min"

/** Círculo de color con la inicial: el «avatar» del perfil. */
@Composable
fun ProfileAvatar(p: ProfileEntity, size: Int, modifier: Modifier = Modifier) {
    val c = Color(p.color)
    Box(
        modifier.size(size.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(c.copy(alpha = 1f), c.copy(red = c.red * 0.55f, green = c.green * 0.55f, blue = c.blue * 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            p.name.trim().take(1).uppercase().ifBlank { "?" }, fontFamily = Bebas, fontSize = (size * 0.52f).sp,
            color = Color.White, textAlign = TextAlign.Center,
        )
    }
}

/** «¿Quién está ahí?» */
@Composable
fun ProfilePickerScreen() {
    val repo = ButacaApp.repo
    val scope = rememberCoroutineScope()
    val profiles by remember { repo.profiles() }.collectAsState(initial = emptyList())
    val settings by repo.prefs.flow.collectAsState()
    var managing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProfileEntity?>(null) }
    var askPin by remember { mutableStateOf<(() -> Unit)?>(null) }
    var expired by remember { mutableStateOf<ProfileEntity?>(null) }
    var createPin by remember { mutableStateOf<(() -> Unit)?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(profiles.isNotEmpty()) { if (profiles.isNotEmpty()) { delay(150); try { first.requestFocus() } catch (_: Exception) { } } }

    // Aparición suave
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(700)) }

    fun withPin(action: () -> Unit) { if (settings.pin.isEmpty()) action() else askPin = action }
    fun enterProfile(p: ProfileEntity) {
        when {
            p.kid && p.timerExpired -> expired = p
            !p.kid && settings.lockAdults && settings.pin.isNotEmpty() -> askPin = { scope.launch { repo.selectProfile(p) } }
            else -> scope.launch { repo.selectProfile(p) }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF1A0B10), C.Bg), radius = 1400f)).alpha(enter.value),
        contentAlignment = Alignment.Center,
    ) {
        Text("BUTACA", style = androidx.compose.ui.text.TextStyle(brush = LogoBrush, fontFamily = Bebas, fontSize = 34.sp, letterSpacing = 1.5.sp),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 30.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(30.dp)) {
            T(if (managing) "Gestionar perfiles" else "¿Quién está ahí?", size = 40, weight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(28.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 40.dp, vertical = 14.dp)) {
                items(profiles, key = { it.id }) { p ->
                    var focused by remember { mutableStateOf(false) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(150.dp)) {
                        FocusSurface(
                            onClick = { if (managing) withPin { editing = p } else enterProfile(p) },
                            onLongClick = { withPin { editing = p } },
                            modifier = Modifier.size(132.dp).then(if (p == profiles.first()) Modifier.focusRequester(first) else Modifier)
                                .onFocusChanged { focused = it.isFocused },
                            radius = 999.dp, container = Color.Transparent, focusedContainer = Color.Transparent, focusedScale = 1.1f, borderWidth = 4.dp,
                        ) {
                            ProfileAvatar(p, 132)
                            if (managing) Box(Modifier.size(132.dp).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                                Icon(Ic.Edit, null, tint = Color.White, modifier = Modifier.size(34.dp))
                            }
                            if (p.kid && p.timerExpired) Box(Modifier.size(132.dp).background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                                Icon(Ic.Lock, null, tint = Color.White, modifier = Modifier.size(34.dp))
                            }
                        }
                        T(p.name, size = 18, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = if (focused) C.Text else C.TextSoft, maxLines = 1)
                        if (p.kid) {
                            val left = repo.minutesLeft(p)
                            Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (p.timerExpired) C.Raised else Color(0x332EC4A6)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                                T(
                                    when { p.timerExpired -> "Tiempo agotado"; left != null -> "Infantil · quedan $left min"; else -> "Infantil" },
                                    size = 11, weight = FontWeight.Bold, color = if (p.timerExpired) C.Muted else Color(0xFF5BE3C4),
                                )
                            }
                        }
                    }
                }
                item(key = "add") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(150.dp)) {
                        FocusSurface(
                            onClick = { withPin { editing = ProfileEntity(0, "", PROFILE_COLORS[profiles.size % PROFILE_COLORS.size], false, 7, 0, 0, false, 20, true, 0, 0) } },
                            modifier = Modifier.size(132.dp), radius = 999.dp, container = C.Surface, focusedContainer = C.Raised, focusedScale = 1.1f, borderWidth = 4.dp,
                        ) { Icon(Ic.Plus, null, tint = C.TextSoft, modifier = Modifier.size(44.dp).align(Alignment.Center)) }
                        T("Añadir perfil", size = 18, color = C.TextSoft)
                    }
                }
            }
            ActionButton(
                if (managing) "Listo" else "Gestionar perfiles", if (managing) Ic.Check else Ic.Edit,
                onClick = { if (managing) managing = false else withPin { managing = true } },
            )
        }
    }

    editing?.let { p ->
        ProfileEditor(
            p, onDismiss = { editing = null },
            onSave = { np ->
                val save = {
                    scope.launch {
                        repo.saveProfile(np)
                        // Con un perfil infantil, los de adulto pasan a pedir PIN
                        if (np.kid && !repo.prefs.value.lockAdults) repo.prefs.update { it.copy(lockAdults = true) }
                    }
                    editing = null
                }
                if (np.kid && repo.prefs.value.pin.isEmpty()) createPin = { save() } else save()
            },
            onDelete = { scope.launch { repo.deleteProfile(p.id) }; editing = null },
        )
    }
    askPin?.let { action ->
        PinDialog("PIN de adulto", "Escribe el PIN para continuar.", onDismiss = { askPin = null }, onPin = { pin ->
            val ok = pin == repo.prefs.value.pin
            if (ok) { askPin = null; action() }
            ok
        })
    }
    createPin?.let { action -> CreatePinFlow(onDone = { createPin = null; action() }, onCancel = { createPin = null }) }
    expired?.let { p -> TimeExpiredDialog(p, onDismiss = { expired = null }, onEnter = { expired = null; scope.launch { repo.selectProfile(it) } }) }
}

/** Crear el PIN la primera vez (para los perfiles infantiles). */
@Composable
fun CreatePinFlow(onDone: () -> Unit, onCancel: () -> Unit) {
    val repo = ButacaApp.repo
    var first by remember { mutableStateOf<String?>(null) }
    if (first == null) {
        PinDialog("Crea un PIN de adulto", "Lo pediremos para salir del perfil infantil, darle más tiempo o cambiar sus ajustes.", onDismiss = onCancel, onPin = { first = it; true })
    } else {
        PinDialog("Repite el PIN", "Vuelve a escribir las mismas 4 cifras.", onDismiss = onCancel, onPin = { pin ->
            if (pin == first) { repo.prefs.update { it.copy(pin = pin) }; onDone(); true } else { first = null; false }
        })
    }
}

/** El perfil infantil ha gastado su tiempo: solo un adulto con PIN puede dar más. */
@Composable
fun TimeExpiredDialog(p: ProfileEntity, onDismiss: () -> Unit, onEnter: (ProfileEntity) -> Unit) {
    val repo = ButacaApp.repo
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }   // 0 aviso, 1 PIN, 2 elegir tiempo
    when (step) {
        0 -> AppDialog(onDismiss) {
            T("Se acabó el tiempo de tele", size = 24, weight = FontWeight.Bold)
            T("${p.name} ya ha visto los ${timerLabel(p.timerMin)} que tenía. Un adulto puede darle más tiempo con el PIN.", size = 15, color = C.TextSoft)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                ActionButton("Volver", null, onClick = onDismiss)
                ActionButton("Dar más tiempo", Ic.Lock, onClick = { step = 1 }, primary = true)
            }
        }
        1 -> PinDialog("PIN de adulto", "Para dar más tiempo a ${p.name}.", onDismiss = onDismiss, onPin = { pin ->
            val ok = pin == repo.prefs.value.pin
            if (ok) step = 2
            ok
        })
        else -> AppDialog(onDismiss) {
            T("¿Cuánto tiempo más?", size = 22, weight = FontWeight.Bold)
            TIMER_OPTIONS.forEach { m ->
                MenuRow(timerLabel(m), Ic.Clock, onClick = {
                    scope.launch {
                        repo.resetTimer(p.id, m)
                        repo.db.profiles().get(p.id)?.let { onEnter(it) }
                    }
                })
            }
        }
    }
}

/** Crear o editar un perfil. */
@Composable
fun ProfileEditor(p: ProfileEntity, onDismiss: () -> Unit, onSave: (ProfileEntity) -> Unit, onDelete: () -> Unit) {
    var d by remember { mutableStateOf(p) }
    var naming by remember { mutableStateOf(p.name.isBlank()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val repo = ButacaApp.repo
    val settings by repo.prefs.flow.collectAsState()
    fun <T> next(list: List<T>, cur: T) = list[(list.indexOf(cur) + 1).mod(list.size)]

    AppDialog(onDismiss, width = 560) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProfileAvatar(d, 64)
            Column {
                T(if (p.id == 0L) "Nuevo perfil" else "Editar perfil", size = 22, weight = FontWeight.Bold)
                T(if (d.kid) "Perfil infantil: solo contenido para su edad" else "Perfil de adulto", size = 13, color = C.Muted)
            }
        }
        MenuRow("Nombre", Ic.Edit, onClick = { naming = true }, trailing = d.name.ifBlank { "Escribe un nombre" })
        MenuRow("Color", null, onClick = { d = d.copy(color = next(PROFILE_COLORS, d.color)) }, trailing = "Cambiar")
        MenuRow("Tipo de perfil", null, onClick = { d = d.copy(kid = !d.kid) }, trailing = if (d.kid) "Infantil" else "Adulto")
        if (d.kid) {
            MenuRow("Edad del contenido", null, onClick = { d = d.copy(kidsLimit = next(AGE_OPTIONS, if (d.kidsLimit in AGE_OPTIONS) d.kidsLimit else 7)) }, trailing = ageLabel(d.kidsLimit))
            MenuRow("Temporizador", Ic.Clock, onClick = { d = d.copy(timerMin = next(TIMER_OPTIONS, d.timerMin), timerUsedMs = 0, timerExpired = false) }, trailing = timerLabel(d.timerMin))
            MenuRow("Consejos animados", null, onClick = { d = d.copy(adsEvery = next(ADS_OPTIONS, d.adsEvery)) }, trailing = if (d.adsEvery <= 0) "Nunca" else "Cada ${d.adsEvery} min")
            MenuRow("Consejos en la esquina", null, onClick = { d = d.copy(tips = !d.tips) }, trailing = if (d.tips) "Sí" else "No")
            MenuRow("Nombres de papá y mamá", null, onClick = { naming = false; editingParents = true }, trailing = settings.parentsNames)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            if (p.id != 0L && p.id != 1L) ActionButton("Eliminar", Ic.Trash, onClick = { confirmDelete = true })
            ActionButton("Cancelar", null, onClick = onDismiss)
            ActionButton("Guardar", Ic.Check, onClick = { if (d.name.isNotBlank()) onSave(d) else naming = true }, primary = true)
        }
    }
    if (naming) TextInputDialog("Nombre del perfil", "Por ejemplo: Lucía", d.name, onDismiss = { naming = false }, onDone = { d = d.copy(name = it); naming = false })
    if (editingParents) TextInputDialog("Nombres de papá y mamá", "Diego y Chari", settings.parentsNames,
        onDismiss = { editingParents = false }, onDone = { v -> repo.prefs.update { it.copy(parentsNames = v) }; editingParents = false })
    if (confirmDelete) ConfirmDialog("¿Eliminar a ${p.name}?", "Se borran sus listas, su historial y sus estadísticas.", "Eliminar",
        onDismiss = { confirmDelete = false }, onConfirm = { confirmDelete = false; onDelete() })
}

private var editingParents by mutableStateOf(false)
