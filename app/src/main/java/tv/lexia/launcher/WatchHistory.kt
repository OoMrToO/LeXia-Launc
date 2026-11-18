package tv.lexia.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What was watched in LeXia: each title once (its page data, so it stays after it leaves the day's
 * list), when it was last played, and when films and episodes were finished. Where it was left is
 * still the player's record ([MediaPlaybackStore]); this keeps the titles and the dates.
 */
internal class WatchHistory(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    class Entry(val pick: Pick, val playedAt: Long, val finishedAt: Long)

    /** A film or episode finished, for the month's count. */
    class Finish(val key: String, val mediaKey: String, val at: Long)

    private fun read(name: String): JSONArray = try {
        JSONArray(preferences.getString(name, "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    /** Every title, last played first. */
    fun entries(): List<Entry> {
        val a = read(KEY_TITLES)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val p = o.optJSONObject("pick")?.let { runCatching { Pick.from(it) }.getOrNull() } ?: return@mapNotNull null
            Entry(p, o.optLong("played"), o.optLong("finished"))
        }.sortedByDescending { it.playedAt }
    }

    fun finishes(): List<Finish> {
        val a = read(KEY_FINISHES)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Finish(o.optString("key"), o.optString("media"), o.optLong("at"))
        }
    }

    private fun write(entries: List<Entry>) {
        val a = JSONArray()
        entries.sortedByDescending { it.playedAt }.take(MAX_TITLES).forEach { e ->
            a.put(JSONObject().put("pick", e.pick.toJson()).put("played", e.playedAt).put("finished", e.finishedAt))
        }
        preferences.edit().putString(KEY_TITLES, a.toString()).apply()
    }

    /** [p] was started (or went on) now. */
    fun played(p: Pick) = synchronized(LOCK) {
        val all = entries()
        val old = all.firstOrNull { it.pick.key == p.key }
        write(all.filter { it.pick.key != p.key } + Entry(p, System.currentTimeMillis(), old?.finishedAt ?: 0L))
    }

    /** [mediaKey] (the film, or one episode of [p]) was watched to the end now; counted once. */
    fun finished(p: Pick, mediaKey: String) = synchronized(LOCK) {
        val now = System.currentTimeMillis()
        val list = finishes()
        if (list.none { it.mediaKey == mediaKey && now - it.at < SAME_FINISH_MS }) {
            val a = JSONArray()
            (list + Finish(p.key, mediaKey, now)).takeLast(MAX_FINISHES).forEach {
                a.put(JSONObject().put("key", it.key).put("media", it.mediaKey).put("at", it.at))
            }
            preferences.edit().putString(KEY_FINISHES, a.toString()).apply()
        }
        val all = entries()
        val old = all.firstOrNull { it.pick.key == p.key }
        write(all.filter { it.pick.key != p.key } + Entry(p, old?.playedAt?.takeIf { it > 0 } ?: now, now))
    }

    /** Titles played before the history was kept: those still in the day's list are added once. */
    fun adopt(picks: List<Pick>, store: MediaPlaybackStore) = synchronized(LOCK) {
        val all = entries()
        val known = all.map { it.pick.key }.toSet()
        val found = picks.distinctBy { it.key }.filter { it.key !in known }.mapNotNull { p ->
            val last = store.last(p.key).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val at = store.updatedAt(last)
            Entry(p, at, if (store.isFinished(last)) at else 0L)
        }
        if (found.isNotEmpty()) write(all + found)
    }

    fun remove(key: String) = synchronized(LOCK) {
        write(entries().filter { it.pick.key != key })
    }

    companion object {
        const val FILE = "watch_history"
        private const val KEY_TITLES = "titles"
        private const val KEY_FINISHES = "finishes"
        private const val MAX_TITLES = 300
        private const val MAX_FINISHES = 2000
        /** One finish counted per film or episode within a day (the player may report twice). */
        private const val SAME_FINISH_MS = 24 * 60 * 60 * 1000L
        private val LOCK = Any()
    }
}
