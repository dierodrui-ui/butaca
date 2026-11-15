package app.butaca.tv.data

import java.text.Normalizer

object TextUtil {
    private val marks = "\\p{Mn}+".toRegex()
    private val spaces = "\\s+".toRegex()

    /** Minúsculas y sin tildes: «Acción» -> «accion». */
    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase().replace(spaces, " ").trim()

    // ---------- Adultos ----------
    private val adultRx = Regex(
        "(?i)(\\bxxx\\b|\\badult|\\bpara adultos\\b|\\+\\s?18\\b|\\b18\\s?\\+|\\bporn|\\berotic|\\berotico|\\bplayboy\\b|\\bhentai\\b|\\bsexo\\b|\\bsex\\b|\\bhustler\\b|\\bbrazzers\\b)"
    )

    fun looksAdult(name: String): Boolean = adultRx.containsMatchIn(norm(name))

    // ---------- Calidad ----------
    private val q4k = Regex("(?i)(\\b4k\\b|\\buhd\\b|2160|ᵁᴴᴰ|\\b8k\\b)")
    private val qFhd = Regex("(?i)(\\bfhd\\b|\\bfull ?hd\\b|1080|ᶠᴴᴰ)")
    private val qHd = Regex("(?i)(\\bhd\\b|720|ᴴᴰ)")
    private val qSd = Regex("(?i)(\\bsd\\b|576|480|\\blow\\b)")

    /** 4 = 4K, 3 = Full HD, 2 = HD (o sin indicar), 1 = SD */
    fun qualityRank(name: String): Int = when {
        q4k.containsMatchIn(name) -> 4
        qFhd.containsMatchIn(name) -> 3
        qSd.containsMatchIn(name) -> 1
        qHd.containsMatchIn(name) -> 2
        else -> 2
    }

    private val qualityTokens = Regex(
        "(?i)(\\b4k\\b|\\buhd\\b|\\b2160p?\\b|\\bfhd\\b|\\bfull ?hd\\b|\\b1080p?\\b|\\bhd\\b|\\b720p?\\b|\\bsd\\b|\\b576p?\\b|\\b480p?\\b|\\bhevc\\b|\\bh\\.?265\\b|\\bh\\.?264\\b|\\b50 ?fps\\b|\\b60 ?fps\\b|\\bhdr\\b|\\bdv\\b|\\bmulti\\b|\\blow\\b|ᵁᴴᴰ|ᶠᴴᴰ|ᴴᴰ|\\b8k\\b)"
    )
    private val bracketsEmpty = Regex("[\\[\\(\\{]\\s*[\\]\\)\\}]")
    private val separators = Regex("[|_\\-–·•:]+\\s*$")

    /** Nombre sin marcas de calidad, para agrupar «La 1 FHD» y «La 1 HD». */
    fun baseName(name: String): String {
        var s = norm(name).replace(qualityTokens, " ")
        s = s.replace(bracketsEmpty, " ").replace(spaces, " ").trim()
        s = s.replace(separators, "").trim()
        return s
    }

    private val yearRx = Regex("(?:\\(|\\[|\\s-\\s|\\s)((?:19|20)\\d{2})(?:\\)|\\]|\\s|$)")

    fun extractYear(name: String): Int? =
        yearRx.findAll(name).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    private val yearStrip = Regex("\\s*[\\(\\[]?((?:19|20)\\d{2})[\\)\\]]?\\s*$")

    /** Título para identificar la misma película aunque esté repetida en varias categorías. */
    fun titleKey(name: String): String {
        var s = baseName(name)
        s = s.replace(yearStrip, "").trim()
        // quita prefijos de país tipo "es:", "|es|", "[es]"
        s = s.replace(Regex("^(\\|?[a-z]{2,3}\\|?\\s*[:|]\\s*)"), "").trim()
        s = s.replace(Regex("^\\[[a-z]{2}\\]\\s*"), "").trim()
        return s
    }

    fun parseYear(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return Regex("(19|20)\\d{2}").find(raw)?.value?.toIntOrNull()
    }

