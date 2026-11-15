package app.butaca.tv.ui.screens

import android.media.MediaPlayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.butaca.tv.KeyRouter
import app.butaca.tv.R
import app.butaca.tv.ui.Jakarta
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Ease = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)       // salida suave
private val EaseIn = CubicBezierEasing(0.7f, 0f, 0.84f, 0f)

/**
 * Intro de cine (unos 4 s): un haz de proyector se abre, golpe de sonido, aparece la butaca
 * con un resplandor rojo, se escribe «Butaca» y un destello recorre el nombre. Cualquier botón la salta.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val beam = remember { Animatable(0f) }      // haz de luz horizontal
    val flash = remember { Animatable(0f) }     // destello del golpe
    val logo = remember { Animatable(0f) }      // aparición del logo
    val glow = remember { Animatable(0f) }      // resplandor detrás del logo
    val word = remember { Animatable(0f) }      // nombre
    val sweep = remember { Animatable(-0.3f) }  // brillo que recorre el nombre
    val out = remember { Animatable(1f) }       // fundido final
    var finished by remember { mutableStateOf(false) }
    fun finish() { if (!finished) { finished = true; onDone() } }

    // Sonido
    DisposableEffect(Unit) {
        val mp = try { MediaPlayer.create(context, R.raw.butaca_intro) } catch (_: Exception) { null }
        mp?.setOnCompletionListener { it.release() }
        mp?.start()
        // Cualquier botón salta la intro
        val previous = KeyRouter.handler
        KeyRouter.handler = { e -> if (e.action == android.view.KeyEvent.ACTION_UP) finish(); true }
        onDispose {
            KeyRouter.handler = previous
            try { if (mp?.isPlaying == true) mp.stop(); mp?.release() } catch (_: Exception) { }
        }
    }

    LaunchedEffect(Unit) {
        // 0,0 – 1,0 s: el haz del proyector se abre (sube el whoosh)
        launch { beam.animateTo(1f, tween(1000, easing = EaseIn)) }
        delay(980)
        // 1,0 s: golpe
        launch { flash.snapTo(1f); flash.animateTo(0f, tween(700, easing = LinearEasing)) }
        launch { beam.animateTo(0f, tween(500)) }
        launch { logo.animateTo(1f, tween(900, easing = Ease)) }
        launch { glow.animateTo(1f, tween(500)); glow.animateTo(0.55f, tween(1600)) }
        delay(350)
        val w = async { word.animateTo(1f, tween(1100, easing = Ease)) }
        delay(700)
        sweep.animateTo(1.3f, tween(1100, easing = LinearEasing))
        w.await()
        delay(250)
        out.animateTo(0f, tween(550, easing = EaseIn))
        finish()
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF040406)).graphicsLayer { alpha = out.value },
        contentAlignment = Alignment.Center,
    ) {
        // Haz de luz y resplandor
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val b = beam.value
            if (b > 0f) {
                val h = 2f + 70f * b * b
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Transparent, 0.5f to Color(0xFFFFD9DE).copy(alpha = 0.85f * b), 1f to Color.Transparent,
                        startX = c.x - size.width * 0.55f * b, endX = c.x + size.width * 0.55f * b,
                    ),
                    topLeft = Offset(0f, c.y - h / 2), size = androidx.compose.ui.geometry.Size(size.width, h),
                )
                drawCircle(Brush.radialGradient(listOf(Color(0xFFE8344B).copy(alpha = 0.5f * b), Color.Transparent), c, size.height * 0.45f * b + 1f), size.height * 0.45f * b + 1f, c)
            }
            val g = glow.value
            if (g > 0f) {
                val r = size.height * (0.55f + 0.1f * g)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFE8344B).copy(alpha = 0.42f * g), Color(0xFF7A0E1E).copy(alpha = 0.18f * g), Color.Transparent), c, r), r, c)
            }
            if (flash.value > 0f) drawRect(Color.White.copy(alpha = 0.55f * flash.value * flash.value))
        }
        // Logo y nombre
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            val l = logo.value
            Image(
                painterResource(R.drawable.logo_mark), contentDescription = null,
                modifier = Modifier.size(150.dp).scale(1.35f - 0.35f * l).alpha(l.coerceIn(0f, 1f))
                    .graphicsLayer { rotationX = (1f - l) * 18f; cameraDistance = 14f * density },
            )
            val wv = word.value
            Text(
                "Butaca",
                fontFamily = Jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp,
                letterSpacing = (22f * (1f - wv) - 1f).sp, color = Color(0xFFD9DCE3),
                modifier = Modifier.alpha(wv)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // destello que recorre las letras
                        val x = size.width * sweep.value
                        drawRect(
                            Brush.linearGradient(
                                0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.9f), 1f to Color.Transparent,
                                start = Offset(x - size.width * 0.18f, 0f), end = Offset(x + size.width * 0.18f, size.height),
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    },
            )
            Text(
                "TU CINE, A TU MANERA", fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                letterSpacing = (6f + 4f * (1f - wv)).sp, color = Color(0xFFE8344B),
                modifier = Modifier.alpha((wv * 1.4f - 0.4f).coerceIn(0f, 1f)),
            )
        }
    }
}
