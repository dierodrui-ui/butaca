package app.butaca.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import app.butaca.tv.ui.components.ConfirmDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import app.butaca.tv.ButacaApp
import app.butaca.tv.data.ContentType
import app.butaca.tv.ui.C
import app.butaca.tv.ui.Ic
import app.butaca.tv.ui.components.MenuRow
import app.butaca.tv.ui.components.T
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab(val label: String) { HOME("Inicio"), SEARCH("Buscar"), WISH("Te digo lo que ver"), LIVE("Directo"), MOVIES("Películas"), SERIES("Series"), FOLDERS("Mis carpetas"), STATS("Mi actividad"), SETTINGS("Ajustes") }

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Ic.Home
    Tab.SEARCH -> Ic.Search
    Tab.WISH -> Ic.Mic
    Tab.LIVE -> Ic.Tv
    Tab.MOVIES -> Ic.Film
    Tab.SERIES -> Ic.Series
    Tab.FOLDERS -> Ic.Folder
    Tab.STATS -> Ic.Stats
    Tab.SETTINGS -> Ic.Settings
}

@Composable
fun MainScreen(onLogout: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var askExit by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
    // Atrás: desde cualquier sección vuelve a Inicio; en Inicio pregunta antes de salir
    BackHandler { if (tab != Tab.HOME) tab = Tab.HOME else askExit = true }
    val requested by ButacaApp.repo.requestedFolder.collectAsState()
    LaunchedEffect(requested) { if (requested != null) tab = Tab.FOLDERS }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Row(Modifier.fillMaxSize()) {
            Rail(tab) { tab = it }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    Tab.HOME -> HomeScreen(onGoFolders = { tab = Tab.FOLDERS })
                    Tab.SEARCH -> SearchScreen()
                    Tab.WISH -> WishScreen()
                    Tab.LIVE -> CatalogScreen(ContentType.LIVE)
                    Tab.MOVIES -> MoviesScreen()
                    Tab.SERIES -> CatalogTabScreen(ContentType.SERIES)
                    Tab.FOLDERS -> FoldersScreen()
                    Tab.STATS -> StatsScreen()
                    Tab.SETTINGS -> SettingsScreen(onLogout)
                }
            }
        }
        SyncBanner(Modifier.align(Alignment.BottomEnd).padding(24.dp))
    }
    if (askExit) {
        ConfirmDialog(
            "¿Quieres salir de Butaca?", "Tus listas y lo que estabas viendo se quedan guardados.", "Salir",
            onDismiss = { askExit = false }, onConfirm = { activity?.finish() },
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Rail(current: Tab, onSelect: (Tab) -> Unit) {
    val repo = ButacaApp.repo
    val settings by repo.prefs.flow.collectAsState()
    val profile by repo.profile.collectAsState()
    var askPin by remember { mutableStateOf(false) }
    val kid = profile?.kid == true
    Column(
        Modifier.width(190.dp).fillMaxHeight().background(C.Rail).padding(horizontal = 12.dp, vertical = 24.dp)
            .focusRestorer(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.padding(start = 12.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            app.butaca.tv.ui.components.BrandLogo(34.dp, 22)
        }
        // Quién está viendo; pulsar para cambiar de perfil (desde un perfil infantil, con PIN)
        profile?.let { p ->
            app.butaca.tv.ui.components.FocusSurface(
                onClick = { if (p.kid && settings.pin.isNotEmpty()) askPin = true else repo.leaveProfile() },
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                radius = 12.dp, container = C.Surface, focusedContainer = C.Raised, focusedScale = 1.02f,
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProfileAvatar(p, 34)
                    Column(Modifier.weight(1f)) {
                        T(p.name, size = 14, weight = FontWeight.SemiBold, maxLines = 1)
                        val left = repo.minutesLeft(p)
                        T(
                            when { p.kid && left != null -> "Quedan $left min"; p.kid -> "Infantil"; else -> "Cambiar de perfil" },
                            size = 11, color = if (p.kid) Color(0xFF5BE3C4) else C.Muted, maxLines = 1,
                        )
                    }
                }
            }
        }
        var dice by remember { mutableStateOf(false) }
        Tab.entries.filter { it != Tab.SETTINGS }.forEach { t ->
            MenuRow(t.label, t.icon(), onClick = { onSelect(t) }, selected = t == current, iconTint = if (t == current) C.Accent else C.Muted)
            // El dado: una película que no hayas visto, elegida con tus gustos
            if (t == Tab.WISH) MenuRow("Sorpréndeme", Ic.Dice, onClick = { dice = true }, iconTint = C.Accent)
        }
        if (dice) DiceDialog(onDismiss = { dice = false })
        Spacer(Modifier.weight(1f))
        val exp = settings.expDate
        val statusText = when {
            exp > 0 -> "Caduca el " + SimpleDateFormat("d MMM yyyy", Locale("es", "ES")).format(Date(exp * 1000))
            settings.accountStatus.isNotBlank() -> settings.accountStatus
            else -> "Cuenta conectada"
        }
        Row(
            Modifier.padding(start = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val active = settings.accountStatus.isBlank() || settings.accountStatus.equals("Active", true)
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (active) C.Green else C.Red))
            T(statusText, size = 12, color = C.Muted, maxLines = 1)
        }
        if (!kid) MenuRow(Tab.SETTINGS.label, Tab.SETTINGS.icon(), onClick = { onSelect(Tab.SETTINGS) }, selected = current == Tab.SETTINGS, iconTint = if (current == Tab.SETTINGS) C.Accent else C.Muted)
    }
    if (askPin) app.butaca.tv.ui.components.PinDialog("PIN de adulto", "Para salir del perfil infantil.", onDismiss = { askPin = false }, onPin = { pin ->
        val ok = pin == repo.prefs.value.pin
        if (ok) { askPin = false; repo.leaveProfile() }
        ok
    })
}

@Composable
private fun SyncBanner(modifier: Modifier) {
    val s by ButacaApp.repo.sync.collectAsState()
    val kids by app.butaca.tv.data.KidsPrep.progress.collectAsState()
    val cloudRunning by app.butaca.tv.data.Cloud.running.collectAsState()
    val text = when {
        s.running || s.error != null -> s.error ?: s.message
        kids != null -> "Leyendo géneros en TMDB… ${kids!!.first} de ${kids!!.second}"
        cloudRunning -> "Copiando tus listas en TMDB…"
        else -> return
    }
    val isError = s.error != null
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(C.Raised).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (isError) C.Red else C.Accent))
        T(text, size = 14, color = C.TextSoft, maxLines = 2, modifier = Modifier.width(360.dp))
    }
}
