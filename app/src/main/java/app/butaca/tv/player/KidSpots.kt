package app.butaca.tv.player

import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.butaca.tv.KeyRouter
import app.butaca.tv.R
import app.butaca.tv.ui.components.T
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Un consejo para los peques: lo que se ve, lo que se dice y los colores. */
data class KidSpot(
    val id: String,
    val title: String,
    val voice: String,       // {n} = nombre del peque, {p} = papá y mamá
    val corner: String,      // versión corta para la esquina
    val emoji: String,
    val top: Color,
    val bottom: Color,
    val body: Color,
)

val KID_SPOTS = listOf(
    KidSpot("water", "¡Hora de beber agua!", "¡Hola {n}! ¿Tienes sed? Es un buen momento para beber un poquito de agua.", "¿Un poquito de agua?", "💧", Color(0xFF38B6FF), Color(0xFF1D5FD6), Color(0xFF7FD3FF)),
    KidSpot("toilet", "¿Necesitas ir al baño?", "{n}, si tienes ganas de ir al baño, ¡ve ahora! La peli te espera aquí.", "¿Ganas de ir al baño? ¡Ve ahora!", "🚽", Color(0xFF9B7BFF), Color(0xFF5B3FD6), Color(0xFFC9B8FF)),
    KidSpot("teeth", "¡Dientes brillantes!", "Recuerda lavarte los dientes después de comer. ¡Así tendrás una sonrisa preciosa!", "Después de comer, ¡a lavarse los dientes!", "🦷", Color(0xFF3DDBC3), Color(0xFF0E8F8A), Color(0xFFFFFFFF)),
    KidSpot("food", "¡Come sano!", "Las frutas y las verduras te dan superpoderes. ¡Come sano para crecer fuerte!", "Fruta y verdura = superpoderes", "🍎", Color(0xFF7BD957), Color(0xFF2E9E3E), Color(0xFFFF5A5A)),
    KidSpot("parents", "¡Te quieren mucho!", "Haz caso a {p}, que te quieren muchísimo. ¡Dales un abrazo enorme!", "{p} te quieren mucho", "❤️", Color(0xFFFF7AA8), Color(0xFFD6336C), Color(0xFFFF4D6D)),
    KidSpot("hands", "¡Manos limpias!", "Antes de comer, lávate bien las manos con agua y jabón. ¡Frota, frota, frota!", "Manos limpias antes de comer", "🧼", Color(0xFF5ED6FF), Color(0xFF2D8CFF), Color(0xFFFFD6A5)),
    KidSpot("eyes", "Descansa los ojos", "Parpadea muy rápido y mira algo que esté lejos. ¡Tus ojos te lo agradecerán!", "Parpadea y mira algo lejos", "👀", Color(0xFFFFC94D), Color(0xFFF08C00), Color(0xFFFFFFFF)),
    KidSpot("move", "¡A moverse!", "¡Levántate y estira los brazos hacia el cielo! Uno, dos y tres. ¡Muy bien!", "¡Estira los brazos hacia el cielo!", "🙆", Color(0xFFFF9F45), Color(0xFFE8590C), Color(0xFFFFD43B)),
    KidSpot("tidy", "¡A recoger!", "Cuando termines de jugar, recoge tus juguetes. ¡Eres genial!", "Al terminar, recoge tus juguetes", "🧸", Color(0xFFB98CFF), Color(0xFF7048E8), Color(0xFFFFA94D)),
    KidSpot("thanks", "Por favor y gracias", "Decir por favor y gracias es mágico. ¡Pruébalo hoy con {p}!", "Por favor y gracias: ¡palabras mágicas!", "✨", Color(0xFF4DD4AC), Color(0xFF0B7285), Color(0xFFFFE066)),
)

fun kidText(s: String, name: String, parents: String) =
    s.replace("{n}", name.ifBlank { "campeón" }).replace("{p}", parents.ifBlank { "papá y mamá" })

/**
 * El «anuncio»: pantalla completa con un personaje animado, sintonía y voz.
 * Dura unos 14 s y no se puede saltar; luego la película sigue donde estaba.
 */
