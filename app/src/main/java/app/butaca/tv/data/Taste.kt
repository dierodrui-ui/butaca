package app.butaca.tv.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.tanh

/**
 * «% para ti»: lo que la app ha aprendido de este perfil.
 *
 *  · 70 % por gustos: géneros que valoras bien (o mal), favoritos, lo que terminas, lo último que has visto,
 *    y si TMDB lo recomienda a partir de títulos que te han gustado (o que no).
 *  · 30 % por la nota de TMDB: desde 6 empieza a sumar y con un 9 o más llega al 30 %.
 */
class TasteModel(
    private val genreAff: Map<String, Float>,      // género (normalizado) → -1..1
    private val related: Map<Int, Float>,          // id de TMDB → -1..1 (recomendado por lo que te gustó / no te gustó)
    private val index: Map<String, MatchRow>,      // stableKey → datos para calcular
    val signals: Int,
    /** Tus notas: lo que has valorado tú manda (un 10 = 100 %, un 7 = 70 %…). */
    private val rated: Map<String, Int> = emptyMap(),
) {
    fun rated(stableKey: String): Int? = rated[stableKey]?.let { it * 10 }

    /** % para un título concreto: tu nota si la tiene; si no, el cálculo. */
    fun matchFor(stableKey: String, genre: String?, rating: Double?, tmdbId: Int?): Int? =
        rated(stableKey) ?: match(stableKey) ?: match(genre, rating, tmdbId)

    val ready: Boolean get() = signals >= 3

    fun match(stableKey: String): Int? = rated(stableKey) ?: index[stableKey]?.let { match(it.genre, it.rating, it.tmdbId) }

    fun match(genre: String?, rating: Double?, tmdbId: Int?): Int? {
        if (!ready) return null
        val genres = genre?.split(',')?.map { TextUtil.norm(it.trim()) }?.filter { it.isNotBlank() }.orEmpty()
        if (genres.isEmpty() && tmdbId == null) return null
        // Géneros: los que más te gustan pesan más que los neutros
        val affs = genres.map { genreAff[it] ?: 0f }
        val g = if (affs.isEmpty()) 0f else (affs.sum() / affs.size) * 0.6f + (affs.maxOrNull() ?: 0f) * 0.4f
        var taste = (g + 1f) / 2f                                    // 0..1
        // Recomendado a partir de lo que te gustó (o de lo que no)
        val rel = tmdbId?.let { related[it] } ?: 0f
        taste = if (rel >= 0) taste + (1f - taste) * rel else taste * (1f + rel)
        val tastePart = taste.coerceIn(0f, 1f) * 70f
        val vote = rating ?: 0.0
        val votePart = (((vote - 6.0) / 3.0).coerceIn(0.0, 1.0) * 30.0).toFloat()
        return (tastePart + votePart).toInt().coerceIn(0, 100)
    }

    /** Todos los títulos de tu catálogo con un «% para ti» de al menos [min]. */
    fun above(min: Int): List<Pair<String, Int>> =
        if (!ready) emptyList() else index.keys.mapNotNull { k -> match(k)?.takeIf { it >= min }?.let { k to it } }

    /** Afinidad 0..1 con unos géneros (para la tirada del dado). */
    fun genreAffinity(genre: String?): Float {
        val genres = genre?.split(',')?.map { TextUtil.norm(it.trim()) }.orEmpty()
        if (genres.isEmpty()) return 0.5f
        return ((genres.map { genreAff[it] ?: 0f }.average().toFloat()) + 1f) / 2f
    }
}

object Taste {
    private val _model = MutableStateFlow<TasteModel?>(null)
    val model: StateFlow<TasteModel?> = _model.asStateFlow()
    private var job: Job? = null
    @Volatile private var profileId: Long = -1

    fun clear() { _model.value = null; profileId = -1 }

    /** Recalcula (con un pequeño retraso para agrupar cambios seguidos). */
    fun rebuild(repo: Repo, delayMs: Long = 1500) {
        job?.cancel()
        job = repo.scope.launch {
            delay(delayMs)
            try { _model.value = repo.buildTaste() } catch (_: Exception) { }
        }
    }

    internal fun set(m: TasteModel) { _model.value = m }
}

private fun daysAgo(t: Long) = (System.currentTimeMillis() - t) / 86_400_000.0

