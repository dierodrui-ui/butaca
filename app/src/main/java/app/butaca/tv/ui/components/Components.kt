package app.butaca.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.butaca.tv.ui.C
import app.butaca.tv.ui.Ic
import coil.compose.AsyncImage

/** Cualquier elemento que se puede seleccionar con el mando: borde ámbar al tener el foco. */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    radius: Dp = 12.dp,
    focusedScale: Float = 1.04f,
    container: Color = C.Surface2,
    focusedContainer: Color = C.Raised,
    content: Color = C.Text,
    focusedContent: Color = C.Text,
    borderWidth: Dp = 3.dp,
    selectedBorder: Boolean = false,
    body: @Composable BoxScope.() -> Unit,
) {
    val shape: Shape = RoundedCornerShape(radius)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = content,
            focusedContainerColor = focusedContainer,
            focusedContentColor = focusedContent,
            pressedContainerColor = focusedContainer,
            pressedContentColor = focusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(
            border = if (selectedBorder) Border(BorderStroke(1.5.dp, C.Border), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(borderWidth, C.Accent), inset = 0.dp, shape = shape),
        ),
        content = body,
    )
}

@Composable
fun T(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 15,
    weight: FontWeight = FontWeight.Normal,
    color: Color = C.Text,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: Int? = null,
) {
    Text(
        // Las estrellas de la nota, en el rojo de la app
        text = if (text.indexOf('★') < 0) androidx.compose.ui.text.AnnotatedString(text) else androidx.compose.ui.text.buildAnnotatedString {
            text.forEach { ch ->
                if (ch == '★') withStyle(androidx.compose.ui.text.SpanStyle(color = C.Accent)) { append(ch) } else append(ch)
            }
        },
        modifier = modifier,
        fontSize = size.sp,
        fontWeight = weight,
        fontFamily = app.butaca.tv.ui.Jakarta,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        lineHeight = (lineHeight ?: (size * 1.35f).toInt()).sp,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, extra: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) Icon(icon, null, tint = C.Accent, modifier = Modifier.size(20.dp))
        T(text, size = 19, weight = FontWeight.SemiBold)
        if (extra != null) T("· $extra", size = 13, color = C.Muted)
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(if (small) 28.dp else 34.dp),
        radius = 999.dp,
        container = if (selected) C.Text else Color.Transparent,
        focusedContainer = if (selected) C.Text else C.Raised,
        content = if (selected) C.Bg else C.TextSoft,
        focusedContent = if (selected) C.Bg else C.Text,
        selectedBorder = !selected,
        focusedScale = 1.05f,
    ) {
        T(
            text, size = if (small) 12 else 13, weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = androidx.tv.material3.LocalContentColor.current,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp),
        )
    }
}

@Composable
fun ActionButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    height: Dp = 42.dp,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(height),
        radius = 12.dp,
        container = if (primary) C.Text else C.Raised,
        focusedContainer = if (primary) C.Text else C.Raised,
        content = if (primary) C.Bg else C.Text,
        focusedContent = if (primary) C.Bg else C.Text,
        focusedScale = 1.05f,
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(icon, null, modifier = Modifier.size(17.dp), tint = androidx.tv.material3.LocalContentColor.current)
            Text(
                text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = androidx.tv.material3.LocalContentColor.current,
                maxLines = 1, softWrap = false,
            )
        }
    }
}

@Composable
fun IconButtonTv(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = C.Text) {
    FocusSurface(onClick = onClick, modifier = modifier.size(42.dp), radius = 12.dp, container = C.Raised, focusedScale = 1.08f) {
        Icon(icon, description, tint = tint, modifier = Modifier.align(Alignment.Center).size(19.dp))
    }
}

/** Imagen con un color de respaldo y el título encima si no hay carátula. */
@Composable
fun Artwork(url: String?, title: String, keySeed: String, modifier: Modifier = Modifier, showTitle: Boolean = true, titleSize: Int = 15) {
    var failed by remember(url) { mutableStateOf(url.isNullOrBlank()) }
    Box(modifier.background(C.toneFor(keySeed))) {
        if (failed && showTitle) {
            T(
                title, size = titleSize, weight = FontWeight.SemiBold, maxLines = 4,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true },
                onSuccess = { failed = false },
            )
        }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (accent) C.Accent else Color(0xD107080C))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        T(text, size = 10, weight = FontWeight.Bold, color = if (accent) C.OnAccent else C.Text)
    }
}

