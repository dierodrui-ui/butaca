package app.butaca.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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
)

/**
 * Cliente de TMDB (The Movie Database). Solo se usa para relacionar títulos:
 * sagas, director, reparto y recomendaciones. Las respuestas se guardan en caché una semana.
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

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap(), ttlHours: Long = 168): JSONObject? =
        withContext(Dispatchers.IO) {
            val key = keyProvider()
            if (key.isBlank()) return@withContext null
            val b = (base + path).toHttpUrl().newBuilder()
            params.forEach { (k, v) -> b.addQueryParameter(k, v) }
            val cacheKey = sha1(b.build().toString())
            val dir = File(cacheDir, "tmdb").apply { mkdirs() }
            val file = File(dir, cacheKey)
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < ttlHours * 3600_000L) {
                try { return@withContext JSONObject(file.readText()) } catch (_: Exception) { }
            }
            b.addQueryParameter("api_key", key)
            try {
                http.newCall(Request.Builder().url(b.build()).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = if (resp.code == 401) "Clave de TMDB no válida" else "TMDB respondió ${resp.code}"
                        return@withContext null
                    }
                    lastError = null
                    val body = resp.body?.string() ?: return@withContext null
                    try { file.writeText(body) } catch (_: Exception) { }
                    JSONObject(body)
                }
            } catch (e: Exception) {
                lastError = "Sin conexión con TMDB"
                null
            }
        }

    private fun yearOf(s: String?): Int? = s?.take(4)?.toIntOrNull()

    private fun parseTitle(o: JSONObject, forceTv: Boolean? = null): TmdbTitle? {
        val media = o.optString("media_type", "")
        val isTv = forceTv ?: (media == "tv" || (media.isBlank() && o.has("name") && !o.has("title")))
        if (media == "person") return null
        val title = (if (isTv) o.optString("name") else o.optString("title")).ifBlank { return null }
        val orig = if (isTv) o.optString("original_name") else o.optString("original_title")
        val date = if (isTv) o.optString("first_air_date") else o.optString("release_date")
        val g = o.optJSONArray("genre_ids")
        val genres = if (g == null) emptyList() else (0 until g.length()).map { g.optInt(it) }
        return TmdbTitle(o.optInt("id"), isTv, title, orig.ifBlank { null }, yearOf(date), genres, o.optDouble("popularity", 0.0))
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
        if (year != null) params[if (isTv) "first_air_date_year" else "primary_release_year"] = year.toString()
        val first = get(path, params)?.optJSONArray("results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
        if (first != null || year == null) return first
        params.remove(if (isTv) "first_air_date_year" else "primary_release_year")
        return get(path, params)?.optJSONArray("results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    }

    suspend fun details(isTv: Boolean, id: Int): JSONObject? =
        if (isTv) get("/tv/$id", mapOf("language" to "es-ES", "append_to_response" to "aggregate_credits,recommendations,similar"))
        else get("/movie/$id", mapOf("language" to "es-ES", "append_to_response" to "credits,recommendations,similar"))

    suspend fun collection(id: Int): List<TmdbTitle> =
        parseList(get("/collection/$id", mapOf("language" to "es-ES"))?.optJSONArray("parts"), forceTv = false)
            .sortedBy { it.year ?: 9999 }

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

    /** Géneros del título: ids y nombres en castellano. */
    fun genres(d: JSONObject): List<Pair<Int, String>> {
        val arr = d.optJSONArray("genres") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { it.optInt("id") to it.optString("name") }
    }

    /** Los más vistos con esos géneros (varias páginas, para que haya más coincidencias con tu servidor). */
    suspend fun discover(isTv: Boolean, genreIds: List<Int>, pages: Int = 5): List<TmdbTitle> {
        if (genreIds.isEmpty()) return emptyList()
        val path = if (isTv) "/discover/tv" else "/discover/movie"
        val out = ArrayList<TmdbTitle>()
        for (p in 1..pages) {
            val res = get(path, mapOf(
                "language" to "es-ES",
                "with_genres" to genreIds.joinToString(","),
                "sort_by" to "vote_count.desc",
                "page" to p.toString(),
            ))?.optJSONArray("results") ?: break
            out += parseList(res, isTv)
        }
        return out
    }

    fun recommendations(d: JSONObject, isTv: Boolean): List<TmdbTitle> =
        parseList(d.optJSONObject("recommendations")?.optJSONArray("results"), isTv) +
            parseList(d.optJSONObject("similar")?.optJSONArray("results"), isTv)

    data class Person(val id: Int, val name: String)

    fun directors(d: JSONObject, isTv: Boolean): List<Person> {
        if (isTv) {
            val arr = d.optJSONArray("created_by") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { Person(it.optInt("id"), it.optString("name")) }
        }
        val crew = d.optJSONObject("credits")?.optJSONArray("crew") ?: return emptyList()
        return (0 until crew.length()).mapNotNull { crew.optJSONObject(it) }
            .filter { it.optString("job") == "Director" }.map { Person(it.optInt("id"), it.optString("name")) }
    }

    fun mainCast(d: JSONObject, isTv: Boolean, count: Int): List<Person> {
        val credits = if (isTv) d.optJSONObject("aggregate_credits") else d.optJSONObject("credits")
        val cast = credits?.optJSONArray("cast") ?: return emptyList()
        return (0 until cast.length()).mapNotNull { cast.optJSONObject(it) }.take(count).map { Person(it.optInt("id"), it.optString("name")) }
    }
}
