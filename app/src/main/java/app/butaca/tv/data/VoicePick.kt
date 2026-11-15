package app.butaca.tv.data

import java.util.Calendar

/**
 * «Te digo lo que ver»: convierte una frase («película de niños tranquila», «ciencia ficción futurista
 * con inteligencia artificial»…) en condiciones de TMDB (géneros, temas, época, país, edad, duración,
 * nota, estudio, persona) y busca lo que encaja en tu servidor.
 */
sealed class Cond(val label: String) {
    class Kind(val tv: Boolean) : Cond(if (tv) "Serie" else "Película")
    class Genre(name: String, val movie: Int?, val tv: Int?) : Cond(name)
    class Mood(name: String, val noMovie: List<Int>, val noTv: List<Int>) : Cond(name)
    class Topic(name: String, val term: String) : Cond(name)
    class Years(name: String, val from: Int?, val to: Int?) : Cond(name)
    class Kids(name: String, val maxCert: Int) : Cond(name)
    class Country(name: String, val iso: String) : Cond(name)
    class Rated(name: String) : Cond(name)
    class Length(name: String, val min: Int?, val max: Int?) : Cond(name)
    class Studio(name: String) : Cond(name)
    class Person(name: String, val id: Int) : Cond(name)
}

data class WishResult(val conds: List<Cond>, val items: List<ItemEntity>, val note: String? = null)

private class Rule(pattern: String, val make: () -> Cond) {
    val rx = Regex("\\b(?:$pattern)\\b")
}

private val CALM_NO = listOf(27, 53, 80, 10752)
private val CALM_NO_TV = listOf(80, 10768, 9648)

private val GENRE_RULES = listOf(
    Rule("ciencia ficcion|sci ?fi|futurista|del futuro|espacial|galactica") { Cond.Genre("Ciencia ficción", 878, 10765) },
    Rule("accion|adrenalina|tiros|peleas|explosiones") { Cond.Genre("Acción", 28, 10759) },
    Rule("aventuras?") { Cond.Genre("Aventura", 12, 10759) },
    Rule("animacion|animadas?|dibujos( animados)?") { Cond.Genre("Animación", 16, 16) },
    Rule("comedias?|divertidas?|graciosas?|para reir|de risa|humor") { Cond.Genre("Comedia", 35, 35) },
    Rule("crimen|criminal|policiacas?|policias|gangsters?|mafia") { Cond.Genre("Crimen", 80, 80) },
    Rule("documental(es)?") { Cond.Genre("Documental", 99, 99) },
    Rule("drama|dramaticas?") { Cond.Genre("Drama", 18, 18) },
    Rule("familiar(es)?|en familia|todos los publicos|para toda la familia") { Cond.Genre("Familia", 10751, 10751) },
    Rule("fantasia|fantasticas?|magicas?") { Cond.Genre("Fantasía", 14, 10765) },
    Rule("historicas?|de epoca") { Cond.Genre("Historia", 36, null) },
    Rule("terror|de miedo|que de miedo|sustos|horror") { Cond.Genre("Terror", 27, null) },
    Rule("musical(es)?") { Cond.Genre("Música", 10402, null) },
    Rule("misterio|enigmas?") { Cond.Genre("Misterio", 9648, 9648) },
    Rule("romanticas?|romance|de amor|enamorarse") { Cond.Genre("Romance", 10749, null) },
    Rule("suspense|thriller|intriga|tension") { Cond.Genre("Suspense", 53, null) },
    Rule("belicas?|de guerra|soldados|militar(es)?") { Cond.Genre("Bélica", 10752, 10768) },
    Rule("del oeste|western|vaqueros") { Cond.Genre("Western", 37, 37) },
)

private val MOOD_RULES = listOf(
    Rule("tranquilas?|relajantes?|relajadas?|suaves?|ligeras?|para desconectar|sin violencia|calmadas?|tiernas?|bonitas?") {
        Cond.Mood("Tranquila (sin terror ni violencia)", CALM_NO, CALM_NO_TV)
    },
    Rule("buen rollo|optimistas?|alegres?|positivas?|feel ?good|para animarme") { Cond.Mood("De buen rollo", listOf(27, 53, 10752), listOf(10768)) },
)

