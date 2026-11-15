package app.butaca.tv.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

// Funciones que usan TMDB para enriquecer la app. Tu servidor solo se usa para saber qué tienes.

data class HeroExtra(val logo: String?, val trailer: String?, val backdrop: String?, val plot: String?, val genres: String?)

data class PersonPage(val info: PersonInfo, val directed: List<ItemEntity>, val acted: List<ItemEntity>)

data class SearchExtras(
    val person: TmdbPerson?,
    val personItems: List<ItemEntity>,
    val topics: List<Pair<String, List<ItemEntity>>>,
)

data class MonthStats(val hours: Int, val titles: Int, val topGenres: List<String>)

// ---------------- Destacado ----------------

suspend fun Repo.heroExtra(item: ItemEntity): HeroExtra? {
    if (!tmdb.enabled) return null
    val isTv = item.type == ContentType.SERIES
    val id = tmdbIdFor(item) ?: return null
    val d = tmdb.details(isTv, id) ?: return null
    return HeroExtra(
        logo = tmdb.logo(d),
        trailer = tmdb.trailer(d),
        backdrop = TmdbClient.img("w1280", d.optString("backdrop_path")),
        plot = d.optString("overview").ifBlank { null },
        genres = tmdb.genres(d).take(3).joinToString(", ") { it.second }.ifBlank { null },
    )
}

// ---------------- Filas de Inicio ----------------

private object HomeCache {
    var at = 0L
    var kids = -1
    var rows: List<RelatedRow> = emptyList()
}

/** «Porque viste…», tendencias, imprescindibles, de temporada… solo con lo que tienes. */
suspend fun Repo.homeRows(force: Boolean = false): List<RelatedRow> {
    if (!tmdb.enabled) return emptyList()
    val kids = prefs.value.kidsLimit
    if (!force && HomeCache.kids == kids && System.currentTimeMillis() - HomeCache.at < 60 * 60_000L && HomeCache.rows.isNotEmpty()) return HomeCache.rows

    val watchedKeys = db.progress().observeWatchedKeys().first().toSet()
    val rows = ArrayList<RelatedRow>()
    val used = HashSet<String>()
    fun add(title: String, list: List<ItemEntity>, min: Int = 3) {
        val fresh = list.filter { it.stableKey !in watchedKeys && it.stableKey !in used }.take(18)
        if (fresh.size >= min) { rows += RelatedRow(title, fresh); fresh.forEach { used += it.stableKey } }
    }

    // Porque viste… (lo último que has terminado)
    val watched = db.progress().observeWatched().first()
    var because = 0
    for (p in watched.take(8)) {
        if (because >= 2) break
        val item = bestItem(p.stableKey) ?: continue
        val isTv = item.type == ContentType.SERIES
        val id = tmdbIdFor(item) ?: continue
        val d = tmdb.details(isTv, id) ?: continue
        val before = rows.size
        add("Porque viste ${item.name}", matchTitles(tmdb.recommendations(d, isTv), item.stableKey))
        if (rows.size > before) because++
    }

    add("Tendencias esta semana", matchTitles(tmdb.trending(3), null))

    // De temporada
    val cal = Calendar.getInstance()
    val month = cal.get(Calendar.MONTH)
    val day = cal.get(Calendar.DAY_OF_MONTH)
    when {
        month == Calendar.DECEMBER || (month == Calendar.JANUARY && day <= 6) ->
            add("Para estas fiestas", matchTitles(tmdb.discover(false, emptyList(), 4, mapOf("with_keywords" to "207317")), null))
        month == Calendar.OCTOBER ->
            add("Para pasar miedo", matchTitles(tmdb.discover(false, listOf(27), 4), null))
        month == Calendar.FEBRUARY && day <= 14 ->
            add("Para San Valentín", matchTitles(tmdb.discover(false, listOf(10749), 4), null))
        month == Calendar.JULY || month == Calendar.AUGUST ->
            add("Cine de verano", matchTitles(tmdb.discover(false, listOf(12, 35), 4), null))
    }

    add("Imprescindibles", matchTitles(tmdb.topRated(false, 4), null))
    val year = cal.get(Calendar.YEAR)
    add("Cine español reciente", matchTitles(tmdb.discover(false, emptyList(), 4, mapOf(
        "with_original_language" to "es", "primary_release_date.gte" to "${year - 3}-01-01",
    )), null))
    add("Series imprescindibles", matchTitles(tmdb.topRated(true, 3), null))
    add("Clásicos de los 90", matchTitles(tmdb.discover(false, emptyList(), 4, mapOf(
        "primary_release_date.gte" to "1990-01-01", "primary_release_date.lte" to "1999-12-31",
    )), null))
    add("Joyas de los 80", matchTitles(tmdb.discover(false, emptyList(), 3, mapOf(
        "primary_release_date.gte" to "1980-01-01", "primary_release_date.lte" to "1989-12-31",
    )), null))

    HomeCache.rows = rows
    HomeCache.at = System.currentTimeMillis()
    HomeCache.kids = kids
    return rows
}

