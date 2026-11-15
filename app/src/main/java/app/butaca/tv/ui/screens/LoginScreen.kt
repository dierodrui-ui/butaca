package app.butaca.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import app.butaca.tv.ButacaApp
import app.butaca.tv.ui.C
import app.butaca.tv.ui.Ic
import app.butaca.tv.ui.components.ActionButton
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.TvTextField
import app.butaca.tv.ui.components.requestFocusSafely
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val repo = ButacaApp.repo
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(repo.prefs.value.server) }
    var user by remember { mutableStateOf(repo.prefs.value.user) }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); requestFocusSafely(first) }

    fun submit() {
        if (busy) return
        if (server.isBlank() || user.isBlank() || pass.isBlank()) {
            error = "Rellena los tres campos"; return
        }
        busy = true; error = null
        scope.launch {
            val err = repo.login(server, user, pass)
            busy = false
            if (err == null) onLoggedIn() else error = err
        }
    }

    Box(Modifier.fillMaxSize().background(C.Bg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(20.dp)).background(C.Surface)
                .border(1.dp, C.BorderSoft, RoundedCornerShape(20.dp)).padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(C.Accent), contentAlignment = Alignment.Center) {
                    Icon(Ic.Play, null, tint = C.OnAccent, modifier = Modifier.size(17.dp))
                }
                T("Butaca", size = 23, weight = FontWeight.Bold)
            }
            T("Conecta tu cuenta", size = 28, weight = FontWeight.SemiBold)
            T("Usa los mismos datos que en XCIPTV: la URL del servidor, tu usuario y tu contraseña.", size = 15, color = C.Muted)

            T("URL del servidor", size = 14, weight = FontWeight.Medium, color = C.TextSoft)
            TvTextField(server, { server = it }, "http://tu-servidor.com:8080", Modifier.focusRequester(first))
            T("Usuario", size = 14, weight = FontWeight.Medium, color = C.TextSoft)
            TvTextField(user, { user = it }, "Tu usuario")
            T("Contraseña", size = 14, weight = FontWeight.Medium, color = C.TextSoft)
            TvTextField(pass, { pass = it }, "Tu contraseña", password = true, onSubmit = { submit() })

            if (error != null) T(error!!, size = 14, color = C.Red)

            ActionButton(
                if (busy) "Conectando…" else "Conectar", null,
                onClick = { submit() }, primary = true, modifier = Modifier.fillMaxWidth(), height = 56.dp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Ic.Lock, null, tint = C.Muted, modifier = Modifier.size(18.dp))
                T("Tus datos se guardan solo en este dispositivo.", size = 13, color = C.Muted)
            }
        }
    }
}
