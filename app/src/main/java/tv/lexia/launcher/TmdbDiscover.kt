package tv.lexia.launcher

import org.json.JSONObject
import java.net.URLEncoder

/**
 * More films and series than today's list holds: TMDB's discover lists (free, the key the build
 * already has), in Turkish, for Keşfet's rows once their own titles run out. Called off the main thread.
 */
internal object TmdbDiscover {
    private const val API = "https://api.themoviedb.org/3"

    /** TMDB's score of the titles found here, by "movie:123" / "tv:123". */
    val score = java.util.concurrent.ConcurrentHashMap<String, Double>()

    /** TMDB's genre ids by their Turkish names (the names pick.json uses), per "movie" / "tv". */
    private val genres = HashMap<String, Map<String, Int>>()

    private fun get(path: String, params: Map<String, String>): JSONObject? {
        val key = BuildConfig.TMDB_API_KEY
        if (key.isBlank()) return null
        val query = (params + mapOf("api_key" to key, "language" to "tr-TR"))
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val body = Net.get("$API/$path?$query") ?: return null
        return try { JSONObject(body) } catch (e: Exception) { null }
    }

    private fun genreIds(kind: String): Map<String, Int> = synchronized(genres) {
        genres[kind]?.let { return it }
        val list = get("genre/$kind/list", emptyMap())?.optJSONArray("genres") ?: return emptyMap()
        val map = (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let { it.optString("name") to it.optInt("id") }
        }.toMap()
        if (map.isNotEmpty()) genres[kind] = map
        map
    }

    /**
     * Page [page] (1-based) of well-liked [kind] ("movie" or "tv") titles; in [genre] (its Turkish
     * name) when given, from [minScore] up, newest first when [newest]. Titles without a poster
     * are left out. Empty when TMDB cannot be reached.
     */
    fun page(kind: String, page: Int, genre: String = "", minScore: Double = 0.0, newest: Boolean = false): List<Pick> {
        val params = HashMap<String, String>()
        params["page"] = page.toString()
        params["sort_by"] = if (newest) (if (kind == "tv") "first_air_date.desc" else "primary_release_date.desc") else "popularity.desc"
        params["vote_count.gte"] = if (newest) "40" else "300"
        params["include_adult"] = "false"
        if (minScore > 0) params["vote_average.gte"] = minScore.toString()
        if (newest) params[if (kind == "tv") "first_air_date.lte" else "primary_release_date.lte"] =
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val names = genreIds(kind)
        if (genre.isNotEmpty()) {
            val id = names[genre] ?: return emptyList()
            params["with_genres"] = id.toString()
        }
        val results = get("discover/$kind", params)?.optJSONArray("results") ?: return emptyList()
        val byId = names.entries.associate { it.value to it.key }
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            val poster = o.optString("poster_path").takeIf { it.startsWith("/") } ?: return@mapNotNull null
            val title = o.optString(if (kind == "tv") "name" else "title").ifBlank { return@mapNotNull null }
            val date = o.optString(if (kind == "tv") "first_air_date" else "release_date")
            val ids = o.optJSONArray("genre_ids")
            val tmdbId = o.optInt("id")
            score["$kind:$tmdbId"] = Math.round(o.optDouble("vote_average", 0.0) * 10) / 10.0
            Pick(
                title = title,
                meta = "",
                overview = o.optString("overview"),
                backdrop = o.optString("backdrop_path").takeIf { it.startsWith("/") } ?: poster,
                reason = "",
                trailer = "",
                logo = "",
                // TMDB's own score, not IMDb's: kept apart ([score]) so no page calls it IMDb.
                imdb = 0.0,
                year = date.take(4),
                genres = (0 until (ids?.length() ?: 0)).mapNotNull { byId[ids!!.optInt(it)] },
                runtime = 0,
                poster = poster,
                kind = if (kind == "tv") "tv" else "movie",
                id = tmdbId
            )
        }
    }
}