// ---------------- Próximamente y avisos ----------------

/** Estrenos en cines que aún no están en tu servidor. */
suspend fun Repo.upcomingNotInServer(): List<TmdbTitle> {
    if (!tmdb.enabled || prefs.value.kidsLimit > 0) return emptyList()
    return tmdb.upcoming(2).filter { matchTitles(listOf(it), null).isEmpty() }.take(20)
}

fun Repo.alerts(): Flow<List<AlertEntity>> = db.misc().observeAlerts()

suspend fun Repo.setAlert(t: TmdbTitle, on: Boolean) {
    val key = (if (t.isTv) "t" else "m") + t.id
    if (on) db.misc().upsertAlert(AlertEntity(key, t.id, t.isTv, t.title, t.poster, t.year, System.currentTimeMillis(), false))
    else db.misc().deleteAlert(key)
}

suspend fun Repo.removeAlert(a: AlertEntity) = db.misc().deleteAlert(a.key)

/** Mira si algún estreno que esperas ya ha llegado a tu servidor. */
suspend fun Repo.checkAlerts() {
    for (a in db.misc().alerts()) {
        if (a.available) continue
        val found = matchTitles(listOf(TmdbTitle(a.tmdbId, a.isTv, a.title, null, a.year)), null)
        if (found.isNotEmpty()) db.misc().upsertAlert(a.copy(available = true))
    }
}

suspend fun Repo.alertItem(a: AlertEntity): ItemEntity? =
    matchTitles(listOf(TmdbTitle(a.tmdbId, a.isTv, a.title, null, a.year)), null).firstOrNull()

// ---------------- Personas ----------------

suspend fun Repo.personPage(id: Int): PersonPage? {
    val info = tmdb.person(id) ?: return null
    val directed = matchTitles(tmdb.personWork(id, asDirector = true), null)
    val acted = matchTitles(tmdb.personWork(id, asDirector = false), null).filter { a -> directed.none { it.stableKey == a.stableKey } }
    return PersonPage(info, directed, acted)
}

// ---------------- Buscador: personas y temas ----------------

suspend fun Repo.searchExtras(query: String): SearchExtras? {
    if (!tmdb.enabled || query.trim().length < 3) return null
    val person = tmdb.searchPerson(query)
    val personItems = person?.let { matchTitles(tmdb.personAll(it.id), null) }.orEmpty()
    val topics = ArrayList<Pair<String, List<ItemEntity>>>()
    for ((kwId, name) in tmdb.searchKeywords(query)) {
        val found = matchTitles(
            tmdb.discover(false, emptyList(), 3, mapOf("with_keywords" to "$kwId")) +
                tmdb.discover(true, emptyList(), 2, mapOf("with_keywords" to "$kwId")),
            null,
        )
        if (found.size >= 2) topics += ("Tema: $name" to found)
    }
    return SearchExtras(person, personItems, topics)
}

// ---------------- Tus estadísticas ----------------

suspend fun Repo.monthStats(): MonthStats {
    val cal = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
    }
    val since = cal.timeInMillis
    val list = (db.progress().observeWatched().first() + db.progress().observeContinue().first())
        .filter { it.updatedAt >= since }.distinctBy { it.stableKey }
    val ms = list.sumOf { if (it.finished) it.durationMs.coerceAtLeast(0) else it.positionMs.coerceAtLeast(0) }
    val items = list.map { it.stableKey }.chunked(400).flatMap { db.items().genresOf(it) }.distinctBy { it.stableKey }
    val genreCount = HashMap<String, Int>()
    items.forEach { i -> i.genre?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach { g -> genreCount[g] = (genreCount[g] ?: 0) + 1 } }
    return MonthStats((ms / 3_600_000L).toInt(), list.size, genreCount.entries.sortedByDescending { it.value }.take(3).map { it.key })
}

