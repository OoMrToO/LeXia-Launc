package tv.lexia.launcher

import android.app.Activity

/**
 * Search over everything: every film, series and person TMDB knows (Turkish series and the
 * platforms' own titles too), on the search page (SearchActivity). Today's list goes with it, so
 * its titles keep their richer data and the empty page shows what is popular today.
 */
internal object SearchAll {
    fun ask(a: Activity, local: List<Pick> = emptyList()) = SearchActivity.open(a, "", local)

    fun run(a: Activity, query: String, local: List<Pick> = emptyList()) {
        if (query.trim().length < 2) return
        SearchActivity.open(a, query.trim(), local)
    }
}
