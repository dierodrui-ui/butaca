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
        val fresh = list.noRepeats().filter { it.stableKey !in watchedKeys && it.stableKey !in used }.take(18)
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

    // Actualidad: lo que se lee hoy en Wikipedia convertido en filas con lo que tienes
    try { trendRows().forEach { (t, l) -> add(t, l) } } catch (_: Exception) { }

    // Para estas fechas: fiestas, eventos y estaciones (calendario propio + palabras clave de TMDB)
    for (ev in activeEvents(Calendar.getInstance()).take(2)) {
        try {
            val kw = ev.keywords.mapNotNull { k -> tmdb.keywordId(k) }.distinct()
            val extra = HashMap<String, String>()
            if (kw.isNotEmpty()) extra["with_keywords"] = kw.joinToString("|")
            val found = ArrayList<TmdbTitle>()
            if (kw.isNotEmpty() || ev.genres.isNotEmpty()) {
                found += tmdb.discover(false, ev.genres, 8, extra)
                if (ev.tv) found += tmdb.discover(true, ev.tvGenres, 3, extra)
            }
            add(ev.title, matchTitles(found.sortedByDescending { it.popularity }, null), min = 4)
        } catch (_: Exception) { }
    }

    add("Imprescindibles", matchTitles(tmdb.topRated(false, 4), null))
    val year = Calendar.getInstance().get(Calendar.YEAR)
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
            batch.chunked(6).forEach { group ->
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

// ---------------- Explorador por etiquetas ----------------

enum class TagKind { GENRE, KEYWORD, COMPANY, NETWORK }

/** Una etiqueta del explorador. Los géneros tienen id distinto en películas y en series. */
data class Tag(val kind: TagKind, val name: String, val movieId: Int? = null, val tvId: Int? = null) {
    val key: String get() = "${kind.name}:$name"
}

data class TagGroup(val title: String, val tags: List<Tag>, val visible: Int = 10)

/** Etiquetas pendientes de abrir desde una ficha (al pulsar un género, un tema o un estudio). */
object ExploreNav {
    @Volatile var pending: List<Tag> = emptyList()
}

// Temas: nombre en castellano → palabra clave de TMDB (en inglés). Ordenados por importancia.
private val TOPICS = listOf(
    "Basada en hechos reales" to "based on true story", "Navidad" to "christmas", "Superhéroes" to "superhero",
    "Basada en novela" to "based on novel or book", "Venganza" to "revenge", "Viajes en el tiempo" to "time travel",
    "Atracos" to "heist", "Espías" to "spy", "Mafia" to "mafia", "Asesino en serie" to "serial killer",
    "Amistad" to "friendship", "Crecer" to "coming of age", "Familia" to "family relationships", "Zombis" to "zombie",
    "Distopía" to "dystopia", "Segunda Guerra Mundial" to "world war ii", "Espacio" to "space", "Extraterrestres" to "alien",
    "Robots" to "robot", "Inteligencia artificial" to "artificial intelligence", "Vampiros" to "vampire",
    "Dinosaurios" to "dinosaur", "Magia" to "magic", "Princesas" to "princess", "Animales" to "animal",
    "Deporte" to "sports", "Fútbol" to "soccer", "Boxeo" to "boxing", "Juicios" to "courtroom", "Política" to "politics",
    "Periodismo" to "journalism", "Policías" to "police", "Supervivencia" to "survival", "Desastres" to "disaster",
    "Música" to "musician", "Cocina" to "cooking", "Instituto" to "high school", "Basada en cómic" to "based on comic",
    "Road movie" to "road movie", "Remake" to "remake",
)

// Estudios por orden de importancia (el id se busca en TMDB la primera vez)
private val STUDIOS = listOf(
    "Walt Disney Pictures", "Pixar", "Marvel Studios", "Warner Bros. Pictures", "Universal Pictures", "Paramount Pictures",
    "Columbia Pictures", "20th Century Fox", "DreamWorks Animation", "Lucasfilm Ltd.", "Illumination", "Studio Ghibli",
    "A24", "Legendary Pictures", "Blumhouse Productions", "Lionsgate", "New Line Cinema", "Metro-Goldwyn-Mayer",
    "Amblin Entertainment", "Atresmedia Cine", "Telecinco Cinema", "El Deseo", "Netflix", "Sony Pictures Animation",
)

// Plataformas de series (ids de cadena en TMDB)
private val NETWORKS = listOf("Netflix" to 213, "HBO" to 49, "Prime Video" to 1024, "Disney+" to 2739, "Apple TV+" to 2552)

// Equivalencias de géneros de película → serie
private val TV_EQUIV = mapOf(28 to 10759, 12 to 10759, 878 to 10765, 14 to 10765, 10752 to 10768)

private object TagCache {
    var genres: List<Tag> = emptyList()
    val keywords = HashMap<String, Int?>()
    val companies = HashMap<String, Int?>()
}

/** Géneros con sus ids de película y de serie. */
suspend fun Repo.genreTags(): List<Tag> {
    if (TagCache.genres.isNotEmpty()) return TagCache.genres
    val movie = tmdb.genreList(false)
    val tv = tmdb.genreList(true)
    val tvByName = tv.associate { TextUtil.norm(it.second) to it.first }
    val movieTags = movie.map { (id, name) -> Tag(TagKind.GENRE, name, id, tvByName[TextUtil.norm(name)] ?: TV_EQUIV[id]) }
    // Géneros que solo existen en series (Infantil, Reality, Telenovela…)
    val usedTv = movieTags.mapNotNull { it.tvId }.toSet()
    val tvOnly = tv.filter { it.first !in usedTv }.map { (id, name) -> Tag(TagKind.GENRE, name, null, id) }
    val tags = movieTags + tvOnly
    if (tags.isNotEmpty()) TagCache.genres = tags
    return tags
}

/** Grupos de etiquetas, de más a menos importantes. */
suspend fun Repo.explorerGroups(includeSlow: Boolean): List<TagGroup> {
    if (!tmdb.enabled) return emptyList()
    // Géneros: los que más hay en tu servidor, primero
    val counts = HashMap<String, Int>()
    db.items().allGenres().forEach { g -> g.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
    val genres = genreTags().sortedByDescending { counts[it.name] ?: 0 }
    val groups = arrayListOf(TagGroup("Géneros", genres, 12))
    groups += TagGroup("Temas", TOPICS.map { Tag(TagKind.KEYWORD, it.first) }, 12)
    groups += TagGroup("Estudios", STUDIOS.map { Tag(TagKind.COMPANY, it) }, 10)
    groups += TagGroup("Plataformas (series)", NETWORKS.map { Tag(TagKind.NETWORK, it.first, null, it.second) }, 5)
    if (includeSlow) Unit
    return groups
}

/** Completa los ids que faltan (temas y estudios se buscan en TMDB la primera vez). */
private suspend fun Repo.resolve(tag: Tag): Tag = when (tag.kind) {
    TagKind.GENRE -> genreTags().firstOrNull { TextUtil.norm(it.name) == TextUtil.norm(tag.name) }
        ?: tag
    TagKind.KEYWORD -> {
        if (tag.movieId != null) tag else {
            val term = TOPICS.firstOrNull { it.first == tag.name }?.second ?: tag.name
            val id = TagCache.keywords.getOrPut(term) { tmdb.keywordId(term) }
            tag.copy(movieId = id, tvId = id)
        }
    }
    TagKind.COMPANY -> {
        if (tag.movieId != null) tag else {
            val id = TagCache.companies.getOrPut(tag.name) { tmdb.companyId(tag.name) }
            tag.copy(movieId = id, tvId = id)
        }
    }
    TagKind.NETWORK -> tag
}

/** Nombres con los que un género puede estar guardado (en películas y en series se llaman distinto a veces). */
private suspend fun Repo.genreNames(tag: Tag): List<String> {
    val names = LinkedHashSet<String>()
    names += tag.name
    val movie = tmdb.genreList(false)
    val tv = tmdb.genreList(true)
    tag.movieId?.let { id -> movie.firstOrNull { it.first == id }?.let { names += it.second } }
    tag.tvId?.let { id -> tv.firstOrNull { it.first == id }?.let { names += it.second } }
    return names.toList()
}

/** Todo lo de tu servidor que encaja con TODAS las etiquetas elegidas. */
suspend fun Repo.explore(selected: List<Tag>): List<ItemEntity> {
    if (selected.isEmpty() || !tmdb.enabled) return emptyList()
    val tags = selected.map { resolve(it) }
    val genres = tags.filter { it.kind == TagKind.GENRE }
    val kws = tags.filter { it.kind == TagKind.KEYWORD }.mapNotNull { it.movieId }
    val comps = tags.filter { it.kind == TagKind.COMPANY }.mapNotNull { it.movieId }
    val nets = tags.filter { it.kind == TagKind.NETWORK }.mapNotNull { it.tvId }
    val onlyGenres = kws.isEmpty() && comps.isEmpty() && nets.isEmpty()

    // 1) Tu catálogo: cada título guarda sus géneros de TMDB, así salen TODOS los que tienes (películas y series)
    val genreNames = genres.map { genreNames(it) }
    fun hasGenres(i: ItemEntity): Boolean {
        val g = i.genre?.split(',')?.map { TextUtil.norm(it.trim()) }?.toSet() ?: return false
        return genreNames.all { alts -> alts.any { TextUtil.norm(it) in g } }
    }
    val local: List<ItemEntity> = if (genres.isEmpty()) emptyList() else {
        genreNames.first().flatMap { db.items().withTmdbGenre(it) }.distinctBy { it.key }
            .filter { !it.isAdult && kidsOk(it) && hasGenres(it) }
    }

    // 2) TMDB discover: para temas, estudios y plataformas (y para lo que aún no se ha leído de tu catálogo)
    suspend fun run(kwJoin: String): List<TmdbTitle> {
        val pages = if (onlyGenres) 5 else 15   // con temas/estudios la lista es corta: la recorremos entera
        val out = ArrayList<TmdbTitle>()
        if (nets.isEmpty() && genres.all { it.movieId != null }) {
            val p = HashMap<String, String>()
            if (genres.isNotEmpty()) p["with_genres"] = genres.mapNotNull { it.movieId }.distinct().joinToString(",")
            if (kws.isNotEmpty()) p["with_keywords"] = kws.joinToString(kwJoin)
            if (comps.isNotEmpty()) p["with_companies"] = comps.joinToString("|")
            if (p.isNotEmpty()) out += tmdb.discover(false, emptyList(), pages, p)
        }
        if (genres.all { it.tvId != null }) {
            val p = HashMap<String, String>()
            if (genres.isNotEmpty()) p["with_genres"] = genres.mapNotNull { it.tvId }.distinct().joinToString(",")
            if (kws.isNotEmpty()) p["with_keywords"] = kws.joinToString(kwJoin)
            if (comps.isNotEmpty()) p["with_companies"] = comps.joinToString("|")
            if (nets.isNotEmpty()) p["with_networks"] = nets.joinToString("|")
            if (p.isNotEmpty()) out += tmdb.discover(true, emptyList(), pages, p)
        }
        return out
    }

    var found = matchTitles(run(","), null)
    if (found.size < 6 && kws.size > 1) found = (found + matchTitles(run("|"), null)).distinctBy { it.stableKey }

    // Con solo géneros: lo más popular primero y después todo lo demás de tu catálogo, por nota
    val merged = if (onlyGenres) {
        val rest = local.sortedByDescending { it.rating ?: 0.0 }
        found + collapseVariants(rest.filter { it.type == ContentType.MOVIE }, ContentType.MOVIE) +
            collapseVariants(rest.filter { it.type == ContentType.SERIES }, ContentType.SERIES)
    } else {
        // Con temas o estudios, si además hay géneros, que también los cumplan
        if (genres.isEmpty()) found else found.filter { it.genre == null || it.tmdbId == null || hasGenres(it) }
    }
    return merged.noRepeats()
}

/** Sin carátulas repetidas: misma clave o misma película de TMDB. */
fun List<ItemEntity>.noRepeats(): List<ItemEntity> {
    val keys = HashSet<String>()
    val ids = HashSet<Int>()
    return filter { keys.add(it.stableKey) && (it.tmdbId == null || ids.add(it.tmdbId)) }
}


// ---------------- Para estas fechas ----------------

/** Un momento del año (o del mundo) con títulos que encajan. Fechas inclusive. */
data class SeasonEvent(
    val title: String,
    val from: java.time.LocalDate,
    val to: java.time.LocalDate,
    val keywords: List<String> = emptyList(),   // palabras clave de TMDB (en inglés)
    val genres: List<Int> = emptyList(),
    val tvGenres: List<Int> = emptyList(),
    val tv: Boolean = true,
    val priority: Int = 5,                        // más alto = más específico
)

/** Domingo de Pascua (algoritmo de Gauss/Meeus para el calendario gregoriano). */
private fun easter(y: Int): java.time.LocalDate {
    val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4
    val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31; val day = ((h + l - 7 * m + 114) % 31) + 1
    return java.time.LocalDate.of(y, month, day)
}

private fun firstSunday(y: Int, month: Int): java.time.LocalDate =
    java.time.LocalDate.of(y, month, 1).with(java.time.temporal.TemporalAdjusters.firstInMonth(java.time.DayOfWeek.SUNDAY))

/** Eventos del mundo con fecha conocida (eclipses visibles en España, grandes torneos…). */
private val WORLD_EVENTS = listOf(
    SeasonEvent("Eclipse total de sol en España", java.time.LocalDate.of(2027, 7, 20), java.time.LocalDate.of(2027, 8, 3),
        listOf("solar eclipse", "eclipse", "astronomy", "astronaut"), priority = 9),
    SeasonEvent("Eclipse anular de sol", java.time.LocalDate.of(2028, 1, 16), java.time.LocalDate.of(2028, 1, 27),
        listOf("solar eclipse", "eclipse", "astronomy"), priority = 9),
    SeasonEvent("Eclipse total de luna", java.time.LocalDate.of(2028, 12, 24), java.time.LocalDate.of(2029, 1, 1),
        listOf("moon", "eclipse", "astronomy"), priority = 8),
    SeasonEvent("Mundial femenino de fútbol", java.time.LocalDate.of(2027, 6, 20), java.time.LocalDate.of(2027, 7, 25),
        listOf("soccer", "football (soccer)", "women's sports"), priority = 7),
    SeasonEvent("Eurocopa 2028", java.time.LocalDate.of(2028, 6, 5), java.time.LocalDate.of(2028, 7, 9),
        listOf("soccer", "football (soccer)"), priority = 7),
    SeasonEvent("Juegos Olímpicos de Los Ángeles", java.time.LocalDate.of(2028, 7, 10), java.time.LocalDate.of(2028, 7, 30),
        listOf("olympic games", "athlete", "sports"), priority = 8),
)

/** Lo que toca hoy, de más a menos específico. */
fun activeEvents(cal: Calendar): List<SeasonEvent> {
    val today = java.time.LocalDate.of(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    val y = today.year
    fun d(m: Int, day: Int, year: Int = y) = java.time.LocalDate.of(year, m, day)
    val e = easter(y)
    val list = ArrayList<SeasonEvent>()
    // Fiestas
    list += SeasonEvent("Para estas Navidades", d(11, 20), d(12, 31), listOf("christmas", "santa claus", "christmas eve"), priority = 9)
    list += SeasonEvent("Para estas Navidades", d(1, 1), d(1, 6), listOf("christmas", "santa claus", "three kings"), priority = 9)
    list += SeasonEvent("Para despedir el año", d(12, 26), d(12, 31), listOf("new year's eve", "new year"), priority = 10)
    list += SeasonEvent("Para San Valentín", d(2, 6), d(2, 14), listOf("valentine's day", "romantic comedy"), genres = listOf(10749), priority = 9)
    list += SeasonEvent("Carnaval", e.minusDays(52), e.minusDays(46), listOf("carnival", "costume party", "masquerade"), priority = 8)
    list += SeasonEvent("Para Semana Santa", e.minusDays(9), e.plusDays(1), listOf("jesus christ", "bible", "biblical epic", "ancient rome"), priority = 9)
    list += SeasonEvent("Día del Padre", d(3, 12), d(3, 19), listOf("father son relationship", "father daughter relationship"), priority = 8)
    list += SeasonEvent("Día del Libro: historias que empezaron en papel", d(4, 16), d(4, 23), listOf("based on novel or book"), priority = 6)
    list += SeasonEvent("Que la Fuerza te acompañe", d(5, 1), d(5, 4), listOf("space opera", "galaxy", "jedi"), priority = 7)
    firstSunday(y, 5).let { m -> list += SeasonEvent("Día de la Madre", m.minusDays(7), m, listOf("mother daughter relationship", "mother son relationship"), priority = 8) }
    list += SeasonEvent("Llegada a la Luna: aniversario", d(7, 14), d(7, 21), listOf("moon landing", "moon", "astronaut", "nasa"), priority = 7)
    list += SeasonEvent("Noche de las Perseidas", d(8, 9), d(8, 14), listOf("meteor", "astronomy", "outer space"), priority = 6)
    list += SeasonEvent("Vuelta al cole", d(9, 1), d(9, 15), listOf("school", "high school", "teacher"), priority = 6)
    list += SeasonEvent("Para pasar miedo en Halloween", d(10, 15), d(11, 1), listOf("halloween", "haunted house", "witch"), genres = listOf(27), tvGenres = listOf(9648), priority = 9)
    list += SeasonEvent("Día de Muertos", d(10, 28), d(11, 2), listOf("day of the dead", "afterlife", "ghost"), priority = 8)
    // Estaciones (siempre hay alguna)
    list += SeasonEvent("Para tardes de invierno", d(1, 7), d(3, 19), listOf("snow", "winter", "cabin"), priority = 2)
    list += SeasonEvent("Para tardes de invierno", d(12, 21), d(12, 31), listOf("snow", "winter"), priority = 1)
    list += SeasonEvent("Llega la primavera: aventuras al aire libre", d(3, 20), d(6, 20), listOf("road trip", "nature", "journey"), priority = 2)
    list += SeasonEvent("Cine de verano", d(6, 21), d(9, 21), listOf("summer", "beach", "summer vacation"), priority = 2)
    list += SeasonEvent("Para tardes de otoño", d(9, 22), d(12, 20), listOf("mystery", "detective", "whodunit"), genres = emptyList(), priority = 2)
    list += WORLD_EVENTS
    return list.filter { !today.isBefore(it.from) && !today.isAfter(it.to) }
        .sortedByDescending { it.priority }
        .distinctBy { it.title }
}
