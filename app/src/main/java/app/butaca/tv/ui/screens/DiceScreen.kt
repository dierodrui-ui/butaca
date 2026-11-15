package app.butaca.tv.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.butaca.tv.ButacaApp
import app.butaca.tv.LocalNav
import app.butaca.tv.data.ItemEntity
import app.butaca.tv.data.rollDice
import app.butaca.tv.data.toRef
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.padding
import app.butaca.tv.ui.C
import app.butaca.tv.ui.Ic
import app.butaca.tv.ui.components.ActionButton
import app.butaca.tv.ui.components.AppDialog
import app.butaca.tv.ui.components.Artwork
import app.butaca.tv.ui.components.LocalTaste
import app.butaca.tv.ui.components.MatchLabel
import app.butaca.tv.ui.components.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * «Sorpréndeme»: el dado rueda y sale una película de tu catálogo que no hayas visto,
 * elegida con tus gustos y con lo último que has visto.
 */
@Composable
fun DiceDialog(onDismiss: () -> Unit) {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var pick by remember { mutableStateOf<ItemEntity?>(null) }
    var rolling by remember { mutableStateOf(true) }
    var face by remember { mutableIntStateOf(5) }
    var rolls by remember { mutableIntStateOf(0) }
    var none by remember { mutableStateOf(false) }
    val spin = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    val shown = remember { HashSet<String>() }
    val playFocus = remember { FocusRequester() }
    var vote by remember { mutableStateOf<Boolean?>(null) }       // pulgar de esta tirada
    var rating by remember { mutableStateOf<ItemEntity?>(null) }  // «Vista» → ¿qué te pareció?
    val folders by remember { repo.folders() }.collectAsState(initial = emptyList())
    val laterId = folders.firstOrNull { it.system == "later" }?.id

    LaunchedEffect(rolls) {
        rolling = true; reveal.snapTo(0f); none = false; vote = null
        val result = scope.launch {
            val r = try { repo.rollDice(shown) } catch (_: Exception) { null }
            pick = r
            r?.let { shown += it.stableKey }
        }
        // El dado rueda (y se va frenando) mientras se elige
        launch { spin.snapTo(0f); spin.animateTo(1f, tween(1500, easing = FastOutSlowInEasing)) }
        var wait = 60L
        repeat(14) { face = (1..6).random(); delay(wait); wait += 12 }
        result.join()
        rolling = false
        if (pick == null) none = true
        reveal.animateTo(1f, tween(500))
        delay(80); try { playFocus.requestFocus() } catch (_: Exception) { }
    }

    rating?.let { r ->
        app.butaca.tv.ui.components.RatingDialog(r.toRef(), onDismiss = { rating = null; rolls++ })
        return
    }
    AppDialog(onDismiss, width = 760) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DiceFace(face, Modifier.size(54.dp).graphicsLayer { rotationZ = spin.value * 720f })
            Column {
                T("Sorpréndeme", size = 24, weight = FontWeight.Bold)
                T(if (rolling) "Tirando el dado…" else "Una que no has visto y que encaja contigo", size = 13, color = C.Muted)
            }
        }
        val p = pick
        if (!rolling && p != null) {
            Row(Modifier.graphicsLayer { alpha = reveal.value; translationY = (1 - reveal.value) * 30f }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
                    Artwork(p.icon, p.name, p.stableKey, Modifier.fillMaxSize(), titleSize = 16)
                }
                // ¿Buena sorpresa? Pulgar arriba (verde) o abajo (rojo: no vuelve a salir)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    ThumbButton(Ic.ThumbUp, Color(0xFF3DD68C), vote == true) {
                        vote = true; app.butaca.tv.data.DiceVotes.vote(repo, p.stableKey, true)
                    }
                    ThumbButton(Ic.ThumbDown, Color(0xFFE8344B), vote == false) {
                        vote = false; app.butaca.tv.data.DiceVotes.vote(repo, p.stableKey, false)
                        nav.toast("Anotado: «${p.name}» no volverá a salir")
                        rolls++
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(p.name, size = 24, weight = FontWeight.Bold, maxLines = 2, lineHeight = 28)
                    T(listOfNotNull(p.year?.toString(), p.genre?.split(',')?.take(3)?.joinToString(", "), p.rating?.let { "★ %.1f".format(it).replace('.', ',') }).joinToString(" · "), size = 14, color = C.Muted, maxLines = 2)
                    MatchLabel(LocalTaste.current?.match(p.genre, p.rating, p.tmdbId))
                    p.plot?.takeIf { it.isNotBlank() }?.let { T(it, size = 13, color = C.TextSoft, maxLines = 4, lineHeight = 18) }
                }
            }
            if (vote == true) T("¡Anotado! Te gusta la sorpresa: tendrá en cuenta títulos así.", size = 13, color = Color(0xFF3DD68C))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                val inLater by remember(p.stableKey) { repo.foldersOf(p.stableKey) }.collectAsState(initial = emptySet())
                val isLater = laterId != null && laterId in inLater
                if (laterId != null) ActionButton(if (isLater) "En tu lista" else "Ver más adelante", Ic.Clock, onClick = {
                    scope.launch { repo.toggleFolder(laterId, p.toRef(), isLater) }
                }, active = if (isLater) C.Gold else null)
                ActionButton("Vista", Ic.Check, onClick = {
                    scope.launch { repo.markWatched(p.toRef(), true) }
                    rating = p
                })
                ActionButton("Otra tirada", Ic.Dice, onClick = { rolls++ })
                ActionButton("Ver ficha", Ic.Info, onClick = { onDismiss(); nav.openItem(p) })
                ActionButton("Reproducir", Ic.Play, onClick = {
                    scope.launch { repo.playRequestFor(p, 0)?.let { onDismiss(); nav.play(it) } }
                }, primary = true, modifier = Modifier.focusRequester(playFocus))
            }
        } else if (none) {
            T("Ya has visto todo lo que encaja… o todavía se está leyendo tu catálogo en TMDB. Prueba en un rato.", size = 15, color = C.TextSoft)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ActionButton("Cerrar", null, onClick = onDismiss, primary = true, modifier = Modifier.focusRequester(playFocus))
            }
        } else {
            Box(Modifier.fillMaxWidth().height(160.dp))
        }
    }
}