    // ---------- Limpieza de nombres ----------
    private val leadingJunk = Regex("^[^\\p{L}\\p{N}¡¿\"'(\\[]+")
    private val trailingJunk = Regex("[^\\p{L}\\p{N})\\]!?.\"'…]+$")

    /** Quita adornos del servidor: «⭐El laberinto» -> «El laberinto», «⭐ CINE ESP ⭐» -> «CINE ESP». */
    fun cleanName(raw: String): String {
        val s = raw.trim().replace(leadingJunk, "").replace(trailingJunk, "").trim()
        return s.ifBlank { raw.trim() }
    }

    /** Primer género, para mostrarlo bajo la carátula. */
    fun firstGenre(genre: String?): String? =
        genre?.split(',', '/', '|', ';')?.map { it.trim() }?.firstOrNull { it.isNotBlank() }
            ?.replaceFirstChar { it.uppercase() }

    // ---------- Búsqueda en español e inglés ----------
    private val stopwords = setOf(
        "de", "del", "la", "las", "el", "los", "y", "e", "a", "al", "en", "un", "una", "unos", "unas", "con", "por", "para",
        "the", "of", "and", "a", "an", "in", "on", "to", "for", "with",
    )

    /** Palabras frecuentes en títulos, en los dos sentidos. */
    private val esEn: Map<String, List<String>> = mapOf(
        "bola" to listOf("ball"), "bolas" to listOf("balls"), "dragon" to listOf("dragon"), "dragones" to listOf("dragons"),
        "guerra" to listOf("war"), "guerras" to listOf("wars"), "estrella" to listOf("star"), "estrellas" to listOf("stars"),
        "galaxia" to listOf("galaxy"), "galaxias" to listOf("galaxy", "galaxies", "star"), "anillo" to listOf("ring"), "anillos" to listOf("rings"),
        "señor" to listOf("lord", "mister", "mr"), "senor" to listOf("lord", "mister", "mr"), "rey" to listOf("king"), "reina" to listOf("queen"),
        "reyes" to listOf("kings"), "principe" to listOf("prince"), "princesa" to listOf("princess"), "caballero" to listOf("knight"),
        "caballeros" to listOf("knights"), "oscuro" to listOf("dark"), "oscura" to listOf("dark"), "oscuridad" to listOf("darkness"),
        "noche" to listOf("night"), "noches" to listOf("nights"), "dia" to listOf("day"), "dias" to listOf("days"),
        "muerto" to listOf("dead"), "muertos" to listOf("dead"), "muerte" to listOf("death"), "vivo" to listOf("alive", "living"),
        "vivos" to listOf("living", "alive"), "vida" to listOf("life"), "mundo" to listOf("world"), "mundos" to listOf("worlds"),
        "tierra" to listOf("earth", "land"), "mar" to listOf("sea"), "oceano" to listOf("ocean"), "cielo" to listOf("sky", "heaven"),
        "infierno" to listOf("hell"), "fuego" to listOf("fire"), "hielo" to listOf("ice"), "agua" to listOf("water"),
        "sangre" to listOf("blood"), "hombre" to listOf("man"), "hombres" to listOf("men"), "mujer" to listOf("woman"),
        "mujeres" to listOf("women"), "nino" to listOf("boy", "child", "kid"), "ninos" to listOf("children", "kids"),
        "nina" to listOf("girl"), "chica" to listOf("girl"), "chicas" to listOf("girls"), "chico" to listOf("boy"), "chicos" to listOf("boys"),
        "padre" to listOf("father"), "madre" to listOf("mother"), "hijo" to listOf("son"), "hija" to listOf("daughter"),
        "hermano" to listOf("brother"), "hermanos" to listOf("brothers"), "hermana" to listOf("sister"), "hermanas" to listOf("sisters"),
        "familia" to listOf("family"), "amigo" to listOf("friend"), "amigos" to listOf("friends"), "amor" to listOf("love"),
        "casa" to listOf("house", "home"), "ciudad" to listOf("city"), "pueblo" to listOf("town", "village"), "isla" to listOf("island"),
        "bosque" to listOf("forest", "woods"), "montaña" to listOf("mountain"), "montana" to listOf("mountain"), "rio" to listOf("river"),
        "perro" to listOf("dog"), "perros" to listOf("dogs"), "gato" to listOf("cat"), "gatos" to listOf("cats"), "lobo" to listOf("wolf"),
        "lobos" to listOf("wolves"), "leon" to listOf("lion"), "tiburon" to listOf("shark", "jaws"), "araña" to listOf("spider"),
        "arana" to listOf("spider"), "murcielago" to listOf("bat"), "pajaro" to listOf("bird"), "caballo" to listOf("horse"),
        "hombre araña" to listOf("spider man", "spiderman"), "vengadores" to listOf("avengers"), "vengador" to listOf("avenger"),
        "piratas" to listOf("pirates"), "pirata" to listOf("pirate"), "caribe" to listOf("caribbean"), "regreso" to listOf("back", "return"),
        "vuelta" to listOf("back", "return"), "futuro" to listOf("future"), "pasado" to listOf("past"), "tiempo" to listOf("time"),
        "juego" to listOf("game"), "juegos" to listOf("games"), "tronos" to listOf("thrones"), "trono" to listOf("throne"),
        "hambre" to listOf("hunger"), "rapido" to listOf("fast"), "rapidos" to listOf("fast"), "furioso" to listOf("furious"),
        "furiosos" to listOf("furious"), "mision" to listOf("mission"), "imposible" to listOf("impossible"), "padrino" to listOf("godfather"),
        "jungla" to listOf("jungle"), "selva" to listOf("jungle"), "cristal" to listOf("glass"), "parque" to listOf("park"),
        "jurasico" to listOf("jurassic"), "planeta" to listOf("planet"), "simios" to listOf("apes"), "mono" to listOf("monkey", "ape"),
        "fantasma" to listOf("ghost", "phantom"), "fantasmas" to listOf("ghosts"), "cazafantasmas" to listOf("ghostbusters"),
        "bestia" to listOf("beast"), "bella" to listOf("beauty"), "sirena" to listOf("mermaid"), "sirenita" to listOf("little mermaid"),
        "nieve" to listOf("snow"), "blancanieves" to listOf("snow white"), "congelado" to listOf("frozen"), "helado" to listOf("frozen"),
        "juguete" to listOf("toy"), "juguetes" to listOf("toys"), "historia" to listOf("story", "history"), "cuento" to listOf("tale", "story"),
        "coches" to listOf("cars"), "coche" to listOf("car"), "pez" to listOf("fish"), "buscando" to listOf("finding"),
        "increibles" to listOf("incredibles"), "monstruos" to listOf("monsters"), "monstruo" to listOf("monster"), "sa" to listOf("inc"),
        "secreto" to listOf("secret"), "secretos" to listOf("secrets"), "perdido" to listOf("lost"), "perdidos" to listOf("lost"),
        "ultimo" to listOf("last"), "ultima" to listOf("last"), "primero" to listOf("first"), "primera" to listOf("first"),
        "nuevo" to listOf("new"), "nueva" to listOf("new"), "viejo" to listOf("old"), "joven" to listOf("young"), "grande" to listOf("big", "great"),
        "pequeño" to listOf("little", "small"), "pequeno" to listOf("little", "small"), "rojo" to listOf("red"), "negro" to listOf("black"),
        "blanco" to listOf("white"), "azul" to listOf("blue"), "verde" to listOf("green"), "dorado" to listOf("golden"), "oro" to listOf("gold"),
        "plata" to listOf("silver"), "hierro" to listOf("iron"), "acero" to listOf("steel"), "capitan" to listOf("captain"),
        "doctor" to listOf("doctor"), "extraño" to listOf("strange", "stranger"), "extrano" to listOf("strange", "stranger"),
        "cosas" to listOf("things"), "cosa" to listOf("thing"), "casa de papel" to listOf("money heist"), "papel" to listOf("paper"),
        "lluvia" to listOf("rain"), "viento" to listOf("wind"), "tormenta" to listOf("storm"), "sol" to listOf("sun"), "luna" to listOf("moon"),
        "espacio" to listOf("space"), "estelar" to listOf("stellar", "star"), "viaje" to listOf("journey", "trip", "voyage"),
        "aventura" to listOf("adventure"), "aventuras" to listOf("adventures"), "leyenda" to listOf("legend"), "leyendas" to listOf("legends"),
        "venganza" to listOf("revenge"), "asesino" to listOf("killer", "assassin"), "asesinos" to listOf("killers", "assassins"),
        "crimen" to listOf("crime"), "policia" to listOf("police", "cop"), "ley" to listOf("law"), "orden" to listOf("order"),
        "escuela" to listOf("school"), "colegio" to listOf("school"), "academia" to listOf("academy"), "bebe" to listOf("baby"),
        "jefe" to listOf("boss"), "mentes" to listOf("minds"), "criminales" to listOf("criminal"), "anatomia" to listOf("anatomy"),
        "amo" to listOf("master"), "maestro" to listOf("master", "teacher"), "brujo" to listOf("wizard", "witcher"),
        "bruja" to listOf("witch"), "brujas" to listOf("witches"), "mago" to listOf("wizard", "magician"), "magia" to listOf("magic"),
        "piedra" to listOf("stone"), "filosofal" to listOf("philosopher", "sorcerer"), "camara" to listOf("chamber"), "prisionero" to listOf("prisoner"),
        "caliz" to listOf("goblet"), "reliquias" to listOf("hallows"), "muerte" to listOf("death"), "alas" to listOf("wings"),
        "corazon" to listOf("heart"), "corazones" to listOf("hearts"), "alma" to listOf("soul"), "almas" to listOf("souls"),
        "diablo" to listOf("devil"), "demonio" to listOf("demon"), "angel" to listOf("angel"), "angeles" to listOf("angels"),
        "dios" to listOf("god"), "dioses" to listOf("gods"), "titanes" to listOf("titans"), "ataque" to listOf("attack"),
        "zombis" to listOf("zombies"), "zombies" to listOf("zombies"), "invasion" to listOf("invasion"), "sombra" to listOf("shadow"),
        "sombras" to listOf("shadows"), "luz" to listOf("light"), "verdad" to listOf("truth"), "mentira" to listOf("lie"),
        "mentiras" to listOf("lies"), "silencio" to listOf("silence"), "corderos" to listOf("lambs"), "codigo" to listOf("code"),
        "lista" to listOf("list"), "puente" to listOf("bridge"), "torre" to listOf("tower"), "torres" to listOf("towers"),
        "puerta" to listOf("door", "gate"), "camino" to listOf("road", "way", "path"), "carretera" to listOf("road", "highway"),
        "loco" to listOf("mad", "crazy"), "locos" to listOf("crazy", "mad"), "furia" to listOf("fury"), "patrulla" to listOf("patrol"),
        "canina" to listOf("paw"), "patrulla canina" to listOf("paw patrol"), "cerdita" to listOf("pig"), "cerdo" to listOf("pig"),
        "oso" to listOf("bear"), "osos" to listOf("bears"), "abeja" to listOf("bee"), "hormiga" to listOf("ant"), "hormigas" to listOf("ants"),
        "tortugas" to listOf("turtles"), "tortuga" to listOf("turtle"), "ninja" to listOf("ninja"), "esponja" to listOf("sponge"),
        "bob esponja" to listOf("spongebob"), "pantalones" to listOf("pants"), "cuadrados" to listOf("square"), "simpson" to listOf("simpsons"),
    )

