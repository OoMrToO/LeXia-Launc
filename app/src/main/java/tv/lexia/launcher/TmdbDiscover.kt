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
        return (0 until results.length()).mapNotNull { i -> results.optJSONObject(i)?.let { pickOf(it, kind) } }
    }

    /** Cached country metadata from TMDB detail endpoints, by "movie:123" / "tv:123". */
    private val countryCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** Country codes for a title. Discover results may already include them; detail is the fallback. */
    fun countries(kind: String, id: Int): List<String> {
        if (id <= 0) return emptyList()
        val k = "${if (kind == "tv") "tv" else "movie"}:$id"
        countryCache[k]?.let { return it }
        val detail = get("${if (kind == "tv") "tv" else "movie"}/$id", emptyMap()) ?: return emptyList()
        val arr = detail.optJSONArray("production_countries")
        val values = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.optString("iso_3166_1")?.takeIf { it.isNotBlank() } }
        val tvOrigins = detail.optJSONArray("origin_country")
        val merged = (values + (0 until (tvOrigins?.length() ?: 0)).mapNotNull { tvOrigins?.optString(it)?.takeIf { it.isNotBlank() } })
            .map { it.uppercase(java.util.Locale.US) }.distinct()
        countryCache[k] = merged
        return merged
    }

    /** A TMDB result (discover, a person's credits) as a page LeXia can open; null without a poster. */
    fun pickOf(o: org.json.JSONObject, kindOf: String): Pick? {
        val kind = if (kindOf == "tv") "tv" else "movie"
        val poster = o.optString("poster_path").takeIf { it.startsWith("/") } ?: return null
        val title = o.optString(if (kind == "tv") "name" else "title").ifBlank { return null }
        val date = o.optString(if (kind == "tv") "first_air_date" else "release_date")
        val ids = o.optJSONArray("genre_ids")
        val byId = genreIds(kind).entries.associate { it.value to it.key }
        val tmdbId = o.optInt("id")
        score["$kind:$tmdbId"] = Math.round(o.optDouble("vote_average", 0.0) * 10) / 10.0
        return Pick(
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
            kind = kind,
            id = tmdbId,
            countries = (0 until (o.optJSONArray("production_countries")?.length() ?: 0)).mapNotNull { o.optJSONArray("production_countries")?.optJSONObject(it)?.optString("iso_3166_1")?.takeIf { it.isNotBlank() } } +
                (0 until (o.optJSONArray("origin_country")?.length() ?: 0)).mapNotNull { o.optJSONArray("origin_country")?.optString(it)?.takeIf { it.isNotBlank() } }
        )
    }

    /** A person (actor, director) by name: TMDB's page, with every film and series they made. */
    fun person(name: String, directing: Boolean): org.json.JSONObject? {
        val found = get("search/person", mapOf("query" to name))?.optJSONArray("results") ?: return null
        val all = (0 until found.length()).mapNotNull { found.optJSONObject(it) }
        val best = all.firstOrNull { it.optString("name").equals(name, true) && (it.optString("known_for_department") == "Directing") == directing }
            ?: all.firstOrNull { it.optString("name").equals(name, true) }
            ?: all.firstOrNull()
            ?: return null
        val id = best.optInt("id")
        val page = get("person/$id", mapOf("append_to_response" to "combined_credits")) ?: return null
        // No Turkish biography: the English one.
        if (page.optString("biography").isBlank()) {
            val key = BuildConfig.TMDB_API_KEY
            Net.get("$API/person/$id?api_key=$key&language=en-US")?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optString("biography")?.takeIf { it.isNotBlank() }?.let { page.put("biography", it) }
        }
        return page
    }
}