private val TOPIC_RULES = listOf(
    "inteligencia artificial|\\bia\\b" to ("Inteligencia artificial" to "artificial intelligence"),
    "robots?|androides?|ciborgs?" to ("Robots" to "robot"),
    "viajes? en el tiempo" to ("Viajes en el tiempo" to "time travel"),
    "espacio|astronautas?|naves? espaciales?|planetas?" to ("Espacio" to "outer space"),
    "extraterrestres|alienigenas|aliens?|invasion" to ("Extraterrestres" to "alien"),
    "zombis?|zombies?" to ("Zombis" to "zombie"),
    "vampiros?" to ("Vampiros" to "vampire"),
    "dinosaurios?" to ("Dinosaurios" to "dinosaur"),
    "piratas?" to ("Piratas" to "pirate"),
    "superheroes?" to ("Superhéroes" to "superhero"),
    "dragones|dragon" to ("Dragones" to "dragon"),
    "brujas?|hechiceros?" to ("Brujas" to "witch"),
    "apocalipsis|postapocaliptica|fin del mundo" to ("Apocalipsis" to "post-apocalyptic future"),
    "distopia|distopicas?" to ("Distopía" to "dystopia"),
    "realidad virtual|videojuegos?" to ("Realidad virtual" to "virtual reality"),
    "tiburones?" to ("Tiburones" to "shark"),
    "perros?" to ("Perros" to "dog"),
    "gatos?" to ("Gatos" to "cat"),
    "caballos?" to ("Caballos" to "horse"),
    "animales" to ("Animales" to "animal"),
    "navidad|navidena|navidenas" to ("Navidad" to "christmas"),
    "halloween" to ("Halloween" to "halloween"),
    "futbol" to ("Fútbol" to "soccer"),
    "boxeo|boxeador" to ("Boxeo" to "boxing"),
    "deportes?|deportivas?" to ("Deporte" to "sports"),
    "coches|carreras" to ("Coches y carreras" to "car race"),
    "baile|bailar" to ("Baile" to "dancing"),
    "cocina|cocineros?|chefs?" to ("Cocina" to "cooking"),
    "instituto|colegio" to ("Instituto" to "high school"),
    "universidad" to ("Universidad" to "college"),
    "juicios?|abogados?" to ("Juicios" to "courtroom"),
    "hospital|medicos" to ("Médicos" to "hospital"),
    "carcel|prision|presos" to ("Cárcel" to "prison"),
    "espias?|espionaje" to ("Espías" to "spy"),
    "atracos?|robos?|ladrones" to ("Atracos" to "heist"),
    "venganza" to ("Venganza" to "revenge"),
    "asesinos? en serie" to ("Asesino en serie" to "serial killer"),
    "detectives?|investigacion" to ("Detectives" to "detective"),
    "samurais?|ninjas?" to ("Samuráis" to "samurai"),
    "vikingos?" to ("Vikingos" to "vikings"),
    "romanos|antigua roma|gladiadores" to ("Antigua Roma" to "ancient rome"),
    "segunda guerra mundial" to ("II Guerra Mundial" to "world war ii"),
    "supervivencia|sobrevivir|isla desierta" to ("Supervivencia" to "survival"),
    "giro final|final sorprendente|giro inesperado|que no te esperas" to ("Giro final" to "twist ending"),
    "hechos reales|historia real|basada en hechos" to ("Basada en hechos reales" to "based on true story"),
    "basada en (un )?libro|basada en (una )?novela" to ("Basada en un libro" to "based on novel or book"),
    "adolescentes|juvenil|de instituto" to ("Adolescentes" to "teenager"),
    "amistad|amigos" to ("Amistad" to "friendship"),
    "princesas?" to ("Princesas" to "princess"),
    "monstruos?" to ("Monstruos" to "monster"),
    "fantasmas?|casa encantada" to ("Fantasmas" to "ghost"),
    "miniserie" to ("Miniserie" to "miniseries"),
    "road movie|viaje por carretera" to ("Viaje por carretera" to "road trip"),
    "mar|oceano|submarino" to ("Mar" to "ocean"),
    "montana|escalada" to ("Montaña" to "mountain"),
).map { (p, v) -> Rule(p) { Cond.Topic(v.first, v.second) } }

