package tv.lexia.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local episode/watch progress for metadata providers and user-authorized playback integrations. */
object WatchProgress {
    data class Episode(
        val seriesId: String,
        val season: Int,
        val episode: Int,
        val positionMs: Long,
        val durationMs: Long,
        val updatedAt: Long,
        val title: String = "",
        val mediaType: String = "tv",
        val tmdbId: Int = 0
    ) {
        val isComplete: Boolean get() = durationMs > 0 && positionMs >= durationMs * 0.9
    }

    private const val PREFS = "lexia_watch_progress"
    private const val KEY = "episodes"

    @Synchronized
    fun save(
        context: Context,
        seriesId: String,
        season: Int,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
        title: String = "",
        mediaType: String = "tv",
        tmdbId: Int = 0
    ) {
        require(seriesId.isNotBlank() && season >= 0 && episode > 0)
        val all = read(context).associateBy { key(it.seriesId, it.season, it.episode) }.toMutableMap()
        val item = Episode(
            seriesId = seriesId,
            season = season,
            episode = episode,
            positionMs = positionMs.coerceAtLeast(0),
            durationMs = durationMs.coerceAtLeast(0),
            updatedAt = System.currentTimeMillis(),
            title = title.take(180),
            mediaType = if (mediaType == "movie") "movie" else "tv",
            tmdbId = tmdbId.coerceAtLeast(0)
        )
        all[key(seriesId, season, episode)] = item
        val json = JSONArray()
        all.values.sortedByDescending { it.updatedAt }.take(2000).forEach {
            json.put(JSONObject()
                .put("seriesId", it.seriesId)
                .put("season", it.season)
                .put("episode", it.episode)
                .put("positionMs", it.positionMs)
                .put("durationMs", it.durationMs)
                .put("updatedAt", it.updatedAt)
                .put("title", it.title)
                .put("mediaType", it.mediaType)
                .put("tmdbId", it.tmdbId))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.toString()).apply()
    }

    @Synchronized
    fun get(context: Context, seriesId: String, season: Int, episode: Int): Episode? =
        read(context).firstOrNull { it.seriesId == seriesId && it.season == season && it.episode == episode }

    @Synchronized
    fun latest(context: Context, seriesId: String): Episode? =
        read(context).filter { it.seriesId == seriesId }.maxByOrNull { it.updatedAt }

    @Synchronized
    fun recent(context: Context, limit: Int = 50): List<Episode> =
        read(context).sortedByDescending { it.updatedAt }.take(limit.coerceIn(1, 200))

    @Synchronized
    fun completed(context: Context, seriesId: String): Set<String> =
        read(context).filter { it.seriesId == seriesId && it.isComplete }
            .map { "${it.season}:${it.episode}" }.toSet()

    @Synchronized
    fun clear(context: Context, seriesId: String) {
        val json = JSONArray()
        read(context).filterNot { it.seriesId == seriesId }.forEach {
            json.put(JSONObject()
                .put("seriesId", it.seriesId)
                .put("season", it.season)
                .put("episode", it.episode)
                .put("positionMs", it.positionMs)
                .put("durationMs", it.durationMs)
                .put("updatedAt", it.updatedAt)
                .put("title", it.title)
                .put("mediaType", it.mediaType)
                .put("tmdbId", it.tmdbId))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.toString()).apply()
    }

    private fun read(context: Context): List<Episode> = try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Episode(
                seriesId = o.optString("seriesId"),
                season = o.optInt("season"),
                episode = o.optInt("episode"),
                positionMs = o.optLong("positionMs"),
                durationMs = o.optLong("durationMs"),
                updatedAt = o.optLong("updatedAt"),
                title = o.optString("title"),
                mediaType = o.optString("mediaType", "tv").let { if (it == "movie") "movie" else "tv" },
                tmdbId = o.optInt("tmdbId", 0)
            ).takeIf { it.seriesId.isNotBlank() && it.episode > 0 }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun key(id: String, season: Int, episode: Int) = "$id:$season:$episode"
}
