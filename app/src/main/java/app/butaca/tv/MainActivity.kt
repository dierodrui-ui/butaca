package app.butaca.tv

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.navigation.NavType
import kotlinx.coroutines.flow.map
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.butaca.tv.data.ContentRef
import app.butaca.tv.data.ContentType
import app.butaca.tv.data.ItemEntity
import app.butaca.tv.data.PlayRequest
import app.butaca.tv.player.PlayerScreen
import app.butaca.tv.ui.ButacaTheme
import app.butaca.tv.ui.C
import app.butaca.tv.ui.components.MenuRequest
import app.butaca.tv.ui.components.QuickMenu
import app.butaca.tv.ui.screens.LoginScreen
import app.butaca.tv.ui.screens.MainScreen
import app.butaca.tv.ui.screens.MovieScreen
import app.butaca.tv.ui.screens.SeriesScreen
import app.butaca.tv.ui.screens.PersonScreen
import kotlinx.coroutines.launch

class AppNav(
    val openRef: (ContentRef) -> Unit,
    val openItem: (ItemEntity) -> Unit,
    val play: (PlayRequest) -> Unit,
    val showMenu: (MenuRequest) -> Unit,
    val toast: (String) -> Unit,
    val openFolder: (Long) -> Unit,
    val openPerson: (Int) -> Unit,
    val openTrailer: (String) -> Unit,
    val openExplore: (List<app.butaca.tv.data.Tag>) -> Unit,
    val openHubRow: (String) -> Unit,
)

val LocalNav = staticCompositionLocalOf<AppNav> { error("Sin navegación") }

/** Para que el reproductor reciba el mando aunque el foco se haya perdido. */
object KeyRouter {
    @Volatile var handler: ((android.view.KeyEvent) -> Boolean)? = null
}

class MainActivity : ComponentActivity() {

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (KeyRouter.handler?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ButacaTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        ButacaApp.repo.checkIdleLock()
        ButacaApp.repo.syncIfNeeded()
        ButacaApp.repo.usageResume()
    }

    override fun onStop() {
        super.onStop()
        ButacaApp.repo.onAppStopped()
        ButacaApp.repo.usagePause()
    }
}

/** Entrada: acceso al servidor → «¿Quién está ahí?» → la app del perfil elegido. */
@Composable
fun AppRoot() {
    val repo = ButacaApp.repo
    val context = LocalContext.current
    val settings by repo.prefs.flow.collectAsState()
    val profile by repo.profile.collectAsState()
    val timeUp by repo.timeUp.collectAsState()
    var intro by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(repo.prefs.value.intro) }
    val metrics = context.resources.displayMetrics
    val tvDensity = remember(metrics.widthPixels, metrics.heightPixels) {
        Density(density = maxOf(metrics.widthPixels, metrics.heightPixels) / 1280f, fontScale = 1f)
    }
    CompositionLocalProvider(LocalDensity provides tvDensity) {
        Box(Modifier.fillMaxSize().background(C.Bg)) {
            val p = profile
            when {
                !settings.loggedIn -> LoginScreen(onLoggedIn = { repo.syncInBackground() })
                p == null -> app.butaca.tv.ui.screens.ProfilePickerScreen()
                else -> androidx.compose.runtime.key(p.id) { ProfileRoot() }
            }
            if (timeUp) app.butaca.tv.ui.screens.TimeUpOverlay(profile?.name ?: "", onDone = { repo.leaveProfile(); repo.clearTimeUp() })
            if (intro) app.butaca.tv.ui.screens.IntroScreen(onDone = { intro = false })
        }
    }
}

