package app.butaca.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Una fila del catálogo ordenado por TMDB. `all` es la lista completa (para «Ver todo»). */
data class HubRow(val id: String, val title: String, val all: List<ItemEntity>, val numbered: Boolean = false)

data class Hub(val rows: List<HubRow>, val identified: Int, val pending: Int, val unclassified: Int)

/** Última versión calculada de cada catálogo (películas y series). */
object HubCache {
    class Entry(val hub: Hub, val at: Long, val kids: Int, val basedOn: Int)
    val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    var hub: Hub?
        get() = entries[ContentType.MOVIE]?.hub
        set(v) { if (v == null) entries.clear() }
    fun hub(type: String): Hub? = entries[type]?.hub
    fun row(id: String): HubRow? = entries.values.firstNotNullOfOrNull { e -> e.hub.rows.firstOrNull { it.id == id } }
}

/** Catálogo por categorías de TMDB: películas o series. */
suspend fun Repo.catalogHub(type: String, force: Boolean = false): Hub =
    if (type == ContentType.SERIES) seriesHub(force) else movieHub(force)

/**
 * Todas tus películas ordenadas en categorías al estilo de las plataformas, con datos de TMDB
 * (género, año, país, popularidad). Las categorías del servidor no se usan.
 */
suspend fun Repo.movieHub(force: Boolean = false): Hub {
    val kids = kidsLimit
    val identifiedNow = db.items().identified(ContentType.MOVIE).size
    val cached = HubCache.entries[ContentType.MOVIE]
    if (!force && cached != null && cached.kids == kids && cached.basedOn == identifiedNow &&
        System.currentTimeMillis() - cached.at < 30 * 60_000L
    ) return cached.hub

    val all = withContext(Dispatchers.Default) {
        collapseVariants(db.items().identified(ContentType.MOVIE).filter { kidsOk(it) }, ContentType.MOVIE)
            .sortedByDescending { it.pop ?: 0.0 }
    }
    val pending = db.items().pendingEnrichCount()
    val unclassified = db.items().unidentifiedCount(ContentType.MOVIE)
    if (!tmdb.enabled || all.isEmpty()) {
        return Hub(emptyList(), all.size, pending, unclassified)
    }

    // id de género → nombre en castellano, tal como queda guardado en cada título
    val names = tmdb.genreList(false).toMap()
    val genreSets = withContext(Dispatchers.Default) {
        all.associate { it.key to (it.genre?.split(',')?.map { g -> TextUtil.norm(g.trim()) }?.toSet() ?: emptySet()) }
    }
    fun has(i: ItemEntity, vararg ids: Int) = ids.any { id -> names[id]?.let { TextUtil.norm(it) in genreSets[i.key].orEmpty() } == true }

    val rows = ArrayList<HubRow>()
    fun add(id: String, title: String, raw: List<ItemEntity>, numbered: Boolean = false, min: Int = 3) {
        val list = raw.noRepeats()
        if (list.size >= min) rows += HubRow(id, title, list, numbered)
    }
    fun genre(id: String, title: String, vararg g: Int) = add(id, title, all.filter { has(it, *g) })
    fun years(id: String, title: String, from: Int, to: Int) = add(id, title, all.filter { (it.year ?: 0) in from..to })

    val year = Calendar.getInstance().get(Calendar.YEAR)
    val watched = user.progress().observeWatchedKeys().first().toSet()

    // 1) Lo que creemos que te gustará (a partir de tus favoritos; si no hay, de lo que has visto)
    val favId = systemFolderId("fav")
    val favs = user.folders().items(favId).filter { it.type == ContentType.MOVIE }
    val seeds = favs.map { it.stableKey }.ifEmpty { user.progress().observeWatched().first().map { it.stableKey } }.take(12)
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
        add("foryou", "Películas que creemos que te gustarán", picks)
    }
    // Siempre existe: en pantalla se rellena con tu «% para ti» (80-100 %)
    if (rows.none { it.id == "foryou" }) rows.add(0, HubRow("foryou", "Películas que creemos que te gustarán", emptyList()))

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
    // Cine infantil: SOLO lo que pueden ver menores de 12 (calificación de TMDB hasta 7 años)
    add("family", "Cine infantil", all.filter { (has(it, 10751) || has(it, 16)) && it.cert != null && it.cert <= 7 })
    // Animación y dibujos: solo películas de animación
    genre("animation", "Animación y dibujos", 16)
    // I.A.: inteligencia artificial, androides y máquinas que piensan
    try {
        val kw = listOfNotNull(tmdb.keywordId("artificial intelligence"), tmdb.keywordId("android"), tmdb.keywordId("sentient machine")).distinct()
        if (kw.isNotEmpty()) {
            val ai = matchTitles(tmdb.discover(false, emptyList(), 20, mapOf("with_keywords" to kw.joinToString("|"))), null)
            add("ai", "I.A. (inteligencia artificial)", ai.filter { it.type == ContentType.MOVIE }.sortedByDescending { it.pop ?: 0.0 })
        }
    } catch (_: Exception) { }
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
            // Que las puedan ver adolescentes (calificación hasta 16) y de 2000 en adelante
            add("teen", "Películas de adolescentes", teen.filter { it.cert != null && it.cert <= 16 && (it.year ?: 0) >= 2000 }.sortedByDescending { it.pop ?: 0.0 })
        }
    } catch (_: Exception) { }
    genre("docs", "Documentales", 99)
    // Lo que no ha entrado en ninguna fila, para que no se pierda nada
    val inRows = rows.flatMap { r -> r.all.map { it.stableKey } }.toHashSet()
    add("others", "Otras películas", all.filter { it.stableKey !in inRows })
    add("all", "Todas las películas", all, min = 1)

    val hub = Hub(rows, all.size, pending, unclassified)   // orden original; el tuyo se aplica con arrange()
    HubCache.entries[ContentType.MOVIE] = HubCache.Entry(hub, System.currentTimeMillis(), kids, identifiedNow)
    return hub
}

