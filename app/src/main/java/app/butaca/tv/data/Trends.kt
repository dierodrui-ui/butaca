package app.butaca.tv.data

import org.json.JSONObject
import java.util.Calendar

/**
 * «Actualidad»: lo que la gente está leyendo hoy en Wikipedia en español (lo más leído del día,
 * noticias y efemérides) convertido en filas con lo que tienes en tu servidor:
 *  · una persona del cine que está en boca de todos → sus películas y series,
 *  · una película o serie de la que se habla (p. ej. un estreno) → su saga y parecidas,
 *  · un tema del momento (un eclipse, un Mundial, una gala de premios…) → títulos relacionados,
 *  · alguien del cine que nació un día como hoy.
 * Se evita a propósito convertir en recomendaciones catástrofes o sucesos trágicos.
 */
private object TrendCache {
    var at = 0L
    var rows: List<Pair<String, List<ItemEntity>>> = emptyList()
}

private data class Topic(val label: String, val pattern: Regex, val keywords: List<String> = emptyList(), val special: String? = null)

private val TOPICS_NOW = listOf(
    Topic("el espacio", Regex("eclipse|cometa|lluvia de estrellas|perseidas|asteroide|artemis|spacex|nasa|estacion espacial|astronauta|marte\\b|alunizaje"),
        listOf("outer space", "astronaut", "space travel", "nasa")),
    Topic("el fútbol", Regex("copa mundial|mundial de futbol|eurocopa|liga de campeones|champions league|copa del rey|balon de oro|clasico"), listOf("soccer", "football (soccer)")),
    Topic("los Juegos Olímpicos", Regex("juegos olimpicos|olimpiada"), listOf("olympic games", "athlete")),
    Topic("el ciclismo", Regex("tour de francia|vuelta a espana|giro de italia"), listOf("cycling", "bicycle")),
    Topic("el tenis", Regex("wimbledon|roland garros|abierto de australia|us open|copa davis"), listOf("tennis")),
    Topic("la Fórmula 1", Regex("formula 1|gran premio de|motogp"), listOf("formula 1", "car race", "racing")),
    Topic("el Vaticano", Regex("conclave|papa francisco|papa leon|vaticano|sumo pontifice"), listOf("pope", "vatican", "catholic church")),
    Topic("la inteligencia artificial", Regex("inteligencia artificial|chatgpt|openai|robot"), listOf("artificial intelligence", "robot")),
    Topic("la gala de los Óscar", Regex("premios oscar|premios de la academia|ceremonia de los oscar"), special = "oscar"),
    Topic("los Goya", Regex("premios goya"), special = "goya"),
    Topic("las elecciones", Regex("elecciones (generales|presidenciales)"), listOf("politics", "election campaign")),
)

// Temas que no convertimos en recomendaciones (respeto ante sucesos graves)
private val SENSITIVE = Regex("atentado|terremoto|huracan|tsunami|incendio|inundacion|dana\\b|accidente|asesinato|tiroteo|guerra|masacre|naufragio|epidemia|fallecid")

private val CINEMA_PERSON = Regex("\\b(actor|actriz|director|directora|cineasta|guionista|realizador|realizadora|comico|comica|humorista)\\b")
private val CINEMA_WORK = Regex("\\b(pelicula|film|largometraje|serie de television|serie|miniserie|telenovela|saga|franquicia|animacion)\\b")

private fun cleanTitle(t: String) = t.replace('_', ' ').replace(Regex("\\s*\\(.*?\\)\\s*"), " ").trim()