/** Carátula vertical: películas y series. */
@Composable
fun PosterCard(
    title: String,
    icon: String?,
    keySeed: String,
    modifier: Modifier = Modifier,
    width: Dp? = 116.dp,
    meta: String? = null,
    isSeries: Boolean = false,
    watched: Boolean = false,
    unavailable: Boolean = false,
    position: Int? = null,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(if (width != null) modifier.width(width) else modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .then(if (highlighted) Modifier.border(3.dp, C.Accent, RoundedCornerShape(10.dp)) else Modifier),
            radius = 10.dp,
            focusedScale = 1.07f,
            container = Color.Transparent,
            focusedContainer = Color.Transparent,
        ) {
            Artwork(icon, title, keySeed, Modifier.fillMaxSize().alpha(if (unavailable) 0.4f else 1f))
            if (position != null) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(7.dp).size(24.dp).clip(CircleShape).background(Color(0xD107080C)),
                    contentAlignment = Alignment.Center,
                ) { T("$position", size = 12, weight = FontWeight.SemiBold) }
            }
            if (isSeries) Badge("SERIE", Modifier.align(Alignment.TopEnd).padding(7.dp))
            // «% para ti» según lo aprendido de tus gustos
            LocalTaste.current?.match(keySeed)?.let { m ->
                MatchPill(m, Modifier.align(if (position == null) Alignment.TopStart else Alignment.TopEnd).padding(7.dp))
            }
            if (watched) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(7.dp).size(24.dp).clip(CircleShape).background(C.Green),
                    contentAlignment = Alignment.Center,
                ) { Icon(Ic.Check, "Vista", tint = C.Bg, modifier = Modifier.size(15.dp)) }
            }
            // Favorito y Ver más adelante
            val marks = LocalMarks.current
            val fav = keySeed in marks.fav
            val later = keySeed in marks.later
            if (fav || later) {
                Row(Modifier.align(Alignment.BottomStart).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (fav) Box(
                        Modifier.size(24.dp).clip(CircleShape).background(Color(0xE607080C)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Ic.HeartFilled, "Favorito", tint = C.Accent, modifier = Modifier.size(14.dp)) }
                    if (later) Box(
                        Modifier.size(24.dp).clip(CircleShape).background(Color(0xE607080C)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Ic.Clock, "Ver más adelante", tint = C.Accent, modifier = Modifier.size(14.dp)) }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        T(title, size = 13, weight = FontWeight.Medium, maxLines = 1, color = if (unavailable) C.Muted else C.Text)
        if (meta != null) T(meta, size = 11, color = C.Muted, maxLines = 1)
    }
}

/** Favoritos y Ver más adelante, disponibles en toda la app para marcar las carátulas. */
val LocalMarks = androidx.compose.runtime.staticCompositionLocalOf { app.butaca.tv.data.Marks() }

/** Tarjeta horizontal: seguir viendo, episodios nuevos. */
@Composable
fun WideCard(
    title: String,
    subtitle: String,
    image: String?,
    keySeed: String,
    modifier: Modifier = Modifier,
    width: Dp = 212.dp,
    progress: Float? = null,
    badge: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusSurface(
            onClick = onClick, onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            radius = 12.dp, focusedScale = 1.05f, container = Color.Transparent, focusedContainer = Color.Transparent,
        ) {
            Artwork(image, title, keySeed, Modifier.fillMaxSize(), titleSize = 16)
            Box(
                Modifier.align(Alignment.Center).size(42.dp).clip(CircleShape).background(Color(0x8C07080C)),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.Play, null, tint = C.Text, modifier = Modifier.size(18.dp)) }
            if (badge != null) Badge(badge, Modifier.align(Alignment.TopStart).padding(10.dp), accent = true)
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x2EFFFFFF))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(C.Accent))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            T(title, size = 14, weight = FontWeight.SemiBold, maxLines = 1)
            T(subtitle, size = 12, color = C.Muted, maxLines = 1)
        }
    }
}