    private val enEs: Map<String, List<String>> by lazy {
        val m = HashMap<String, MutableList<String>>()
        esEn.forEach { (es, ens) -> ens.forEach { en -> m.getOrPut(en) { mutableListOf() }.add(es) } }
        m
    }

    /** Títulos conocidos que no se traducen palabra por palabra. */
    private val phrases: List<Pair<String, String>> = listOf(
        "bola de dragon" to "dragon ball",
        "la guerra de las galaxias" to "star wars",
        "guerra de las galaxias" to "star wars",
        "el señor de los anillos" to "the lord of the rings",
        "el senor de los anillos" to "the lord of the rings",
        "el padrino" to "the godfather",
        "tiburon" to "jaws",
        "regreso al futuro" to "back to the future",
        "los vengadores" to "avengers",
        "vengadores" to "avengers",
        "el hombre araña" to "spider-man",
        "hombre araña" to "spider-man",
        "el caballero oscuro" to "the dark knight",
        "juego de tronos" to "game of thrones",
        "la casa del dragon" to "house of the dragon",
        "los juegos del hambre" to "the hunger games",
        "a todo gas" to "fast & furious",
        "rapidos y furiosos" to "fast & furious",
        "mision imposible" to "mission impossible",
        "la jungla de cristal" to "die hard",
        "jungla de cristal" to "die hard",
        "parque jurasico" to "jurassic park",
        "el planeta de los simios" to "planet of the apes",
        "los cazafantasmas" to "ghostbusters",
        "la bella y la bestia" to "beauty and the beast",
        "la sirenita" to "the little mermaid",
        "blancanieves" to "snow white",
        "frozen el reino del hielo" to "frozen",
        "toy story" to "toy story",
        "buscando a nemo" to "finding nemo",
        "monstruos sa" to "monsters inc",
        "los increibles" to "the incredibles",
        "el rey leon" to "the lion king",
        "harry potter y la piedra filosofal" to "harry potter and the sorcerer",
        "el silencio de los corderos" to "the silence of the lambs",
        "la lista de schindler" to "schindler's list",
        "cadena perpetua" to "the shawshank redemption",
        "el club de la lucha" to "fight club",
        "la casa de papel" to "money heist",
        "mentes criminales" to "criminal minds",
        "anatomia de grey" to "grey's anatomy",
        "los simpson" to "the simpsons",
        "bob esponja" to "spongebob",
        "la patrulla canina" to "paw patrol",
        "patrulla canina" to "paw patrol",
        "peppa pig" to "peppa pig",
        "ataque a los titanes" to "attack on titan",
        "the walking dead" to "the walking dead",
        "los pitufos" to "the smurfs",
        "gru mi villano favorito" to "despicable me",
        "mi villano favorito" to "despicable me",
        "los minions" to "minions",
        "piratas del caribe" to "pirates of the caribbean",
        "indiana jones" to "indiana jones",
        "tortugas ninja" to "ninja turtles",
        "el brujo" to "the witcher",
        "cosas extrañas" to "stranger things",
        "la ley y el orden" to "law & order",
        "el exorcista" to "the exorcist",
        "el resplandor" to "the shining",
        "la naranja mecanica" to "a clockwork orange",
        "lo que el viento se llevo" to "gone with the wind",
        "solo en casa" to "home alone",
        "sonrisas y lagrimas" to "the sound of music",
        "la vida es bella" to "life is beautiful",
        "el lobo de wall street" to "the wolf of wall street",
        "atrapame si puedes" to "catch me if you can",
        "pulp fiction" to "pulp fiction",
    ).map { norm(it.first) to norm(it.second) }

