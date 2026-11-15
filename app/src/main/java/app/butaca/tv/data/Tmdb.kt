package app.butaca.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Un título de TMDB, para buscarlo luego en tu servidor. */
data class TmdbTitle(
    val id: Int,
    val isTv: Boolean,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val genreIds: List<Int> = emptyList(),
    val popularity: Double = 0.0,
    val language: String? = null,
    val poster: String? = null,
    val date: String? = null,
)

data class TmdbPerson(val id: Int, val name: String, val role: String?, val photo: String?)

data class PersonInfo(
    val id: Int,
    val name: String,
    val photo: String?,
    val bio: String?,
    val department: String?,
    val birthday: String?,
    val place: String?,
)

data class TvStatus(
    val status: String?,          // "Returning Series", "Ended", "Canceled"…
    val seasons: Int,
    val nextAirDate: String?,
    val nextSeason: Int?,
    val nextEpisode: Int?,
)

data class TmdbEpisode(val number: Int, val name: String?, val overview: String?, val still: String?, val airDate: String?, val runtime: Int?)

/**
 * Cliente de TMDB (The Movie Database).
 * Este producto usa la API de TMDB pero no está respaldado ni certificado por TMDB.
 */
class TmdbClient(
    private val http: OkHttpClient,
    private val cacheDir: File,
    private val keyProvider: () -> String,
) {
    private val base = "https://api.themoviedb.org/3"

    val enabled: Boolean get() = keyProvider().isNotBlank()

    /** Último problema al hablar con TMDB (para mostrarlo en Ajustes). */
    @Volatile var lastError: String? = null

    companion object {
        fun img(size: String, path: String?): String? = path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

        /** Edad recomendada a partir de la calificación (España primero, EE. UU. si no hay). */
        fun parseCert(raw: String?): Int? {
            val s = raw?.trim()?.uppercase() ?: return null
            if (s.isBlank()) return null
            return when (s) {
                "A", "TP", "APTA", "AL", "ALL", "0", "G", "TV-Y", "TV-G", "U" -> 0
                "7", "+7", "7+", "PG", "TV-Y7", "TV-PG" -> 7
                "12", "+12", "12+", "13", "PG-13", "TV-14" -> 12
                "16", "+16", "16+", "R", "15" -> 16
                "18", "+18", "18+", "X", "NC-17", "TV-MA" -> 18
                else -> s.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 18)
            }
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private val cacheRoot by lazy { File(cacheDir, "tmdb").apply { mkdirs() } }
    @Volatile private var lastTrim = 0L

    /** Mantiene la caché por debajo de ~120 MB. */
    private fun trimCache() {
        val now = System.currentTimeMillis()
        if (now - lastTrim < 10 * 60_000L) return
        lastTrim = now
        val files = cacheRoot.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < 120L * 1024 * 1024) break
            total -= f.length(); f.delete()
        }
    }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap(), ttlHours: Long = 168, auth: Map<String, String> = emptyMap()): JSONObject? =
        withContext(Dispatchers.IO) {
            val key = keyProvider().trim()
            if (key.isBlank()) return@withContext null
            val b = (base + path).toHttpUrl().newBuilder()
            params.forEach { (k, v) -> b.addQueryParameter(k, v) }
            val cacheKey = sha1(b.build().toString())
            val file = File(cacheRoot, cacheKey)
            if (ttlHours > 0 && file.exists() && System.currentTimeMillis() - file.lastModified() < ttlHours * 3600_000L) {
                try { return@withContext JSONObject(file.readText()) } catch (_: Exception) { }
            }
            b.addQueryParameter("api_key", key)
            auth.forEach { (k, v) -> b.addQueryParameter(k, v) }
            try {
                http.newCall(Request.Builder().url(b.build()).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = if (resp.code == 401) "Clave de TMDB no válida" else "TMDB respondió ${resp.code}"
                        return@withContext null
                    }
                    lastError = null
                    val body = resp.body?.string() ?: return@withContext null
                    if (ttlHours > 0) { try { file.writeText(body) } catch (_: Exception) { }; trimCache() }
                    JSONObject(body)
                }
            } catch (e: Exception) {
                lastError = "Sin conexión con TMDB"
                null
            }
        }

    private suspend fun send(method: String, path: String, body: JSONObject?, auth: Map<String, String> = emptyMap()): JSONObject? =
        withContext(Dispatchers.IO) {
            val key = keyProvider().trim()
            if (key.isBlank()) return@withContext null
            val b = (base + path).toHttpUrl().newBuilder().addQueryParameter("api_key", key)
            auth.forEach { (k, v) -> b.addQueryParameter(k, v) }
            val rb = (body ?: JSONObject()).toString().toRequestBody("application/json;charset=utf-8".toMediaType())
            try {
                http.newCall(Request.Builder().url(b.build()).method(method, rb).build()).execute().use { resp ->
                    val txt = resp.body?.string() ?: return@withContext null
                    try { JSONObject(txt) } catch (_: Exception) { null }
                }
            } catch (_: Exception) {
                null
            }
        }

    /** Comprueba la clave y la conexión. Devuelve un texto para enseñar al usuario. */
    suspend fun test(): String = withContext(Dispatchers.IO) {
        val key = keyProvider().trim()
        if (key.isBlank()) return@withContext "No hay ninguna clave configurada."
        val url = "$base/configuration".toHttpUrl().newBuilder().addQueryParameter("api_key", key).build()
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                when (r.code) {
                    200 -> { lastError = null; "Funciona correctamente (clave de ${key.length} caracteres)." }
                    401 -> "La clave no es válida. Revisa que sea la «Clave de la API» corta, sin espacios."
                    404 -> "La clave no es válida o TMDB aún no la ha activado."
                    429 -> "TMDB pide esperar un poco (demasiadas consultas)."
                    else -> "TMDB respondió con el código ${r.code}."
                }
            }
        } catch (e: Exception) {
            "No hay conexión con TMDB: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ===================== Títulos =====================

    private fun yearOf(s: String?): Int? = s?.take(4)?.toIntOrNull()

    private fun parseTitle(o: JSONObject, forceTv: Boolean? = null): TmdbTitle? {
        val media = o.optString("media_type", "")
        if (media == "person") return null
        val isTv = forceTv ?: (media == "tv" || (media.isBlank() && o.has("name") && !o.has("title")))
        val title = (if (isTv) o.optString("name") else o.optString("title")).ifBlank { return null }
        val orig = if (isTv) o.optString("original_name") else o.optString("original_title")
        val date = if (isTv) o.optString("first_air_date") else o.optString("release_date")
        val g = o.optJSONArray("genre_ids")
        val genres = if (g == null) emptyList() else (0 until g.length()).map { g.optInt(it) }
        return TmdbTitle(
            o.optInt("id"), isTv, title, orig.ifBlank { null }, yearOf(date), genres, o.optDouble("popularity", 0.0),
            o.optString("original_language").ifBlank { null }, img("w342", o.optString("poster_path")), date.ifBlank { null },
        )
    }

    private fun parseList(arr: JSONArray?, forceTv: Boolean? = null): List<TmdbTitle> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseTitle(it, forceTv) } }
    }

    /** Busca el título en TMDB por nombre y año. */
    suspend fun findId(isTv: Boolean, name: String, year: Int?): Int? {
        val path = if (isTv) "/search/tv" else "/search/movie"
        val clean = TextUtil.cleanName(name).replace(Regex("\\s*[\\(\\[](19|20)\\d{2}[\\)\\]]\\s*$"), "").trim()
        val params = mutableMapOf("query" to clean, "language" to "es-ES")
        val yearKey = if (isTv) "first_air_date_year" else "primary_release_year"
        if (year != null) params[yearKey] = year.toString()
        val first = get(path, params)?.optJSONArray("results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
        if (first != null || year == null) return first
        params.remove(yearKey)
        return get(path, params)?.optJSONArray("results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    }

    /** Ficha completa (para la pantalla de detalle). */
    suspend fun details(isTv: Boolean, id: Int): JSONObject? =
        if (isTv) get("/tv/$id", mapOf(
            "language" to "es-ES",
            "append_to_response" to "aggregate_credits,recommendations,similar,keywords,images,videos,content_ratings",
            "include_image_language" to "es,en,null", "include_video_language" to "es,en",
        ))
        else get("/movie/$id", mapOf(
            "language" to "es-ES",
            "append_to_response" to "credits,recommendations,similar,keywords,images,videos,release_dates",
            "include_image_language" to "es,en,null", "include_video_language" to "es,en",
        ))

    /** Ficha ligera: géneros, sinopsis, fondo y edad. Para completar carátulas y el perfil infantil. */
    suspend fun light(isTv: Boolean, id: Int): JSONObject? =
        if (isTv) get("/tv/$id", mapOf("language" to "es-ES", "append_to_response" to "content_ratings"), ttlHours = 24 * 30)
        else get("/movie/$id", mapOf("language" to "es-ES", "append_to_response" to "release_dates"), ttlHours = 24 * 30)

    fun genres(d: JSONObject): List<Pair<Int, String>> {
        val arr = d.optJSONArray("genres") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { it.optInt("id") to it.optString("name") }
    }

    fun keywords(d: JSONObject, isTv: Boolean): List<Pair<Int, String>> {
        val kw = d.optJSONObject("keywords") ?: return emptyList()
        val arr = (if (isTv) kw.optJSONArray("results") else kw.optJSONArray("keywords")) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { it.optInt("id") to it.optString("name") }
    }

    fun certification(d: JSONObject, isTv: Boolean): Int? {
        fun pick(country: String): Int? {
            if (isTv) {
                val arr = d.optJSONObject("content_ratings")?.optJSONArray("results") ?: return null
                return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { it.optString("iso_3166_1") == country }?.optString("rating")?.let { parseCert(it) }
            }
            val arr = d.optJSONObject("release_dates")?.optJSONArray("results") ?: return null
            val c = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("iso_3166_1") == country } ?: return null
            val rd = c.optJSONArray("release_dates") ?: return null
            return (0 until rd.length()).mapNotNull { rd.optJSONObject(it)?.optString("certification") }
                .firstNotNullOfOrNull { parseCert(it) }
        }
        return pick("ES") ?: pick("US")
    }

    fun logo(d: JSONObject): String? {
        val arr = d.optJSONObject("images")?.optJSONArray("logos") ?: return null
        val logos = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val pick = logos.firstOrNull { it.optString("iso_639_1") == "es" }
            ?: logos.firstOrNull { it.optString("iso_639_1") == "en" }
            ?: logos.firstOrNull()
        return img("w500", pick?.optString("file_path"))
    }

    /** Clave de YouTube del tráiler (en castellano si lo hay). */
    fun trailer(d: JSONObject): String? {
        val arr = d.optJSONObject("videos")?.optJSONArray("results") ?: return null
        val vids = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("site") == "YouTube" }
        fun best(lang: String) = vids.filter { it.optString("iso_639_1") == lang }
            .sortedWith(compareBy({ if (it.optString("type") == "Trailer") 0 else if (it.optString("type") == "Teaser") 1 else 2 }, { if (it.optBoolean("official")) 0 else 1 }))
            .firstOrNull()
        return (best("es") ?: best("en") ?: vids.firstOrNull())?.optString("key")?.ifBlank { null }
    }

    fun runtimeMinutes(d: JSONObject, isTv: Boolean): Int? =
        if (isTv) d.optJSONArray("episode_run_time")?.let { if (it.length() > 0) it.optInt(0) else null }?.takeIf { it > 0 }
        else d.optInt("runtime").takeIf { it > 0 }

    fun tvStatus(d: JSONObject): TvStatus {
        val next = d.optJSONObject("next_episode_to_air")
        return TvStatus(
            status = d.optString("status").ifBlank { null },
            seasons = d.optInt("number_of_seasons"),
            nextAirDate = next?.optString("air_date")?.ifBlank { null },
            nextSeason = next?.optInt("season_number"),
            nextEpisode = next?.optInt("episode_number"),
        )
    }

    fun people(d: JSONObject, isTv: Boolean, count: Int): List<TmdbPerson> {
        val out = ArrayList<TmdbPerson>()
        directors(d, isTv).forEach { out += it }
        val credits = if (isTv) d.optJSONObject("aggregate_credits") else d.optJSONObject("credits")
        val cast = credits?.optJSONArray("cast")
        if (cast != null) (0 until cast.length()).mapNotNull { cast.optJSONObject(it) }.take(count).forEach { c ->
            val role = if (isTv) c.optJSONArray("roles")?.optJSONObject(0)?.optString("character") else c.optString("character")
            out += TmdbPerson(c.optInt("id"), c.optString("name"), role?.ifBlank { null }, img("w185", c.optString("profile_path")))
        }
        return out.distinctBy { it.id }
    }

    fun directors(d: JSONObject, isTv: Boolean): List<TmdbPerson> {
        if (isTv) {
            val arr = d.optJSONArray("created_by") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .map { TmdbPerson(it.optInt("id"), it.optString("name"), "Creación", img("w185", it.optString("profile_path"))) }
        }
        val crew = d.optJSONObject("credits")?.optJSONArray("crew") ?: return emptyList()
        return (0 until crew.length()).mapNotNull { crew.optJSONObject(it) }
            .filter { it.optString("job") == "Director" }
            .map { TmdbPerson(it.optInt("id"), it.optString("name"), "Dirección", img("w185", it.optString("profile_path"))) }
    }

    fun mainCast(d: JSONObject, isTv: Boolean, count: Int): List<TmdbPerson> =
        people(d, isTv, count).filter { it.role != "Dirección" && it.role != "Creación" }.take(count)

    fun recommendations(d: JSONObject, isTv: Boolean): List<TmdbTitle> =
        parseList(d.optJSONObject("recommendations")?.optJSONArray("results"), isTv) +
            parseList(d.optJSONObject("similar")?.optJSONArray("results"), isTv)

    /** Recomendaciones de TMDB («si te gustó esto…»), en su orden, varias páginas. */
    suspend fun recommendationPages(isTv: Boolean, id: Int, d: JSONObject, pages: Int = 3): List<TmdbTitle> {
        val out = ArrayList(parseList(d.optJSONObject("recommendations")?.optJSONArray("results"), isTv))
        for (p in 2..pages) {
            val arr = get("/${if (isTv) "tv" else "movie"}/$id/recommendations", mapOf("language" to "es-ES", "page" to p.toString()))
                ?.optJSONArray("results") ?: break
            if (arr.length() == 0) break
            out += parseList(arr, isTv)
        }
        return out.distinctBy { it.id }
    }

    /** Títulos parecidos según TMDB, varias páginas. */
    suspend fun similarPages(isTv: Boolean, id: Int, d: JSONObject, pages: Int = 2): List<TmdbTitle> {
        val out = ArrayList(parseList(d.optJSONObject("similar")?.optJSONArray("results"), isTv))
        for (p in 2..pages) {
            val arr = get("/${if (isTv) "tv" else "movie"}/$id/similar", mapOf("language" to "es-ES", "page" to p.toString()))
                ?.optJSONArray("results") ?: break
            if (arr.length() == 0) break
            out += parseList(arr, isTv)
        }
        return out.distinctBy { it.id }
    }

    suspend fun collection(id: Int): List<TmdbTitle> =
        parseList(get("/collection/$id", mapOf("language" to "es-ES"))?.optJSONArray("parts"), forceTv = false)
            .sortedBy { it.year ?: 9999 }

    suspend fun season(tvId: Int, number: Int): List<TmdbEpisode> {
        val arr = get("/tv/$tvId/season/$number", mapOf("language" to "es-ES"))?.optJSONArray("episodes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            TmdbEpisode(
                it.optInt("episode_number"), it.optString("name").ifBlank { null }, it.optString("overview").ifBlank { null },
                img("w300", it.optString("still_path")), it.optString("air_date").ifBlank { null }, it.optInt("runtime").takeIf { r -> r > 0 },
            )
        }
    }

    // ===================== Descubrir =====================

    suspend fun discover(isTv: Boolean, genreIds: List<Int>, pages: Int = 5, extra: Map<String, String> = emptyMap()): List<TmdbTitle> {
        if (genreIds.isEmpty() && extra.isEmpty()) return emptyList()
        val path = if (isTv) "/discover/tv" else "/discover/movie"
        val out = ArrayList<TmdbTitle>()
        for (p in 1..pages) {
            val params = mutableMapOf("language" to "es-ES", "sort_by" to "vote_count.desc", "page" to p.toString())
            if (genreIds.isNotEmpty()) params["with_genres"] = genreIds.joinToString(",")
            params.putAll(extra)
            val obj = get(path, params) ?: break
            val res = obj.optJSONArray("results") ?: break
            out += parseList(res, isTv)
            if (p >= obj.optInt("total_pages", 1)) break
        }
        return out
    }

    suspend fun trending(pages: Int = 3): List<TmdbTitle> {
        val out = ArrayList<TmdbTitle>()
        for (p in 1..pages) out += parseList(get("/trending/all/week", mapOf("language" to "es-ES", "page" to "$p"), ttlHours = 12)?.optJSONArray("results"))
        return out
    }

    suspend fun topRated(isTv: Boolean, pages: Int = 4): List<TmdbTitle> {
        val out = ArrayList<TmdbTitle>()
        val path = if (isTv) "/tv/top_rated" else "/movie/top_rated"
        for (p in 1..pages) out += parseList(get(path, mapOf("language" to "es-ES", "page" to "$p"), ttlHours = 72)?.optJSONArray("results"), isTv)
        return out
    }

    suspend fun upcoming(pages: Int = 2): List<TmdbTitle> {
        val out = ArrayList<TmdbTitle>()
        for (p in 1..pages) out += parseList(get("/movie/upcoming", mapOf("language" to "es-ES", "region" to "ES", "page" to "$p"), ttlHours = 24)?.optJSONArray("results"), false)
        return out
    }

    // ===================== Personas =====================

    suspend fun person(id: Int): PersonInfo? {
        val es = get("/person/$id", mapOf("language" to "es-ES")) ?: return null
        var bio = es.optString("biography").ifBlank { null }
        if (bio == null) bio = get("/person/$id", mapOf("language" to "en-US"))?.optString("biography")?.ifBlank { null }
        return PersonInfo(
            id, es.optString("name"), img("h632", es.optString("profile_path")), bio,
            es.optString("known_for_department").ifBlank { null }, es.optString("birthday").ifBlank { null },
            es.optString("place_of_birth").ifBlank { null },
        )
    }

    /** Películas y series de una persona (como director o como actor/actriz). */
    suspend fun personWork(personId: Int, asDirector: Boolean): List<TmdbTitle> {
        val o = get("/person/$personId/combined_credits", mapOf("language" to "es-ES")) ?: return emptyList()
        return if (asDirector) {
            val crew = o.optJSONArray("crew") ?: return emptyList()
            (0 until crew.length()).mapNotNull { i ->
                crew.optJSONObject(i)?.takeIf { it.optString("job") == "Director" || it.optString("job") == "Creator" }
            }.sortedByDescending { it.optDouble("popularity", 0.0) }.mapNotNull { parseTitle(it) }
        } else {
            val cast = o.optJSONArray("cast") ?: return emptyList()
            (0 until cast.length()).mapNotNull { cast.optJSONObject(it) }
                .sortedByDescending { it.optDouble("popularity", 0.0) }.mapNotNull { parseTitle(it) }
        }
    }

    /** Todo lo de una persona: dirigido y actuado. */
    suspend fun personAll(personId: Int): List<TmdbTitle> =
        (personWork(personId, true) + personWork(personId, false)).distinctBy { it.id to it.isTv }

    /** Persona que encaja con lo escrito (p. ej. «tom hanks», «almodovar»). */
    suspend fun searchPerson(query: String): TmdbPerson? {
        val res = get("/search/person", mapOf("query" to query, "language" to "es-ES"), ttlHours = 72)?.optJSONArray("results") ?: return null
        val q = TextUtil.norm(query)
        val p = (0 until res.length()).mapNotNull { res.optJSONObject(it) }
            .firstOrNull { val n = TextUtil.norm(it.optString("name")); n.contains(q) || q.contains(n) || n.split(' ').any { w -> w.length > 3 && w == q } }
            ?: return null
        if (p.optDouble("popularity", 0.0) < 1.0) return null
        return TmdbPerson(p.optInt("id"), p.optString("name"), p.optString("known_for_department"), img("w185", p.optString("profile_path")))
    }

    /** Temas (palabras clave). TMDB los tiene en inglés: traducimos las palabras sueltas que conocemos. */
    suspend fun searchKeywords(query: String): List<Pair<Int, String>> {
        val variants = (listOf(query) + TextUtil.searchTokens(query).flatten()).map { it.trim() }.filter { it.length >= 3 }.distinct().take(4)
        val out = LinkedHashMap<Int, String>()
        for (v in variants) {
            val res = get("/search/keyword", mapOf("query" to v), ttlHours = 24 * 7)?.optJSONArray("results") ?: continue
            (0 until res.length()).mapNotNull { res.optJSONObject(it) }
                .filter { TextUtil.norm(it.optString("name")) == TextUtil.norm(v) || TextUtil.norm(it.optString("name")).startsWith(TextUtil.norm(v)) }
                .take(2).forEach { out.putIfAbsent(it.optInt("id"), it.optString("name")) }
        }
        return out.entries.take(3).map { it.key to it.value }
    }

    /** Títulos en español y en inglés para lo que escribes en el buscador. */
    suspend fun titleVariants(query: String): List<String> {
        val out = LinkedHashSet<String>()
        for (lang in listOf("es-ES", "en-US")) {
            val res = get("/search/multi", mapOf("query" to query, "language" to lang), ttlHours = 72)?.optJSONArray("results")
            parseList(res).take(5).forEach { t ->
                out.add(t.title)
                t.originalTitle?.let { o -> if (o.any { it in 'a'..'z' || it in 'A'..'Z' }) out.add(o) }
            }
        }
        return out.toList()
    }

    // ===================== Etiquetas (explorador) =====================

    /** Lista oficial de géneros en castellano. */
    suspend fun genreList(isTv: Boolean): List<Pair<Int, String>> {
        val arr = get("/genre/${if (isTv) "tv" else "movie"}/list", mapOf("language" to "es-ES"), ttlHours = 24 * 30)?.optJSONArray("genres")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { it.optInt("id") to it.optString("name") }
    }

    /** Id de una palabra clave (en inglés) de TMDB. */
    suspend fun keywordId(term: String): Int? {
        val arr = get("/search/keyword", mapOf("query" to term), ttlHours = 24 * 30)?.optJSONArray("results") ?: return null
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val t = TextUtil.norm(term)
        return (list.firstOrNull { TextUtil.norm(it.optString("name")) == t } ?: list.firstOrNull())?.optInt("id")?.takeIf { it > 0 }
    }

    /** Id de un estudio/productora por su nombre. */
    suspend fun companyId(name: String): Int? {
        val arr = get("/search/company", mapOf("query" to name), ttlHours = 24 * 30)?.optJSONArray("results") ?: return null
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val n = TextUtil.norm(name)
        return (list.firstOrNull { TextUtil.norm(it.optString("name")) == n } ?: list.firstOrNull())?.optInt("id")?.takeIf { it > 0 }
    }

    // ===================== Estudios =====================

    data class Studio(val id: Int, val name: String, val isNetwork: Boolean)

    fun studios(d: JSONObject, isTv: Boolean): List<Studio> {
        val out = ArrayList<Studio>()
        if (isTv) d.optJSONArray("networks")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.forEach { out += Studio(it.optInt("id"), it.optString("name"), true) }
        }
        d.optJSONArray("production_companies")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .sortedByDescending { if (it.optString("logo_path").isNotBlank()) 1 else 0 }
                .forEach { out += Studio(it.optInt("id"), it.optString("name"), false) }
        }
        return out.filter { it.id > 0 && it.name.isNotBlank() }.distinctBy { it.id to it.isNetwork }
    }

    suspend fun byStudio(isTv: Boolean, studio: Studio, pages: Int = 4): List<TmdbTitle> =
        discover(isTv, emptyList(), pages, mapOf((if (studio.isNetwork) "with_networks" else "with_companies") to studio.id.toString()))

    // ===================== Cuenta de TMDB (copia en la nube) =====================

    suspend fun newRequestToken(): String? =
        get("/authentication/token/new", ttlHours = 0)?.optString("request_token")?.ifBlank { null }

    suspend fun newSession(requestToken: String): String? =
        send("POST", "/authentication/session/new", JSONObject().put("request_token", requestToken))
            ?.takeIf { it.optBoolean("success") }?.optString("session_id")?.ifBlank { null }

    suspend fun accountInfo(session: String): Pair<Int, String>? {
        val o = get("/account", ttlHours = 0, auth = mapOf("session_id" to session)) ?: return null
        val id = o.optInt("id").takeIf { it > 0 } ?: return null
        return id to o.optString("username")
    }

    suspend fun setWatchlist(session: String, accountId: Int, isTv: Boolean, id: Int, on: Boolean): Boolean =
        send("POST", "/account/$accountId/watchlist",
            JSONObject().put("media_type", if (isTv) "tv" else "movie").put("media_id", id).put("watchlist", on),
            mapOf("session_id" to session))?.optBoolean("success") ?: false

    suspend fun setFavorite(session: String, accountId: Int, isTv: Boolean, id: Int, on: Boolean): Boolean =
        send("POST", "/account/$accountId/favorite",
            JSONObject().put("media_type", if (isTv) "tv" else "movie").put("media_id", id).put("favorite", on),
            mapOf("session_id" to session))?.optBoolean("success") ?: false

    /** Lista de la cuenta: watchlist o favorite, de películas («movies») o series («tv»). */
    suspend fun accountList(session: String, accountId: Int, kind: String, isTv: Boolean): List<TmdbTitle> {
        val out = ArrayList<TmdbTitle>()
        var page = 1
        while (page <= 20) {
            val o = get("/account/$accountId/$kind/${if (isTv) "tv" else "movies"}", mapOf("page" to "$page", "language" to "es-ES"), ttlHours = 0,
                auth = mapOf("session_id" to session)) ?: break
            out += parseList(o.optJSONArray("results"), isTv)
            if (page >= o.optInt("total_pages", 1)) break
            page++
        }
        return out
    }

    /** Listas propias (solo admiten películas en esta versión de la API). */
    suspend fun myLists(session: String, accountId: Int): List<Pair<Int, String>> {
        val o = get("/account/$accountId/lists", mapOf("page" to "1"), ttlHours = 0, auth = mapOf("session_id" to session)) ?: return emptyList()
        val arr = o.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { it.optInt("id") to it.optString("name") }
    }

    suspend fun createList(session: String, name: String): Int? =
        send("POST", "/list", JSONObject().put("name", name).put("description", "Butaca").put("language", "es"), mapOf("session_id" to session))
            ?.optInt("list_id")?.takeIf { it > 0 }

    suspend fun listItems(listId: Int): List<TmdbTitle> =
        parseList(get("/list/$listId", mapOf("language" to "es-ES"), ttlHours = 0)?.optJSONArray("items"), false)

    suspend fun addToList(session: String, listId: Int, movieId: Int): Boolean =
        send("POST", "/list/$listId/add_item", JSONObject().put("media_id", movieId), mapOf("session_id" to session))?.optBoolean("success") ?: false
}