/** La app de un perfil: navegación entre pantallas. */
@Composable
fun ProfileRoot() {
    val repo = ButacaApp.repo
    val nav = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf<MenuRequest?>(null) }

    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }

    val openItem: (ItemEntity) -> Unit = { item ->
        when (item.type) {
            ContentType.LIVE -> scope.launch {
                val req = repo.playRequestFor(item)
                if (req != null) { repo.pendingPlay = req; nav.navigate("player") }
            }
            ContentType.SERIES -> nav.navigate("series/${Uri.encode(item.key)}")
            else -> nav.navigate("movie/${Uri.encode(item.key)}")
        }
    }

    val appNav = remember {
        AppNav(
            openRef = { ref ->
                scope.launch {
                    val item = ref.itemKey?.let { repo.itemByKey(it) } ?: repo.bestItem(ref.stableKey)
                    if (item == null) toast("«${ref.title}» ya no está en tu servidor")
                    else openItem(item)
                }
            },
            openItem = openItem,
            play = { req -> repo.pendingPlay = req; nav.navigate("player") },
            showMenu = { menu = it },
            toast = toast,
            openFolder = { id ->
                repo.requestedFolder.value = id
                nav.popBackStack("main", inclusive = false)
            },
            openPerson = { id -> nav.navigate("person/$id") },
            openExplore = { tags -> app.butaca.tv.data.ExploreNav.pending = tags; nav.navigate("explore") },
            openHubRow = { id -> nav.navigate("hub/$id") },
            openTrailer = { key ->
                // La app de YouTube de Google TV; si no está, el navegador
                val yt = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key"))
                try { context.startActivity(yt) } catch (_: Exception) {
                    try {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")))
                    } catch (_: Exception) { toast("No se ha podido abrir YouTube en este dispositivo") }
                }
            },
        )
    }

    val start = "main"

    // Todo se dibuja como en el diseño: una pantalla de 1280 de ancho, sea cual sea la tele
    val metrics = context.resources.displayMetrics
    val tvDensity = remember(metrics.widthPixels, metrics.heightPixels) {
        val w = maxOf(metrics.widthPixels, metrics.heightPixels)
        Density(density = w / 1280f, fontScale = 1f)
    }

    val marks by remember { ButacaApp.repo.marks() }.collectAsState(initial = app.butaca.tv.data.Marks())
    val taste by app.butaca.tv.data.Taste.model.collectAsState()
    val progressMap by remember { repo.activity().map { l -> l.associate { it.stableKey to it.fraction } } }.collectAsState(initial = emptyMap())
    val rateAsk by app.butaca.tv.data.RatePrompt.request.collectAsState()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    CompositionLocalProvider(
        LocalNav provides appNav, LocalDensity provides tvDensity, app.butaca.tv.ui.components.LocalMarks provides marks,
        app.butaca.tv.ui.components.LocalTaste provides taste,
        app.butaca.tv.ui.components.LocalProgress provides progressMap,
    ) {
        Box(Modifier.fillMaxSize().background(C.Bg)) {
            NavHost(navController = nav, startDestination = start) {
                composable("main") {
                    MainScreen(onLogout = { repo.leaveProfile(); repo.logout() })
                }
                composable("movie/{key}", arguments = listOf(navArgument("key") { type = NavType.StringType })) { e ->
                    MovieScreen(Uri.decode(e.arguments?.getString("key") ?: ""), onBack = { nav.popBackStack() })
                }
                composable("series/{key}", arguments = listOf(navArgument("key") { type = NavType.StringType })) { e ->
                    SeriesScreen(Uri.decode(e.arguments?.getString("key") ?: ""), onBack = { nav.popBackStack() })
                }
                composable("person/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { e ->
                    PersonScreen(e.arguments?.getInt("id") ?: 0, onBack = { nav.popBackStack() })
                }
                composable("hub/{id}") { e ->
                    app.butaca.tv.ui.screens.HubRowScreen(e.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() })
                }
                composable("explore") {
                    app.butaca.tv.ui.screens.ExploreScreen(app.butaca.tv.data.ExploreNav.pending, onBack = { nav.popBackStack() })
                }
                composable("player") {
                    val req = repo.pendingPlay
                    if (req == null) nav.popBackStack()
                    else PlayerScreen(req, onExit = { nav.popBackStack() })
                }
            }
            menu?.let { m ->
                QuickMenu(m, onOpen = { appNav.openRef(it) }, onDismiss = { menu = null })
            }
            app.butaca.tv.ui.screens.KidTimeWarning()
            // Al terminar una película o serie: «¿Qué te ha parecido?» (cuando ya has salido del reproductor)
            rateAsk?.let { ref ->
                if (route != "player") app.butaca.tv.ui.components.RatingDialog(ref, onDismiss = { app.butaca.tv.data.RatePrompt.done() })
            }
        }
    }
}
