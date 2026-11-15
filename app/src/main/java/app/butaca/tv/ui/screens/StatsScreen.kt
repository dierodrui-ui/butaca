package app.butaca.tv.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.butaca.tv.ButacaApp
import app.butaca.tv.KeyRouter
import app.butaca.tv.LocalNav
import app.butaca.tv.data.ContentRef
import app.butaca.tv.data.UsageDayEntity
import app.butaca.tv.data.WatchLogEntity
import app.butaca.tv.ui.C
import app.butaca.tv.ui.components.Artwork
import app.butaca.tv.ui.components.FocusSurface
import app.butaca.tv.ui.components.PosterCard
import app.butaca.tv.ui.components.SectionTitle
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.TvRow
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

fun durationLabel(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> "menos de 1 min"
        min < 60 -> "$min min"
        min % 60 == 0L -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60} min"
    }
}

private val dayIn = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private fun dayLabel(day: String, pattern: String) = try { SimpleDateFormat(pattern, Locale("es", "ES")).format(dayIn.parse(day)!!) } catch (_: Exception) { day }

/** «Mi actividad»: estadísticas e historial del perfil que está viendo. */
@Composable
fun StatsScreen() {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    val profile by repo.profile.collectAsState()
    val stats = remember { repo.user.stats() }
    val days by remember { stats.observeDays(60) }.collectAsState(initial = emptyList())
    val totalApp by remember { stats.observeTotalApp() }.collectAsState(initial = 0L)
    val totalWatch by remember { stats.observeTotalWatch() }.collectAsState(initial = 0L)
    val firstDay by remember { stats.observeFirstDay() }.collectAsState(initial = null)
    val history by remember { stats.observeHistory() }.collectAsState(initial = emptyList())
    val top by remember { stats.observeTop(15) }.collectAsState(initial = emptyList())
    val finished by remember { repo.user.progress().observeWatched() }.collectAsState(initial = emptyList())
    val genres by produceState(emptyList<Pair<String, Long>>(), top) {
        val counts = HashMap<String, Long>()
        val items = repo.db.items().byStableKeys(top.map { it.stableKey }).associateBy { it.stableKey }
        top.forEach { t -> items[t.stableKey]?.genre?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach { g -> counts[g] = (counts[g] ?: 0) + t.total } }
        value = counts.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value }
    }

    val cal = Calendar.getInstance()
    val last14 = (13 downTo 0).map { back ->
        val c = cal.clone() as Calendar; c.add(Calendar.DAY_OF_MONTH, -back); dayIn.format(c.time)
    }
    val byDay = days.associateBy { it.day }
    val week = last14.takeLast(7).sumOf { byDay[it]?.appMs ?: 0L }
    val today = byDay[last14.last()]

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 28.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "head") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                profile?.let { ProfileAvatar(it, 56) }
                Column {
                    T("Mi actividad", size = 28, weight = FontWeight.Bold)
                    T(
                        (profile?.name ?: "") + (firstDay?.let { " · usando Butaca desde el " + dayLabel(it, "d 'de' MMMM yyyy") } ?: ""),
                        size = 14, color = C.Muted,
                    )
                }
            }
        }
        item(key = "cards") {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatCard("Tiempo en Butaca", durationLabel(totalApp), "desde que empezaste", Modifier.weight(1f))
                StatCard("Viendo películas y series", durationLabel(totalWatch), "en total", Modifier.weight(1f))
                StatCard("Esta semana", durationLabel(week), "hoy: " + durationLabel(today?.appMs ?: 0), Modifier.weight(1f))
                StatCard("Títulos terminados", "${finished.size}", "películas y series", Modifier.weight(1f))
            }
        }
        item(key = "chart") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Últimos 14 días")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Legend(C.Raised, "Con la app abierta")
                    Legend(C.Accent, "Viendo algo")
                }
                DaysChart(last14, byDay)
            }
        }
        if (top.isNotEmpty()) item(key = "top") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Lo que más has visto")
                TvRow {
                    items(top, key = { "t" + it.stableKey }) { t ->
                        PosterCard(
                            title = t.title, icon = t.icon, keySeed = t.stableKey, meta = durationLabel(t.total),
                            isSeries = t.type == "series",
                            onClick = { nav.openRef(ContentRef(t.stableKey, t.type, t.title, t.icon, null, null, null)) },
                        )
                    }
                }
            }
        }
        if (genres.isNotEmpty()) item(key = "genres") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Tus géneros")
                val max = genres.maxOf { it.second }.coerceAtLeast(1)
                genres.forEach { (g, ms) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        T(g, size = 14, modifier = Modifier.width(150.dp), maxLines = 1)
                        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(999.dp)).background(C.Surface)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((ms.toFloat() / max).coerceIn(0.03f, 1f)).clip(RoundedCornerShape(999.dp)).background(C.Accent))
                        }
                        T(durationLabel(ms), size = 13, color = C.Muted, modifier = Modifier.width(110.dp))
                    }
                }
            }
        }
        item(key = "histTitle") { SectionTitle("Historial", extra = if (history.isEmpty()) "todavía vacío" else null) }
        var lastDay = ""
        history.forEach { h ->
            if (h.day != lastDay) {
                val d = h.day
                lastDay = d
                item(key = "d$d") { T(dayLabel(d, "EEEE d 'de' MMMM").replaceFirstChar { it.uppercase() }, size = 13, weight = FontWeight.Bold, color = C.Faint) }
            }
            item(key = "h${h.day}${h.stableKey}") { HistoryRow(h) }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, sub: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        T(title, size = 12, weight = FontWeight.SemiBold, color = C.Muted, maxLines = 1)
        T(value, size = 26, weight = FontWeight.ExtraBold, maxLines = 1)
        T(sub, size = 12, color = C.Faint, maxLines = 1)
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        T(text, size = 12, color = C.Muted)
    }
}

