package tv.lexia.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Launcher settings, stored on the device. */
class Prefs(ctx: Context) {
    companion object {
        /** 0 morning (5–12), 1 afternoon (12–17), 2 evening (17–22), 3 night. */
        fun daySlot(hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): Int = when (hour) {
            in 5..11 -> 0
            in 12..16 -> 1
            in 17..21 -> 2
            else -> 3
        }

        /** Every genre the film list knows, in Turkish (the names TMDB gives). */
        val GENRES = listOf(
            "Aksiyon", "Macera", "Animasyon", "Komedi", "Suç", "Belgesel", "Dram", "Aile",
            "Fantastik", "Tarih", "Korku", "Müzik", "Gizem", "Romantik", "Bilim-Kurgu", "Gerilim", "Savaş", "Vahşi Batı"
        )

        /** Rows below the apps: id to name, in their default order. */
        val ROWS = linkedMapOf(
            "top" to "Top 10 film",
            "tv" to "Top 10 dizi",
            "cinema" to "Şu an sinemalarda",
            "soon" to "Yakında vizyonda",
            "g1" to "Sana göre 1. tür",
            "g2" to "Sana göre 2. tür",
            "g3" to "Sana göre 3. tür",
            "local" to "Yerli yapımlar",
            "like" to "Beğendiklerine benzer",
            "recent" to "Son baktıkların",
            "list" to "Listem"
        )
    }