private val COUNTRY_RULES = listOf(
    Rule("espanolas?|espanol|de espana|en castellano") { Cond.Country("Española", "ES") },
    Rule("coreanas?|de corea") { Cond.Country("Coreana", "KR") },
    Rule("japonesas?|de japon|anime") { Cond.Country("Japonesa", "JP") },
    Rule("francesas?|de francia") { Cond.Country("Francesa", "FR") },
    Rule("italianas?|de italia") { Cond.Country("Italiana", "IT") },
    Rule("britanicas?|inglesas?") { Cond.Country("Británica", "GB") },
    Rule("mexicanas?") { Cond.Country("Mexicana", "MX") },
    Rule("argentinas?") { Cond.Country("Argentina", "AR") },
    Rule("alemanas?") { Cond.Country("Alemana", "DE") },
    Rule("nordicas?|suecas?|danesas?|noruegas?") { Cond.Country("Nórdica", "SE|DK|NO|FI|IS") },
    Rule("americanas?|estadounidenses?|de hollywood") { Cond.Country("Estadounidense", "US") },
    Rule("bollywood|indias?") { Cond.Country("India", "IN") },
)

private val STUDIO_RULES = listOf(
    "disney" to "Walt Disney Pictures", "pixar" to "Pixar", "marvel" to "Marvel Studios", "dreamworks" to "DreamWorks Animation",
    "ghibli" to "Studio Ghibli", "a24" to "A24", "warner" to "Warner Bros. Pictures", "lucasfilm|star wars" to "Lucasfilm Ltd.",
    "dc comics|\\bdc\\b" to "DC Comics", "illumination|minions" to "Illumination", "blumhouse" to "Blumhouse Productions",
).map { (p, n) -> Rule(p) { Cond.Studio(n) } }

private val STOP = setOf(
    "que", "tenga", "tengan", "una", "uno", "un", "la", "el", "los", "las", "lo", "algo", "nada", "todo", "pelicula", "peliculas",
    "peli", "serie", "series", "para", "por", "con", "sin", "y", "o", "de", "del", "en", "me", "te", "quiero", "ver", "busco", "salga",
    "sea", "este", "esta", "muy", "mas", "noche", "tarde", "hoy", "ahora", "bien", "buena", "algun", "alguna",
)