@Composable
fun KidSpotOverlay(spot: KidSpot, name: String, parents: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val total = 14_000
    val progress = remember { Animatable(0f) }
    val pop = remember { Animatable(0f) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    val voice = kidText(spot.voice, name, parents)

    DisposableEffect(Unit) {
        val prev = KeyRouter.handler
        KeyRouter.handler = { true }   // los peques no lo pueden saltar
        val jingle = try { MediaPlayer.create(context, R.raw.kid_jingle) } catch (_: Exception) { null }
        jingle?.setOnCompletionListener { it.release() }
        jingle?.start()
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale("es", "ES")
                engine?.setPitch(1.18f)
                engine?.setSpeechRate(0.92f)
                tts = engine
            }
        }
        onDispose {
            KeyRouter.handler = prev
            try { engine?.stop(); engine?.shutdown() } catch (_: Exception) { }
            try { if (jingle?.isPlaying == true) jingle.stop(); jingle?.release() } catch (_: Exception) { }
        }
    }
    LaunchedEffect(tts) {
        val e = tts ?: return@LaunchedEffect
        delay(1300)
        e.speak(voice, TextToSpeech.QUEUE_FLUSH, null, "spot")
    }
    LaunchedEffect(Unit) {
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(total, easing = LinearEasing))
        onDone()
    }

    val inf = rememberInfiniteTransition(label = "spot")
    val t by inf.animateFloat(0f, 1000f, infiniteRepeatable(tween(1_000_000, easing = LinearEasing)), label = "t")

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(spot.top, spot.bottom)))) {
        // Burbujas y estrellas de fondo
        Canvas(Modifier.fillMaxSize()) {
            val r = java.util.Random(spot.id.hashCode().toLong())
            val secs = t
            repeat(26) { i ->
                val x = r.nextFloat() * size.width
                val speed = 30f + r.nextFloat() * 60f
                val y = size.height - ((secs * speed + r.nextFloat() * size.height) % (size.height + 80f))
                val rad = 6f + r.nextFloat() * 22f
                drawCircle(Color.White.copy(alpha = 0.10f + 0.12f * r.nextFloat()), rad, Offset(x + sin(secs + i) * 12f, y))
            }
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 70.dp, vertical = 50.dp), verticalAlignment = Alignment.CenterVertically) {
            // Escena animada
            Canvas(Modifier.weight(1f).fillMaxHeight()) { drawScene(spot, t) }
            // Texto
            Column(Modifier.weight(1f).scale(0.6f + 0.4f * pop.value), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                T(spot.title, size = 50, weight = FontWeight.ExtraBold, color = Color.White, lineHeight = 56)
                T(voice, size = 24, weight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.95f), lineHeight = 33)
            }
        }
        // La película sigue enseguida
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val left = ((1f - progress.value) * total / 1000f).toInt() + 1
            T("La película sigue en $left…", size = 16, weight = FontWeight.Bold, color = Color.White.copy(alpha = 0.9f))
            Box(Modifier.width(360.dp).height(8.dp).clip(RoundedCornerShape(99.dp)).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).clip(RoundedCornerShape(99.dp)).background(Color.White))
            }
        }
    }
}

// ---------------- Dibujos ----------------

private fun DrawScope.face(c: Offset, r: Float, t: Float, eyeColor: Color = Color(0xFF1F1F3A)) {
    val blink = if ((t % 3.3f) < 0.13f) 0.12f else 1f
    val ex = r * 0.34f; val ey = -r * 0.12f
    for (s in listOf(-1f, 1f)) {
        val e = Offset(c.x + s * ex, c.y + ey)
        drawOval(Color.White, Offset(e.x - r * 0.17f, e.y - r * 0.2f * blink), Size(r * 0.34f, r * 0.4f * blink))
        val look = sin(t * 0.9f) * r * 0.04f
        drawOval(eyeColor, Offset(e.x - r * 0.09f + look, e.y - r * 0.1f * blink), Size(r * 0.18f, r * 0.22f * blink))
        drawCircle(Color.White, r * 0.035f, Offset(e.x - r * 0.03f + look, e.y - r * 0.05f * blink))
        drawCircle(Color(0x66FF6F91), r * 0.11f, Offset(c.x + s * r * 0.55f, c.y + r * 0.2f))
    }
    val smile = Path().apply {
        moveTo(c.x - r * 0.24f, c.y + r * 0.22f)
        quadraticBezierTo(c.x, c.y + r * 0.52f, c.x + r * 0.24f, c.y + r * 0.22f)
    }
    drawPath(smile, eyeColor, style = Stroke(r * 0.07f, cap = StrokeCap.Round))
}

