package app.butaca.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.butaca.tv.ButacaApp
import app.butaca.tv.data.ContentRef
import app.butaca.tv.data.TasteModel
import app.butaca.tv.data.clearRating
import app.butaca.tv.data.rate
import app.butaca.tv.ui.C
import app.butaca.tv.ui.Ic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lo aprendido de tus gustos, disponible en toda la app para pintar el «% para ti». */
val LocalTaste = staticCompositionLocalOf<TasteModel?> { null }

fun matchColor(m: Int): Color = when {
    m >= 80 -> Color(0xFF3DD68C)
    m >= 65 -> Color(0xFFA9E34B)
    m >= 50 -> Color(0xFFFFC24A)
    else -> Color(0xFF9CA3B3)
}

/** Píldora pequeña para las carátulas: «87 %». */
@Composable
fun MatchPill(match: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xE607080C)).padding(horizontal = 7.dp, vertical = 3.dp),
    ) { T("$match%", size = 11, weight = FontWeight.ExtraBold, color = matchColor(match)) }
}

/** En la ficha: «87 % para ti». */
@Composable
fun MatchLabel(match: Int?) {
    if (match == null) return
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(matchColor(match).copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { T("$match% para ti", size = 14, weight = FontWeight.ExtraBold, color = matchColor(match)) }
}

private fun scoreColor(n: Int) = lerp(Color(0xFFE8344B), Color(0xFF3DD68C), (n - 1) / 9f)
private fun scoreWord(n: Int) = when (n) {
    1, 2 -> "No me ha gustado nada"
    3, 4 -> "No es para mí"
    5, 6 -> "Está bien"
    7, 8 -> "Me ha gustado"
    else -> "¡Me ha encantado!"
}

/** «¿Qué te ha parecido?»: nota del 1 al 10 que alimenta tus recomendaciones. */
@Composable
fun RatingDialog(ref: ContentRef, onDismiss: () -> Unit, onRated: (Int) -> Unit = {}) {
    val repo = ButacaApp.repo
    val scope = rememberCoroutineScope()
    val current by remember(ref.stableKey) { repo.user.taste().observe(ref.stableKey) }.collectAsState(initial = null)
    var focused by remember { mutableStateOf(current ?: 7) }
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); try { start.requestFocus() } catch (_: Exception) { } }
    AppDialog(onDismiss, width = 640) {
        T("¿Qué te ha parecido?", size = 24, weight = FontWeight.Bold)
        T(ref.title, size = 15, color = C.TextSoft, maxLines = 1)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..10).forEach { n ->
                val col = scoreColor(n)
                val sel = current == n
                FocusSurface(
                    onClick = { scope.launch { repo.rate(ref, n); onRated(n); onDismiss() } },
                    modifier = Modifier.size(48.dp)
                        .then(if (n == (current ?: 7)) Modifier.focusRequester(start) else Modifier)
                        .onFocusChanged { if (it.isFocused) focused = n },
                    radius = 999.dp, container = if (sel) col else col.copy(alpha = 0.18f), focusedContainer = col,
                    content = if (sel) Color.White else col, focusedContent = Color.White, focusedScale = 1.15f,
                ) { T("$n", size = 18, weight = FontWeight.ExtraBold, color = Color.Unspecified, modifier = Modifier.align(Alignment.Center)) }
            }
        }
        T(scoreWord(focused), size = 16, weight = FontWeight.SemiBold, color = scoreColor(focused))
        T("Tu nota enseña a Butaca lo que te gusta: con ella calcula el «% para ti» de cada título y lo que te recomienda.", size = 12, color = C.Faint)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            if (current != null) ActionButton("Quitar mi nota", Ic.Trash, onClick = { scope.launch { repo.clearRating(ref.stableKey); onDismiss() } })
            ActionButton("Ahora no", null, onClick = onDismiss)
        }
    }
}

/** Botón de la ficha: «Valorar» o «Tu nota: 8». */
@Composable
fun RateButton(ref: ContentRef) {
    val repo = ButacaApp.repo
    val score by remember(ref.stableKey) { repo.user.taste().observe(ref.stableKey) }.collectAsState(initial = null)
    var open by remember { mutableStateOf(false) }
    ActionButton(score?.let { "Tu nota: $it" } ?: "Valorar", Ic.Star, onClick = { open = true })
    if (open) RatingDialog(ref, onDismiss = { open = false })
}
