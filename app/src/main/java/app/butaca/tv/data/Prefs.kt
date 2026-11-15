package app.butaca.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val server: String = "",
    val user: String = "",
    val pass: String = "",
    val accountStatus: String = "",
    val expDate: Long = 0L,
    val maxConnections: Int = 0,
    val lastSync: Long = 0L,
    // Reproducción
    val quality: Int = 0,          // 0 la mejor (4K), 1 Full HD, 2 HD
    val autoDown: Boolean = true,
    val audioLang: Int = 0,        // 0 castellano, 1 inglés, 2 original
    val preferSpain: Boolean = true,
    val rememberTrack: Boolean = true,
    val compat: Boolean = true,
    val intro: Boolean = true,      // intro con sonido al abrir la app
    val subs: Int = 0,             // 0 solo forzados, 1 siempre castellano, 2 desactivados
    // Control parental
    val adultHide: Boolean = true,
    val pin: String = "",
    val relock: Boolean = true,
    val newCatsHidden: Boolean = true,
    val tmdbKey: String = "",
    // Perfil infantil: 0 desactivado, 7 o 12 (edad máxima)
    val kidsLimit: Int = 0,
    // Cuenta de TMDB para la copia en la nube
    val tmdbSession: String = "",
    val tmdbAccountId: Int = 0,
    val tmdbUser: String = "",
    // Orden y categorías ocultas de «Películas por categorías de TMDB» (ids separados por comas)
    val hubOrder: String = "",
    // Perfiles
    val lastProfile: Long = 1L,
    val profilesReady: Boolean = false,
    val parentsNames: String = "Diego y Chari",
    val lockAdults: Boolean = false,              // pedir PIN para entrar en perfiles de adulto   // para el consejo «haz caso a tus padres»
    val hubHidden: String = "",
    val tmdbLists: String = "",      // "folderId:listId,folderId:listId"
    val lastCloudSync: Long = 0L,
) {
    val loggedIn: Boolean get() = server.isNotBlank() && user.isNotBlank()
    val maxQuality: Int get() = when (quality) { 1 -> 3; 2 -> 2; else -> 4 }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("butaca", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<Settings> = _flow.asStateFlow()
    val value: Settings get() = _flow.value

    private fun read() = Settings(
        server = sp.getString("server", "") ?: "",
        user = sp.getString("user", "") ?: "",
        pass = sp.getString("pass", "") ?: "",
        accountStatus = sp.getString("accountStatus", "") ?: "",
        expDate = sp.getLong("expDate", 0L),
        maxConnections = sp.getInt("maxConnections", 0),
        lastSync = sp.getLong("lastSync", 0L),
        quality = sp.getInt("quality", 0),
        autoDown = sp.getBoolean("autoDown", true),
        audioLang = sp.getInt("audioLang", 0),
        preferSpain = sp.getBoolean("preferSpain", true),
        rememberTrack = sp.getBoolean("rememberTrack", true),
        compat = sp.getBoolean("compat", true),
        intro = sp.getBoolean("intro", true),
        subs = sp.getInt("subs", 0),
        adultHide = sp.getBoolean("adultHide", true),
        pin = sp.getString("pin", "") ?: "",
        relock = sp.getBoolean("relock", true),
        newCatsHidden = sp.getBoolean("newCatsHidden", true),
        tmdbKey = sp.getString("tmdbKey", "") ?: "",
        kidsLimit = sp.getInt("kidsLimit", 0),
        tmdbSession = sp.getString("tmdbSession", "") ?: "",
        tmdbAccountId = sp.getInt("tmdbAccountId", 0),
        tmdbUser = sp.getString("tmdbUser", "") ?: "",
        tmdbLists = sp.getString("tmdbLists", "") ?: "",
        hubOrder = sp.getString("hubOrder", "") ?: "",
        lastProfile = sp.getLong("lastProfile", 1L),
        profilesReady = sp.getBoolean("profilesReady", false),
        parentsNames = sp.getString("parentsNames", "Diego y Chari") ?: "Diego y Chari",
        lockAdults = sp.getBoolean("lockAdults", false),
        hubHidden = sp.getString("hubHidden", "") ?: "",
        lastCloudSync = sp.getLong("lastCloudSync", 0L),
    )

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val s = transform(_flow.value)
        sp.edit()
            .putString("server", s.server)
            .putString("user", s.user)
            .putString("pass", s.pass)
            .putString("accountStatus", s.accountStatus)
            .putLong("expDate", s.expDate)
            .putInt("maxConnections", s.maxConnections)
            .putLong("lastSync", s.lastSync)
            .putInt("quality", s.quality)
            .putBoolean("autoDown", s.autoDown)
            .putInt("audioLang", s.audioLang)
            .putBoolean("preferSpain", s.preferSpain)
            .putBoolean("rememberTrack", s.rememberTrack)
            .putBoolean("compat", s.compat)
            .putInt("subs", s.subs)
            .putBoolean("adultHide", s.adultHide)
            .putString("pin", s.pin)
            .putBoolean("relock", s.relock)
            .putBoolean("intro", s.intro)
            .putBoolean("newCatsHidden", s.newCatsHidden)
            .putString("tmdbKey", s.tmdbKey)
            .putInt("kidsLimit", s.kidsLimit)
            .putString("tmdbSession", s.tmdbSession)
            .putInt("tmdbAccountId", s.tmdbAccountId)
            .putString("tmdbUser", s.tmdbUser)
            .putString("tmdbLists", s.tmdbLists)
            .putString("hubOrder", s.hubOrder)
            .putLong("lastProfile", s.lastProfile)
            .putBoolean("profilesReady", s.profilesReady)
            .putString("parentsNames", s.parentsNames)
            .putBoolean("lockAdults", s.lockAdults)
            .putString("hubHidden", s.hubHidden)
            .putLong("lastCloudSync", s.lastCloudSync)
            .apply()
        _flow.value = s
    }
}