@Composable
private fun ThumbButton(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, on: Boolean, onClick: () -> Unit) {
    app.butaca.tv.ui.components.FocusSurface(
        onClick = onClick, modifier = Modifier.size(52.dp), radius = 999.dp,
        container = if (on) color else color.copy(alpha = 0.18f), focusedContainer = color,
        content = if (on) Color.White else color, focusedContent = Color.White, focusedScale = 1.12f,
    ) { androidx.tv.material3.Icon(icon, null, tint = androidx.tv.material3.LocalContentColor.current, modifier = Modifier.size(24.dp).align(Alignment.Center)) }
}

/** Cara de un dado con sus puntos. */
@Composable
private fun DiceFace(n: Int, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFFFF5466), Color(0xFFC8102E))), size = Size(s, s), cornerRadius = CornerRadius(s * 0.22f))
        val r = s * 0.085f
        val a = s * 0.27f; val m = s * 0.5f; val b = s * 0.73f
        val dots = when (n) {
            1 -> listOf(m to m)
            2 -> listOf(a to a, b to b)
            3 -> listOf(a to a, m to m, b to b)
            4 -> listOf(a to a, b to a, a to b, b to b)
            5 -> listOf(a to a, b to a, m to m, a to b, b to b)
            else -> listOf(a to a, b to a, a to m, b to m, a to b, b to b)
        }
        dots.forEach { (x, y) -> drawCircle(Color.White, r, Offset(x, y)) }
    }
}