suspend fun Repo.buildTaste(): TasteModel {
    val sum = HashMap<String, Float>()
    var signals = 0
    fun add(genre: String?, w: Float) {
        genre?.split(',')?.map { TextUtil.norm(it.trim()) }?.filter { it.isNotBlank() }?.forEach { g -> sum[g] = (sum[g] ?: 0f) + w }
    }
    val ratings = user.taste().all()
    val rated = ratings.associateBy { it.stableKey }
    val keys = HashSet<String>()

    // 1) Tus notas (lo que más pesa). Las recientes cuentan algo más.
    ratings.forEach { r ->
        val w = ((r.score - 5.5f) / 4.5f) * (0.75f + 0.25f * exp(-daysAgo(r.at) / 120.0).toFloat())
        add(r.genre, w * 1.4f); signals++
    }
    // 2) Favoritos (si no tienen nota)
    val favId = systemFolderId("fav")
    val favs = user.folders().items(favId).filter { it.stableKey !in rated }
    keys += favs.map { it.stableKey }
    // 3) Lo que has terminado sin valorar
    val finished = user.progress().observeWatched().first().filter { it.stableKey !in rated }
    keys += finished.map { it.stableKey }
    // 4) Lo último que has visto (aunque no lo terminaras), con más peso cuanto más reciente
    val recent = user.stats().observeTop(40).first().filter { it.stableKey !in rated }
    keys += recent.map { it.stableKey }
    val laterId = systemFolderId("later")
    val later = user.folders().items(laterId).filter { it.stableKey !in rated }
    keys += later.map { it.stableKey }

    val info = db.items().byStableKeys(keys.toList()).associateBy { it.stableKey }
    favs.forEach { f -> add(info[f.stableKey]?.genre, 0.8f); signals++ }
    finished.forEach { p -> if (p.stableKey !in favs.map { it.stableKey }) { add(info[p.stableKey]?.genre, 0.35f); signals++ } }
    recent.forEach { t ->
        if (t.total >= 15 * 60_000L) { add(info[t.stableKey]?.genre, (0.25f * exp(-daysAgo(t.lastAt) / 14.0)).toFloat()); signals++ }
    }
    later.forEach { l -> add(info[l.stableKey]?.genre, 0.12f) }
    // Pulgares del dado (sin haberla visto: cuentan menos que una nota)
    val ups = DiceVotes.up(this); val downs = DiceVotes.down(this)
    val voted = db.items().byStableKeys((ups + downs).toList().take(400)).associateBy { it.stableKey }
    ups.forEach { k -> voted[k]?.let { add(it.genre, 0.3f); signals++ } }
    downs.forEach { k -> voted[k]?.let { add(it.genre, -0.35f); signals++ } }

    val genreAff = sum.mapValues { (_, v) -> tanh(v / 2.2f) }

    // Recomendaciones de TMDB a partir de lo que te gustó (y de lo que no)
    val related = HashMap<Int, Float>()
    if (tmdb.enabled) {
        val seeds = ArrayList<Triple<Int, Boolean, Float>>()   // tmdbId, esSerie, fuerza (-1..1)
        ratings.filter { it.tmdbId != null && (it.score >= 7 || it.score <= 4) }.take(14).forEach { r ->
            seeds += Triple(r.tmdbId!!, r.type == ContentType.SERIES, if (r.score >= 7) (r.score - 6) / 4f else -(5 - r.score) / 4f)
        }
        favs.take(10).forEach { f ->
            val it0 = info[f.stableKey] ?: return@forEach
            val id = it0.tmdbId ?: return@forEach
            seeds += Triple(id, it0.type == ContentType.SERIES, 0.6f)
        }
        for ((id, isTv, strength) in seeds.distinctBy { it.first }.take(18)) {
            val d = try { tmdb.details(isTv, id) } catch (_: Exception) { null } ?: continue
            val recs = tmdb.recommendations(d, isTv)
            recs.forEachIndexed { i, t ->
                val v = strength * (1f - i / 30f).coerceAtLeast(0.2f)
                val cur = related[t.id] ?: 0f
                related[t.id] = (cur + v).coerceIn(-1f, 1f)
            }
        }
    }
    val index = db.items().matchIndex().associateBy { it.stableKey }
    return TasteModel(genreAff, related, index, signals, ratings.associate { it.stableKey to it.score })
}

// ----- Notas -----

/** Guarda tu nota (1..10) y recalcula tus gustos. */
suspend fun Repo.rate(ref: ContentRef, score: Int) {
    val item = db.items().byStableKeys(listOf(ref.stableKey)).firstOrNull()
    user.taste().set(
        TasteEntity(ref.stableKey, ref.type, ref.title, ref.icon, score.coerceIn(1, 10), item?.genre, item?.tmdbId, System.currentTimeMillis())
    )
    Taste.rebuild(this, 200)
}

suspend fun Repo.clearRating(stableKey: String) { user.taste().clear(stableKey); Taste.rebuild(this, 200) }

/** Al terminar algo, la app pregunta qué te ha parecido (una vez por título). */
object RatePrompt {
    val request = MutableStateFlow<ContentRef?>(null)
    private val asked = HashSet<String>()
    fun ask(ref: ContentRef) { if (asked.add(ref.stableKey)) request.value = ref }
    fun done() { request.value = null }
}

// ----- El dado -----

/**
 * Una película de tu catálogo que no hayas visto, elegida al azar pero con tus gustos:
 * las que más encajan (y con lo último que has visto) tienen muchas más papeletas.
 */