// ---------------- Perfil infantil: preparar edades ----------------

object KidsPrep {
    /** (hechos, total) mientras se preparan las edades; null cuando no hay nada en marcha. */
    val progress = MutableStateFlow<Pair<Int, Int>?>(null)
    @Volatile var job: Job? = null
}

/** Pide a TMDB la edad recomendada de todo el catálogo (una sola vez; luego queda guardada). */
fun Repo.startKidsPrep() {
    if (!tmdb.enabled || KidsPrep.job?.isActive == true) return
    KidsPrep.job = scope.launch {
        val total = db.items().pendingEnrichCount()
        var done = 0
        KidsPrep.progress.value = 0 to total
        while (true) {
            val batch = db.items().pendingEnrich(40)
            if (batch.isEmpty()) break
            val before = db.items().pendingEnrichCount()
            batch.chunked(4).forEach { group ->
                coroutineScope { group.map { async { try { enrichWithTmdb(it) } catch (_: Exception) { } } }.awaitAll() }
            }
            done += batch.size
            KidsPrep.progress.value = done.coerceAtMost(total) to total
            // Sin conexión: paramos para no quedarnos en bucle
            if (db.items().pendingEnrichCount() >= before) break
        }
        KidsPrep.progress.value = null
    }
}

// ---------------- Copia en la nube (cuenta de TMDB) ----------------

object Cloud {
    val message = MutableStateFlow<String?>(null)
    val running = MutableStateFlow(false)
}

fun approveUrl(token: String) = "https://www.themoviedb.org/authenticate/$token"

suspend fun Repo.cloudStart(): String? = tmdb.newRequestToken()

/** Tras aprobar en la web de TMDB: crea la sesión y hace la primera copia. Devuelve un error o null. */
suspend fun Repo.cloudFinish(token: String): String? {
    val session = tmdb.newSession(token) ?: return "Todavía no está aprobado. Abre el enlace, inicia sesión en TMDB y pulsa «Aprobar»."
    val acc = tmdb.accountInfo(session) ?: return "No se ha podido leer tu cuenta de TMDB."
    prefs.update { it.copy(tmdbSession = session, tmdbAccountId = acc.first, tmdbUser = acc.second) }
    cloudSync()
    return null
}

fun Repo.cloudDisconnect() {
    prefs.update { it.copy(tmdbSession = "", tmdbAccountId = 0, tmdbUser = "", tmdbLists = "", lastCloudSync = 0L) }
    Cloud.message.value = null
}

fun Repo.cloudSyncInBackground() { scope.launch { cloudSync() } }

private fun parseLists(raw: String): MutableMap<Long, Int> =
    raw.split(',').mapNotNull { p -> p.split(':').takeIf { it.size == 2 }?.let { (a, b) -> a.toLongOrNull()?.let { k -> b.toIntOrNull()?.let { v -> k to v } } } }
        .toMap().toMutableMap()

private const val WATCHED_FOLDER = -1L
private const val PREFIX = "Butaca · "

/**
 * Copia en los dos sentidos (sin borrar nada):
 * - «Ver más adelante» ↔ lista de seguimiento de TMDB (películas y series)
 * - «Favoritos» ↔ favoritos de TMDB (películas y series)
 * - «Vistas» y tus carpetas ↔ listas «Butaca · …» de TMDB (solo películas: es lo que permite TMDB)
 */
