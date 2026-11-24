package tv.lexia.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Hafta sonu rehberi: what is new this week, page by page like a story. In cinemas, new on the
 * platforms, new episodes over the weekend and LeXia's pick. Opens by itself once a week, on the
 * first visit from Friday evening, and any time from the bell.
 */
class WeekendActivity : Activity() {

    companion object {
        /** The week, Monday to Sunday, so Friday to Sunday share one key. */
        private fun weekKey(): String {
            val c = Calendar.getInstance()
            c.firstDayOfWeek = Calendar.MONDAY
            c.minimalDaysInFirstWeek = 4
            return "${c.getWeekYear()}-${c.get(Calendar.WEEK_OF_YEAR)}"
        }

        /** Friday from 17:00, Saturday or Sunday, and not yet shown this week. */
        fun due(ctx: Context): Boolean {
            val c = Calendar.getInstance()
            val d = c.get(Calendar.DAY_OF_WEEK)
            val weekend = (d == Calendar.FRIDAY && c.get(Calendar.HOUR_OF_DAY) >= 17) || d == Calendar.SATURDAY || d == Calendar.SUNDAY
            return weekend && Prefs(ctx).weekendSeen != weekKey() && !Remote.isOff("weekend")
        }

        /** The weekend plan's days, in order, with their names. */
        private val planDays = listOf("fri" to "Cuma akşamı", "sat" to "Cumartesi", "sun" to "Pazar")

        /** This week's plan: day ("fri", "sat", "sun") to film; an older week's plan counts as empty. */
        internal fun plan(ctx: Context): Map<String, Pick> {
            val o = try { JSONObject(Prefs(ctx).weekendPlan) } catch (e: Exception) { return emptyMap() }
            if (o.optString("week") != weekKey()) return emptyMap()
            val days = o.optJSONObject("days") ?: return emptyMap()
            return planDays.mapNotNull { (d, _) -> days.optJSONObject(d)?.let { j -> d to Pick.from(j) } }.toMap()
        }

        private fun savePlan(ctx: Context, plan: Map<String, Pick>, told: String = "") {
            val days = JSONObject()
            for ((d, p) in plan) days.put(d, p.toJson())
            Prefs(ctx).weekendPlan = JSONObject().put("week", weekKey()).put("days", days).put("told", told).toString()
        }

        /**
         * Today's film from the weekend plan, once a day (the home screen says it when it opens):
         * null when there is none or it was already said today.
         */
        fun todayOnce(ctx: Context): String? {
            val day = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
                Calendar.FRIDAY -> "fri"
                Calendar.SATURDAY -> "sat"
                Calendar.SUNDAY -> "sun"
                else -> return null
            }
            val plan = plan(ctx)
            val film = plan[day] ?: return null
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
            val told = try { JSONObject(Prefs(ctx).weekendPlan).optString("told") } catch (e: Exception) { "" }
            if (told == today) return null
            savePlan(ctx, plan, today)
            return film.title
        }

