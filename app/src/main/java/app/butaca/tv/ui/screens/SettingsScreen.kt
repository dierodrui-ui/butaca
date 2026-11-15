package app.butaca.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.fillMaxWidth
import app.butaca.tv.data.cloudStart
import app.butaca.tv.data.cloudFinish
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
import app.butaca.tv.ui.components.TextInputDialog
import app.butaca.tv.data.startKidsPrep
import app.butaca.tv.data.cloudSyncInBackground
import app.butaca.tv.data.cloudDisconnect
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
    var editingKey by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var pendingKids by remember { mutableStateOf<Int?>(null) }
    var kidsPinFor by remember { mutableStateOf<Int?>(null) }
    val kidsProgress by app.butaca.tv.data.KidsPrep.progress.collectAsState()
    val cloudRunning by app.butaca.tv.data.Cloud.running.collectAsState()
    val cloudMsg by app.butaca.tv.data.Cloud.message.collectAsState()
    val nav = app.butaca.tv.LocalNav.current
    var tmdbTest by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
                    item {
                        SettingRow("Intro al abrir Butaca", "La presentación con el logo y el sonido. Se salta con cualquier botón.",
                            onClick = { set { it.copy(intro = !it.intro) } }, switch = s.intro)
                    }
                    item { Header("SUBTÍTULOS") }
                    item {
                        val o = listOf("Solo forzados en castellano", "Siempre en castellano", "Desactivados")
                        SettingRow("Subtítulos", "Los forzados traducen carteles y frases en otro idioma.",
                            onClick = { set { it.copy(subs = (it.subs + 1) % 3) } }, value = o[s.subs.coerceIn(0, 2)])
                    }
                }
                Section.PARENTAL -> {
                    item { Header("PERFIL INFANTIL") }
                    item {
                        val kp = kidsProgress
                        SettingRow(
                            "Perfil infantil",
                            if (kp != null) "Preparando edades con TMDB: ${kp.first} de ${kp.second}…"
                            else "Solo muestra lo apto para la edad elegida, según la calificación oficial (TMDB). Para quitarlo hace falta el PIN.",
                            onClick = {
                                val next = when (s.kidsLimit) { 0 -> 7; 7 -> 12; else -> 0 }
                                when {
                                    !repo.tmdb.enabled -> nav.toast("El perfil infantil necesita la clave de TMDB (Ajustes › Cuenta)")
                                    s.kidsLimit == 0 && s.pin.isEmpty() -> { pendingKids = next; changingPin = true }
                                    s.kidsLimit == 0 -> { repo.prefs.update { it.copy(kidsLimit = next) }; repo.startKidsPrep() }
                                    else -> kidsPinFor = next
                                }
                            },
                            value = when (s.kidsLimit) { 7 -> "Hasta 7 años"; 12 -> "Hasta 12 años"; else -> "Desactivado" },
                        )
                    }
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
                    item {
                        val hasKey = s.tmdbKey.isNotBlank() || app.butaca.tv.BuildConfig.TMDB_KEY.isNotBlank()
                        SettingRow(
                            "Clave de TMDB",
                            "Para sagas, director, reparto, recomendaciones y búsqueda en dos idiomas.",
                            onClick = { editingKey = true },
                            value = if (s.tmdbKey.isNotBlank()) "Propia" else if (hasKey) "Incluida" else "Añadir",
                        )
                    }
                    item { Header("COPIA EN LA NUBE (CUENTA DE TMDB)") }
                    if (s.tmdbSession.isBlank()) {
                        item {
                            SettingRow(
                                "Conectar tu cuenta de TMDB",
                                "Guarda «Ver más adelante», Favoritos, Vistas y tus carpetas en tu cuenta. Si reinstalas o cambias de aparato, se recuperan.",
                                onClick = { if (repo.tmdb.enabled) connecting = true else nav.toast("Primero añade la clave de TMDB") },
                                value = "Conectar",
                            )
                        }
                    } else {
                        item {
                            val last = if (s.lastCloudSync > 0) SimpleDateFormat("d MMM, HH:mm", Locale("es", "ES")).format(Date(s.lastCloudSync)) else "nunca"
                            SettingRow(
                                "Copiar ahora",
                                cloudMsg ?: "Cuenta: ${s.tmdbUser.ifBlank { "conectada" }} · última copia: $last. Se hace sola cada 6 horas.",
                                onClick = { if (!cloudRunning) repo.cloudSyncInBackground() },
                                value = if (cloudRunning) "Copiando…" else "Copiar",
                            )
                        }
                        item {
                            SettingRow(
                                "Desconectar la cuenta de TMDB", "Tus listas se quedan en este aparato y en tu cuenta de TMDB.",
                                onClick = { repo.cloudDisconnect() }, value = "Desconectar",
                            )
                        }
                    }
                    item {
                        SettingRow(
                            "Probar TMDB", tmdbTest ?: "Comprueba si la clave funciona y hay conexión.",
                            onClick = { tmdbTest = "Probando…"; scope.launch { tmdbTest = repo.tmdb.test() } },
                            value = "Probar",
                        )
                    }
                    item { SettingRow("Cerrar sesión", "Tus carpetas y favoritos se quedan guardados en este dispositivo.", onClick = { confirmLogout = true }, value = "Salir") }
                    item {
                        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            T("Butaca ${appVersion()} · Las actualizaciones conservan tus carpetas, favoritos y progreso.", size = 13, color = C.Muted)
                            T("Datos de películas y series: TMDB (themoviedb.org). Este producto usa la API de TMDB pero no está respaldado ni certificado por TMDB.", size = 12, color = C.Muted)
                        }
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
                if (pin == first) {
                    repo.prefs.update { it.copy(pin = pin) }; changingPin = false
                    // Si veníamos de activar el perfil infantil, ahora sí se activa
                    pendingKids?.let { k -> repo.prefs.update { it.copy(kidsLimit = k) }; repo.startKidsPrep(); pendingKids = null }
                    true
                }
                else { first = null; false }
            })
        }
    }
    kidsPinFor?.let { next ->
        PinDialog("Escribe tu PIN", "Necesario para cambiar o quitar el perfil infantil.", onDismiss = { kidsPinFor = null }, onPin = { pin ->
            val ok = pin == repo.prefs.value.pin
            if (ok) { repo.prefs.update { it.copy(kidsLimit = next) }; kidsPinFor = null; if (next > 0) repo.startKidsPrep() }
            ok
        })
    }
    if (connecting) CloudConnectDialog(onDismiss = { connecting = false })
    if (editingKey) {
        TextInputDialog(
            "Clave de TMDB", "Pega o escribe la clave (deja vacío para usar la incluida)", s.tmdbKey,
            onDismiss = { editingKey = false },
            onDone = { k ->
                repo.prefs.update { it.copy(tmdbKey = k.trim()) }; editingKey = false
                tmdbTest = "Probando…"; scope.launch { tmdbTest = repo.tmdb.test() }
            },
            allowEmpty = true,
        )
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

/** Código QR para abrir el enlace en el móvil. */
private fun qrBitmap(text: String, size: Int = 480): androidx.compose.ui.graphics.ImageBitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(
        text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
    )
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp.asImageBitmap()
}

