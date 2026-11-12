package tv.lexia.launcher

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Next-episode dates of series, from the "episodes" list in pick.json. */
object Episodes {
    /** [kind]: "finale" (last of the season), "premiere" (a new season starts) or "". */
    class Next(val date: String, val season: Int, val episode: Int, val kind: String = "")

    /** One series with its coming episode, for the calendar. */
    class Show(val id: Int, val title: String, val backdrop: String, val next: Next, val info: Pick? = null)

    /**
     * The full record of each series in today's list [o] (and in Listem), by id and by picture, so a
     * series known only from the episode calendar still opens a full page: its own "info" is the last resort.
     */
    fun finder(o: JSONObject, listed: List<Pick>): (Show) -> Pick {
        val byId = HashMap<Int, Pick>()
        val byBack = HashMap<String, Pick>()
        fun take(p: Pick) {
            if (!p.isSeries) return
            if (p.id > 0) byId.getOrPut(p.id) { p }
            if (p.backdrop.isNotEmpty()) byBack.getOrPut(p.backdrop) { p }
        }
        for (name in listOf("top10tv", "platforms", "local", "digital")) {
            val a = o.optJSONArray(name) ?: continue
            for (i in 0 until a.length()) try {
                take(Pick.from(a.getJSONObject(i)))
            } catch (e: Exception) {
            }
        }
        for (p in listed) take(p)
        return { s ->
            byId[s.id] ?: byBack[s.backdrop] ?: s.info ?: Pick.from(
                JSONObject().put("title", s.title).put("backdrop", s.backdrop).put("type", "tv").put("id", s.id)
            )
        }
    }

    private val shows = ArrayList<Show>()

    private val tr = Locale("tr", "TR")
    private val day = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val byId = HashMap<Int, Next>()
    private val byBackdrop = HashMap<String, Next>()

    /** Changes whenever new dates are loaded, so cards can redraw. */
    @Volatile var version = 0
        private set

    fun load(o: JSONObject) {
        val a = o.optJSONArray("episodes") ?: return
        val ids = HashMap<Int, Next>()
        val backs = HashMap<String, Next>()
        val list = ArrayList<Show>()
        val image = Regex("^/[A-Za-z0-9_-]+\\.(jpg|jpeg|png)$")
        for (i in 0 until a.length()) {
            val e = a.optJSONObject(i) ?: continue
            val n = e.optJSONObject("next") ?: continue
            val date = n.optString("date")
            if (!day.matches(date)) continue
            val next = Next(date, n.optInt("season"), n.optInt("episode"), n.optString("kind").takeIf { it == "finale" || it == "premiere" } ?: "")
            val id = e.optInt("id")
            if (id > 0) ids[id] = next
            val back = e.optString("backdrop").takeIf { image.matches(it) } ?: ""
            if (back.isNotEmpty()) backs[back] = next
            val title = e.optString("title")
            val info = try {
                e.optJSONObject("info")?.let { Pick.from(it) }?.takeIf { it.isSeries && it.title.isNotEmpty() }
            } catch (x: Exception) {
                null
            }
            if (title.isNotEmpty()) list.add(Show(id, title, back, next, info))
        }
        synchronized(this) {
            byId.clear(); byId.putAll(ids)
            byBackdrop.clear(); byBackdrop.putAll(backs)
            shows.clear(); shows.addAll(list)
            version++
        }
    }

    /** The coming episode of [p], or null if none is announced or it is already out. */
    fun of(p: Pick): Next? {
        if (!p.isSeries) return null
        val n = synchronized(this) { (if (p.id > 0) byId[p.id] else null) ?: byBackdrop[p.backdrop] } ?: return null
        val d = daysUntil(n.date) ?: return null
        return if (d >= 0) n else null
    }

    /** Series whose next episode comes within [days] days, soonest first. */
    fun upcoming(days: Int): List<Show> = synchronized(this) {
        shows.filter { s -> daysUntil(s.next.date)?.let { it in 0..days } == true }.sortedBy { it.next.date }
    }

    fun daysUntil(date: String): Int? = try {
        val c = Calendar.getInstance()
        c.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!
        val today = Calendar.getInstance()
        for (x in listOf(c, today)) {
            x.set(Calendar.HOUR_OF_DAY, 12); x.set(Calendar.MINUTE, 0); x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0)
        }
        ((c.timeInMillis - today.timeInMillis) / 86400000L).toInt()
    } catch (e: Exception) {
        null
    }

    /** "Yeni bölüm", "Sezon finali" or "Yeni sezon". */
    fun what(n: Next): String = when (n.kind) {
        "finale" -> "Sezon finali"
        "premiere" -> "Yeni sezon"
        else -> "Yeni bölüm"
    }

    /** Short badge for a poster: "Bugün sezon finali", "Yeni bölüm · Cuma", "Yeni sezon · 14 Kas". */
    fun badge(n: Next): String {
        val d = daysUntil(n.date) ?: return ""
        val w = what(n)
        return when {
            d == 0 -> "Bugün " + w.lowercase(tr)
            d == 1 -> "Yarın " + w.lowercase(tr)
            d < 7 -> "$w · " + dayName(n.date, "EEEE")
            else -> "$w · " + dayName(n.date, "d MMM")
        }
    }

    /** Full line for the detail page: "Yeni bölüm: Cuma, 14 Kasım  ·  2. sezon 5. bölüm". */
    fun line(n: Next): String {
        val d = daysUntil(n.date) ?: return ""
        val whenText = when (d) {
            0 -> "bugün"
            1 -> "yarın, " + dayName(n.date, "d MMMM")
            else -> dayName(n.date, "EEEE, d MMMM")
        }
        val which = when {
            n.kind == "premiere" && n.season > 0 -> "  ·  ${n.season}. sezon başlıyor"
            n.season > 0 && n.episode > 0 -> "  ·  ${n.season}. sezon ${n.episode}. bölüm"
            else -> ""
        }
        return "${what(n)}: $whenText$which"
    }

    private fun dayName(date: String, pattern: String): String = try {
        SimpleDateFormat(pattern, tr).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!)
    } catch (e: Exception) {
        ""
    }
}