/** Entiende la frase. Lo que no reconoce lo ignora (no inventa). */
suspend fun Repo.parseWish(phrase: String): List<Cond> {
    var text = " " + TextUtil.norm(phrase).replace(Regex("[^a-z0-9ñ ]"), " ").replace(Regex("\\s+"), " ") + " "
    val out = ArrayList<Cond>()
    fun take(rules: List<Rule>, max: Int = 9) {
        var n = 0
        for (r in rules) {
            if (n >= max) break
            val m = r.rx.find(text) ?: continue
            out += r.make(); n++
            text = text.replaceRange(m.range, " ".repeat(m.value.length))
        }
    }
    // Para niños (antes que «familia»)
    Regex("\\b(ninos pequenos|peques|bebes|preescolar)\\b").find(text)?.let { out += Cond.Kids("Para los más pequeños", 0); text = text.replace(it.value, " ") }
        ?: Regex("\\b(para ninos|de ninos|infantil(es)?|para crios|para los ninos|ninos)\\b").find(text)?.let {
            out += Cond.Kids("Para niños", 7); out += Cond.Genre("Familia", 10751, 10751); text = text.replace(it.value, " ")
        }
    take(TOPIC_RULES, 3)
    // Tipo
    when {
        Regex("\\b(series?|temporadas|capitulos|miniserie)\\b").containsMatchIn(text) -> out += Cond.Kind(true)
        Regex("\\b(peliculas?|pelis?|films?|largometraje)\\b").containsMatchIn(text) -> out += Cond.Kind(false)
    }
    take(GENRE_RULES, 3)
    take(MOOD_RULES, 1)
    take(COUNTRY_RULES, 1)
    take(STUDIO_RULES, 1)
    // Época
    val year = Calendar.getInstance().get(Calendar.YEAR)
    val decades = mapOf("sesenta" to 1960, "setenta" to 1970, "ochenta" to 1980, "noventa" to 1990, "60" to 1960, "70" to 1970, "80" to 1980, "90" to 1990)
    Regex("\\b(?:de los|anos) (sesenta|setenta|ochenta|noventa|60|70|80|90)\\b").find(text)?.let { m ->
        val d = decades[m.groupValues[1]]!!; out += Cond.Years("Años ${d % 100}", d, d + 9)
    } ?: Regex("\\b(?:de los )?(2000|dos mil)\\b").find(text)?.let { out += Cond.Years("Años 2000", 2000, 2009) }
        ?: Regex("\\b(?:del?|en) ((?:19|20)\\d\\d)\\b").find(text)?.let { m -> val y = m.groupValues[1].toInt(); out += Cond.Years("De $y", y, y) }
        ?: Regex("\\b(clasicas?|antiguas?|en blanco y negro|de las de antes)\\b").find(text)?.let { out += Cond.Years("Clásica", null, 1979) }
        ?: Regex("\\b(recientes?|nuevas?|actuales?|modernas?|ultimas?|estrenos?|de este ano|de ahora)\\b").find(text)?.let { out += Cond.Years("Reciente", year - 3, null) }
    // Nota
    if (Regex("\\b(buenas?|bien valoradas?|de las mejores|obras? maestras?|top|premiadas?|de culto|imprescindibles?|muy buenas?)\\b").containsMatchIn(text)) out += Cond.Rated("Muy bien valorada")
    // Duración
    if (Regex("\\b(cortas?|cortitas?|que no dure mucho|corta de duracion)\\b").containsMatchIn(text)) out += Cond.Length("Corta (menos de 1 h 40)", null, 100)
    else if (Regex("\\b(largas?|epicas?)\\b").containsMatchIn(text)) out += Cond.Length("Larga", 140, null)
    // Persona: «con Tom Hanks», «dirigida por Almodóvar», «de Spielberg»
    Regex("\\b(?:con|protagonizadas? por|dirigidas? por|del director|de la directora|que salga|donde salga|de)\\s+([a-zñ]+(?:\\s+[a-zñ]+){0,2})").findAll(text).forEach { m ->
        if (out.any { it is Cond.Person }) return@forEach
        val words = m.groupValues[1].split(' ').filter { it.length > 1 }.dropWhile { it in STOP }.takeWhile { it !in STOP }
        for (k in words.size downTo 1) {
            val cand = words.take(k).joinToString(" ")
            if (cand.length < 4 || (k == 1 && cand.length < 6)) continue
            val p = try { tmdb.searchPerson(cand) } catch (_: Exception) { null } ?: continue
            if (p.role == "Acting" || p.role == "Directing" || p.role == "Writing") { out += Cond.Person(p.name, p.id); break }
        }
    }
    return out
}

