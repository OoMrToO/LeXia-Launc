package tv.lexia.launcher

import android.app.Activity

/**
 * Search over everything: today's list first, then every film, series and person TMDB knows
 * (Turkish series and the platforms' own titles too). A film opens its page, a person theirs.
 */
internal object SearchAll {
    fun ask(a: Activity, local: List<Pick> = emptyList()) {
        Dialogs.input(a, "Film, dizi ya da oyuncu ara", "") { q -> run(a, q, local) }
    }

    fun run(a: Activity, query: String, local: List<Pick> = emptyList()) {
        val q = query.trim()
        if (q.length < 2) return
        if (BuildConfig.TMDB_API_KEY.isBlank()) {
            Glass.toast(a, "Bu sürümde TMDB anahtarı yok; yalnızca bugünkü listede aranabilir")
            return
        }
        Glass.toast(a, "“$q” aranıyor…")
        Thread {
            val found = try { TmdbDiscover.search(q) } catch (e: Exception) { emptyList() }
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                // Titles of today's list keep their richer data (trailer, IMDb score).
                val byId = local.filter { it.id > 0 }.associateBy { "${it.kind}:${it.id}" }
                val items = found.take(24)
                if (items.isEmpty()) {
                    Glass.toast(a, "“$q” için sonuç bulunamadı")
                    return@runOnUiThread
                }
                val rows = items.map { f ->
                    val p = f.pick
                    if (p != null) {
                        val score = TmdbDiscover.score["${p.kind}:${p.id}"]
                        p.title to listOfNotNull(
                            if (p.isSeries) "Dizi" else "Film",
                            p.year.takeIf { it.isNotEmpty() },
                            score?.takeIf { it > 0 }?.let { String.format(java.util.Locale("tr", "TR"), "TMDB %.1f", it) }
                        ).joinToString("  ·  ")
                    } else {
                        f.person to listOfNotNull(if (f.directing) "Yönetmen" else "Oyuncu", f.known.takeIf { it.isNotEmpty() }).joinToString("  ·  ")
                    }
                }
                Dialogs.list(a, "“$q”", rows, "Filmler, diziler ve kişiler", R.drawable.ic_search) { i ->
                    val f = items[i]
                    val p = f.pick
                    if (p != null) DetailActivity.open(a, byId["${p.kind}:${p.id}"] ?: p)
                    else PersonActivity.open(a, f.person, f.directing)
                }
            }
        }.start()
    }
}