    private val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private fun bool(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    private fun int(k: String, d: Int) = p.getInt(k, d)
    private fun putInt(k: String, v: Int) = p.edit().putInt(k, v).apply()
    private fun str(k: String) = p.getString(k, "") ?: ""
    private fun putStr(k: String, v: String) = p.edit().putString(k, v).apply()

    // Films
    var showPicks: Boolean get() = bool("picks", true); set(v) = putBool("picks", v)
    var autoRotate: Boolean get() = bool("rotate", true); set(v) = putBool("rotate", v)
    /** Seconds between films. */
    var rotateSec: Int get() = int("rotateSec", 15); set(v) = putInt("rotateSec", v)
    /** Preferred genres; empty means all. */
    var genres: Set<String>
        get() = HashSet(p.getStringSet("genres", emptySet()) ?: emptySet())
        set(v) = p.edit().putStringSet("genres", HashSet(v)).apply()

    // Look
    /** "film" shows film pictures, "plain" a calm gradient. */
    var background: String get() = p.getString("bg", "film") ?: "film"; set(v) = putStr("bg", v)
    /** 0 small, 1 medium, 2 large. */
    var cardSize: Int get() = int("card", 1); set(v) = putInt("card", v)
    var greet: Boolean get() = bool("greet", true); set(v) = putBool("greet", v)
    var name: String get() = str("name"); set(v) = putStr("name", v)
    var clock24: Boolean get() = bool("clock24", true); set(v) = putBool("clock24", v)
    var weather: Boolean get() = bool("weather", true); set(v) = putBool("weather", v)
    /** Faint rain or snow over the picture when it rains or snows outside. */
    var ambience: Boolean get() = bool("ambience", true); set(v) = putBool("ambience", v)
    /** "Şu an sinemalarda" and "Yakında vizyonda" rows. */
    var cinema: Boolean get() = bool("cinema", true); set(v) = putBool("cinema", v)
    /** Chosen city; empty means detect from the connection. */
    var city: String get() = str("city"); set(v) = putStr("city", v)
    var cityLat: Float get() = p.getFloat("lat", 0f); set(v) = p.edit().putFloat("lat", v).apply()
    var cityLon: Float get() = p.getFloat("lon", 0f); set(v) = p.edit().putFloat("lon", v).apply()

    // Screensaver: minutes, 0 = off
    var saverMin: Int get() = int("saverMin", 5); set(v) = putInt("saverMin", v)
    /** What the screensaver shows: "cinema" (in cinemas + coming soon) or "picks" (today's films). */
    var saverContent: String get() = p.getString("saverContent", "mix") ?: "mix"; set(v) = putStr("saverContent", v)
    /**
     * Minutes the film screensaver keeps the screen on before the TV's own saver and sleep take over;
     * 0 = leave it to the TV, -1 = until a remote key is pressed.
     */
    var saverHoldMin: Int get() = int("saverHold", 60); set(v) = putInt("saverHold", v)
    /** Screensaver clock: 0 hidden, 1 normal, 2 large. */
    var saverClock: Int get() = int("saverClock", 1); set(v) = putInt("saverClock", v)
    /** Weather next to the screensaver clock. */
    var saverWeather: Boolean get() = bool("saverWeather", true); set(v) = putBool("saverWeather", v)
    /** Seconds between films in the screensaver. */
    var saverSec: Int get() = int("saverSec", 20); set(v) = putInt("saverSec", v)
    /** Nightly sleep time as minutes after midnight; -1 = off. */
    var nightSleep: Int get() = int("nightSleep", -1); set(v) = putInt("nightSleep", v)

    // Feel
    /** Soft tick when moving with the remote. */
    var navSound: Boolean get() = bool("navSound", true); set(v) = putBool("navSound", v)
    /** Sounds: "soft" (soft tick + open, back and add sounds), "classic" (the old tick + the same sounds). Off is [navSound] false. */
    var soundSet: String get() = p.getString("soundSet", "soft") ?: "soft"; set(v) = putStr("soundSet", v)
    /** Light mode: no blur, tilt, light sweep, Ken Burns or rain/snow, for slower TVs. */
    var lite: Boolean get() = bool("lite", false); set(v) = putBool("lite", v)
    /** The light-mode suggestion for TVs with little memory was shown (once). */
    var liteAsked: Boolean get() = bool("liteAsked", false); set(v) = putBool("liteAsked", v)

    // Apps
    var hidden: Set<String>
        get() = HashSet(p.getStringSet("hidden", emptySet()) ?: emptySet())
        set(v) = p.edit().putStringSet("hidden", HashSet(v)).apply()
    // "recent" was this switch long before the "Son baktıkların" row; test builds 188–196 wrote the
    // film list under the same name, which made this read crash. Read it only if it is still a switch.
    var recentFirst: Boolean get() = p.all["recent"] as? Boolean ?: false; set(v) = putBool("recent", v)
    /** App shelf order: "manual" (yours), "recent" (last opened first), "time" (what you open at this hour). */
    var appSort: String
        get() = p.getString("appSort", null) ?: if (recentFirst) "recent" else "manual"
        set(v) = putStr("appSort", v)

    /** Opens per app in each part of the day: key to counts for morning, afternoon, evening, night. */
    fun usage(): Map<String, IntArray> = try {
        val o = JSONObject(p.getString("usage", "{}") ?: "{}")
        o.keys().asSequence().associateWith { k ->
            val a = o.getJSONArray(k)
            IntArray(4) { a.optInt(it) }
        }
    } catch (e: Exception) {
        emptyMap()
    }
    /** App key opened when the TV starts; empty = none. */
    var startApp: String get() = str("startApp"); set(v) = putStr("startApp", v)
    var lastBoot: Long get() = p.getLong("lastBoot", 0); set(v) = p.edit().putLong("lastBoot", v).apply()
    var order: List<String>
        get() = str("order").split("\n").filter { it.isNotEmpty() }
        set(v) = putStr("order", v.joinToString("\n"))

    /** Order of the rows below the apps; unknown ids dropped, new ones added at the end. */
    var rowOrder: List<String>
        get() {
            val list = str("rowOrder").split(",").filter { it in ROWS }.distinct().toMutableList()
            // Rows added in a later version go where they sit by default (before Listem), not last.
            val all = ROWS.keys.toList()
            for ((i, id) in all.withIndex()) {
                if (id in list) continue
                val after = all.drop(i + 1).firstOrNull { it in list }
                if (after == null) list.add(id) else list.add(list.indexOf(after), id)
            }
            return list
        }
        set(v) = putStr("rowOrder", v.joinToString(","))

    var hiddenRows: Set<String>
        get() = HashSet(p.getStringSet("hiddenRows", emptySet()) ?: emptySet())
        set(v) = p.edit().putStringSet("hiddenRows", HashSet(v)).apply()

    fun toggleRow(id: String) {
        val h = hiddenRows.toMutableSet()
        if (!h.remove(id)) h.add(id)
        hiddenRows = h
    }

    /** Moves row [id] by [d] places (negative = up). */
    fun moveRow(id: String, d: Int) {
        val o = rowOrder.toMutableList()
        val i = o.indexOf(id)
        val n = i + d
        if (i < 0 || n < 0 || n >= o.size) return
        o.removeAt(i)
        o.add(n, id)
        rowOrder = o
    }

    /** Apps on the home screen; null until [Apps.favorites] sets them up from the TV apps. */
    var favorites: Set<String>?
        get() = if (p.contains("fav")) HashSet(p.getStringSet("fav", emptySet()) ?: emptySet()) else null
        set(v) = if (v == null) p.edit().remove("fav").apply() else p.edit().putStringSet("fav", HashSet(v)).apply()
    /** TV apps already seen, so a newly installed one is added to the favourites once. */
    var knownTv: Set<String>
        get() = HashSet(p.getStringSet("knownTv", emptySet()) ?: emptySet())
        set(v) = p.edit().putStringSet("knownTv", HashSet(v)).apply()

    fun toggleFavorite(key: String) {
        val f = (favorites ?: emptySet()).toMutableSet()
        if (!f.remove(key)) f.add(key)
        favorites = f
    }

    /** Apps in a panel at the left edge instead of a row under the film. */
    var appsSide: Boolean get() = bool("appsSide", false); set(v) = putBool("appsSide", v)
    /** Open LeXia when the TV starts, for boxes that start their own home screen. */
    var bootOpen: Boolean get() = bool("bootOpen", true); set(v) = putBool("bootOpen", v)
    /**
     * Test TV: gets every new build at once, before the owner releases it to everyone (admin page ›
     * Yayın). Switched on by pressing OK seven times on Ayarlar › Hakkında › Yüklü sürüm.
     */
    var tester: Boolean get() = bool("tester", false); set(v) = putBool("tester", v)
    /** Trailers play inside LeXia (YouTube's embedded player) instead of the YouTube app. */
    var trailerInApp: Boolean get() = bool("trailerInApp", true); set(v) = putBool("trailerInApp", v)
    /** Trailers play in LeXia's own player, without YouTube's page; the embedded player is the fallback. */
    var trailerNative: Boolean get() = bool("trailerNative", true); set(v) = putBool("trailerNative", v)
    /** Sound to start a film with: "" the TV's language, "orig" whatever the file puts first, or a language code. */
    var playAudioLang: String get() = str("playAudioLang"); set(v) = putStr("playAudioLang", v)
    /** Subtitles to start a film with: "" the TV's language, "off" none, or a language code. */
    var playSubLang: String get() = str("playSubLang"); set(v) = putStr("playSubLang", v)
    /** No subtitles when the sound is already in the subtitle language. */
    var playSubSmart: Boolean get() = bool("playSubSmart", true); set(v) = putBool("playSubSmart", v)
    /** "Oynat" starts the most fitting source by itself; the source list is one Back away. */
    /** Ayarlar › Oynatma: the Nuvio provider asked first, and played without a list ("" = none). */
    var favSource: String get() = str("favSource"); set(v) = putStr("favSource", v)
    var favSourceName: String get() = str("favSourceName"); set(v) = putStr("favSourceName", v)
    var playAutoSource: Boolean get() = bool("playAutoSource", true); set(v) = putBool("playAutoSource", v)
    /** Subtitle size in the player: 0 small, 1 normal, 2 large, 3 very large. */
    var playSubSize: Int get() = int("playSubSize", 1); set(v) = putInt("playSubSize", v)
    /** Counts starts, so each start shows a different film first. */
    var heroTurn: Int get() = int("heroTurn", 0); set(v) = putInt("heroTurn", v)
    /** App cards: "card" rounded banner, "sharp" square-cornered banner, "circle" / "square" the app's icon. */
    var appShape: String get() = p.getString("appShape", "card") ?: "card"; set(v) = putStr("appShape", v)
    /** Films behind the home screen: "picks" today's films, "cinema" in cinemas now, "platform" new on streaming, "mix". */
    /** Default "mix": today's films, cinemas and new on streaming in turn, so everyone sees what LeXia has. */
    var heroSource: String get() = p.getString("heroSource", "mix") ?: "mix"; set(v) = putStr("heroSource", v)
    /** Streaming platforms to show under "Platformlarda yeni"; empty = all of them (installed apps first). */
    var platforms: Set<String>
        get() = HashSet(p.getStringSet("platforms", emptySet()) ?: emptySet())
        set(v) = p.edit().putStringSet("platforms", HashSet(v)).apply()
    /** The build whose "Yenilikler" card was already liked or disliked. */
    var votedBuild: Int get() = int("votedBuild", 0); set(v) = putInt("votedBuild", v)
    /** Larger text everywhere, for reading from across the room. */
    var bigText: Boolean get() = bool("bigText", false); set(v) = putBool("bigText", v)
    /** Glass behind panels and menus: 0 dark, 1 medium, 2 see-through. */
    var glass: Int get() = int("glass", 1); set(v) = putInt("glass", v)
    /** The genre step of the first-run guide was shown. */
    var tasteAsked: Boolean get() = bool("tasteAsked", false); set(v) = putBool("tasteAsked", v)
    /** Days ("yyyy-MM-dd", comma separated) the "Ana Sayfa tuşunu ayarla" button was shown; it stops after five. */
    var homeNudgeDays: String get() = str("homeNudgeDays"); set(v) = putStr("homeNudgeDays", v)
    /** The owner turned that reminder off. */
    /** Film gecesi: {"at": time, "film": Pick JSON}, or "" when none is planned. */
    var movieNight: String get() = str("movieNight"); set(v) = putStr("movieNight", v)
    /** The planned time whose card was already shown. */
    var movieNightShown: Long get() = p.getLong("movieNightShown", 0L); set(v) = p.edit().putLong("movieNightShown", v).apply()
    /** The week ("yyyy-ww") whose weekend guide opened by itself. */
    var weekendSeen: String get() = str("weekendSeen"); set(v) = putStr("weekendSeen", v)
    /** The morning card ("Günaydın") after the first start of the day. */
    var morningCard: Boolean get() = bool("morningCard", true); set(v) = putBool("morningCard", v)
    /** Day ("yyyy-MM-dd") the morning card was shown. */
    var morningSeen: String get() = str("morningSeen"); set(v) = putStr("morningSeen", v)
    /** Week ("yyyy-ww") the Listem reminder was given. */
    var listNudgeWeek: String get() = str("listNudgeWeek"); set(v) = putStr("listNudgeWeek", v)
    /** Films already announced as "artık Netflix'te", as "key|platform". */
    var nowStreaming: Set<String>
        get() = HashSet(p.getStringSet("nowStreaming", emptySet()) ?: emptySet())
        set(v) = p.edit().putStringSet("nowStreaming", HashSet(v)).apply()
    /** Platform badges on app cards already opened today, as "yyyy-MM-dd|platform". */
    var platformSeen: Set<String>
        get() = HashSet(p.getStringSet("platformSeen", emptySet()) ?: emptySet())
        set(v) = p.edit().putStringSet("platformSeen", HashSet(v)).apply()
    /** When each Listem film was added (key to time); films added by older versions have none. */
    var listAdded: Map<String, Long>
        get() = try {
            val o = JSONObject(p.getString("listAdded", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getLong(it) }
        } catch (e: Exception) {
            emptyMap()
        }
        set(v) {
            val o = JSONObject()
            for ((k, t) in v) o.put(k, t)
            putStr("listAdded", o.toString())
        }
    /** Seconds where each trailer was left (YouTube key to seconds), to carry on from there. */
    var trailerAt: Map<String, Int>
        get() = try {
            val o = JSONObject(p.getString("trailerAt", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getInt(it) }
        } catch (e: Exception) {
            emptyMap()
        }
        set(v) {
            val o = JSONObject()
            // Only the last 30 trailers are remembered.
            for ((k, t) in v.entries.toList().takeLast(30)) o.put(k, t)
            putStr("trailerAt", o.toString())
        }
    /** A fresh install already looked for a backup to bring back (see [Backup.sync]). */
    var backupChecked: Boolean get() = bool("backupChecked", false); set(v) = putBool("backupChecked", v)
    var homeNudgeOff: Boolean get() = bool("homeNudgeOff", false); set(v) = putBool("homeNudgeOff", v)

    /** Last time each app was opened. */
    fun usedAt(): Map<String, Long> = try {
        val o = JSONObject(p.getString("used", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getLong(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    fun markUsed(key: String) {
        val o = try { JSONObject(p.getString("used", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        o.put(key, System.currentTimeMillis())
        putStr("used", o.toString())
        // Count this open in the current part of the day, for the time-based order.
        val u = try { JSONObject(p.getString("usage", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val a = u.optJSONArray(key) ?: JSONArray(listOf(0, 0, 0, 0))
        val slot = daySlot()
        a.put(slot, a.optInt(slot) + 1)
        u.put(key, a)
        putStr("usage", u.toString())
    }

    // Safety and updates
    var pin: String get() = str("pin"); set(v) = putStr("pin", v)
    var autoUpdate: Boolean get() = bool("autoUpdate", false); set(v) = putBool("autoUpdate", v)

    // Info
    var pickDate: String get() = str("pickDate"); set(v) = putStr("pickDate", v)

    var watchlist: List<Pick>
        get() = try {
            val a = JSONArray(p.getString("watch", "[]"))
            (0 until a.length()).map { Pick.from(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) {
            putStr("watch", JSONArray(v.map { it.toJson() }).toString())
            // Remember when each film came in, for "Listende 3 haftadır bekliyor".
            val old = listAdded
            val now = System.currentTimeMillis()
            listAdded = v.associate { it.key to (old[it.key] ?: now) }
        }

    /** Film pages opened lately, newest first (at most 12), for "Son baktıkların". */
    var recent: List<Pick>
        get() = try {
            // Moved from "recent" (see recentFirst) on first read.
            (p.all["recent"] as? String)?.let { p.edit().putString("recentFilms", it).remove("recent").apply() }
            val a = JSONArray((p.all["recentFilms"] as? String) ?: "[]")
            (0 until a.length()).map { Pick.from(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) = putStr("recentFilms", JSONArray(v.take(12).map { it.toJson() }).toString())

    fun addRecent(film: Pick) {
        recent = listOf(film) + recent.filter { it.key != film.key }
    }

    /** Films to remind about on their Turkish release day. */
    var reminders: List<Pick>
        get() = try {
            val a = JSONArray(p.getString("remind", "[]"))
            (0 until a.length()).map { Pick.from(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) = putStr("remind", JSONArray(v.map { it.toJson() }).toString())

    /** First-run guide: started once (skips the welcome), done when finished. */
    var setupStarted: Boolean get() = bool("setupStarted", false); set(v) = putBool("setupStarted", v)
    var setupDone: Boolean get() = bool("setupDone", false); set(v) = putBool("setupDone", v)

    /** Last build whose "what's new" card was shown; 0 on a fresh install. */
    var seenBuild: Int get() = int("seenBuild", 0); set(v) = putInt("seenBuild", v)
    /** Fingerprint of the last "what's new" list shown, so the same list is not shown twice. */
    var seenNews: String get() = str("seenNews"); set(v) = putStr("seenNews", v)
    /** Day this TV last said "I'm on" to the site (yyyy-MM-dd), so it counts once a day. */
    var pingDay: String get() = str("pingDay"); set(v) = putStr("pingDay", v)
    /** Week ("2026-W46") and month ("2026-11") this TV was last counted in. */
    var pingWeek: String get() = str("pingWeek"); set(v) = putStr("pingWeek", v)
    var pingMonth: String get() = str("pingMonth"); set(v) = putStr("pingMonth", v)
    /** The setup build already said it was opened. */
    var pingSetup: Boolean get() = bool("pingSetup", false); set(v) = putBool("pingSetup", v)
    /** The owner's starting settings were looked at once (applied only on a fresh install). */
    var defaultsDone: Boolean get() = bool("defaultsDone", false); set(v) = putBool("defaultsDone", v)
    /** First line of the last crash already sent to the site, so each crash is sent once. */
    var crashSent: String get() = str("crashSent"); set(v) = putStr("crashSent", v)
    /** New-episode notices already opened, as "id:date". */
    var episodesSeen: Set<String>
        get() = HashSet(p.getStringSet("epSeen", emptySet()) ?: emptySet())
        set(v) = p.edit().putStringSet("epSeen", HashSet(v)).apply()

    /** Films marked "İzledim" (by [Pick.key]); kept out of suggestions. */
    var watched: Set<String>
        get() = HashSet(p.getStringSet("watched", emptySet()) ?: emptySet())
        set(v) = p.edit().putStringSet("watched", HashSet(v)).apply()

    /** Films marked "Beğendim": key to genres, so similar films can come first. */
    var liked: Map<String, List<String>>
        get() = try {
            val o = JSONObject(p.getString("liked", "{}") ?: "{}")
            o.keys().asSequence().associateWith { k ->
                val a = o.getJSONArray(k)
                (0 until a.length()).map { a.getString(it) }
            }
        } catch (e: Exception) {
            emptyMap()
        }
        set(v) {
            val o = JSONObject()
            for ((k, g) in v) o.put(k, JSONArray(g))
            putStr("liked", o.toString())
        }

    /** Films marked "İlgimi çekmiyor": key to genres; never suggested again, their genres move back. */
    var disliked: Map<String, List<String>>
        get() = try {
            val o = JSONObject(p.getString("disliked", "{}") ?: "{}")
            o.keys().asSequence().associateWith { k ->
                val a = o.getJSONArray(k)
                (0 until a.length()).map { a.getString(it) }
            }
        } catch (e: Exception) {
            emptyMap()
        }
        set(v) {
            val o = JSONObject()
            for ((k, g) in v) o.put(k, JSONArray(g))
            putStr("disliked", o.toString())
        }

    /** Genres turned down more often than liked; they come last in suggestions. */
    fun dislikedGenres(): Set<String> {
        val down = disliked.values.flatten().groupingBy { it }.eachCount()
        val up = liked.values.flatten().groupingBy { it }.eachCount()
        return down.filter { (g, n) -> n > (up[g] ?: 0) }.keys
    }

    /** How often each genre appears among liked films, most liked first. */
    fun likedGenres(): List<String> =
        liked.values.flatten().groupingBy { it }.eachCount().entries
            .sortedWith(compareBy({ -it.value }, { it.key })).map { it.key }

    /** The owner's message already shown as a card (its id). */
    var remoteSeen: String get() = str("remoteSeen"); set(v) = putStr("remoteSeen", v)

    /** Notices already seen in the bell panel. */
    var noticesRead: Set<String>
        get() = HashSet(p.getStringSet("noticesRead", emptySet()) ?: emptySet())
        set(v) = p.edit().putStringSet("noticesRead", HashSet(v)).apply()

    /** Puts the given settings back to their defaults. */
    fun resetKeys(keys: List<String>) {
        val e = p.edit()
        for (k in keys) e.remove(k)
        e.apply()
    }

    fun reset() = p.edit().clear().apply()
}
