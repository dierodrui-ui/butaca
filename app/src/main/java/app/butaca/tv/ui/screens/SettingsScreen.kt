package app.butaca.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.butaca.tv.ButacaApp
import app.butaca.tv.data.CategoryEntity
import app.butaca.tv.data.ContentType
import app.butaca.tv.data.Settings
import app.butaca.tv.ui.C
import app.butaca.tv.ui.components.ConfirmDialog
import app.butaca.tv.ui.components.MenuRow
import app.butaca.tv.ui.components.PinDialog
import app.butaca.tv.ui.components.SettingRow
import app.butaca.tv.ui.components.T
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Section(val label: String) { PLAYBACK("Reproducción"), PARENTAL("Control parental"), CATEGORIES("Categorías del servidor"), ACCOUNT("Cuenta y servidor") }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(onLogout: () -> Unit) {
    val repo = ButacaApp.repo
    val s by repo.prefs.flow.collectAsState()
    val unlocked by repo.adultUnlocked.collectAsState()
    var section by remember { mutableStateOf(Section.PLAYBACK) }
    var onlyAdult by remember { mutableStateOf(false) }
    // Acción pendiente que necesita el PIN antes de hacerse
    var guarded by remember { mutableStateOf<(() -> Unit)?>(null) }
    var changingPin by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    fun withPin(action: () -> Unit) {
        if (!s.adultHide || unlocked || s.pin.isEmpty()) action() else guarded = action
    }
    fun set(t: (Settings) -> Settings) = repo.prefs.update(t)

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(280.dp).fillMaxHeight().background(C.Panel).padding(top = 28.dp, start = 14.dp, end = 14.dp)) {
            T("Ajustes", size = 24, weight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 14.dp))
            Column(Modifier.focusRestorer(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Section.entries.forEach { sec ->
                    MenuRow(sec.label, null, onClick = { section = sec; if (sec != Section.CATEGORIES) onlyAdult = false }, selected = section == sec)
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(start = 32.dp, end = 40.dp, top = 30.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    T(section.label, size = 30, weight = FontWeight.SemiBold)
                    T(
                        when (section) {
                            Section.PLAYBACK -> "Se aplica a todo lo que reproduces, sin tener que tocar nada cada vez."
                            Section.PARENTAL -> "Lo que está oculto no se ve en ninguna parte de la app sin el PIN."
                            Section.CATEGORIES -> "Pulsa OK sobre una categoría para cambiar entre visible, oculta o de adultos."
                            Section.ACCOUNT -> "Los datos de tu servidor."
                        }, size = 14, color = C.Muted,
                    )
                }
            }
            when (section) {
                Section.PLAYBACK -> {
                    item { Header("CALIDAD") }
                    item {
                        val q = listOf("La mejor disponible · hasta 4K", "Full HD (1080p)", "HD (720p)")
                        SettingRow("Calidad preferida", "Si hay varias versiones de un canal o película, elige la mejor.",
                            onClick = { set { it.copy(quality = (it.quality + 1) % 3) } }, value = q[s.quality.coerceIn(0, 2)])
                    }
                    item {
                        SettingRow("Bajar calidad si se corta", "Pasa a otra versión si la conexión no da para la actual.",
                            onClick = { set { it.copy(autoDown = !it.autoDown) } }, switch = s.autoDown)
                    }
                    item { Header("AUDIO") }
                    item {
                        val l = listOf("Castellano", "Inglés", "Idioma original")
                        SettingRow("Idioma de audio", "Se elige solo al empezar cualquier película, serie o canal.",
                            onClick = { set { it.copy(audioLang = (it.audioLang + 1) % 3) } }, value = l[s.audioLang.coerceIn(0, 2)])
                    }
                    item {
                        SettingRow("Dar prioridad al castellano de España", "Si hay castellano y latino, pone el de España.",
                            onClick = { set { it.copy(preferSpain = !it.preferSpain) } }, switch = s.preferSpain)
                    }
                    item {
                        SettingRow("Recordar la pista en cada serie", "Si cambias de pista en un episodio, los siguientes empiezan igual.",
                            onClick = { set { it.copy(rememberTrack = !it.rememberTrack) } }, switch = s.rememberTrack)
                    }
                    item {
                        SettingRow("Leer audio AC3, E-AC3 y DTS", "Evita que se salte la pista en castellano por su formato.",
                            onClick = { set { it.copy(compat = !it.compat) } }, switch = s.compat)
                    }
                    item { Header("SUBTÍTULOS") }
                    item {
                        val o = listOf("Solo forzados en castellano", "Siempre en castellano", "Desactivados")
                        SettingRow("Subtítulos", "Los forzados traducen carteles y frases en otro idioma.",
                            onClick = { set { it.copy(subs = (it.subs + 1) % 3) } }, value = o[s.subs.coerceIn(0, 2)])
                    }
                }
                Section.PARENTAL -> {
                    item { Header("CONTENIDO PARA ADULTOS") }
                    item {
                        SettingRow("Ocultar contenido para adultos", "No aparece en Inicio, Novedades, el buscador, las categorías ni tus listas.",
                            onClick = {
                                if (s.adultHide) withPin { set { it.copy(adultHide = false) } }
                                else set { it.copy(adultHide = true) }
                            }, switch = s.adultHide)
                    }
                    item { Header("DESBLOQUEO") }
                    item {
                        SettingRow("PIN para mostrarlo", "Se pide cada vez que quieras ver el contenido oculto.",
                            onClick = { withPin { changingPin = true } }, value = if (s.pin.isEmpty()) "Crear PIN" else "Cambiar PIN")
                    }
                    item {
                        SettingRow("Volver a ocultar automáticamente", "Al salir de la app o tras 30 minutos sin usarla.",
                            onClick = { set { it.copy(relock = !it.relock) } }, switch = s.relock)
                    }
                    item { Header("DETECCIÓN") }
                    item {
                        SettingRow("Categorías marcadas como adultos", "Por la marca del servidor y por nombres como XXX, Adultos o +18.",
                            onClick = { withPin { onlyAdult = true; section = Section.CATEGORIES } }, value = "Revisar")
                    }
                    item {
                        SettingRow("Ocultar categorías nuevas dudosas", "Si el servidor añade una categoría sospechosa, queda oculta hasta que la revises.",
                            onClick = { set { it.copy(newCatsHidden = !it.newCatsHidden) } }, switch = s.newCatsHidden)
                    }
                }
                Section.CATEGORIES -> {
                    item { CategoriesList(onlyAdult, onToggleOnly = { onlyAdult = !onlyAdult }, withPin = { withPin(it) }) }
                }
                Section.ACCOUNT -> {
                    item {
                        val exp = if (s.expDate > 0) SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("es", "ES")).format(Date(s.expDate * 1000)) else "Sin fecha"
                        SettingRow("Estado de la cuenta", "Caduca: $exp", onClick = {}, value = if (s.accountStatus.equals("Active", true)) "Activa" else s.accountStatus.ifBlank { "—" })
                    }
                    item { SettingRow("Servidor", s.server, onClick = {}) }
                    item { SettingRow("Usuario", s.user, onClick = {}) }
                    item {
                        val last = if (s.lastSync > 0) SimpleDateFormat("d MMM, HH:mm", Locale("es", "ES")).format(Date(s.lastSync)) else "nunca"
                        SettingRow("Actualizar catálogo ahora", "Última actualización: $last. Se hace sola una vez al día.",
                            onClick = { repo.syncInBackground() }, value = "Actualizar")
                    }
                    item { SettingRow("Cerrar sesión", "Tus carpetas y favoritos se quedan guardados en este dispositivo.", onClick = { confirmLogout = true }, value = "Salir") }
                    item {
                        T("Butaca ${appVersion()} · Las actualizaciones conservan tus carpetas, favoritos y progreso.", size = 13, color = C.Muted, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }

    guarded?.let { action ->
        PinDialog("Escribe tu PIN", "Necesario para cambiar el control parental.", onDismiss = { guarded = null }, onPin = { pin ->
            val ok = repo.unlockAdult(pin)
            if (ok) { guarded = null; action() }
            ok
        })
    }
    if (changingPin) {
        var first by remember { mutableStateOf<String?>(null) }
        if (first == null) {
            PinDialog("Nuevo PIN", "Elige 4 cifras.", onDismiss = { changingPin = false }, onPin = { first = it; true })
        } else {
            PinDialog("Repite el PIN", "Vuelve a escribir las mismas 4 cifras.", onDismiss = { changingPin = false }, onPin = { pin ->
                if (pin == first) { repo.prefs.update { it.copy(pin = pin) }; changingPin = false; true }
                else { first = null; false }
            })
        }
    }
    if (confirmLogout) {
        ConfirmDialog("¿Cerrar sesión?", "Tendrás que volver a escribir el servidor, el usuario y la contraseña.", "Cerrar sesión",
            onDismiss = { confirmLogout = false }, onConfirm = onLogout)
    }
}

@Composable
private fun Header(text: String) {
    T(text, size = 11, weight = FontWeight.Bold, color = C.Faint, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 4.dp))
}

@Composable
private fun CategoriesList(onlyAdult: Boolean, onToggleOnly: () -> Unit, withPin: (() -> Unit) -> Unit) {
    val repo = ButacaApp.repo
    val scope = rememberCoroutineScope()
    val all by remember() { repo.allCategories() }.collectAsState(initial = emptyList())
    val list = if (onlyAdult) all.filter { it.isAdult } else all
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow(
            if (onlyAdult) "Mostrando solo las de adultos" else "Mostrando todas",
            "Pulsa para cambiar el filtro.", onClick = onToggleOnly, value = if (onlyAdult) "Solo adultos" else "Todas",
        )
        listOf(ContentType.LIVE to "DIRECTO", ContentType.MOVIE to "PELÍCULAS", ContentType.SERIES to "SERIES").forEach { (type, label) ->
            val cats = list.filter { it.type == type }
            if (cats.isNotEmpty()) {
                Header(label)
                cats.forEach { cat -> CategoryRow(cat) { next ->
                    val change = {
                        scope.launch {
                            when (next) {
                                "visible" -> { repo.setCategoryHidden(cat.key, false); if (cat.isAdult) repo.setCategoryAdult(cat, false) }
                                "hidden" -> { repo.setCategoryHidden(cat.key, true); if (cat.isAdult) repo.setCategoryAdult(cat, false) }
                                "adult" -> { repo.setCategoryHidden(cat.key, false); repo.setCategoryAdult(cat, true) }
                            }
                        }
                        Unit
                    }
                    if (cat.isAdult) withPin(change) else change()
                } }
            }
        }
    }
}

@Composable
private fun CategoryRow(cat: CategoryEntity, onChange: (String) -> Unit) {
    val state = when {
        cat.isAdult -> "Adultos (oculta)"
        cat.hidden -> "Oculta"
        else -> "Visible"
    }
    val next = when {
        cat.isAdult -> "visible"
        cat.hidden -> "adult"
        else -> "hidden"
    }
    SettingRow(cat.name, "Pulsa OK para cambiar", onClick = { onChange(next) }, value = state)
}

private fun appVersion(): String = try {
    val ctx = ButacaApp.instance
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
} catch (_: Exception) { "" }
