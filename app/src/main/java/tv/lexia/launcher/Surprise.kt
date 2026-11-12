package tv.lexia.launcher

import android.app.Activity
import android.widget.Toast
import java.util.Locale
import kotlin.random.Random

/**
 * "Bu akşam ne izlesem?": asks for a genre, then a length, and opens one film that fits.
 * Films come from today's picks and this week's Top 10. Higher IMDb scores come up more often.
 */
object Surprise {
    private class Length(val name: String, val fits: (Pick) -> Boolean)

    private val lengths = listOf(
        Length("Süre fark etmez") { true },
        Length("1,5 saatten kısa") { it.runtime in 1..90 },
        Length("2 saatten kısa") { it.runtime in 1..120 },
        Length("Uzun bir film (2 saat ve üstü)") { it.runtime >= 120 }
    )

    private var pool: List<Pick> = emptyList()
    private val seen = HashSet<String>()
    /** Why the current films were chosen, shown on the detail page. */
    private var why = ""

    fun ask(a: Activity, films: List<Pick>, liked: Set<String>) {
        val all = films.distinctBy { it.key }
        if (all.isEmpty()) {
            Glass.toast(a, "Film listesi henüz yüklenmedi")
            return
        }
        // Genre options, with how many films each has; empty ones are left out.
        val counts = HashMap<String, Int>()
        for (p in all) for (g in p.genres) counts[g] = (counts[g] ?: 0) + 1
        val rows = ArrayList<Pair<String, String>>()
        val subsets = ArrayList<Pair<String, List<Pick>>>()
        rows.add("Fark etmez" to "${all.size} film")
        subsets.add("" to all)
        val fav = all.filter { p -> p.genres.any { it in liked } }
        if (liked.isNotEmpty() && fav.isNotEmpty()) {
            rows.add("Sevdiğim türler" to "${fav.size} film")
            subsets.add("Sevdiğin türler" to fav)
        }
        for ((g, n) in counts.entries.sortedWith(compareBy({ -it.value }, { it.key }))) {
            rows.add(g to "$n film")
            subsets.add(g to all.filter { g in it.genres })
        }
        // First line: the duel, two films at a time until one is left.
        rows.add(0, "Film düellosu" to "İki film, sen seç")
        subsets.add(0, "" to all)
        Dialogs.list(a, "Ne tür bir film?", rows, "Sonra süreyi seç; IMDb puanı yüksek olan daha sık gelir.", R.drawable.ic_dice) { i ->
            if (i == 0) {
                DuelActivity.open(a, all)
                return@list
            }
            val (genre, films2) = subsets[i]
            askLength(a, genre, films2)
        }
    }

    private fun askLength(a: Activity, genre: String, films: List<Pick>) {
        val options = lengths.map { l -> l to films.filter(l.fits) }.filter { it.second.isNotEmpty() }
        val from = when (genre) {
            "" -> "Bütün türlerden seçilecek."
            "Sevdiğin türler" -> "Sevdiğin türlerden seçilecek."
            else -> "$genre filmleri arasından seçilecek."
        }
        Dialogs.list(a, "Ne kadar sürsün?", options.map { it.first.name to "${it.second.size} film" }, from, R.drawable.ic_dice) { i ->
            val (length, chosen) = options[i]
            pool = chosen
            seen.clear()
            why = listOf(genre, if (length === lengths[0]) "" else length.name).filter { it.isNotEmpty() }.joinToString("  ·  ")
            next(a)
        }
    }

    /** Opens another film from the same choice, without repeats until all were shown. */
    fun next(a: Activity) {
        if (pool.isEmpty()) {
            Glass.toast(a, "Film listesi henüz yüklenmedi")
            return
        }
        var left = pool.filter { it.key !in seen }
        if (left.isEmpty()) {
            seen.clear()
            left = pool
        }
        val p = weighted(left)
        seen.add(p.key)
        val parts = ArrayList<String>()
        if (why.isNotEmpty()) parts.add(why)
        if (p.imdb > 0) parts.add(String.format(Locale("tr", "TR"), "IMDb %.1f", p.imdb))
        DetailActivity.open(a, p, surprise = true, why = parts.joinToString("  ·  "))
    }

    /** Random, but an 8.5 film comes up three times as often as a 6.5 one. */
    private fun weighted(list: List<Pick>): Pick {
        val w = list.map { (((if (it.imdb > 0) it.imdb else 6.5) - 5.5).coerceAtLeast(0.5)) }
        var r = Random.nextDouble() * w.sum()
        for (i in list.indices) {
            r -= w[i]
            if (r <= 0) return list[i]
        }
        return list.last()
    }
}
