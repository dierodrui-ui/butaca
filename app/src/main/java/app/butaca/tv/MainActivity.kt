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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
import kotlinx.coroutines.launch

class AppNav(
    val openRef: (ContentRef) -> Unit,
    val openItem: (ItemEntity) -> Unit,
    val play: (PlayRequest) -> Unit,
    val showMenu: (MenuRequest) -> Unit,
    val toast: (String) -> Unit,
    val openFolder: (Long) -> Unit,
)

val LocalNav = staticCompositionLocalOf<AppNav> { error("Sin navegación") }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ButacaTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        ButacaApp.repo.checkIdleLock()
        ButacaApp.repo.syncIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        ButacaApp.repo.onAppStopped()
    }
}

@Composable
fun AppRoot() {
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
        )
    }

    val start = if (repo.prefs.value.loggedIn) "main" else "login"

    // Todo se dibuja como en el diseño: una pantalla de 1280 de ancho, sea cual sea la tele
    val metrics = context.resources.displayMetrics
    val tvDensity = remember(metrics.widthPixels, metrics.heightPixels) {
        val w = maxOf(metrics.widthPixels, metrics.heightPixels)
        Density(density = w / 1280f, fontScale = 1f)
    }

    CompositionLocalProvider(LocalNav provides appNav, LocalDensity provides tvDensity) {
        Box(Modifier.fillMaxSize().background(C.Bg)) {
            NavHost(navController = nav, startDestination = start) {
                composable("login") {
                    LoginScreen(onLoggedIn = {
                        repo.syncInBackground()
                        nav.navigate("main") { popUpTo("login") { inclusive = true } }
                    })
                }
                composable("main") {
                    MainScreen(onLogout = {
                        repo.logout()
                        nav.navigate("login") { popUpTo("main") { inclusive = true } }
                    })
                }
                composable("movie/{key}", arguments = listOf(navArgument("key") { type = NavType.StringType })) { e ->
                    MovieScreen(Uri.decode(e.arguments?.getString("key") ?: ""), onBack = { nav.popBackStack() })
                }
                composable("series/{key}", arguments = listOf(navArgument("key") { type = NavType.StringType })) { e ->
                    SeriesScreen(Uri.decode(e.arguments?.getString("key") ?: ""), onBack = { nav.popBackStack() })
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
        }
    }
}