/** Conectar la cuenta de TMDB: se aprueba desde el móvil escaneando el código. */
@Composable
private fun CloudConnectDialog(onDismiss: () -> Unit) {
    val repo = ButacaApp.repo
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        token = repo.cloudStart()
        if (token == null) msg = "No se ha podido conectar con TMDB. Comprueba la clave en Ajustes › Cuenta."
    }
    app.butaca.tv.ui.components.AppDialog(onDismiss, width = 600) {
        T("Conectar tu cuenta de TMDB", size = 22, weight = FontWeight.SemiBold)
        T(
            "1. Escanea el código con el móvil (o escribe la dirección en el navegador).\n" +
                "2. Inicia sesión en TMDB y pulsa «Aprobar».\n" +
                "3. Vuelve aquí y pulsa «Ya lo he aprobado».",
            size = 14, color = C.TextSoft,
        )
        token?.let { t ->
            val url = app.butaca.tv.data.approveUrl(t)
            val qr = remember(url) { qrBitmap(url) }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.Image(qr, "Código QR", modifier = Modifier.size(170.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    T("O entra en:", size = 13, color = C.Muted)
                    T(url, size = 14, weight = FontWeight.SemiBold, color = C.Accent)
                }
            }
        }
        msg?.let { T(it, size = 14, color = C.Red) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End)) {
            app.butaca.tv.ui.components.ActionButton("Cancelar", null, onClick = onDismiss)
            app.butaca.tv.ui.components.ActionButton(
                if (busy) "Comprobando…" else "Ya lo he aprobado", null, primary = true,
                onClick = {
                    val t = token ?: return@ActionButton
                    if (busy) return@ActionButton
                    busy = true
                    scope.launch {
                        val err = repo.cloudFinish(t)
                        busy = false
                        if (err == null) onDismiss() else msg = err
                    }
                },
            )
        }
    }
}