/** Busca en TMDB con esas condiciones y devuelve lo que tienes. Si no hay casi nada, amplía con cuidado. */
suspend fun Repo.runWish(conds: List<Cond>): WishResult {
    if (conds.isEmpty() || !tmdb.enabled) return WishResult(conds, emptyList())
    val kind = conds.filterIsInstance<Cond.Kind>().firstOrNull()
    val genres = conds.filterIsInstance<Cond.Genre>().distinctBy { it.label }
    val moods = conds.filterIsInstance<Cond.Mood>()
    val topics = conds.filterIsInstance<Cond.Topic>()
    val years = conds.filterIsInstance<Cond.Years>().firstOrNull()
    val kids = conds.filterIsInstance<Cond.Kids>().firstOrNull()
    val country = conds.filterIsInstance<Cond.Country>().firstOrNull()
    val rated = conds.any { it is Cond.Rated }
    val length = conds.filterIsInstance<Cond.Length>().firstOrNull()
    val studio = conds.filterIsInstance<Cond.Studio>().firstOrNull()
    val person = conds.filterIsInstance<Cond.Person>().firstOrNull()

    val kwIds = topics.mapNotNull { t -> tmdb.keywordId(t.term) }.distinct()
    val companyId = studio?.let { tmdb.companyId(it.label) }

    suspend fun search(kwJoin: String?, useMood: Boolean, useGenres: Boolean): List<TmdbTitle> {
        val out = ArrayList<TmdbTitle>()
        // Películas
        if (kind?.tv != true && (!useGenres || genres.all { it.movie != null })) {
            val p = HashMap<String, String>()
            p["sort_by"] = "popularity.desc"
            if (useGenres && genres.isNotEmpty()) p["with_genres"] = genres.mapNotNull { it.movie }.joinToString(",")
            if (useMood && moods.isNotEmpty()) p["without_genres"] = moods.flatMap { it.noMovie }.distinct()
                .filter { g -> genres.none { it.movie == g } }.joinToString(",")
            if (kwJoin != null && kwIds.isNotEmpty()) p["with_keywords"] = kwIds.joinToString(kwJoin)
            years?.from?.let { p["primary_release_date.gte"] = "$it-01-01" }
            years?.to?.let { p["primary_release_date.lte"] = "$it-12-31" }
            kids?.let { p["certification_country"] = "ES"; p["certification.lte"] = if (it.maxCert == 0) "A" else "7" }
            country?.let { p["with_origin_country"] = it.iso }
            if (rated) { p["vote_average.gte"] = "7.2"; p["vote_count.gte"] = "300" } else p["vote_count.gte"] = "40"
            length?.max?.let { p["with_runtime.lte"] = "$it" }
            length?.min?.let { p["with_runtime.gte"] = "$it" }
            companyId?.let { p["with_companies"] = "$it" }
            person?.let { p["with_people"] = "${it.id}" }
            out += tmdb.discover(false, emptyList(), if (kind == null) 8 else 12, p)
        }
        // Series (sin persona: TMDB no filtra series por reparto)
        if (kind?.tv != false && person == null && (!useGenres || genres.all { it.tv != null })) {
            val p = HashMap<String, String>()
            p["sort_by"] = "popularity.desc"
            val g = if (useGenres) genres.mapNotNull { it.tv }.toMutableList() else mutableListOf()
            if (kids != null && g.none { it == 10762 }) {
                p["with_genres"] = (g.filter { it != 10751 }.map { "$it" } + listOf("10762|10751")).joinToString(",")
            } else if (g.isNotEmpty()) p["with_genres"] = g.joinToString(",")
            if (useMood && moods.isNotEmpty()) p["without_genres"] = moods.flatMap { it.noTv }.distinct()
                .filter { x -> g.none { it == x } }.joinToString(",")
            if (kwJoin != null && kwIds.isNotEmpty()) p["with_keywords"] = kwIds.joinToString(kwJoin)
            years?.from?.let { p["first_air_date.gte"] = "$it-01-01" }
            years?.to?.let { p["first_air_date.lte"] = "$it-12-31" }
            country?.let { p["with_origin_country"] = it.iso }
            if (rated) { p["vote_average.gte"] = "7.5"; p["vote_count.gte"] = "200" } else p["vote_count.gte"] = "20"
            companyId?.let { p["with_companies"] = "$it" }
            out += tmdb.discover(true, emptyList(), if (kind == null) 4 else 10, p)
        }
        return out.sortedByDescending { it.popularity }
    }

    var items = matchTitles(search(",", true, true), null).noRepeats()
    var note: String? = null
    if (items.size < 8 && kwIds.size > 1) {
        items = (items + matchTitles(search("|", true, true), null)).noRepeats()
        if (items.isNotEmpty()) note = "Con todos los temas a la vez había poco: también salen las que tienen alguno."
    }
    if (items.size < 5 && kwIds.isNotEmpty() && genres.isNotEmpty()) {
        val wider = matchTitles(search(null, true, true), null)
        if (wider.isNotEmpty()) {
            items = (items + wider).noRepeats()
            note = "No tienes casi nada con esos temas exactos: te añado lo que encaja por género y estilo."
        }
    }
    return WishResult(conds, items, note)
}