/** Personaje: una bolita con cara, brazos que saludan y un pequeño salto. */
private fun DrawScope.buddy(c: Offset, r: Float, color: Color, t: Float, armsUp: Boolean = false) {
    val hop = -abs(sin(t * 2.6f)) * r * 0.18f
    val cc = c + Offset(0f, hop)
    drawOval(Color(0x33000000), Offset(c.x - r * 0.8f, c.y + r * 1.02f), Size(r * 1.6f, r * 0.28f))
    for (s in listOf(-1f, 1f)) {
        val base = cc + Offset(s * r * 0.9f, r * 0.15f)
        val ang = if (armsUp) (-PI / 2.4).toFloat() * s else (sin(t * 5f + s) * 0.5f + 0.4f) * s
        val end = base + Offset(cos(ang) * r * 0.55f * s, if (armsUp) -r * 0.75f + sin(t * 6f) * r * 0.08f else sin(ang) * r * 0.55f)
        drawLine(color.copy(red = color.red * 0.85f, green = color.green * 0.85f, blue = color.blue * 0.85f), base, end, r * 0.17f, StrokeCap.Round)
    }
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 1f), color.copy(red = color.red * 0.8f, green = color.green * 0.8f, blue = color.blue * 0.8f)), cc - Offset(r * 0.3f, r * 0.3f), r * 1.4f), r, cc)
    drawCircle(Color.White.copy(alpha = 0.35f), r * 0.18f, cc - Offset(r * 0.45f, r * 0.5f))
    face(cc, r, t)
}

private fun heart(c: Offset, s: Float) = Path().apply {
    moveTo(c.x, c.y + s * 0.35f)
    cubicTo(c.x - s * 1.1f, c.y - s * 0.35f, c.x - s * 0.45f, c.y - s * 1.05f, c.x, c.y - s * 0.45f)
    cubicTo(c.x + s * 0.45f, c.y - s * 1.05f, c.x + s * 1.1f, c.y - s * 0.35f, c.x, c.y + s * 0.35f)
    close()
}

