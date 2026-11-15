package app.butaca.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Una fila del catálogo ordenado por TMDB. `all` es la lista completa (para «Ver todo»). */
data class HubRow(val id: String, val title: String, val all: List<ItemEntity>, val numbered: Boolean = false)

data class Hub(val rows: List<HubRow>, val identified: Int, val pending: Int, val unclassified: Int)

/** Última versión calculada, para abrir «Ver todo» sin volver a calcular. */
object HubCache {
    @Volatile var hub: Hub? = null
    @Volatile var at = 0L
    @Volatile var kids = -1
    @Volatile var basedOn = -1
    fun row(id: String): HubRow? = hub?.rows?.firstOrNull { it.id == id }
}

/**
 * Todas tus películas ordenadas en categorías al estilo de las plataformas, con datos de TMDB
 * (género, año, país, popularidad). Las categorías del servidor no se usan.
 */
suspend fun Repo.movieHub(force: Boolean = false): Hub {
    val kids = prefs.value.kidsLimit
    val identifiedNow = db.items().identified(ContentType.MOVIE).size
    val cached = HubCache.hub
    if (!force && cached != null && HubCache.kids == kids && HubCache.basedOn == identifiedNow &&
        System.currentTimeMillis() - HubCache.at < 30 * 60_000L
    ) return cached

    val all = withContext(Dispatchers.Default) {
        collapseVariants(db.items().identified(ContentType.MOVIE).filter { kidsOk(it) }, ContentType.MOVIE)
            .sortedByDescending { it.pop ?: 0.0 }
    }
    val pending = db.items().pendingEnrichCount()
    val unclassified = db.items().unidentifiedCount(ContentType.MOVIE)
    if (!tmdb.enabled || all.isEmpty()) {
        return Hub(emptyList(), all.size, pending, unclassified).also { HubCache.hub = it }
    }

    // id de género → nombre en castellano, tal como queda guardado en cada título
    val names = tmdb.genreList(false).toMap()
    val genreSets = withContext(Dispatchers.Default) {
        all.associate { it.key to (it.genre?.split(',')?.map { g -> TextUtil.norm(g.trim()) }?.toSet() ?: emptySet()) }
    }
    fun has(i: ItemEntity, vararg ids: Int) = ids.any { id -> names[id]?.let { TextUtil.norm(it) in genreSets[i.key].orEmpty() } == true }

    val rows = ArrayList<HubRow>()
    fun add(id: String, title: String, list: List<ItemEntity>, numbered: Boolean = false, min: Int = 3) {
        if (list.size >= min) rows += HubRow(id, title, list, numbered)
    }
    fun genre(id: String, title: String, vararg g: Int) = add(id, title, all.filter { has(it, *g) })
    fun years(id: String, title: String, from: Int, to: Int) = add(id, title, all.filter { (it.year ?: 0) in from..to })

    val year = Calendar.getInstance().get(Calendar.YEAR)
    val watched = db.progress().observeWatchedKeys().first().toSet()

    // 1) Lo que creemos que te gustará (a partir de tus favoritos; si no hay, de lo que has visto)
    val favId = systemFolderId("fav")
    val favs = db.folders().items(favId).filter { it.type == ContentType.MOVIE }
    val seeds = favs.map { it.stableKey }.ifEmpty { db.progress().observeWatched().first().map { it.stableKey } }.take(12)
    if (seeds.isNotEmpty()) {
        val score = HashMap<String, Double>()
        val byKey = HashMap<String, ItemEntity>()
        val skip = seeds.toSet() + watched
        for (k in seeds) {
            val item = bestItem(k) ?: continue
            if (item.type != ContentType.MOVIE) continue
            val id = tmdbIdFor(item) ?: continue
            val d = tmdb.details(false, id) ?: continue
            val recs = tmdb.recommendations(d, false)
            val matched = matchTitles(recs, item.stableKey)
            val rank = recs.withIndex().associate { it.value.id to it.index }
            matched.forEach { m ->
                if (m.stableKey in skip) return@forEach
                val r = m.tmdbId?.let { rank[it] } ?: 30
                score[m.stableKey] = (score[m.stableKey] ?: 0.0) + (40.0 - r).coerceAtLeast(1.0)
                byKey[m.stableKey] = m
            }
        }
        val picks = score.entries.sortedByDescending { it.value }.mapNotNull { byKey[it.key] }.take(30)
        add("foryou", if (favs.isNotEmpty()) "Películas que creemos que te gustarán" else "Porque has visto…", picks)
    }

    genre("action", "Cine de acción", 28)
    genre("scifi", "Cine de ciencia ficción", 878)
    // Espacio: palabra clave de TMDB
    try {
        val kw = listOfNotNull(tmdb.keywordId("space"), tmdb.keywordId("outer space"), tmdb.keywordId("astronaut")).distinct()
        if (kw.isNotEmpty()) {
            val space = matchTitles(tmdb.discover(false, emptyList(), 20, mapOf("with_keywords" to kw.joinToString("|"))), null)
            add("space", "Cine de espacio", space.sortedByDescending { it.pop ?: 0.0 })
        }
    } catch (_: Exception) { }
    add("family", "Cine infantil y para toda la familia", all.filter { has(it, 10751) || (has(it, 16) && (it.cert ?: 0) <= 12) })
    // Top 10 de lo más visto ahora en España que tienes
    try {
        add("top10", "Top 10 en España", matchTitles(tmdb.popular(false, "ES", 5), null).take(10), numbered = true)
    } catch (_: Exception) { }
    add("spain", "Cine español", all.filter { it.country == "ES" })
    genre("crime", "Películas policiacas", 80)
    years("y2000", "Películas de los 2000", 2000, 2009)
    years("y90", "Películas de los 90", 1990, 1999)
    years("y80", "Películas de los 80", 1980, 1989)
    years("y2010", "Películas de 2010 a 2020", 2010, 2020)
    for (y in year downTo 2020) years("y$y", "Estrenos de $y", y, y)
    genre("horror", "Cine de terror", 27)
    genre("mystery", "Cine de misterio", 9648)
    genre("thriller", "Cine de suspense", 53)
    genre("drama", "Cine drama", 18)
    genre("comedy", "Películas de comedia", 35)
    genre("romance", "Películas románticas", 10749)
    genre("war", "Películas de guerra y militares", 10752)
    genre("western", "Películas del oeste", 37)
    try {
        val kw = listOfNotNull(tmdb.keywordId("teenager"), tmdb.keywordId("high school"), tmdb.keywordId("coming of age")).distinct()
        if (kw.isNotEmpty()) {
            val teen = matchTitles(tmdb.discover(false, emptyList(), 20, mapOf("with_keywords" to kw.joinToString("|"))), null)
            add("teen", "Películas de adolescentes", teen.sortedByDescending { it.pop ?: 0.0 })
        }
    } catch (_: Exception) { }
    genre("docs", "Documentales", 99)
    // Lo que no ha entrado en ninguna fila, para que no se pierda nada
    val inRows = rows.flatMap { r -> r.all.map { it.stableKey } }.toHashSet()
    add("others", "Otras películas", all.filter { it.stableKey !in inRows })
    add("all", "Todas las películas", all, min = 1)

    val hub = Hub(rows, all.size, pending, unclassified)
    HubCache.hub = hub
    HubCache.at = System.currentTimeMillis()
    HubCache.kids = kids
    HubCache.basedOn = identifiedNow
    return hub
}