    /** Frases equivalentes en el otro idioma para la consulta completa. */
    fun phraseVariants(q: String): List<String> {
        val n = norm(q)
        val out = LinkedHashSet<String>()
        phrases.forEach { (es, en) ->
            if (n.contains(es)) out.add(n.replace(es, en))
            if (n.contains(en)) out.add(n.replace(en, es))
            // también sin el artículo inicial
            val esShort = es.removePrefix("el ").removePrefix("la ").removePrefix("los ").removePrefix("las ")
            val enShort = en.removePrefix("the ")
            if (n.contains(esShort) && esShort.length > 3) out.add(enShort)
            if (n.contains(enShort) && enShort.length > 3) out.add(esShort)
        }
        out.remove(n)
        return out.toList()
    }

    /**
     * Palabras de la búsqueda, cada una con sus traducciones.
     * «bola de dragon» -> [[bola, ball], [dragon]]: encuentra «Dragon Ball Z» aunque el orden cambie.
     */
    fun searchTokens(q: String): List<List<String>> =
        norm(q).split(' ', '-', ':', ',', '.').filter { it.isNotBlank() && it !in stopwords }.map { t ->
            (listOf(t) + esEn[t].orEmpty() + enEs[t].orEmpty()).map { norm(it) }.distinct()
        }
}
