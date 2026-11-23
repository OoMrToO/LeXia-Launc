package tv.lexia.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * What LeXia learns about sources. Each site's record (how often it played, how often it would
 * not open) puts reliable ones first when a source is picked by itself; and a title's sources are
 * kept for a few hours, so coming back to it starts at once while a fresh search runs behind.
 */
internal class SourceMemory(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val dir = File(app.cacheDir, "sources").apply { mkdirs() }

    // ---------- Which sites play ----------

    private fun stats(): JSONObject = try {
        JSONObject(preferences.getString(KEY_STATS, "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun count(site: String, played: Boolean) = synchronized(LOCK) {
        val name = site.trim().lowercase()
        if (name.isEmpty()) return
        val all = stats()
        val row = all.optJSONArray(name) ?: JSONArray().put(0).put(0)
        val i = if (played) 0 else 1
        row.put(i, row.optInt(i) + 1)
        // What a site did lately counts most: past [RECENT] tries the old record is halved, so a
        // site that was fixed comes back up and one that broke goes down within days.
        if (row.optInt(0) + row.optInt(1) > RECENT) {
            row.put(0, row.optInt(0) / 2)
            row.put(1, row.optInt(1) / 2)
        }
        all.put(name, row)
        preferences.edit().putString(KEY_STATS, all.toString()).apply()
    }

    fun played(site: String) = count(site, true)
    fun failed(site: String) = count(site, false)

    /**
     * How much a site can be trusted, 0..1: what share of its tries played, with one of each assumed
     * at the start (a new site is 0.5, neither good nor bad). Rounded to tenths, so small differences
     * leave the order to picture quality.
     */
    fun trust(site: String): Double {
        val row = stats().optJSONArray(site.trim().lowercase()) ?: return 0.5
        val ok = row.optInt(0)
        val bad = row.optInt(1)
        return Math.round((ok + 1.0) / (ok + bad + 2.0) * 10) / 10.0
    }

    /**
     * A site that keeps not opening: three failures or more, and most of its tries failed. Its
     * sources go to the end of the list, marked, so the ones that play are what the eye meets first.
     */
    fun unreliable(site: String): Boolean {
        val row = stats().optJSONArray(site.trim().lowercase()) ?: return false
        return row.optInt(1) >= 3 && trust(site) <= 0.3
    }

    // ---------- A title's sources, for a few hours ----------

    private fun fileOf(key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    private fun toJson(s: MediaStream): JSONObject = JSONObject()
        .put("addon", s.addonName).put("name", s.name).put("description", s.description).put("url", s.url)
        .put("headers", JSONObject(s.headers))
        .put("subtitles", JSONArray().apply {
            s.subtitles.forEach { put(JSONObject().put("url", it.url).put("language", it.language).put("label", it.label)) }
        })
        .put("fileName", s.fileName).put("sizeBytes", s.sizeBytes)

    private fun fromJson(s: JSONObject): MediaStream? {
        val headers = s.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty()
        val subs = s.optJSONArray("subtitles")?.let { a ->
            (0 until a.length()).mapNotNull { j ->
                a.optJSONObject(j)?.let { MediaSubtitle(it.optString("url"), it.optString("language"), it.optString("label")) }
            }
        }.orEmpty()
        return MediaStream(
            addonName = s.optString("addon"),
            name = s.optString("name"),
            description = s.optString("description"),
            url = s.optString("url").takeIf { it.isNotBlank() } ?: return null,
            headers = headers,
            subtitles = subs,
            fileName = s.optString("fileName"),
            sizeBytes = s.optLong("sizeBytes")
        )
    }

    fun keep(key: String, streams: List<MediaStream>) {
        if (key.isBlank() || streams.isEmpty()) return
        val list = JSONArray()
        streams.take(MAX_KEPT).forEach { list.put(toJson(it)) }
        try {
            fileOf(key).writeText(JSONObject().put("at", System.currentTimeMillis()).put("streams", list).toString())
        } catch (_: Exception) {
        }
    }

    /** The sources kept for [key], if found less than [KEEP_MS] ago. */
    fun kept(key: String): List<MediaStream> {
        if (key.isBlank()) return emptyList()
        val o = try {
            JSONObject(fileOf(key).readText())
        } catch (e: Exception) {
            return emptyList()
        }
        if (System.currentTimeMillis() - o.optLong("at") > KEEP_MS) return emptyList()
        val list = o.optJSONArray("streams") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { fromJson(it) } }
    }

    // ---------- The source a film or episode last played from ----------

    private val lastDir = File(app.filesDir, "last_source").apply { mkdirs() }

    private fun lastFile(key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(lastDir, "$hash.json")
    }

    /** [stream] is what [key] (a film, or an episode "tt…:2:5") plays from now: "Devam et" starts it again. */
    fun playedFrom(key: String, stream: MediaStream) {
        if (key.isBlank()) return
        try {
            lastFile(key).writeText(JSONObject().put("at", System.currentTimeMillis()).put("stream", toJson(stream)).toString())
            // Old ones go: a month is plenty to come back to something.
            val old = System.currentTimeMillis() - LAST_MS
            lastDir.listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    /** The source [key] last played from, if within a month; null when none (or it stopped playing). */
    fun lastPlayed(key: String): MediaStream? {
        if (key.isBlank()) return null
        val o = try {
            JSONObject(lastFile(key).readText())
        } catch (e: Exception) {
            return null
        }
        if (System.currentTimeMillis() - o.optLong("at") > LAST_MS) return null
        return o.optJSONObject("stream")?.let { fromJson(it) }
    }

    /** The last source would not open again (its address expired): the next "Devam et" searches. */
    fun forgetPlayed(key: String) {
        try {
            lastFile(key).delete()
        } catch (_: Exception) {
        }
    }

    /** A kept source would not play: it is dropped, the rest stay. */
    fun drop(key: String, url: String) {
        val rest = kept(key).filter { it.url != url }
        if (rest.isEmpty()) fileOf(key).delete() else keep(key, rest)
    }

    companion object {
        private const val FILE = "source_memory"
        private const val KEY_STATS = "stats"
        /** Sources found less than three hours ago still play (their addresses carry tokens that expire). */
        private const val KEEP_MS = 3 * 60 * 60 * 1000L
        private const val MAX_KEPT = 80
        private const val RECENT = 30
        private const val LAST_MS = 30L * 24 * 60 * 60 * 1000
        private val LOCK = Any()
    }
}
