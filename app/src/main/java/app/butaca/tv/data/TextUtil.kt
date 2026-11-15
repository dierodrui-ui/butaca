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
}