/** Fila de ajustes: interruptor o valor que cambia al pulsar OK. */
@Composable
fun SettingRow(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    switch: Boolean? = null,
    value: String? = null,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        radius = 12.dp, focusedScale = 1.01f, container = C.Surface, focusedContainer = C.Surface3,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                T(label, size = 15, weight = FontWeight.SemiBold)
                T(description, size = 13, color = C.Muted)
            }
            if (switch != null) {
                Box(
                    Modifier.width(52.dp).height(30.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (switch) C.Accent else Color(0xFF3A4250)).padding(3.dp),
                    contentAlignment = if (switch) Alignment.CenterEnd else Alignment.CenterStart,
                ) { Box(Modifier.size(24.dp).clip(CircleShape).background(C.Text)) }
            }
            if (value != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(C.Surface3)
                        .border(1.5.dp, Color(0xFF3A4250), RoundedCornerShape(10.dp))
                        .padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    T(value, size = 14, weight = FontWeight.SemiBold, maxLines = 1)
                    Icon(Ic.Chevron, null, tint = C.Text, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun MenuRow(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    iconTint: Color = C.Text,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    FocusSurface(
        onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(40.dp),
        radius = 10.dp, focusedScale = 1.02f,
        container = if (selected) C.Raised else Color.Transparent,
        focusedContainer = C.Raised,
        content = if (selected) C.Text else C.TextSoft,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
            T(
                text, size = 14, weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = androidx.tv.material3.LocalContentColor.current, maxLines = 1, modifier = Modifier.weight(1f),
            )
            if (trailing != null) T(trailing, size = 13, color = C.Muted)
        }
    }
}

@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        T(title, size = 18, weight = FontWeight.SemiBold)
        T(text, size = 14, color = C.Muted)
    }
}

@Composable
fun Scrim(modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.horizontalGradient(
                0f to Color(0xF207080C), 0.45f to Color(0xB307080C), 1f to Color(0x0007080C),
            )
        )
    )
}

@Composable
fun BottomFade(modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.verticalGradient(0f to Color(0x0007080C), 1f to C.Bg)))
}

/** «Comedia · ★ 7,3» bajo la carátula. */
fun itemMeta(genre: String?, year: Int?, rating: Double?): String? {
    val g = app.butaca.tv.data.TextUtil.firstGenre(genre)
    val r = rating?.let { "★ " + String.format(java.util.Locale("es", "ES"), "%.1f", it) }
    return listOfNotNull(g ?: year?.toString(), r).joinToString(" · ").ifBlank { null }
}

fun itemMeta(item: app.butaca.tv.data.ItemEntity): String? = itemMeta(item.genre, item.year, item.rating)

// ---------- Desplazamiento al estilo Google TV ----------

/** Coloca el elemento con el foco a una altura fija (para que no queden carátulas cortadas arriba). */
@OptIn(ExperimentalFoundationApi::class)
private class PivotSpec(private val pivotPx: Float, private val enabled: () -> Boolean) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        if (enabled()) offset - pivotPx else edgeDistance(offset, size, containerSize, 0f)
}

/** En filas horizontales: lo justo para ver el elemento y un poco del siguiente. */
@OptIn(ExperimentalFoundationApi::class)
private class EdgeSpec(private val marginPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        edgeDistance(offset, size, containerSize, marginPx)
}

private fun edgeDistance(offset: Float, size: Float, container: Float, margin: Float): Float {
    val leading = offset - margin
    val trailing = offset + size + margin - container
    return when {
        leading < 0 && trailing > 0 -> 0f
        leading < 0 -> leading
        trailing > 0 -> trailing
        else -> 0f
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PivotScope(pivot: Dp, enabled: () -> Boolean = { true }, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { pivot.toPx() }
    val spec = remember(px) { PivotSpec(px, enabled) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun TvRow(modifier: Modifier = Modifier, gap: Dp = 18.dp, content: LazyListScope.() -> Unit) {
    val px = with(LocalDensity.current) { 60.dp.toPx() }
    val spec = remember(px) { EdgeSpec(px) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyRow(
            modifier.focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp),
            content = content,
        )
    }
}

/** Degradado arriba para que lo que se sale por arriba no se vea cortado de golpe. */
@Composable
fun TopFade(modifier: Modifier = Modifier, height: Dp = 48.dp) {
    Box(modifier.fillMaxWidth().height(height).background(Brush.verticalGradient(0f to C.Bg, 1f to Color(0x0007080C))))
}

/** Logo de Butaca: el nombre en rojo, con la letra de cartel de cine. */
@Composable
fun BrandLogo(markSize: Dp, textSize: Int) {
    Text(
        "BUTACA",
        style = androidx.compose.ui.text.TextStyle(
            brush = app.butaca.tv.ui.LogoBrush, fontFamily = app.butaca.tv.ui.Bebas,
            fontSize = (textSize * 1.55f).sp, letterSpacing = 1.2.sp,
        ),
        modifier = Modifier.height(markSize + 4.dp),
    )
}