suspend fun Repo.rollDice(exclude: Set<String> = emptySet()): ItemEntity? {
    val model = Taste.model.value ?: buildTaste().also { Taste.set(it) }
    val seen = user.progress().observeWatchedKeys().first().toHashSet()
    val rated = user.taste().all().map { it.stableKey }.toHashSet()
    val downVotes = DiceVotes.down(this)
    // Lo último que has visto empuja hacia géneros parecidos
    val recentGenres = HashMap<String, Float>()
    val recent = user.stats().observeHistory().first().take(12)
    val recentInfo = db.items().byStableKeys(recent.map { it.stableKey }).associateBy { it.stableKey }
    recent.forEachIndexed { i, h ->
        recentInfo[h.stableKey]?.genre?.split(',')?.map { TextUtil.norm(it.trim()) }?.forEach { g -> recentGenres[g] = (recentGenres[g] ?: 0f) + (1f - i / 14f) }
    }
    val maxRecent = recentGenres.values.maxOrNull() ?: 1f
    val pool = collapseVariants(db.items().identified(ContentType.MOVIE).filter { kidsOk(it) }, ContentType.MOVIE)
        .filter { it.stableKey !in seen && it.stableKey !in rated && it.stableKey !in exclude && it.stableKey !in downVotes }
    if (pool.isEmpty()) return null
    val scored = pool.map { item ->
        val m = (model.match(item.genre, item.rating, item.tmdbId) ?: (50 + (((item.rating ?: 6.0) - 6.0) * 8).toInt())).coerceIn(1, 100)
        val rg = item.genre?.split(',')?.map { TextUtil.norm(it.trim()) }?.maxOfOrNull { recentGenres[it] ?: 0f } ?: 0f
        val w = Math.pow(m / 100.0, 3.0) * (1.0 + 0.6 * (rg / maxRecent)) + 0.002
        item to w
    }.sortedByDescending { it.second }.take(120)
    val total = scored.sumOf { it.second }
    var r = Math.random() * total
    for ((item, w) in scored) { r -= w; if (r <= 0) return item }
    return scored.first().first
}



/**
 * «Muy compatibles contigo ahora»: lo que no has visto con un 90-100 % para ti.
 * Cada vez que entras sale una selección distinta (las más altas, un poco barajadas).
 */
suspend fun Repo.topMatches(min: Int = 90, limit: Int = 24): List<ItemEntity> {
    val model = Taste.model.value ?: return emptyList()
    if (!model.ready) return emptyList()
    val seen = user.progress().observeWatchedKeys().first().toHashSet()
    val down = DiceVotes.down(this)
    val picks = model.above(min).filter { it.first !in seen && it.first !in down }
        .sortedByDescending { it.second }.take(90).shuffled().take(limit * 2)
    val items = picks.map { it.first }.chunked(400).flatMap { db.items().byStableKeys(it) }
        .filter { !it.isAdult && kidsOk(it) }
    val byKey = (collapseVariants(items.filter { it.type == ContentType.MOVIE }, ContentType.MOVIE) +
        collapseVariants(items.filter { it.type == ContentType.SERIES }, ContentType.SERIES)).associateBy { it.stableKey }
    return picks.mapNotNull { byKey[it.first] }.noRepeats().take(limit)
}

/** Pulgares del dado: abajo = no vuelve a salir (y resta un poco), arriba = suma un poco. Por perfil. */
object DiceVotes {
    private fun sp() = app.butaca.tv.ButacaApp.instance.getSharedPreferences("dice_votes", android.content.Context.MODE_PRIVATE)
    private fun pid(repo: Repo) = repo.profile.value?.id ?: 0L
    fun down(repo: Repo): Set<String> = sp().getStringSet("down_${pid(repo)}", emptySet()) ?: emptySet()
    fun up(repo: Repo): Set<String> = sp().getStringSet("up_${pid(repo)}", emptySet()) ?: emptySet()
    fun vote(repo: Repo, stableKey: String, good: Boolean) {
        val p = pid(repo)
        val d = down(repo).toMutableSet(); val u = up(repo).toMutableSet()
        if (good) { u += stableKey; d -= stableKey } else { d += stableKey; u -= stableKey }
        sp().edit().putStringSet("down_$p", d).putStringSet("up_$p", u).apply()
        Taste.rebuild(repo)
    }
}


/** Títulos que has quitado de una lista concreta («No mostrar en esta lista»). Por perfil. */
object ListExclusions {
    private fun sp() = app.butaca.tv.ButacaApp.instance.getSharedPreferences("list_excl", android.content.Context.MODE_PRIVATE)
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)
    private fun key(repo: Repo) = "p_${repo.profile.value?.id ?: 0L}"
    fun all(repo: Repo): Set<String> = sp().getStringSet(key(repo), emptySet()) ?: emptySet()
    fun hidden(repo: Repo, listId: String, stableKey: String) = "$listId::$stableKey" in all(repo)
    fun hide(repo: Repo, listId: String, stableKey: String) {
        sp().edit().putStringSet(key(repo), all(repo) + "$listId::$stableKey").apply()
        version.value++
    }
    fun clear(repo: Repo, listId: String) {
        sp().edit().putStringSet(key(repo), all(repo).filterNot { it.startsWith("$listId::") }.toSet()).apply()
        version.value++
    }
}