private fun star(c: Offset, r: Float) = Path().apply {
    for (i in 0 until 10) {
        val a = (PI / 5 * i - PI / 2).toFloat()
        val rr = if (i % 2 == 0) r else r * 0.45f
        val p = Offset(c.x + cos(a) * rr, c.y + sin(a) * rr)
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

private fun DrawScope.drawScene(spot: KidSpot, t: Float) {
    val w = size.width; val h = size.height
    val r = minOf(w, h) * 0.2f
    val hero = Offset(w * 0.36f, h * 0.55f)
    val prop = Offset(w * 0.76f, h * 0.5f)
    when (spot.id) {
        "water" -> {
            buddy(hero, r, spot.body, t)
            // Vaso que se llena con ondas
            val gw = r * 1.1f; val gh = r * 1.6f
            val top = prop.y - gh / 2
            val level = 0.25f + 0.6f * ((t * 0.12f) % 1f)
            val wy = top + gh * (1 - level)
            val wave = Path().apply {
                moveTo(prop.x - gw / 2, wy)
                var x = -gw / 2
                while (x <= gw / 2) { lineTo(prop.x + x, wy + sin(x / 14f + t * 4f) * 5f); x += 6f }
                lineTo(prop.x + gw / 2 * 0.85f, top + gh); lineTo(prop.x - gw / 2 * 0.85f, top + gh); close()
            }
            drawPath(wave, Color(0xCC4DABF7))
            val glass = Path().apply {
                moveTo(prop.x - gw / 2, top); lineTo(prop.x - gw / 2 * 0.85f, top + gh); lineTo(prop.x + gw / 2 * 0.85f, top + gh); lineTo(prop.x + gw / 2, top)
            }
            drawPath(glass, Color.White, style = Stroke(r * 0.07f, cap = StrokeCap.Round))
            // Gotas
            repeat(3) { i ->
                val p = ((t * 0.8f + i / 3f) % 1f)
                val d = Offset(prop.x + (i - 1) * r * 0.3f, top - r * 0.9f + p * r * 0.8f)
                drawCircle(Color.White.copy(alpha = 1f - p), r * 0.08f, d)
            }
        }
        "toilet" -> {
            buddy(hero, r, spot.body, t)
            // Inodoro sencillo y brillos
            val tw = r * 1.2f
            drawRoundRect(Color.White, Offset(prop.x - tw * 0.4f, prop.y - r * 1.1f), Size(tw * 0.8f, r * 0.8f), CornerRadius(r * 0.15f))
            drawOval(Color.White, Offset(prop.x - tw * 0.6f, prop.y - r * 0.35f), Size(tw * 1.2f, r * 0.55f))
            drawRoundRect(Color.White, Offset(prop.x - tw * 0.38f, prop.y - r * 0.1f), Size(tw * 0.76f, r * 0.85f), CornerRadius(r * 0.3f))
            drawOval(Color(0xFF8BD3FF), Offset(prop.x - tw * 0.45f, prop.y - r * 0.27f), Size(tw * 0.9f, r * 0.32f))
            repeat(3) { i -> drawPath(star(prop + Offset((i - 1) * r * 0.8f, -r * 1.5f + sin(t * 3 + i) * 10f), r * 0.14f), Color(0xFFFFE066)) }
        }
        "teeth" -> {
            // Diente con cara y cepillo que va y viene
            val tc = hero + Offset(0f, -abs(sin(t * 2.4f)) * r * 0.15f)
            val tooth = Path().apply {
                moveTo(tc.x - r, tc.y - r * 0.4f)
                cubicTo(tc.x - r, tc.y - r * 1.2f, tc.x - r * 0.2f, tc.y - r * 1.1f, tc.x, tc.y - r * 0.85f)
                cubicTo(tc.x + r * 0.2f, tc.y - r * 1.1f, tc.x + r, tc.y - r * 1.2f, tc.x + r, tc.y - r * 0.4f)
                cubicTo(tc.x + r, tc.y + r * 0.5f, tc.x + r * 0.6f, tc.y + r * 1.3f, tc.x + r * 0.35f, tc.y + r * 1.3f)
                cubicTo(tc.x + r * 0.1f, tc.y + r * 1.3f, tc.x + r * 0.15f, tc.y + r * 0.5f, tc.x, tc.y + r * 0.5f)
                cubicTo(tc.x - r * 0.15f, tc.y + r * 0.5f, tc.x - r * 0.1f, tc.y + r * 1.3f, tc.x - r * 0.35f, tc.y + r * 1.3f)
                cubicTo(tc.x - r * 0.6f, tc.y + r * 1.3f, tc.x - r, tc.y + r * 0.5f, tc.x - r, tc.y - r * 0.4f)
                close()
            }
            drawPath(tooth, Color.White)
            face(tc + Offset(0f, -r * 0.2f), r * 0.8f, t)
            val bx = prop.x + sin(t * 7f) * r * 0.45f
            rotate(-25f, Offset(bx, prop.y)) {
                drawRoundRect(Color(0xFFFF6B6B), Offset(bx - r * 1.1f, prop.y - r * 0.12f), Size(r * 2.0f, r * 0.24f), CornerRadius(r * 0.12f))
                drawRoundRect(Color.White, Offset(bx + r * 0.55f, prop.y - r * 0.45f), Size(r * 0.45f, r * 0.33f), CornerRadius(r * 0.06f))
            }
            repeat(7) { i ->
                val p = ((t * 0.6f + i / 7f) % 1f)
                drawCircle(Color.White.copy(alpha = 0.8f * (1 - p)), r * (0.06f + 0.06f * (i % 3)), Offset(prop.x + (i - 3) * r * 0.18f, prop.y - r * 0.5f - p * r * 1.2f))
            }
        }
        "food" -> {
            // Manzana, zanahoria y plátano que botan por turnos
            val items = listOf(Offset(w * 0.22f, h * 0.55f), Offset(w * 0.5f, h * 0.55f), Offset(w * 0.78f, h * 0.55f))
            items.forEachIndexed { i, c0 ->
                val c = c0 + Offset(0f, -abs(sin(t * 3f + i * 1.1f)) * r * 0.35f)
                when (i) {
                    0 -> {
                        drawCircle(Color(0xFFFF4D4D), r * 0.8f, c)
                        drawLine(Color(0xFF6B3E1F), c - Offset(0f, r * 0.75f), c - Offset(-r * 0.1f, r * 1.05f), r * 0.1f, StrokeCap.Round)
                        drawOval(Color(0xFF51CF66), Offset(c.x + r * 0.05f, c.y - r * 1.1f), Size(r * 0.45f, r * 0.22f))
                        face(c, r * 0.7f, t + i)
                    }
                    1 -> {
                        val carrot = Path().apply { moveTo(c.x - r * 0.45f, c.y - r * 0.6f); lineTo(c.x + r * 0.45f, c.y - r * 0.6f); lineTo(c.x, c.y + r * 1.0f); close() }
                        drawPath(carrot, Color(0xFFFF922B))
                        repeat(3) { k -> drawLine(Color(0xFF40C057), c - Offset(0f, r * 0.6f), c + Offset((k - 1) * r * 0.25f, -r * 1.05f), r * 0.1f, StrokeCap.Round) }
                        face(c + Offset(0f, -r * 0.2f), r * 0.55f, t + i)
                    }
                    else -> {
                        drawArc(Color(0xFFFFD43B), 20f, 140f, false, Offset(c.x - r * 0.8f, c.y - r * 1.0f), Size(r * 1.6f, r * 1.6f), style = Stroke(r * 0.42f, cap = StrokeCap.Round))
                        face(c + Offset(0f, r * 0.25f), r * 0.5f, t + i)
                    }
                }
            }
        }
        "parents" -> {
            val beat = 1f + 0.08f * sin(t * 6f)
            drawPath(heart(Offset(w * 0.36f, h * 0.55f), r * 1.15f * beat), Color(0xFFFF4D6D))
            drawPath(heart(Offset(w * 0.66f, h * 0.52f), r * 1.0f * (2f - beat)), Color(0xFFFF8FAB))
            face(Offset(w * 0.36f, h * 0.5f), r * 0.75f, t)
            face(Offset(w * 0.66f, h * 0.47f), r * 0.65f, t + 1f)
            repeat(8) { i ->
                val p = ((t * 0.35f + i / 8f) % 1f)
                drawPath(heart(Offset(w * (0.15f + 0.1f * i), h * (0.95f - p * 0.9f)), r * 0.16f), Color.White.copy(alpha = 0.7f * (1 - p)))
            }
        }
        "hands" -> {
            buddy(hero, r, Color(0xFF74C0FC), t)
            for (s in listOf(-1f, 1f)) {
                val c = prop + Offset(s * r * 0.45f + sin(t * 8f) * r * 0.08f * s, 0f)
                drawRoundRect(spot.body, Offset(c.x - r * 0.35f, c.y - r * 0.2f), Size(r * 0.7f, r * 0.8f), CornerRadius(r * 0.3f))
                repeat(4) { k -> drawRoundRect(spot.body, Offset(c.x - r * 0.33f + k * r * 0.17f, c.y - r * 0.65f), Size(r * 0.15f, r * 0.55f), CornerRadius(r * 0.08f)) }
            }
            repeat(10) { i ->
                val p = ((t * 0.5f + i / 10f) % 1f)
                val bc = prop + Offset((i - 5) * r * 0.16f + sin(t * 2 + i) * 8f, -r * 0.6f - p * r * 1.6f)
                drawCircle(Color.White.copy(alpha = 0.35f * (1 - p)), r * (0.08f + 0.05f * (i % 3)), bc)
                drawCircle(Color.White.copy(alpha = 0.9f * (1 - p)), r * (0.08f + 0.05f * (i % 3)), bc, style = Stroke(2.5f))
            }
        }
        "eyes" -> {
            // Ojo grande que mira a un lado y a otro y parpadea; sol al fondo
            drawCircle(Color(0xFFFFF3BF), r * 0.7f, Offset(w * 0.8f, h * 0.22f))
            val c = Offset(w * 0.45f, h * 0.55f)
            val blink = if ((t % 2.2f) < 0.18f) 0.1f else 1f
            drawOval(Color.White, Offset(c.x - r * 1.5f, c.y - r * 0.85f * blink), Size(r * 3f, r * 1.7f * blink))
            val look = sin(t * 1.4f) * r * 0.7f
            drawCircle(Color(0xFF4C6EF5), r * 0.55f * blink.coerceAtLeast(0.2f), c + Offset(look, 0f))
            drawCircle(Color(0xFF1C1C3A), r * 0.25f * blink.coerceAtLeast(0.2f), c + Offset(look, 0f))
            drawCircle(Color.White, r * 0.1f, c + Offset(look - r * 0.12f, -r * 0.12f))
            drawOval(Color(0xFF1C1C3A), Offset(c.x - r * 1.5f, c.y - r * 0.85f * blink), Size(r * 3f, r * 1.7f * blink), style = Stroke(r * 0.08f))
        }
        "move" -> {
            buddy(Offset(w * 0.5f, h * 0.58f - abs(sin(t * 3f)) * r * 0.6f), r * 1.1f, spot.body, t, armsUp = true)
            repeat(6) { i ->
                val a = (t * 0.8f + i * PI.toFloat() / 3f)
                drawPath(star(Offset(w * 0.5f + cos(a) * r * 2.2f, h * 0.5f + sin(a) * r * 1.4f), r * 0.18f), Color.White.copy(alpha = 0.85f))
            }
        }
        "tidy" -> {
            buddy(hero, r, Color(0xFFFFC078), t)
            // Caja y bloques que caen dentro uno tras otro
            val bw = r * 1.8f
            drawRoundRect(Color(0xFF9C6644), Offset(prop.x - bw / 2, prop.y), Size(bw, r * 1.0f), CornerRadius(r * 0.1f))
            val colors = listOf(Color(0xFFFF6B6B), Color(0xFF4DABF7), Color(0xFFFFD43B))
            colors.forEachIndexed { i, col ->
                val p = ((t * 0.45f - i * 0.3f) % 1f + 1f) % 1f
                val y = prop.y - r * 1.8f + p.coerceAtMost(0.7f) / 0.7f * r * 1.6f
                drawRoundRect(col, Offset(prop.x - r * 0.65f + i * r * 0.45f, y), Size(r * 0.4f, r * 0.4f), CornerRadius(r * 0.06f))
            }
            drawRoundRect(Color(0xFFB08968), Offset(prop.x - bw / 2 - r * 0.08f, prop.y - r * 0.08f), Size(bw + r * 0.16f, r * 0.2f), CornerRadius(r * 0.08f))
        }
        else -> {
            buddy(hero, r, spot.body, t)
            // Varita mágica con chispas y arcoíris
            val rainbow = listOf(Color(0xFFFF6B6B), Color(0xFFFFA94D), Color(0xFFFFE066), Color(0xFF69DB7C), Color(0xFF4DABF7), Color(0xFF9775FA))
            rainbow.forEachIndexed { i, col ->
                val rr = r * (2.1f - i * 0.16f)
                drawArc(col, 180f, 180f, false, Offset(prop.x - rr, prop.y + r * 0.6f - rr), Size(rr * 2, rr * 2), style = Stroke(r * 0.16f))
            }
            val tip = prop + Offset(sin(t * 3f) * r * 0.3f, -r * 0.4f)
            drawLine(Color(0xFF343A40), tip, tip + Offset(-r * 0.9f, r * 1.1f), r * 0.12f, StrokeCap.Round)
            drawPath(star(tip, r * 0.32f * (1f + 0.15f * sin(t * 8f))), Color(0xFFFFE066))
            repeat(6) { i ->
                val p = ((t * 0.7f + i / 6f) % 1f)
                drawPath(star(tip + Offset(cos(i.toFloat()) * r * p * 1.3f, sin(i.toFloat()) * r * p * 1.3f), r * 0.1f * (1 - p)), Color.White)
            }
        }
    }
}

/**
 * «Mosca» de televisión para los peques: cada pocos minutos aparece en la esquina un consejo corto
 * durante 20 s, con un estilo limpio y letra grande para que lo pueda leer.
 */
@Composable
fun KidTipBug(name: String, parents: String, paused: Boolean) {
    var tip by remember { mutableStateOf<KidSpot?>(null) }
    LaunchedEffect(paused) {
        if (paused) { tip = null; return@LaunchedEffect }
        var i = (0 until KID_SPOTS.size).random()
        delay(90_000)
        while (true) {
            tip = KID_SPOTS[i % KID_SPOTS.size]; i++
            delay(20_000)
            tip = null
            delay(220_000)
        }
    }
    Box(Modifier.fillMaxSize().padding(top = 28.dp, end = 30.dp), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(tip != null, enter = fadeIn(tween(600)) + slideInHorizontally(tween(700)) { it / 2 }, exit = fadeOut(tween(600)) + slideOutHorizontally(tween(700)) { it / 2 }) {
            val s = tip ?: KID_SPOTS.first()
            Row(
                Modifier.widthIn(max = 430.dp).clip(RoundedCornerShape(18.dp))
                    .background(Brush.horizontalGradient(listOf(s.bottom.copy(alpha = 0.92f), s.top.copy(alpha = 0.92f))))
                    .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                T(s.emoji, size = 30)
                Column {
                    T("CONSEJO BUTACA", size = 10, weight = FontWeight.ExtraBold, color = Color.White.copy(alpha = 0.8f))
                    T(kidText(s.corner, name, parents), size = 20, weight = FontWeight.ExtraBold, color = Color.White, lineHeight = 25)
                }
            }
        }
    }
}
