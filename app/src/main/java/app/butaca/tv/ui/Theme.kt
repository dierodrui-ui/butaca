package app.butaca.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object C {
    // Paleta «sala de cine»: negros profundos con un rojo butaca como acento
    val Bg = Color(0xFF07080C)
    val Rail = Color(0xFF0B0D12)
    val Panel = Color(0xFF0E1016)
    val Surface = Color(0xFF13151C)
    val Surface2 = Color(0xFF171A22)
    val Surface3 = Color(0xFF1A1D26)
    val Raised = Color(0xFF232736)
    val Border = Color(0xFF2B2F3D)
    val BorderSoft = Color(0xFF232733)
    val Text = Color(0xFFF4F5F8)
    val TextSoft = Color(0xFFCBCFD8)
    val Muted = Color(0xFF9CA3B3)
    val Faint = Color(0xFF7F8798)
    val Accent = Color(0xFFE8344B)
    val AccentSoft = Color(0xFFFF7A8C)
    val OnAccent = Color(0xFFFFFFFF)
    val Gold = Color(0xFFFFC24A)
    val Green = Color(0xFF3DD68C)
    val Red = Color(0xFFFF5A6E)

    val FolderColors = listOf(0xFFF5C451, 0xFF7FA8FF, 0xFF5ED3A0, 0xFFE58BB5, 0xFFB79CFF, 0xFF6FD3E8, 0xFFFF8A5B)

    private val posterTones = listOf(
        0xFF3D4A2C, 0xFF2B3F4F, 0xFF4F3B26, 0xFF46322F, 0xFF2F2F4A, 0xFF3E2A3A,
        0xFF4A3A28, 0xFF4B302B, 0xFF35432E, 0xFF2C3A44, 0xFF263E45, 0xFF44372C, 0xFF33304A,
    )

    fun toneFor(key: String): Color = Color(posterTones[(key.hashCode() and 0x7fffffff) % posterTones.size])
}

/** Tipografía de la app: Plus Jakarta Sans (moderna y muy legible en la tele). */
val Jakarta = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(app.butaca.tv.R.font.jakarta_regular, androidx.compose.ui.text.font.FontWeight.Normal),
    androidx.compose.ui.text.font.Font(app.butaca.tv.R.font.jakarta_medium, androidx.compose.ui.text.font.FontWeight.Medium),
    androidx.compose.ui.text.font.Font(app.butaca.tv.R.font.jakarta_semibold, androidx.compose.ui.text.font.FontWeight.SemiBold),
    androidx.compose.ui.text.font.Font(app.butaca.tv.R.font.jakarta_bold, androidx.compose.ui.text.font.FontWeight.Bold),
    androidx.compose.ui.text.font.Font(app.butaca.tv.R.font.jakarta_extrabold, androidx.compose.ui.text.font.FontWeight.ExtraBold),
)

/** Letra del logo (BUTACA): condensada y rotunda, como los carteles de cine. */
val Bebas = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(app.butaca.tv.R.font.bebas_neue))

/** Rojo del logo, de más claro arriba a más intenso abajo. */
val LogoBrush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFFF2E45), Color(0xFFC8102E)))

private fun butacaTypography(): androidx.tv.material3.Typography {
    val b = androidx.tv.material3.Typography()
    fun androidx.compose.ui.text.TextStyle.j() = copy(fontFamily = Jakarta)
    return androidx.tv.material3.Typography(
        displayLarge = b.displayLarge.j(), displayMedium = b.displayMedium.j(), displaySmall = b.displaySmall.j(),
        headlineLarge = b.headlineLarge.j(), headlineMedium = b.headlineMedium.j(), headlineSmall = b.headlineSmall.j(),
        titleLarge = b.titleLarge.j(), titleMedium = b.titleMedium.j(), titleSmall = b.titleSmall.j(),
        bodyLarge = b.bodyLarge.j(), bodyMedium = b.bodyMedium.j(), bodySmall = b.bodySmall.j(),
        labelLarge = b.labelLarge.j(), labelMedium = b.labelMedium.j(), labelSmall = b.labelSmall.j(),
    )
}