@Composable
private fun DaysChart(days: List<String>, byDay: Map<String, UsageDayEntity>) {
    val max = days.maxOf { byDay[it]?.appMs ?: 0L }.coerceAtLeast(30 * 60_000L)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(900)) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(18.dp)) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val slot = size.width / days.size
            val bw = slot * 0.5f
            days.forEachIndexed { i, d ->
                val u = byDay[d]
                val x = i * slot + (slot - bw) / 2
                val appH = size.height * ((u?.appMs ?: 0L).toFloat() / max) * grow.value
                val watchH = size.height * ((u?.watchMs ?: 0L).toFloat() / max) * grow.value
                drawRoundRect(C.Raised, Offset(x, size.height - appH), Size(bw, appH), CornerRadius(6f, 6f))
                drawRoundRect(C.Accent, Offset(x + bw * 0.2f, size.height - watchH), Size(bw * 0.6f, watchH), CornerRadius(6f, 6f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            days.forEach { d -> T(dayLabel(d, "EEE").take(2).uppercase() + "\n" + dayLabel(d, "d"), size = 10, color = C.Faint, modifier = Modifier.weight(1f), lineHeight = 13) }
        }
    }
}

@Composable
private fun HistoryRow(h: WatchLogEntity) {
    val nav = LocalNav.current
    FocusSurface(
        onClick = { nav.openRef(ContentRef(h.stableKey, h.type, h.title, h.icon, null, null, null)) },
        modifier = Modifier.fillMaxWidth().height(64.dp), radius = 12.dp, container = C.Surface, focusedContainer = C.Raised, focusedScale = 1.01f,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(width = 34.dp, height = 50.dp).clip(RoundedCornerShape(6.dp))) { Artwork(h.icon, h.title, h.stableKey, Modifier.fillMaxSize()) }
            Column(Modifier.weight(1f)) {
                T(h.title, size = 15, weight = FontWeight.SemiBold, maxLines = 1)
                T(listOfNotNull(if (h.type == "series") "Serie" else "Película", h.subtitle).joinToString(" · "), size = 12, color = C.Muted, maxLines = 1)
            }
            T(durationLabel(h.watchedMs), size = 14, weight = FontWeight.SemiBold, color = C.AccentSoft)
            T(SimpleDateFormat("HH:mm", Locale("es", "ES")).format(java.util.Date(h.lastAt)), size = 12, color = C.Faint)
        }
    }
}

/** Fin del tiempo del perfil infantil: mensaje amable y vuelta a «¿Quién está ahí?». */
@Composable
fun TimeUpOverlay(name: String, onDone: () -> Unit) {
    val a = remember { Animatable(0f) }
    DisposableEffect(Unit) {
        val prev = KeyRouter.handler
        KeyRouter.handler = { true }   // nada de saltárselo
        onDispose { KeyRouter.handler = prev }
    }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(900)); delay(7000); onDone() }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1033), Color(0xFF1B1347)))).alpha(a.value),
        contentAlignment = Alignment.Center,
    ) {
        // Estrellas
        Canvas(Modifier.fillMaxSize()) {
            val r = java.util.Random(7)
            repeat(60) {
                val x = r.nextFloat() * size.width; val y = r.nextFloat() * size.height * 0.8f
                drawCircle(Color.White.copy(alpha = 0.3f + 0.5f * r.nextFloat() * a.value), 1.5f + r.nextFloat() * 2.5f, Offset(x, y))
            }
            // Luna
            val c = Offset(size.width * 0.78f, size.height * 0.24f)
            drawCircle(Color(0xFFFFE9A8), 70f, c)
            drawCircle(Color(0xFF12123A), 64f, c + Offset(28f, -14f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            T("¡Se acabó el tiempo de tele${if (name.isNotBlank()) ", $name" else ""}!", size = 40, weight = FontWeight.ExtraBold, color = Color.White)
            T("Ahora toca descansar los ojos y jugar a otra cosa. ¡Hasta luego!", size = 20, color = Color(0xFFD7D9FF))
        }
    }
}

/** Aviso para los peques cuando quedan pocos minutos. */
@Composable
fun KidTimeWarning() {
    val repo = ButacaApp.repo
    val profile by repo.profile.collectAsState()
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profile?.id, profile?.timerMin) {
        val p = profile ?: return@LaunchedEffect
        if (!p.kid || p.timerMin <= 0) return@LaunchedEffect
        var warned5 = false; var warned1 = false
        while (true) {
            val left = repo.minutesLeft(repo.profile.value) ?: break
            if (left in 2..5 && !warned5) { warned5 = true; msg = "¡Quedan $left minutos de tele!"; delay(9000); msg = null }
            else if (left <= 1 && !warned1) { warned1 = true; msg = "¡Queda 1 minuto de tele!"; delay(9000); msg = null }
            delay(15_000)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(msg != null, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            Box(
                Modifier.padding(top = 26.dp).clip(RoundedCornerShape(999.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF2EC4A6), Color(0xFF4F8CFF)))).padding(horizontal = 26.dp, vertical = 12.dp)
            ) { T("⏰  " + (msg ?: ""), size = 20, weight = FontWeight.ExtraBold, color = Color.White) }
        }
    }
}
