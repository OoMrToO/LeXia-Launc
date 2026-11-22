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
            id = tmdbId
        )
    }

    /** Any discover list: [params] are TMDB's own (with_genres by Turkish name via [genre]). */
    fun discover(kind: String, page: Int, params: Map<String, String>, genre: String = ""): List<Pick> {
        val all = HashMap(params)
        all["page"] = page.toString()
        all["include_adult"] = "false"
        if (genre.isNotEmpty()) {
            val names = genreIds(kind)
            val ids = genre.split(',').mapNotNull { names[it.trim()] }
            if (ids.isEmpty()) return emptyList()
            all["with_genres"] = ids.joinToString(",")
        }
        val results = get("discover/$kind", all)?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i -> results.optJSONObject(i)?.let { pickOf(it, kind) } }
    }

    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /** Coming out from today on, the most awaited first. */
    fun upcoming(kind: String, page: Int): List<Pick> = discover(kind, page, mapOf(
        (if (kind == "tv") "first_air_date.gte" else "primary_release_date.gte") to today(),
        "sort_by" to "popularity.desc",
        "region" to "TR"
    ))

    /** TMDB's "if you liked this" for a title. */
    fun similar(p: Pick, page: Int = 1): List<Pick> {
        if (p.id <= 0) return emptyList()
        val kind = if (p.isSeries) "tv" else "movie"
        val results = get("$kind/${p.id}/recommendations", mapOf("page" to page.toString()))?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i -> results.optJSONObject(i)?.let { pickOf(it, kind) } }
    }

    /** A search over every film, series and person TMDB knows; persons come as names. */
    class Found(val pick: Pick?, val person: String = "", val directing: Boolean = false, val known: String = "")

    fun search(query: String): List<Found> {
        val results = get("search/multi", mapOf("query" to query, "include_adult" to "false"))?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            when (o.optString("media_type")) {
                "movie" -> pickOf(o, "movie")?.let { Found(it) }
                "tv" -> pickOf(o, "tv")?.let { Found(it) }
                "person" -> o.optString("name").takeIf { it.isNotBlank() }?.let {
                    val known = o.optJSONArray("known_for")
                    val titles = (0 until minOf(2, known?.length() ?: 0)).mapNotNull { j ->
                        known!!.optJSONObject(j)?.let { k -> k.optString("title").ifBlank { k.optString("name") } }
                    }
                    Found(null, it, o.optString("known_for_department") == "Directing", titles.joinToString(", "))
                }
                else -> null
            }
        }
    }

    /**
     * What a title found on TMDB (Keşfet's "Daha fazla", a person's films) lacks next to today's
     * list: its trailers, title logo, length, cast, director and IMDb id, from its full TMDB page.
     * Null when TMDB cannot be reached or the title has no id.
     */
    fun complete(p: Pick): Pick? {
        if (p.id <= 0) return null
        val kind = if (p.isSeries) "tv" else "movie"
        val o = get("$kind/${p.id}", mapOf(
            "append_to_response" to "videos,images,external_ids,credits",
            "include_video_language" to "tr,en,null",
            "include_image_language" to "tr,en,null"
        )) ?: return null
        val out = p.toJson()
        // Trailers on YouTube: Turkish first, then the original; trailers before teasers.
        val videos = o.optJSONObject("videos")?.optJSONArray("results")
        val list = (0 until (videos?.length() ?: 0)).mapNotNull { videos!!.optJSONObject(it) }
            .filter { it.optString("site") == "YouTube" && it.optString("key").isNotBlank() && it.optString("type") in setOf("Trailer", "Teaser") }
            .sortedWith(compareBy({ if (it.optString("iso_639_1") == "tr") 0 else 1 }, { if (it.optString("type") == "Trailer") 0 else 1 }, { if (it.optBoolean("official")) 0 else 1 }))
            .take(6)
        if (list.isNotEmpty() && p.trailer.isEmpty()) {
            out.put("trailer", list[0].optString("key"))
            out.put("trailers", org.json.JSONArray(list.map {
                val turkish = it.optString("iso_639_1") == "tr"
                val teaser = it.optString("type") == "Teaser"
                org.json.JSONObject().put("k", it.optString("key")).put("n", when {
                    turkish && teaser -> "Türkçe tanıtım"
                    turkish -> "Türkçe fragman"
                    teaser -> "Tanıtım"
                    else -> "Orijinal fragman"
                })
            }))
        }
        if (p.logo.isEmpty()) {
            val logos = o.optJSONObject("images")?.optJSONArray("logos")
            val all = (0 until (logos?.length() ?: 0)).mapNotNull { logos!!.optJSONObject(it) }
            (all.firstOrNull { it.optString("iso_639_1") == "tr" } ?: all.firstOrNull { it.optString("iso_639_1") == "en" } ?: all.firstOrNull())
                ?.optString("file_path")?.takeIf { it.startsWith("/") }?.let { out.put("logo", it) }
        }
        if (p.runtime <= 0) {
            val minutes = if (kind == "tv") o.optJSONArray("episode_run_time")?.optInt(0) ?: 0 else o.optInt("runtime")
            if (minutes > 0) out.put("runtime", minutes)
        }
        if (p.overview.isBlank()) out.put("overview", o.optString("overview"))
        if (p.tagline.isBlank()) out.put("tagline", o.optString("tagline"))
        if (kind == "tv" && p.seasons <= 0) out.put("seasons", o.optInt("number_of_seasons"))
        if (p.imdbId.isBlank()) {
            o.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }?.let { out.put("imdb_id", it) }
        }
        val credits = o.optJSONObject("credits")
        if (p.cast.isEmpty()) {
            val cast = credits?.optJSONArray("cast")
            out.put("cast", org.json.JSONArray((0 until minOf(10, cast?.length() ?: 0)).mapNotNull { i ->
                cast!!.optJSONObject(i)?.let {
                    org.json.JSONObject().put("name", it.optString("name")).put("role", it.optString("character"))
                        .put("photo", it.optString("profile_path"))
                }
            }))
        }
        if (p.director.isBlank()) {
            val name = if (kind == "tv") o.optJSONArray("created_by")?.optJSONObject(0)?.optString("name")
            else credits?.optJSONArray("crew")?.let { c -> (0 until c.length()).mapNotNull { c.optJSONObject(it) }.firstOrNull { it.optString("job") == "Director" }?.optString("name") }
            if (!name.isNullOrBlank()) out.put("director", name)
        }
        return try { Pick.from(out) } catch (e: Exception) { null }
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