suspend fun Repo.cloudSync(): String {
    val s = prefs.value
    if (s.tmdbSession.isBlank() || s.tmdbAccountId == 0 || !tmdb.enabled) return "No hay cuenta de TMDB conectada."
    if (Cloud.running.value) return "Ya se está copiando."
    Cloud.running.value = true
    Cloud.message.value = "Copiando tus listas en TMDB…"
    var pushed = 0
    var pulled = 0
    try {
        val session = s.tmdbSession
        val accId = s.tmdbAccountId
        ensureSystemFolders()
        val laterId = systemFolderId("later")
        val favId = systemFolderId("fav")

        suspend fun idsOf(folderId: Long): List<Pair<ItemEntity, Int>> =
            db.folders().items(folderId).mapNotNull { fi ->
                val item = bestItem(fi.stableKey) ?: return@mapNotNull null
                val id = tmdbIdFor(item) ?: return@mapNotNull null
                item to id
            }

        // ---- Ver más adelante ↔ watchlist; Favoritos ↔ favoritos ----
        for ((folderId, kind) in listOf(laterId to "watchlist", favId to "favorite")) {
            val remote = tmdb.accountList(session, accId, kind, false) + tmdb.accountList(session, accId, kind, true)
            val remoteIds = remote.map { it.id to it.isTv }.toSet()
            for ((item, id) in idsOf(folderId)) {
                val isTv = item.type == ContentType.SERIES
                if ((id to isTv) !in remoteIds) {
                    val ok = if (kind == "watchlist") tmdb.setWatchlist(session, accId, isTv, id, true) else tmdb.setFavorite(session, accId, isTv, id, true)
                    if (ok) pushed++
                }
            }
            val localKeys = db.folders().items(folderId).map { it.stableKey }.toSet()
            for (found in matchTitles(remote, null)) {
                if (found.stableKey !in localKeys) { addToFolder(folderId, found.toRef()); pulled++ }
            }
        }

        // ---- Vistas y carpetas propias ↔ listas «Butaca · …» ----
        val mapping = parseLists(s.tmdbLists)
        val remoteLists = tmdb.myLists(session, accId).toMutableList()
        val userFolders = db.folders().observeFolders().first().filter { it.system == null }
        val targets = listOf(WATCHED_FOLDER to "Vistas") + userFolders.map { it.id to it.name }
        for ((folderId, name) in targets) {
            val listId = mapping[folderId]
                ?: remoteLists.firstOrNull { it.second == PREFIX + name }?.first
                ?: tmdb.createList(session, PREFIX + name)?.also { remoteLists += it to PREFIX + name }
                ?: continue
            mapping[folderId] = listId
            val remoteIds = tmdb.listItems(listId).map { it.id }.toSet()
            val local: List<Pair<ItemEntity, Int>> = if (folderId == WATCHED_FOLDER) {
                db.progress().observeWatched().first().mapNotNull { p ->
                    val item = bestItem(p.stableKey) ?: return@mapNotNull null
                    tmdbIdFor(item)?.let { item to it }
                }
            } else idsOf(folderId)
            for ((item, id) in local) {
                if (item.type == ContentType.MOVIE && id !in remoteIds && tmdb.addToList(session, listId, id)) pushed++
            }
        }
        // Listas «Butaca · X» que no existen aquí (por ejemplo, tras reinstalar): se crean y se rellenan
        val remoteFolders = remoteLists.filter { it.second.startsWith(PREFIX) }
        for ((listId, fullName) in remoteFolders) {
            val name = fullName.removePrefix(PREFIX)
            val items = matchTitles(tmdb.listItems(listId), null)
            if (name == "Vistas") {
                val watchedKeys = db.progress().observeWatchedKeys().first().toSet()
                items.filter { it.stableKey !in watchedKeys }.forEach { markWatched(it.toRef(), true); pulled++ }
                mapping[WATCHED_FOLDER] = listId
                continue
            }
            val folder = db.folders().observeFolders().first().firstOrNull { it.system == null && it.name == name }
            val folderId = folder?.id ?: createFolder(name, 0xFF7FA8FF)
            mapping[folderId] = listId
            val localKeys = db.folders().items(folderId).map { it.stableKey }.toSet()
            items.filter { it.stableKey !in localKeys }.forEach { addToFolder(folderId, it.toRef()); pulled++ }
        }

        prefs.update { it.copy(tmdbLists = mapping.entries.joinToString(",") { e -> "${e.key}:${e.value}" }, lastCloudSync = System.currentTimeMillis()) }
        val msg = "Copia hecha: $pushed subidos a TMDB, $pulled recuperados."
        Cloud.message.value = msg
        return msg
    } catch (e: Exception) {
        val msg = "No se pudo completar la copia: ${e.message ?: "error de red"}"
        Cloud.message.value = msg
        return msg
    } finally {
        Cloud.running.value = false
    }
}