suspend fun Repo.trendRows(): List<Pair<String, List<ItemEntity>>> {
    if (!tmdb.enabled) return emptyList()
    if (System.currentTimeMillis() - TrendCache.at < 3 * 3600_000L && TrendCache.at > 0) return TrendCache.rows
    val out = ArrayList<Pair<String, List<ItemEntity>>>()
    val used = HashSet<String>()
    fun add(title: String, list: List<ItemEntity>, min: Int = 3) {
        val fresh = list.noRepeats().filter { it.stableKey !in used }.take(20)
        if (fresh.size >= min) { out += title to fresh; fresh.forEach { used += it.stableKey } }
    }

    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }   // lo más leído de ayer ya está calculado
    val y = cal.get(Calendar.YEAR)
    val m = "%02d".format(cal.get(Calendar.MONTH) + 1)
    val d = "%02d".format(cal.get(Calendar.DAY_OF_MONTH))
    val feed: JSONObject? = tmdb.getUrl("https://es.wikipedia.org/api/rest_v1/feed/featured/$y/$m/$d")
    val articles = feed?.optJSONObject("mostread")?.optJSONArray("articles")
    val list = if (articles == null) emptyList() else (0 until articles.length()).mapNotNull { articles.optJSONObject(it) }
        .filter { a -> !a.optString("title").contains(':') }   // fuera páginas especiales
        .take(45)
    // Las noticias del día también cuentan para los temas
    val news = feed?.optJSONArray("news")
    val newsText = if (news == null) "" else (0 until news.length()).mapNotNull { news.optJSONObject(it)?.optString("story") }
        .joinToString(" ").replace(Regex("<[^>]+>"), " ")

    // 1) Personas del cine de las que se habla
    var people = 0
    for (a in list) {
        if (people >= 2) break
        val desc = TextUtil.norm(a.optString("description"))
        if (!CINEMA_PERSON.containsMatchIn(desc)) continue
        val name = cleanTitle(a.optJSONObject("titles")?.optString("normalized") ?: a.optString("title"))
        val p = try { tmdb.searchPerson(name) } catch (_: Exception) { null } ?: continue
        val director = desc.contains("director") || desc.contains("cineasta") || desc.contains("realizador")
        val work = matchTitles(tmdb.personWork(p.id, asDirector = director).ifEmpty { tmdb.personWork(p.id, asDirector = false) }, null)
        val before = out.size
        add("Actualidad · Se habla de ${p.name}", work)
        if (out.size > before) people++
    }

    // 2) Películas y series de las que se habla (un estreno, un final de temporada…)
    val talked = ArrayList<ItemEntity>()
    for (a in list.take(30)) {
        val desc = TextUtil.norm(a.optString("description"))
        if (!CINEMA_WORK.containsMatchIn(desc) || CINEMA_PERSON.containsMatchIn(desc)) continue
        val isTv = desc.contains("serie") || desc.contains("telenovela")
        val title = cleanTitle(a.optJSONObject("titles")?.optString("normalized") ?: a.optString("title"))
        val yr = Regex("(19|20)\\d{2}").find(a.optString("description"))?.value?.toIntOrNull()
        val id = try { tmdb.findId(isTv, title, yr) } catch (_: Exception) { null } ?: continue
        val det = tmdb.details(isTv, id) ?: continue
        val self = matchTitles(listOf(TmdbTitle(id, isTv, title, null, yr, emptyList(), 0.0, null, null, null)), null)
        talked += self
        det.optJSONObject("belongs_to_collection")?.let { c -> talked += matchTitles(tmdb.collection(c.optInt("id")), null) }
        if (self.isEmpty()) talked += matchTitles(tmdb.recommendations(det, isTv).take(12), null).take(4)
    }
    add("Actualidad · De lo que más se habla", talked)

    // 3) Temas del momento
    val text = TextUtil.norm(list.joinToString(" ") { it.optString("title").replace('_', ' ') + " " + it.optString("description") } + " " + newsText)
    var topics = 0
    for (t in TOPICS_NOW) {
        if (topics >= 2) break
        val hit = t.pattern.find(text) ?: continue
        // si el tema aparece junto a un suceso grave, no lo usamos
        val around = text.substring((hit.range.first - 120).coerceAtLeast(0), (hit.range.last + 120).coerceAtMost(text.length))
        if (SENSITIVE.containsMatchIn(around)) continue
        val found: List<TmdbTitle> = when (t.special) {
            "oscar" -> tmdb.discover(false, emptyList(), 5, mapOf(
                "vote_average.gte" to "7.6", "vote_count.gte" to "3000"))
            "goya" -> tmdb.discover(false, emptyList(), 5, mapOf(
                "with_origin_country" to "ES", "vote_average.gte" to "6.8", "vote_count.gte" to "150"))
            else -> {
                val kw = t.keywords.mapNotNull { tmdb.keywordId(it) }.distinct()
                if (kw.isEmpty()) emptyList()
                else tmdb.discover(false, emptyList(), 6, mapOf("with_keywords" to kw.joinToString("|"))) +
                    tmdb.discover(true, emptyList(), 2, mapOf("with_keywords" to kw.joinToString("|")))
            }
        }
        val before = out.size
        add("Actualidad · Por ${t.label}", matchTitles(found, null), min = 4)
        if (out.size > before) topics++
    }

    // 4) Nació un día como hoy
    try {
        val now = Calendar.getInstance()
        val births = tmdb.getUrl("https://es.wikipedia.org/api/rest_v1/feed/onthisday/births/%02d/%02d".format(now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)), ttlHours = 24)
            ?.optJSONArray("births")
        var best: Pair<String, List<ItemEntity>>? = null
        var tried = 0
        if (births != null) for (i in 0 until births.length()) {
            if (tried >= 14) break
            val page = births.optJSONObject(i)?.optJSONArray("pages")?.optJSONObject(0) ?: continue
            val desc = TextUtil.norm(page.optString("description"))
            if (!CINEMA_PERSON.containsMatchIn(desc)) continue
            tried++
            val name = cleanTitle(page.optJSONObject("titles")?.optString("normalized") ?: page.optString("title"))
            val p = tmdb.searchPerson(name) ?: continue
            val director = desc.contains("director") || desc.contains("cineasta")
            val work = matchTitles(tmdb.personWork(p.id, asDirector = director), null).noRepeats().filter { it.stableKey !in used }
            if (work.size >= 4 && work.size > (best?.second?.size ?: 0)) best = "Un día como hoy nació ${p.name}" to work
        }
        best?.let { add(it.first, it.second, min = 4) }
    } catch (_: Exception) { }

    TrendCache.rows = out
    TrendCache.at = System.currentTimeMillis()
    return out
}