        fun open(a: Activity) {
            Prefs(a).weekendSeen = weekKey()
            a.startActivity(Intent(a, WeekendActivity::class.java))
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private class Item(val film: Pick, val line: String)
    private class Section(val label: String, val title: String, val items: List<Item>, val cover: Boolean = false)

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private val tr = Locale("tr", "TR")
    private var sections: List<Section> = emptyList()
    private var page = 0
    private var shown: Item? = null

    private lateinit var backA: ImageView
    private lateinit var backB: ImageView
    private lateinit var segments: LinearLayout
    private lateinit var label: TextView
    private lateinit var heading: TextView
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var line: TextView
    private lateinit var about: TextView
    private lateinit var listBtn: TextView
    private lateinit var trailerBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var prevBtn: TextView
    private lateinit var posters: LinearLayout
    private lateinit var text: LinearLayout
    private var imageToken = 0

    /** The film's silent trailer behind the page, a few seconds after it is chosen. */
    private lateinit var trailerStage: FrameLayout
    private lateinit var trailer: HeroTrailer
    private val trailerSoon = Runnable { startTrailer() }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var logoView: ImageView
    private lateinit var badges: LinearLayout
    private lateinit var whereBadge: TextView
    private lateinit var imdbBadge: LinearLayout
    private lateinit var imdbScore: TextView
    private lateinit var certBadge: TextView
    private lateinit var cast: LinearLayout
    private lateinit var infoBtn: TextView
    private lateinit var planBtn: TextView
    private lateinit var familyBtn: TextView
    /** "Aileyle": only what suits everyone (general audience, 7+, family and animation). */
    private var family = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        sections = load()
        if (sections.isEmpty()) {
            Glass.toast(this, "Bu hafta için liste henüz hazır değil")
            finish()
            return
        }
        val m = ui.match
        val w = ui.wrap
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        backA = ImageView(this)
        backB = ImageView(this)
        for (b in listOf(backA, backB)) {
            b.scaleType = ImageView.ScaleType.CENTER_CROP
            b.alpha = 0f
            frame.addView(b, ui.fl(m, m))
        }
        trailerStage = FrameLayout(this)
        frame.addView(trailerStage, ui.fl(m, m))
        trailer = HeroTrailer(this, trailerStage)
        trailer.onShowing = { on -> if (on) for (b in listOf(backA, backB)) b.animate().alpha(0f).setDuration(900).start() }
        val side = View(this)
        side.background = ui.gradient(
            GradientDrawable.Orientation.LEFT_RIGHT,
            Color.argb(240, 0, 0, 0), Color.argb(170, 0, 0, 0), Color.argb(20, 0, 0, 0)
        )
        frame.addView(side, ui.fl(m, m))
        val low = View(this)
        low.background = ui.gradient(GradientDrawable.Orientation.BOTTOM_TOP, Color.argb(235, 0, 0, 0), Color.TRANSPARENT)
        frame.addView(low, ui.fl(m, ui.u(560), Gravity.BOTTOM))

        val page = ui.vbox()
        page.clipChildren = false
        page.clipToPadding = false
        page.setPadding(ui.u(110), ui.u(56), ui.u(110), ui.u(50))

        // Top: one segment per page, and the weekend's dates.
        val top = ui.hbox()
        top.gravity = Gravity.CENTER_VERTICAL
        segments = ui.hbox()
        top.addView(segments, ui.ll(ui.u(520), ui.u(6)))
        heading = ui.label("HAFTA SONU REHBERİ  ·  " + weekendDates(), C.TEXT2)
        top.addView(heading, ui.ll(0, w, left = 30, weight = 1f))
        familyBtn = ui.pill("", 52, 19)
        familyBtn.setOnClickListener {
            family = !family
            styleFamily()
            reload()
        }
        top.addView(familyBtn)
        val marathon = ui.pill("▶   Fragman maratonu", 52, 19)
        marathon.setOnClickListener {
            val films = sections.flatMap { it.items }.map { it.film }.filter { it.trailer.isNotEmpty() }.distinctBy { it.key }
            if (films.isEmpty()) Glass.toast(this, "Bu hafta fragman bulunamadı") else TrailerActivity.marathon(this, films)
        }
        top.addView(marathon)
        styleFamily()
        page.addView(top, ui.ll(m, w))

        text = ui.vbox()
        text.gravity = Gravity.BOTTOM
        label = ui.label("", Color.parseColor("#FFD9A8"))
        text.addView(label, ui.ll(w, w))
        logoView = ImageView(this)
        logoView.adjustViewBounds = true
        logoView.maxHeight = ui.u(150)
        logoView.maxWidth = ui.u(620)
        logoView.scaleType = ImageView.ScaleType.FIT_START
        logoView.visibility = View.GONE
        text.addView(logoView, ui.ll(w, w, top = 14))
        title = ui.text("", 68, C.TEXT, 800)
        title.letterSpacing = -0.02f
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        text.addView(title, ui.ll(ui.u(1100), w, top = 12))
        // Where it is (platform or cinema), IMDb and the age band, as small badges.
        badges = ui.hbox()
        badges.gravity = Gravity.CENTER_VERTICAL
        whereBadge = ui.whereBadge()
        badges.addView(whereBadge, ui.ll(w, w, right = 18))
        imdbBadge = ui.hbox()
        imdbBadge.gravity = Gravity.CENTER_VERTICAL
        val imdb = ui.text("IMDb", 15, Color.BLACK, 800)
        imdb.setPadding(ui.u(9), ui.u(3), ui.u(9), ui.u(3))
        imdb.background = ui.round(Color.parseColor("#F5C518"), D.BADGE)
        imdbBadge.addView(imdb)
        imdbScore = ui.text("", 21, C.TEXT, 600)
        imdbBadge.addView(imdbScore, ui.ll(w, w, left = 10))
        badges.addView(imdbBadge, ui.ll(w, w, right = 18))
        certBadge = ui.certBadge()
        badges.addView(certBadge, ui.ll(w, w, right = 18))
        meta = ui.text("", 21, C.TEXT2, 500)
        badges.addView(meta)
        text.addView(badges, ui.ll(w, w, top = 16))
        line = ui.text("", 24, C.TEXT, 600)
        text.addView(line, ui.ll(w, w, top = 10))
        about = ui.text("", 22, C.TEXT2, 400)
        about.maxLines = 2
        about.ellipsize = TextUtils.TruncateAt.END
        about.setLineSpacing(0f, 1.2f)
        text.addView(about, ui.ll(ui.u(980), w, top = 16))
        // The leading actors' faces, under the story.
        cast = ui.hbox()
        text.addView(cast, ui.ll(w, w, top = 22))
        page.addView(text, ui.ll(m, 0, weight = 1f, top = 40))

        val buttons = ui.hbox()
        buttons.clipChildren = false
        buttons.clipToPadding = false
        trailerBtn = ui.pill("▶   Fragman", 64, 23)
        trailerBtn.setOnClickListener { shown?.film?.let { if (it.trailer.isNotEmpty()) TrailerActivity.open(this, it) } }
        listBtn = ui.pill("", 64, 23)
        listBtn.setOnClickListener { toggleList() }
        val info = ui.pill("Detaylar", 64, 23)
        info.setOnClickListener { shown?.film?.let { DetailActivity.open(this, it) } }
        infoBtn = info
        planBtn = ui.pill("", 64, 23)
        planBtn.setOnClickListener { choosePlanDay() }
        prevBtn = ui.pill("‹   Önceki", 64, 23)
        prevBtn.setOnClickListener { go(this.page - 1) }
        nextBtn = ui.pill("", 64, 23, primary = true)
        nextBtn.setOnClickListener { if (this.page < sections.size - 1) go(this.page + 1) else finish() }
        for (b in listOf(nextBtn, trailerBtn, planBtn, listBtn, info, prevBtn)) buttons.addView(b)
        page.addView(buttons, ui.ll(m, w, top = 26))

        posters = ui.hbox()
        posters.clipChildren = false
        posters.clipToPadding = false
        posters.setPadding(ui.u(10), ui.u(26), ui.u(10), ui.u(20))
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.addView(posters)
        page.addView(scroll, ui.ll(m, w, top = 6, left = -10))

        frame.addView(page, ui.fl(m, m))
        setContentView(frame)
        Sound.attach(this)
        go(0)
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(D.SLOW).start()
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean =
        Pointer.handle(this, ev) || super.dispatchGenericMotionEvent(ev)

    override fun onResume() {
        super.onResume()
        updateListBtn()
        // Back on the page: the chosen film's trailer comes again after a breath.
        if (::trailer.isInitialized && sections.getOrNull(page)?.cover == false && !Fx.lite && prefs.heroTrailer) {
            handler.removeCallbacks(trailerSoon)
            handler.postDelayed(trailerSoon, 3_000L)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Media keys page through, like a story.
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (page < sections.size - 1) go(page + 1)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (page > 0) go(page - 1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** "14–16 Kasım" for this week's Friday to Sunday. */
    private fun weekendDates(): String {
        val c = Calendar.getInstance()
        while (c.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) c.add(Calendar.DAY_OF_YEAR, if (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) -1 else 1)
        val fri = c.clone() as Calendar
        c.add(Calendar.DAY_OF_YEAR, 2)
        val month = SimpleDateFormat("MMMM", tr)
        return if (fri.get(Calendar.MONTH) == c.get(Calendar.MONTH)) {
            "${fri.get(Calendar.DAY_OF_MONTH)}–${c.get(Calendar.DAY_OF_MONTH)} ${month.format(c.time)}".uppercase(tr)
        } else {
            "${fri.get(Calendar.DAY_OF_MONTH)} ${month.format(fri.time)} – ${c.get(Calendar.DAY_OF_MONTH)} ${month.format(c.time)}".uppercase(tr)
        }
    }

    private fun dayName(date: String): String = when (val d = Episodes.daysUntil(date)) {
        null -> ""
        0 -> "Bugün"
        1 -> "Yarın"
        -1 -> "Dün"
        else -> try {
            SimpleDateFormat("EEEE", tr).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!)
                .replaceFirstChar { it.titlecase(tr) }
        } catch (e: Exception) {
            if (d < 0) "" else "$d gün sonra"
        }
    }

    /** This week's pages, from the saved film list; empty pages are left out. */
    private fun load(): List<Section> {
        val o = try {
            JSONObject(java.io.File(filesDir, "pick.json").readText())
        } catch (e: Exception) {
            return emptyList()
        }
        Remote.loadSaved(this)
        fun list(name: String): List<Pick> {
            val a = o.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).map { Pick.from(a.getJSONObject(it)) }
                .filter { it.title.isNotEmpty() && it.backdrop.isNotEmpty() && !Remote.isHidden(it) && suits(it) }
        }
        val skip = prefs.watched + prefs.disliked.keys
        val out = ArrayList<Section>()

        // In cinemas: out this week, or coming in the next three days.
        val cinema = (list("cinema") + list("soon"))
            .filter { p -> Episodes.daysUntil(p.release)?.let { it in -6..3 } == true }
            .distinctBy { it.key }
            .sortedByDescending { it.imdb }
            .map { p ->
                val d = Episodes.daysUntil(p.release) ?: 0
                Item(p, if (d > 0) "${dayName(p.release)} vizyonda" else "Bu hafta vizyona girdi")
            }
        if (cinema.isNotEmpty()) out.add(Section("BU HAFTA VİZYONDA", "Sinemada", cinema.take(8)))

        // New on the platforms; the ones chosen in Platformlarım, else all.
        val mine = prefs.platforms
        val streaming = list("platforms")
            .filter { mine.isEmpty() || it.provider in mine }
            .filter { it.key !in skip }
            .map { Item(it, it.provider.ifEmpty { "Platformlarda yeni" }) }
        if (streaming.isNotEmpty()) out.add(Section("PLATFORMLARA YENİ GELENLER", "Platformlarda", streaming.take(10)))

        // New episodes from today to Sunday.
        Episodes.load(o)
        val days = run {
            val c = Calendar.getInstance()
            when (c.get(Calendar.DAY_OF_WEEK)) {
                Calendar.FRIDAY -> 2
                Calendar.SATURDAY -> 1
                Calendar.SUNDAY -> 0
                else -> (Calendar.SUNDAY + 7 - c.get(Calendar.DAY_OF_WEEK)) % 7
            }
        }
        val find = Episodes.finder(o, prefs.watchlist)
        val episodes = Episodes.upcoming(days).filter { it.backdrop.isNotEmpty() }.map { s ->
            val p = find(s)
            val what = when {
                s.next.kind == "finale" -> "Sezon finali"
                s.next.kind == "premiere" -> "Yeni sezon"
                s.next.season > 0 && s.next.episode > 0 -> "${s.next.season}. sezon ${s.next.episode}. bölüm"
                else -> "Yeni bölüm"
            }
            Item(p, "$what  ·  ${dayName(s.next.date)}")
        }.filter { suits(it.film) }
        if (episodes.isNotEmpty()) out.add(Section("HAFTA SONU YENİ BÖLÜMLER", "Dizilerde", episodes.take(10)))

        // A series that has finished, short enough for one weekend.
        val all = (list("top10tv") + list("platforms") + list("picks") + list("local")).distinctBy { it.key }
        val finish = all.filter { it.isSeries && it.ended && it.seasons in 1..2 && it.key !in skip }
            .sortedByDescending { it.imdb }
            .map { Item(it, "Tamamı yayında  ·  ${it.seasons} sezon") }
        if (finish.isNotEmpty()) out.add(Section("BU HAFTA SONU BİTİR", "Bitir", finish.take(8)))

        // What was left half way: films and series to finish.
        val store = MediaPlaybackStore(this)
        val everything = (all + list("top10") + list("cinema") + prefs.watchlist).distinctBy { it.key }
        val left = everything.mapNotNull { p ->
            WatchMarks.of(store, p)?.takeIf { it.fraction != null || it.episode != null }?.let { m ->
                Triple(p, m.at, m.episode ?: m.fraction?.let { f -> "%${(f * 100).toInt()} izlendi" }.orEmpty())
            }
        }.filter { suits(it.first) }.sortedByDescending { it.second }
            .map { (p, _, l) -> Item(p, "Yarım kaldı  ·  $l") }
        if (left.isNotEmpty()) out.add(Section("YARIM KALANLAR", "Yarım kalanlar", left.take(8)))

        // LeXia's pick: the best rated of today's films not yet watched.
        val picks = list("picks").filter { it.key !in skip && !it.isSeries }.sortedByDescending { it.imdb }
            .map { Item(it, "LeXia'nın hafta sonu önerisi") }
        if (picks.isNotEmpty()) out.add(Section("HAFTA SONU ÖNERİMİZ", "Önerimiz", picks.take(6)))

        // The weekend plan, last: Friday evening, Saturday, Sunday.
        val plan = plan(this)
        val planned = planDays.mapNotNull { (d, name) -> plan[d]?.let { Item(it, name) } }
        if (planned.isNotEmpty()) out.add(Section("HAFTA SONU PROGRAMIN", "Programın", planned))

        if (out.isEmpty()) return out
        // The cover: the weekend's dates, what is in it, the week's biggest film behind.
        val films = cinema.size + streaming.count { !it.film.isSeries }
        val shows = episodes.size + streaming.count { it.film.isSeries }
        val summary = listOfNotNull(
            "$films yeni film".takeIf { films > 0 },
            "$shows yeni bölüm ve dizi".takeIf { shows > 0 },
            "${finish.size} bitirilecek dizi".takeIf { finish.isNotEmpty() }
        ).joinToString("  ·  ")
        val star = out.flatMap { it.items }.maxByOrNull { it.film.imdb } ?: return out
        val highlights = out.map { it.items.first() }.distinctBy { it.film.key }
        out.add(0, Section("BU HAFTA SONU", "Kapak", listOf(Item(star.film, summary)) + highlights.filter { it.film.key != star.film.key }, cover = true))
        return out
    }

    /** "Aileyle" on: nothing frightening or violent, only what suits everyone. */
    private fun suits(p: Pick): Boolean {
        if (!family) return true
        fun has(vararg w: String) = p.genres.any { g -> val l = g.lowercase(tr); w.any { it in l } }
        if (has("korku", "gerilim", "suç", "savaş")) return false
        return p.cert == "Genel" || p.cert == "7+" || has("aile", "animasyon", "çocuk")
    }

    private fun styleFamily() {
        familyBtn.text = if (family) "✓   Aileyle" else "Aileyle"
        (familyBtn.layoutParams as? LinearLayout.LayoutParams)?.rightMargin = ui.u(14)
    }

    /** The pages again (family switched, plan changed), staying on the same page where it still is. */
    private fun reload() {
        val keep = sections.getOrNull(page)?.title
        val again = load()
        if (again.isEmpty()) {
            Glass.toast(this, "Aileyle uygun bir şey bulunamadı")
            family = false
            styleFamily()
            return
        }
        sections = again
        val i = sections.indexOfFirst { it.title == keep }.takeIf { it >= 0 } ?: 0
        go(i, focusNext = false)
    }

    /** "Programa ekle": the chosen film goes to Friday evening, Saturday or Sunday. */
    private fun choosePlanDay() {
        val film = shown?.film ?: return
        val plan = plan(this).toMutableMap()
        val current = plan.entries.firstOrNull { it.value.key == film.key }?.key
        val rows = planDays.map { (d, name) -> name to (plan[d]?.title?.takeIf { d != current }.orEmpty()) } +
            listOfNotNull(("Programdan çıkar" to "").takeIf { current != null })
        Dialogs.list(this, "Programa ekle", rows, film.title, R.drawable.ic_calendar, planDays.indexOfFirst { it.first == current }) { i ->
            if (i < planDays.size) {
                plan.entries.removeAll { it.value.key == film.key }
                plan[planDays[i].first] = film
                Glass.toast(this, "${planDays[i].second}: ${film.title}")
            } else {
                plan.entries.removeAll { it.value.key == film.key }
            }
            savePlan(this, plan)
            updatePlanBtn()
            reload()
        }
    }

    private fun updatePlanBtn() {
        if (!::planBtn.isInitialized) return
        val film = shown?.film
        val day = film?.let { f -> plan(this).entries.firstOrNull { it.value.key == f.key }?.key }
        planBtn.text = day?.let { d -> "✓   " + planDays.first { it.first == d }.second } ?: "+   Programa ekle"
    }

    /** The chosen film's silent trailer, after a few seconds on it. */
    private fun startTrailer() {
        val p = shown?.film ?: return
        if (sections.getOrNull(page)?.cover == true || Fx.lite || !prefs.heroTrailer || !hasWindowFocus()) return
        val key = p.homeTrailer
        if (key.isBlank()) return
        trailer.sound = false
        trailer.start(key)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(trailerSoon)
        if (::trailer.isInitialized) trailer.stop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::trailer.isInitialized) trailer.release()
        super.onDestroy()
    }

    /** Shows page [i]: the segments, the posters and the first film. */
    private fun go(i: Int, focusNext: Boolean = true) {
        if (i !in sections.indices) return
        page = i
        val s = sections[i]
        segments.removeAllViews()
        for (k in sections.indices) {
            val seg = View(this)
            seg.background = ui.round(if (k <= i) Color.WHITE else Color.argb(70, 255, 255, 255), 3)
            segments.addView(seg, ui.ll(0, ui.match, weight = 1f, right = if (k < sections.size - 1) 8 else 0))
        }
        label.text = s.label
        prevBtn.visibility = if (i > 0) View.VISIBLE else View.GONE
        nextBtn.text = when {
            s.cover -> "Başla   ›"
            i < sections.size - 1 -> "Sıradaki: " + sections[i + 1].title + "   ›"
            else -> "Bitir"
        }
        posters.removeAllViews()
        for (item in s.items) posters.addView(poster(item))
        show(s.items.first(), force = true)
        text.alpha = 0f
        text.translationX = ui.uf(-30)
        text.animate().alpha(1f).translationX(0f).setDuration(D.SLOW).start()
        if (focusNext) nextBtn.requestFocus()
    }

    private fun poster(item: Item): View {
        val cw = 150
        val card = FrameLayout(this)
        card.isFocusable = true
        card.isClickable = true
        card.background = ui.round(C.CARD, D.CARD)
        card.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        card.addView(img, ui.fl(ui.match, ui.match))
        if (item.film.poster.isNotEmpty()) Img.load(this, "w500", item.film.poster, ui.u(cw), img)
        val fx = Fx.decorate(ui, card, D.CARD)
        card.setOnFocusChangeListener { v, f ->
            ui.lift(v, f, 1.1f, 30)
            fx(f)
            if (f) show(item)
        }
        card.setOnClickListener { DetailActivity.open(this, item.film) }
        card.layoutParams = ui.ll(ui.u(cw), ui.u(cw * 3 / 2), right = 26)
        return card
    }

    /** Puts [item] in the large picture and the text. */
    private fun show(item: Item, force: Boolean = false) {
        if (!force && shown === item) return
        shown = item
        val p = item.film
        val cover = sections.getOrNull(page)?.cover == true && sections[page].items.firstOrNull() === item
        handler.removeCallbacks(trailerSoon)
        trailer.stop()
        logoView.visibility = View.GONE
        title.visibility = View.VISIBLE
        if (cover) {
            // The cover: the weekend's dates big, what is in it, the week's film behind.
            title.text = weekendDates().split(' ').joinToString(" ") { w -> w.lowercase(tr).replaceFirstChar { it.titlecase(tr) } }
            badges.visibility = View.GONE
            line.text = item.line
            about.text = "Bu hafta neler var, sayfa sayfa. Beğendiğini Programa ekle; o gün ana ekran hatırlatır."
            about.visibility = View.VISIBLE
            cast.removeAllViews()
        } else {
            title.text = p.title
            loadLogo(p)
            badges.visibility = View.VISIBLE
            val where = when {
                item.line.contains("vizyon") -> ""
                p.provider.isNotEmpty() -> p.provider
                else -> p.where.firstOrNull()
            }
            ui.setWhere(whereBadge, where)
            imdbBadge.visibility = if (p.imdb > 0) View.VISIBLE else View.GONE
            imdbScore.text = String.format(tr, "%.1f", p.imdb)
            certBadge.text = p.cert
            certBadge.visibility = if (p.cert.isEmpty()) View.GONE else View.VISIBLE
            meta.text = p.details(2)
            meta.visibility = if (meta.text.isNullOrBlank()) View.GONE else View.VISIBLE
            line.text = item.line
            about.text = p.overview
            about.visibility = if (p.overview.isEmpty()) View.GONE else View.VISIBLE
            showCast(p)
            if (!Fx.lite && prefs.heroTrailer && p.homeTrailer.isNotBlank()) handler.postDelayed(trailerSoon, 3_000L)
        }
        for (b in listOf(trailerBtn, planBtn, listBtn, infoBtn)) b.visibility = if (cover) View.GONE else View.VISIBLE
        if (!cover && p.trailer.isEmpty()) trailerBtn.visibility = View.GONE
        updateListBtn()
        updatePlanBtn()
        val token = ++imageToken
        val width = resources.displayMetrics.widthPixels
        Thread {
            val b = Net.bitmap("https://image.tmdb.org/t/p/" + (if (width > 1280) "original" else "w1280") + p.backdrop, width, true)
            runOnUiThread {
                if (b == null || token != imageToken || isFinishing) return@runOnUiThread
                val front = if (backA.alpha >= backB.alpha) backA else backB
                val back = if (front === backA) backB else backA
                back.setImageBitmap(b)
                (back.parent as? FrameLayout)?.let { parent ->
                    // Keep the pictures under the shades and text.
                    parent.removeView(back)
                    parent.addView(back, 1)
                }
                back.alpha = 0f
                back.scaleX = 1.04f
                back.scaleY = 1.04f
                back.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(900).start()
                front.animate().alpha(0f).setDuration(900).start()
            }
        }.start()
    }

    private fun loadLogo(p: Pick) {
        val path = p.logo.takeIf { it.isNotEmpty() } ?: return
        val want = p.key
        Thread {
            val b = Net.bitmap("https://image.tmdb.org/t/p/w500$path", ui.u(620), false)?.takeIf { !Img.isDark(it) }
            runOnUiThread {
                if (b == null || isFinishing || shown?.film?.key != want) return@runOnUiThread
                logoView.setImageBitmap(b)
                logoView.visibility = View.VISIBLE
                title.visibility = View.GONE
            }
        }.start()
    }

    /** Up to five leading actors: a round photo and the name; OK opens their page. */
    private fun showCast(p: Pick) {
        cast.removeAllViews()
        for (c in p.cast.filter { it.photo.isNotEmpty() }.take(5)) {
            val col = ui.vbox()
            col.gravity = Gravity.CENTER_HORIZONTAL
            val face = FrameLayout(this)
            val disc = GradientDrawable()
            disc.shape = GradientDrawable.OVAL
            disc.setColor(Color.argb(40, 255, 255, 255))
            face.background = disc
            face.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            face.clipToOutline = true
            val img = ImageView(this)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            face.addView(img, ui.fl(ui.match, ui.match))
            Img.load(this, "w185", c.photo, ui.u(84), img)
            col.addView(face, ui.ll(ui.u(84), ui.u(84)))
            val name = ui.text(c.name.substringBefore(' ') + " " + c.name.substringAfterLast(' ').take(1) + ".", 15, C.TEXT2, 600)
            name.maxLines = 1
            col.addView(name, ui.ll(ui.wrap, ui.wrap, top = 8))
            cast.addView(col, ui.ll(ui.u(110), ui.wrap, right = 12))
        }
        cast.visibility = if (cast.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun inList(): Boolean = shown?.let { s -> prefs.watchlist.any { it.key == s.film.key } } == true

    private fun updateListBtn() {
        if (!::listBtn.isInitialized) return
        listBtn.text = if (inList()) "✓   Listemde" else "+   Listeme ekle"
    }

    private fun toggleList() {
        val film = shown?.film ?: return
        val list = prefs.watchlist
        prefs.watchlist = if (inList()) list.filter { it.key != film.key } else listOf(film) + list
        updateListBtn()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
