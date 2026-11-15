package app.butaca.tv.data

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader

data class Account(val server: String, val user: String, val pass: String)

data class AuthResult(
    val ok: Boolean,
    val status: String,
    val expDate: Long,
    val maxConnections: Int,
    val message: String?,
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val ext: String,
    val plot: String?,
    val image: String?,
    val durationSecs: Long,
)

data class SeriesInfo(
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val releaseDate: String?,
    val rating: Double?,
    val cover: String?,
    val backdrop: String?,
    val seasons: List<Int>,
    val episodes: List<Episode>,
    val tmdbId: String? = null,
)

data class VodInfo(
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val releaseDate: String?,
    val durationSecs: Long,
    val rating: Double?,
    val poster: String?,
    val backdrop: String?,
    val tmdbId: String? = null,
)

/**
 * Cliente de la API Xtream Codes (la misma que usa XCIPTV).
 */
class XtreamClient(private val http: OkHttpClient) {

    companion object {
        const val USER_AGENT = "Butaca/1.0 (Android TV)"

        fun normalizeServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            s = s.removeSuffix("/player_api.php").removeSuffix("/get.php").removeSuffix("/c").trimEnd('/')
            return s
        }
    }

    private fun apiUrl(a: Account, action: String?, extra: Map<String, String> = emptyMap()): HttpUrl {
        val base = ("${a.server}/player_api.php").toHttpUrlOrNull()
            ?: throw IOException("La dirección del servidor no es válida")
        val b = base.newBuilder()
            .addQueryParameter("username", a.user)
            .addQueryParameter("password", a.pass)
        if (action != null) b.addQueryParameter("action", action)
        extra.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

    private fun getString(url: HttpUrl): String {
        http.newCall(request(url)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("El servidor respondió ${resp.code}")
            return resp.body?.string() ?: throw IOException("Respuesta vacía del servidor")
        }
    }

    suspend fun authenticate(a: Account): AuthResult = withContext(Dispatchers.IO) {
        val body = getString(apiUrl(a, null))
        val root = try { JSONObject(body) } catch (e: Exception) {
            throw IOException("El servidor no ha respondido como un servidor Xtream")
        }
        val ui = root.optJSONObject("user_info")
            ?: return@withContext AuthResult(false, "", 0, 0, "Usuario o contraseña incorrectos")
        val auth = ui.optString("auth", "0")
        val status = ui.optString("status", "")
        val exp = ui.optString("exp_date", "").toLongOrNull() ?: 0L
        val maxCon = ui.optString("max_connections", "0").toIntOrNull() ?: 0
        val ok = auth == "1" || auth.equals("true", true)
        AuthResult(
            ok = ok,
            status = status,
            expDate = exp,
            maxConnections = maxCon,
            message = when {
                !ok -> "Usuario o contraseña incorrectos"
                status.isNotBlank() && !status.equals("Active", true) -> "La cuenta no está activa ($status)"
                else -> null
            },
        )
    }

    /**
     * Lee una lista grande sin cargarla entera en memoria. Cada elemento llega como
     * un mapa de texto (los números y booleanos se convierten a texto).
     */
    suspend fun streamList(
        a: Account,
        action: String,
        onBatch: suspend (List<Map<String, String>>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        http.newCall(request(apiUrl(a, action))).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("El servidor respondió ${resp.code}")
            val stream = resp.body?.byteStream() ?: return@use
            val reader = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
            reader.isLenient = true
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                reader.skipValue()
                return@use
            }
            reader.beginArray()
            val batch = ArrayList<Map<String, String>>(500)
            while (reader.hasNext()) {
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue(); continue
                }
                batch.add(readFlatObject(reader))
                if (batch.size >= 500) {
                    onBatch(ArrayList(batch)); batch.clear()
                }
            }
            reader.endArray()
            if (batch.isNotEmpty()) onBatch(batch)
        }
    }

    private fun readFlatObject(reader: JsonReader): Map<String, String> {
        val map = HashMap<String, String>(24)
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> map[name] = reader.nextString()
                JsonToken.BOOLEAN -> map[name] = if (reader.nextBoolean()) "1" else "0"
                JsonToken.NULL -> reader.nextNull()
                JsonToken.BEGIN_ARRAY -> {
                    // p. ej. backdrop_path: ["url", ...] -> nos quedamos con el primero
                    reader.beginArray()
                    var first: String? = null
                    while (reader.hasNext()) {
                        if (first == null && (reader.peek() == JsonToken.STRING)) first = reader.nextString()
                        else reader.skipValue()
                    }
                    reader.endArray()
                    if (first != null) map[name] = first
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return map
    }

    suspend fun categories(a: Account, action: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        streamList(a, action) { batch ->
            batch.forEach { m ->
                val id = m["category_id"] ?: return@forEach
                out.add(id to (m["category_name"] ?: "Sin nombre"))
            }
        }
        return out
    }

    suspend fun vodInfo(a: Account, vodId: String): VodInfo? = withContext(Dispatchers.IO) {
        val body = getString(apiUrl(a, "get_vod_info", mapOf("vod_id" to vodId)))
        val root = try { JSONObject(body) } catch (e: Exception) { return@withContext null }
        val info = root.optJSONObject("info") ?: return@withContext null
        VodInfo(
            plot = info.optStringOrNull("plot") ?: info.optStringOrNull("description"),
            genre = info.optStringOrNull("genre"),
            cast = info.optStringOrNull("cast") ?: info.optStringOrNull("actors"),
            director = info.optStringOrNull("director"),
            releaseDate = info.optStringOrNull("releasedate") ?: info.optStringOrNull("release_date"),
            durationSecs = info.optString("duration_secs", "").toLongOrNull() ?: 0L,
            rating = info.optString("rating", "").toDoubleOrNull(),
            poster = info.optStringOrNull("movie_image") ?: info.optStringOrNull("cover_big"),
            backdrop = firstString(info.opt("backdrop_path")),
            tmdbId = (info.optStringOrNull("tmdb_id") ?: info.optStringOrNull("tmdb"))?.takeIf { it != "0" },
        )
    }

    suspend fun seriesInfo(a: Account, seriesId: String): SeriesInfo? = withContext(Dispatchers.IO) {
        val body = getString(apiUrl(a, "get_series_info", mapOf("series_id" to seriesId)))
        val root = try { JSONObject(body) } catch (e: Exception) { return@withContext null }
        val info = root.optJSONObject("info") ?: JSONObject()
        val episodes = ArrayList<Episode>()
        fun readEp(o: JSONObject, seasonHint: Int) {
            val id = o.optString("id", "")
            if (id.isBlank()) return
            val epInfo = o.optJSONObject("info")
            episodes.add(
                Episode(
                    id = id,
                    season = o.optString("season", "").toIntOrNull() ?: seasonHint,
                    number = o.optString("episode_num", "").toIntOrNull() ?: 0,
                    title = o.optString("title", "Episodio"),
                    ext = o.optString("container_extension", "mp4").ifBlank { "mp4" },
                    plot = epInfo?.optStringOrNull("plot"),
                    image = epInfo?.optStringOrNull("movie_image"),
                    durationSecs = epInfo?.optString("duration_secs", "")?.toLongOrNull() ?: 0L,
                )
            )
        }
        when (val eps = root.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k ->
                val arr = eps.optJSONArray(k) ?: return@forEach
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { readEp(it, k.toIntOrNull() ?: 1) }
            }
            is JSONArray -> for (i in 0 until eps.length()) {
                when (val inner = eps.opt(i)) {
                    is JSONArray -> for (j in 0 until inner.length()) inner.optJSONObject(j)?.let { readEp(it, i + 1) }
                    is JSONObject -> readEp(inner, 1)
                }
            }
        }
        episodes.sortWith(compareBy({ it.season }, { it.number }))
        SeriesInfo(
            plot = info.optStringOrNull("plot"),
            genre = info.optStringOrNull("genre"),
            cast = info.optStringOrNull("cast"),
            releaseDate = info.optStringOrNull("releaseDate") ?: info.optStringOrNull("release_date"),
            rating = info.optString("rating", "").toDoubleOrNull(),
            cover = info.optStringOrNull("cover"),
            backdrop = firstString(info.opt("backdrop_path")),
            seasons = episodes.map { it.season }.distinct().sorted(),
            episodes = episodes,
            tmdbId = (info.optStringOrNull("tmdb") ?: info.optStringOrNull("tmdb_id"))?.takeIf { it != "0" },
        )
    }

    // ---------- Direcciones de reproducción ----------
    fun liveUrl(a: Account, streamId: String) = "${a.server}/live/${a.user}/${a.pass}/$streamId.ts"
    fun movieUrl(a: Account, streamId: String, ext: String?) =
        "${a.server}/movie/${a.user}/${a.pass}/$streamId.${ext?.ifBlank { null } ?: "mp4"}"
    fun episodeUrl(a: Account, episodeId: String, ext: String?) =
        "${a.server}/series/${a.user}/${a.pass}/$episodeId.${ext?.ifBlank { null } ?: "mp4"}"
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "").trim()
    return v.ifBlank { null }
}

private fun firstString(v: Any?): String? = when (v) {
    is String -> v.ifBlank { null }
    is JSONArray -> (0 until v.length()).asSequence().map { v.optString(it, "") }.firstOrNull { it.isNotBlank() }
    else -> null
}
