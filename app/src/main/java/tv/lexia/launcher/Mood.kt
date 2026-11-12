package tv.lexia.launcher

import java.util.Calendar

/**
 * A film that suits the weather outside: a warm drama on a rainy evening, a mystery in fog.
 * Same film all day (it changes with the date), unwatched, liked genres first.
 */
object Mood {
    private class Kind(val genres: List<String>, val line: (slot: String, noun: String) -> String)

    private val nouns = mapOf(
        "Dram" to "dram", "Romantik" to "aşk hikâyesi", "Aile" to "aile filmi", "Animasyon" to "animasyon",
        "Fantastik" to "fantastik film", "Gerilim" to "gerilim", "Gizem" to "gizem", "Korku" to "korku",
        "Suç" to "suç filmi", "Macera" to "macera", "Aksiyon" to "aksiyon filmi", "Komedi" to "komedi",
        "Bilim-Kurgu" to "bilim kurgu"
    )

    private val rain = Kind(listOf("Dram", "Romantik", "Aile", "Animasyon")) { s, n -> "Yağmurlu bir $s için sıcacık bir $n." }
    private val snow = Kind(listOf("Aile", "Animasyon", "Fantastik", "Romantik", "Dram")) { s, n -> "Karlı bir $s için battaniyelik bir $n." }
    private val storm = Kind(listOf("Gerilim", "Gizem", "Korku")) { _, n -> "Dışarıda fırtına var; tam $n havası." }
    private val fog = Kind(listOf("Gizem", "Gerilim", "Suç")) { _, n -> "Sisli havaya yakışan bir $n." }
    private val sunny = Kind(listOf("Macera", "Aksiyon", "Komedi", "Animasyon")) { s, n -> "Güneşli bir $s için enerjik bir $n." }
    private val clearNight = Kind(listOf("Bilim-Kurgu", "Fantastik", "Gizem")) { _, n -> "Açık bir gece; bir $n iyi gider." }
    private val cold = Kind(listOf("Dram", "Romantik", "Komedi", "Aile")) { s, n -> "Soğuk bir $s için içini ısıtacak bir $n." }
    private val mild = Kind(listOf("Komedi", "Macera", "Romantik")) { s, n -> "Güzel bir $s için keyifli bir $n." }
    private val cloudy = Kind(listOf("Komedi", "Dram", "Macera")) { s, n -> "Bulutlu bir $s için keyifli bir $n." }

    /** Open-Meteo weather code and °C to a kind of film; null when the weather is unknown. */
    private fun kindOf(code: Int, temp: Int, night: Boolean): Kind? = when {
        code < 0 -> null
        code in 95..99 -> storm
        code in 71..77 || code == 85 || code == 86 -> snow
        code in 51..67 || code in 80..82 -> rain
        code == 45 || code == 48 -> fog
        temp <= 8 -> cold
        code <= 1 && night -> clearNight
        code <= 1 && temp >= 24 -> sunny
        code <= 1 -> mild
        else -> cloudy
    }

    /** The film and its one-line reason, or null if nothing fits. */
    fun pick(code: Int, temp: Int, films: List<Pick>, watched: Set<String>, liked: List<String>): Pair<Pick, String>? {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val slot = when {
            h < 5 || h >= 22 -> "gece"
            h < 12 -> "sabah"
            h < 17 -> "gün"
            else -> "akşam"
        }
        val kind = kindOf(code, temp, slot == "gece") ?: return null
        val pool = films.distinctBy { it.key }
            .filter { it.key !in watched && it.backdrop.isNotEmpty() && it.genres.any { g -> g in kind.genres } }
        if (pool.isEmpty()) return null
        // Liked genres first, then the best rated; the date picks among the top few.
        val ranked = pool.sortedWith(
            compareBy<Pick>({ p -> if (p.genres.any { it in liked }) 0 else 1 }, { -it.imdb })
        ).take(4)
        val p = ranked[c.get(Calendar.DAY_OF_YEAR) % ranked.size]
        val genre = kind.genres.first { it in p.genres }
        return p to kind.line(slot, nouns[genre] ?: genre.lowercase())
    }

    /** A copy of [p] with another reason line. */
    fun withReason(p: Pick, reason: String): Pick = Pick.from(p.toJson().put("reason", reason))
}