/**
 * Todas tus series por categorías de TMDB: plataformas, géneros de series, españolas, épocas y estrenos.
 * Los ids van con «s_» para que su orden no se mezcle con el de películas.
 */
suspend fun Repo.seriesHub(force: Boolean = false): Hub {
    val kids = kidsLimit
    val identifiedNow = db.items().identified(ContentType.SERIES).size
    val cached = HubCache.entries[ContentType.SERIES]
    if (!force && cached != null && cached.kids == kids && cached.basedOn == identifiedNow &&
        System.currentTimeMillis() - cached.at < 30 * 60_000L
    ) return cached.hub
    val all = withContext(Dispatchers.Default) {
        collapseVariants(db.items().identified(ContentType.SERIES).filter { kidsOk(it) }, ContentType.SERIES)
            .sortedByDescending { it.pop ?: 0.0 }
    }
    val pending = db.items().pendingEnrichCount()
    val unclassified = db.items().unidentifiedCount(ContentType.SERIES)
    if (!tmdb.enabled || all.isEmpty()) return Hub(emptyList(), all.size, pending, unclassified)

    val names = tmdb.genreList(true).toMap()
    val genreSets = withContext(Dispatchers.Default) {
        all.associate { it.key to (it.genre?.split(',')?.map { g -> TextUtil.norm(g.trim()) }?.toSet() ?: emptySet()) }
    }
    fun has(i: ItemEntity, vararg ids: Int) = ids.any { id -> names[id]?.let { TextUtil.norm(it) in genreSets[i.key].orEmpty() } == true }
    val rows = ArrayList<HubRow>()
    fun add(id: String, title: String, raw: List<ItemEntity>, numbered: Boolean = false, min: Int = 3) {
        val list = raw.noRepeats()
        if (list.size >= min) rows += HubRow("s_$id", title, list, numbered)
    }
    fun genre(id: String, title: String, vararg g: Int) = add(id, title, all.filter { has(it, *g) })
    fun years(id: String, title: String, from: Int, to: Int) = add(id, title, all.filter { (it.year ?: 0) in from..to })
    suspend fun network(id: String, title: String, net: Int) = try {
        add(id, title, matchTitles(tmdb.discover(true, emptyList(), 15, mapOf("with_networks" to "$net", "sort_by" to "popularity.desc")), null)
            .filter { it.type == ContentType.SERIES })
    } catch (_: Exception) { }

    // «Series que creemos que te gustarán» se rellena en pantalla con tu «% para ti»
    add("foryou", "Series que creemos que te gustarán", emptyList(), min = 0)
    try { add("top10", "Top 10 en España", matchTitles(tmdb.popular(true, "ES", 5), null).filter { it.type == ContentType.SERIES }.take(10), numbered = true) } catch (_: Exception) { }
    network("netflix", "Series de Netflix", 213)
    network("hbo", "Series de HBO y Max", 49)
    network("prime", "Series de Prime Video", 1024)
    network("disney", "Series de Disney+", 2739)
    network("apple", "Series de Apple TV+", 2552)
    add("spain", "Series españolas", all.filter { it.country == "ES" })
    genre("action", "Acción y aventura", 10759)
    genre("crime", "Series policiacas y de crimen", 80)
    genre("mystery", "Series de misterio", 9648)
    genre("drama", "Series de drama", 18)
    genre("comedy", "Series de comedia", 35)
    genre("scifi", "Ciencia ficción y fantasía", 10765)
    genre("animation", "Animación", 16)
    genre("kids", "Series infantiles y para toda la familia", 10762, 10751)
    genre("docs", "Series documentales", 99)
    genre("reality", "Realities", 10764)
    genre("war", "Bélicas y política", 10768)
    genre("soap", "Telenovelas", 10766)
    try {
        val kw = tmdb.keywordId("miniseries")
        if (kw != null) add("mini", "Miniseries", matchTitles(tmdb.discover(true, emptyList(), 10, mapOf("with_keywords" to "$kw")), null).filter { it.type == ContentType.SERIES })
    } catch (_: Exception) { }
    val year = Calendar.getInstance().get(Calendar.YEAR)
    for (y in year downTo 2020) years("y$y", "Estrenos de $y", y, y)
    years("y2010", "Series de 2010 a 2019", 2010, 2019)
    years("y2000", "Series de los 2000", 2000, 2009)
    years("y90", "Series de los 90 y antes", 1950, 1999)
    val inRows = rows.flatMap { r -> r.all.map { it.stableKey } }.toHashSet()
    add("others", "Otras series", all.filter { it.stableKey !in inRows })
    add("all", "Todas las series", all, min = 1)
    val hub = Hub(rows, all.size, pending, unclassified)
    HubCache.entries[ContentType.SERIES] = HubCache.Entry(hub, System.currentTimeMillis(), kids, identifiedNow)
    return hub
}

/** Aplica tu orden y tus categorías ocultas. Lo que no has movido sigue en su sitio de siempre. */
fun Hub.arrange(order: String, hidden: String): List<HubRow> {
    val hide = hidden.split(',').filter { it.isNotBlank() }.toSet()
    val saved = order.split(',').filter { it.isNotBlank() }
    val byId = rows.associateBy { it.id }
    val out = ArrayList<HubRow>()
    saved.forEach { id -> byId[id]?.let { out += it } }
    // las nuevas (p. ej. «Estrenos de 2027») entran en su posición natural
    rows.forEachIndexed { i, r ->
        if (r.id in saved) return@forEachIndexed
        val prev = rows.subList(0, i).lastOrNull { it.id in saved || out.contains(it) }
        val at = if (prev == null) 0 else out.indexOf(prev) + 1
        out.add(at.coerceIn(0, out.size), r)
    }
    return out.filter { it.id !in hide }
}