@Composable
fun ButacaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = butacaTypography(),
        colorScheme = darkColorScheme(
            primary = C.Accent,
            onPrimary = C.OnAccent,
            background = C.Bg,
            onBackground = C.Text,
            surface = C.Surface,
            onSurface = C.Text,
            surfaceVariant = C.Raised,
            onSurfaceVariant = C.Muted,
            border = C.Border,
        ),
        content = content,
    )
}

/** Iconos de trazo, los mismos del diseño. */
object Ic {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun stroke(name: String, vararg d: String, width: Float = 1.8f): ImageVector {
        val b = ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
        )
        d.forEach { p ->
            b.addPath(
                pathData = PathParser().parsePathString(p).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private fun filled(name: String, vararg d: String): ImageVector {
        val b = ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
        )
        d.forEach { p -> b.addPath(pathData = PathParser().parsePathString(p).toNodes(), fill = SolidColor(Color.White)) }
        return b.build()
    }

    private const val FOLDER = "M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z"
    private const val HEART = "M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z"

    val Home = stroke("home", "M3 10.5 12 3l9 7.5V21h-6v-6H9v6H3z")
    val Stats = stroke("stats", "M4 20V10M10 20V4M16 20v-7M22 20H2")
    val Mic = stroke("mic", "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5 11a7 7 0 0 0 14 0M12 18v3M9 21h6")
    val Search = stroke("search", circle(11f, 11f, 7f), "M20 20l-4-4")
    val Tv = stroke("tv", "M4 5h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M8 21h8M12 18v3")
    val Film = stroke(
        "film", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M7 3v18M17 3v18M3 8h4M3 16h4M17 8h4M17 16h4",
    )
    val Series = stroke("series", "M12 3 2 8l10 5 10-5z", "M2 13l10 5 10-5")
    val Folder = stroke("folder", FOLDER)
    val FolderPlus = stroke("folderPlus", FOLDER, "M12 10v6M9 13h6")
    val Settings = stroke(
        "settings", "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12",
        circle(16f, 6f, 2f), circle(10f, 12f, 2f), circle(18f, 18f, 2f),
    )
    val Play = filled("play", "M7 4v16l13-8z")
    val Clock = stroke("clock", circle(12f, 12f, 9f), "M12 7v5l3 2")
    val Heart = stroke("heart", HEART)
    val HeartFilled = filled("heartFilled", HEART)
    val Check = stroke("check", "M5 12l5 5 9-10", width = 2.4f)
    val Plus = stroke("plus", "M12 5v14M5 12h14", width = 2f)
    val Minus = stroke("minus", circle(12f, 12f, 9f), "M8 12h8")
    val Info = stroke("info", "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M7 9h10M7 13h6")
    val Back = stroke("back", "M15 5l-7 7 7 7", width = 2f)
    val Chevron = stroke("chevron", "M9 5l7 7-7 7", width = 2f)
    val Sort = stroke("sort", "M8 9l4-4 4 4M8 15l4 4 4-4", width = 2f)
    val Lock = stroke("lock", "M7 11h10a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z", "M8 11V8a4 4 0 0 1 8 0v3")
    val Refresh = stroke("refresh", "M20 12a8 8 0 1 1-2.3-5.7", "M20 4v5h-5")
    val Edit = stroke("edit", "M4 20h4L19 9l-4-4L4 16z", width = 2f)
    val Move = stroke("move", "M2 12h20M6 8l-4 4 4 4M18 8l4 4-4 4")
    val EyeOff = stroke("eyeOff", "M3 3l18 18", "M10.6 6.1A9.8 9.8 0 0 1 12 6c5 0 9 6 9 6a17 17 0 0 1-2.6 3.2M6.6 6.6C4 8.4 3 12 3 12s4 6 9 6a9 9 0 0 0 4.4-1.1")
    val Trash = stroke("trash", "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3")
    val Close = stroke("close", "M6 6l12 12M18 6L6 18", width = 2f)
    val Star = filled("star", "M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z")
}
