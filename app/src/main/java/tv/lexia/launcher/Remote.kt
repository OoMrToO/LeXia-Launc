package tv.lexia.launcher

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings the owner changes for every TV at once, from the LeXia admin page: a message,
 * a lowest allowed version, and features to switch off. Read from remote.json in the
 * repository (with the film list) and kept on the TV for offline starts.
 */
object Remote {

    /** Features the admin page can switch off for everyone. */
    val FEATURES = setOf("bell", "calendar", "mood", "history", "genres", "sound", "surprise", "weekend", "night")

    /** [from] is "yyyy-MM-ddTHH:mm" (TV's local time) or empty: shown from then on. [until] is a day, inclusive. */
    /** [below]: only TVs on an older build than this see it (0 = every TV). */
    class Message(val id: String, val title: String, val text: String, val until: String, val from: String = "", val below: Int = 0)

    /** A film the owner puts first on every TV's home screen, with an optional line of their own. */
    /** [more]: further featured films after the first, in order (key, own line). Older builds read only the first. */
    class Pin(val key: String, val note: String, val until: String, val from: String = "", val more: List<Pair<String, String>> = emptyList()) {
        /** Every featured film in order: the first, then [more]. */
        val all: List<Pair<String, String>> get() = listOf(key to note) + more
    }

    @Volatile var message: Message? = null
        private set
    @Volatile var minVersion = 0
        private set
    @Volatile var off: Set<String> = emptySet()
        private set
    /** Highest build TVs may update to (0 = no limit), to stop a bad release spreading. */
    @Volatile var hold = 0
        private set
    /** Films hidden on every TV, as "movie:123" / "tv:456". */
    @Volatile var hidden: Set<String> = emptySet()
        private set
    @Volatile var pin: Pin? = null
        private set
    /** Content filter for every TV: no 18+ titles, and/or a lowest IMDb score (0 = none). */
    @Volatile var noAdult = false
        private set
    @Volatile var minImdb = 0.0
        private set
    /** Home screen rows switched off on every TV (ids from [Prefs.ROWS]). */
    @Volatile var rowsOff: Set<String> = emptySet()
        private set
    /** Settings a fresh install starts with, chosen on the admin page. */
    @Volatile private var defaults: JSONObject? = null

    fun rowOff(id: String) = id in rowsOff

    private val moment = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$")
    private fun now() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(Date())

    /** Inside its window: after [from] (if set) and not past [until] (if set). */
    private fun running(from: String, until: String): Boolean {
        val n = now()
        if (from.isNotEmpty() && n < from) return false
        return until.isEmpty() || n.substring(0, 10) <= until
    }

    /** The id used by [hidden] and [Pin] for a film. */
    fun keyOf(p: Pick) = (if (p.isSeries) "tv:" else "movie:") + p.id

    /** Hidden by the owner, or left out by the content filter. */
    fun isHidden(p: Pick): Boolean {
        if (p.id > 0 && keyOf(p) in hidden) return true
        if (noAdult && p.cert == "18+") return true
        return minImdb > 0 && p.imdb > 0 && p.imdb < minImdb
    }

    /** The featured film while it runs. */
    fun activePin(): Pin? = pin?.takeIf { running(it.from, it.until) }

    /** Changes when anything that alters the film lists changes. */
    fun filmSig() = hidden.sorted().joinToString(",") + "|" + (activePin()?.all?.joinToString(";") { it.first + it.second } ?: "") +
        "|" + noAdult + minImdb

    /** Everything the home screen shows from remote.json, so a change is noticed between refreshes. */
    fun sig() = (activeMessage()?.id ?: "") + "|" + minVersion + mustUpdate() + "|" + off.sorted() + "|" +
        rowsOff.sorted() + "|" + filmSig()

    private val filmKey = Regex("^(movie|tv):\\d{1,9}$")

    fun isOff(feature: String) = feature in off

    private val idText = Regex("^[A-Za-z0-9_.:-]{1,40}$")
    private val day = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** Reads a remote.json body; anything odd is ignored rather than trusted. */
    fun use(body: String?) {
        if (body.isNullOrBlank()) return
        val o = try { JSONObject(body) } catch (e: Exception) { return }
        val m = o.optJSONObject("message")
        message = m?.let {
            val id = it.optString("id")
            val title = it.optString("title").trim().take(80)
            val text = it.optString("text").trim().take(500)
            val until = it.optString("until").takeIf { u -> day.matches(u) } ?: ""
            val from = it.optString("from").takeIf { f -> moment.matches(f) } ?: ""
            val below = it.optInt("below", 0).coerceIn(0, 100000)
            if (idText.matches(id) && (title.isNotEmpty() || text.isNotEmpty())) Message(id, title, text, until, from, below) else null
        }
        minVersion = o.optInt("minVersion", 0).coerceIn(0, 100000)
        val a = o.optJSONArray("off")
        off = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.filter { it in FEATURES }.toSet()
        hold = o.optInt("hold", 0).coerceIn(0, 100000)
        val h = o.optJSONArray("hide")
        hidden = (0 until minOf(h?.length() ?: 0, 300)).mapNotNull { i ->
            // Each entry is {"k": "movie:123", "t": "Title"}; the title is only for the admin page.
            val k = h!!.optJSONObject(i)?.optString("k") ?: h.optString(i)
            k.takeIf { filmKey.matches(it) }
        }.toSet()
        pin = o.optJSONObject("pin")?.let {
            val k = it.optString("k")
            val until = it.optString("until").takeIf { u -> day.matches(u) } ?: ""
            val from = it.optString("from").takeIf { f -> moment.matches(f) } ?: ""
            val m = it.optJSONArray("more")
            val more = (0 until minOf(m?.length() ?: 0, 9)).mapNotNull { i ->
                val e = m!!.optJSONObject(i) ?: return@mapNotNull null
                val mk = e.optString("k")
                if (filmKey.matches(mk) && mk != k) mk to e.optString("note").trim().take(120) else null
            }.distinctBy { it.first }
            if (filmKey.matches(k)) Pin(k, it.optString("note").trim().take(120), until, from, more) else null
        }
        val f = o.optJSONObject("filter")
        noAdult = f?.optBoolean("adult", false) ?: false
        minImdb = (f?.optDouble("minImdb", 0.0) ?: 0.0).takeIf { it in 0.0..9.0 } ?: 0.0
        val ro = o.optJSONArray("rowsOff")
        rowsOff = (0 until (ro?.length() ?: 0)).map { ro!!.optString(it) }.filter { it in Prefs.ROWS.keys }.toSet()
        defaults = o.optJSONObject("defaults")
    }

    /** The message while it is still running (no end date, or the end date not passed). */
    fun activeMessage(): Message? = message?.takeIf { m ->
        val build = BuildConfig.BUILD_NO.toIntOrNull() ?: 0
        running(m.from, m.until) && (m.below <= 0 || build in 1 until m.below)
    }

    /**
     * A fresh install (first two days) takes the owner's chosen settings once. TVs that were set up
     * before are only marked, so nobody's own choices are overwritten. Only known values are used.
     */
    private fun applyDefaults(ctx: Context) {
        val prefs = Prefs(ctx)
        if (prefs.defaultsDone) return
        val d = defaults
        val fresh = try {
            System.currentTimeMillis() - ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime < 2 * 86400000L
        } catch (e: Exception) {
            false
        }
        if (fresh && d != null) {
            fun int(k: String, ok: List<Int>, set: (Int) -> Unit) { if (d.has(k)) d.optInt(k, -99).takeIf { it in ok }?.let(set) }
            fun str(k: String, ok: List<String>, set: (String) -> Unit) { if (d.has(k)) d.optString(k).takeIf { it in ok }?.let(set) }
            fun bool(k: String, set: (Boolean) -> Unit) { if (d.has(k)) set(d.optBoolean(k)) }
            int("saverMin", listOf(1, 2, 5, 10, 0)) { prefs.saverMin = it }
            str("saverContent", listOf("mix", "cinema", "platform", "history", "picks")) { prefs.saverContent = it }
            int("saverSec", listOf(10, 20, 30, 60)) { prefs.saverSec = it }
            str("appSort", listOf("manual", "recent", "time")) { prefs.appSort = it }
            bool("ambience") { prefs.ambience = it }
            bool("navSound") { prefs.navSound = it }
            bool("weather") { prefs.weather = it }
            bool("autoUpdate") { prefs.autoUpdate = it }
            bool("lite") { prefs.lite = it }
        }
        // Once the settings have been read (or there were none), a TV is never changed again.
        prefs.defaultsDone = true
    }

    /** This LeXia is one person's own: nothing is counted or reported. Kept so callers stay the same. */
    fun ping(ctx: Context) {}

    /** Crashes stay on the TV (Ayarlar › Hakkında › Son hata); nothing is sent. */
    fun sendCrash(ctx: Context) {}

    /** True when this build is older than the lowest allowed version (the setup build never is). */
    fun mustUpdate(): Boolean {
        if (BuildConfig.SETUP) return false
        val build = BuildConfig.BUILD_NO.toIntOrNull() ?: return false
        return build in 1 until minVersion
    }

    private var loaded = false

    /**
     * This LeXia is one person's own: there are no remote settings (no announcements, no forced
     * update, no held builds, nothing switched off from afar). Everything stays at its default.
     * Kept as calls so the screens that ask stay the same.
     */
    fun loadSaved(ctx: Context) {
        if (loaded) return
        loaded = true
    }

    fun refresh(ctx: Context) {
        loadSaved(ctx)
    }
}
