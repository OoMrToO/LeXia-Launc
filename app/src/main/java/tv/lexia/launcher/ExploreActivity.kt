package tv.lexia.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/**
 * Keşfet: every film and series in today's list on one page, with filters (genre, film or series,
 * score, length, platform). "Bu akşam 2 saatten kısa, Netflix'te bir gerilim" in a few presses.
 */
class ExploreActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var grid: LinearLayout
    private lateinit var countText: TextView
    private lateinit var filterBar: LinearLayout
    private val tr = Locale("tr", "TR")
    private var all: List<Pick> = emptyList()

    // Filters: "" = all.
    private var genre = ""
    private var kind = ""
    private var minScore = 0.0
    private var maxMinutes = 0
    private var platform = ""
    /** Where: "" all, "cinema" in cinemas now, "soon" coming to cinemas, "stream" on the platforms, "local" Turkish. */
    private var where = ""
    /** When it came out: "" any, "week" last 7 days, "month" last 30 days, "year" this year, "soon" still to come. */
    private var date = ""
    /** How to watch: "" any, "mine" on a platform whose app is on this TV. */
    private var how = ""
    /** Platforms (by name) whose app is on this TV. */
    private var mine: Set<String> = emptySet()
    /** Which lists each title came from (cinema, soon, platforms, local...). */
    private val sources = HashMap<String, MutableSet<String>>()
    /** Posters come in four at a time, not all at once. */
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    @Volatile private var gen = 0

    companion object {
        private const val MAX = 30
    }

    override fun onDestroy() {
        super.onDestroy()
        pool.shutdownNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        val m = ui.match
        val w = ui.wrap

        val frame = FrameLayout(this)
        val blur = Fx.backdrop
        frame.background = if (blur != null) {
            android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.BitmapDrawable(resources, blur),
                android.graphics.drawable.ColorDrawable(Color.argb(ui.dimAlpha() + 70, 0, 0, 0))
            ))
        } else {
            ui.gradient(GradientDrawable.Orientation.TL_BR, Color.parseColor("#1A1F29"), Color.parseColor("#0A0B0E"), Color.BLACK)
        }

        val page = ui.vbox()
        page.setPadding(ui.u(110), ui.u(64), ui.u(110), 0)
        page.clipChildren = false
        page.clipToPadding = false
        val head = ui.hbox()
        head.gravity = Gravity.BOTTOM
        head.addView(ui.text("Keşfet", 54, C.TEXT, 700), ui.ll(0, w, weight = 1f))
        countText = ui.text("", 22, C.TEXT3, 500)
        head.addView(countText, ui.ll(w, w, bottom = 8))
        page.addView(head, ui.ll(m, w))

        filterBar = ui.hbox()
        filterBar.clipChildren = false
        filterBar.clipToPadding = false
        filterBar.setPadding(ui.u(6), ui.u(10), ui.u(6), ui.u(10))
        val bar = android.widget.HorizontalScrollView(this)
        bar.isHorizontalScrollBarEnabled = false
        bar.clipChildren = false
        bar.clipToPadding = false
        bar.addView(filterBar)
        page.addView(bar, ui.ll(m, w, top = 18, bottom = 18, left = -6))

        grid = ui.vbox()
        grid.clipChildren = false
        grid.clipToPadding = false
        grid.setPadding(ui.u(20), ui.u(20), ui.u(20), ui.u(80))
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.addView(grid, FrameLayout.LayoutParams(m, w))
        page.addView(scroll, ui.ll(m, 0, left = -20, right = -20, weight = 1f))
        frame.addView(page, ui.fl(m, m))
        setContentView(frame)
        Sound.attach(this)

        // The list is read off the main thread, so the page opens at once.
        countText.text = "Hazırlanıyor…"
        Thread {
            val list = load()
            val apps = list.flatMap { platformsOf(it) }.toSet().filter { Apps.forPlatform(this, it) != null }.toSet()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                all = list
                mine = apps
                buildFilters()
                show()
            }
        }.start()
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(D.MED).start()
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean =
        Pointer.handle(this, ev) || super.dispatchGenericMotionEvent(ev)

    /** Every titled film and series in the saved list, once; hidden and turned-down ones left out. */
    private fun load(): List<Pick> {
        val o = try {
            JSONObject(java.io.File(filesDir, "pick.json").readText())
        } catch (e: Exception) {
            return emptyList()
        }
        Remote.loadSaved(this)
        val out = ArrayList<Pick>()
        fun add(name: String) {
            val a = o.optJSONArray(name) ?: return
            for (i in 0 until a.length()) {
                val p = Pick.from(a.getJSONObject(i))
                out.add(p)
                sources.getOrPut(p.key) { HashSet() }.add(name)
            }
        }
        for (n in listOf("picks", "platforms", "top10", "top10tv", "cinema", "soon", "local", "history")) add(n)
        val g = o.optJSONArray("genres")
        for (i in 0 until (g?.length() ?: 0)) {
            val f = g!!.getJSONObject(i).optJSONArray("films") ?: continue
            for (j in 0 until f.length()) out.add(Pick.from(f.getJSONObject(j)))
        }
        val down = prefs.disliked.keys
        // The same title can come in several lists; the one with a platform name wins.
        return out.filter { it.title.isNotEmpty() && it.poster.isNotEmpty() && !Remote.isHidden(it) && it.key !in down }
            .sortedBy { if (it.provider.isNotEmpty()) 0 else 1 }
            .distinctBy { it.key }
            .sortedByDescending { it.imdb }
    }

    private fun platformsOf(p: Pick) = (listOfNotNull(p.provider.takeIf { it.isNotEmpty() }) + p.where).distinct()

    private fun from(p: Pick, list: String) = sources[p.key]?.contains(list) == true

    /** Days from today to the film's date (its Turkish release, else its digital release); null when unknown. */
    private fun days(p: Pick): Int? = Episodes.daysUntil(p.release) ?: Episodes.daysUntil(p.digital)

    private fun matchesWhere(p: Pick): Boolean = when (where) {
        "cinema" -> from(p, "cinema")
        "soon" -> from(p, "soon") || (days(p) ?: 0) > 0 && !from(p, "platforms")
        "stream" -> platformsOf(p).isNotEmpty()
        "local" -> from(p, "local")
        else -> true
    }

    private fun matchesDate(p: Pick): Boolean {
        if (date.isEmpty()) return true
        val d = days(p)
        return when (date) {
            "soon" -> d != null && d > 0
            "week" -> d != null && d in -7..0
            "month" -> d != null && d in -30..0
            "year" -> p.year == java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString() && (d == null || d <= 0)
            else -> true
        }
    }

    private fun matches(p: Pick): Boolean =
        matchesWhere(p) && matchesDate(p) &&
            (how.isEmpty() || (how == "mine" && platformsOf(p).any { it in mine })) &&
        (genre.isEmpty() || genre in p.genres) &&
            (kind.isEmpty() || (kind == "tv") == p.isSeries) &&
            (minScore <= 0 || p.imdb >= minScore) &&
            (maxMinutes <= 0 || (p.runtime in 1..maxMinutes)) &&
            (platform.isEmpty() || platform in platformsOf(p))

    /** The filter buttons; each opens its choices and shows the current one. */
    private fun buildFilters() {
        filterBar.removeAllViews()
        val genres = all.flatMap { it.genres }.distinct().sortedBy { it.lowercase(tr) }
        val plats = all.flatMap { platformsOf(it) }.distinct().sortedBy { it.lowercase(tr) }
        fun chip(label: String, values: List<String>, names: List<String>, current: () -> String, set: (String) -> Unit) {
            val b = ui.pill("", 58, 21)
            fun refresh() {
                val i = values.indexOf(current())
                b.text = if (i <= 0) label else names[i]
            }
            refresh()
            b.setOnClickListener {
                Dialogs.chooseOne(this, label, names, values.indexOf(current()).coerceAtLeast(0)) {
                    set(values[it])
                    refresh()
                    show()
                }
            }
            filterBar.addView(b)
        }
        chip("Sırala", listOf("", "new", "soon", "az"),
            listOf("En yüksek puan", "En yeni çıkanlar", "En yakında çıkacaklar", "Ada göre (A–Z)"), { sort }) { sort = it }
        chip("Nerede", listOf("", "cinema", "soon", "stream", "local"),
            listOf("Her yerde", "Sinemada şimdi", "Yakında vizyonda", "Platformlarda", "Yerli yapımlar"), { where }) { where = it }
        chip("Nasıl izlerim", listOf("", "mine"),
            listOf("Her yol", "Sende var (yeni abonelik gerekmez)"), { how }) { how = it }
        chip("Tarih", listOf("", "week", "month", "year", "soon"),
            listOf("Bütün tarihler", "Son bir hafta", "Son bir ay", "Bu yıl", "Henüz çıkmadı"), { date }) { date = it }
        chip("Tür", listOf("") + genres, listOf("Bütün türler") + genres, { genre }) { genre = it }
        chip("Film ya da dizi", listOf("", "movie", "tv"), listOf("Film ve dizi", "Yalnızca film", "Yalnızca dizi"), { kind }) { kind = it }
        chip("Puan", listOf("", "7", "8"), listOf("Bütün puanlar", "IMDb 7 ve üstü", "IMDb 8 ve üstü"), { if (minScore > 0) minScore.toInt().toString() else "" }) {
            minScore = it.toDoubleOrNull() ?: 0.0
        }
        chip("Süre", listOf("", "100", "130"), listOf("Bütün süreler", "1 sa 40 dk'dan kısa", "2 sa 10 dk'dan kısa"), { if (maxMinutes > 0) maxMinutes.toString() else "" }) {
            maxMinutes = it.toIntOrNull() ?: 0
        }
        if (plats.isNotEmpty()) chip("Platform", listOf("") + plats, listOf("Bütün platformlar") + plats, { platform }) { platform = it }
        // Trailers of what is shown, one after another, in LeXia's own player.
        if (prefs.trailerInApp) {
            val run = ui.iconPill("Fragman maratonu", R.drawable.ic_film)
            run.setOnClickListener { chooseMarathon() }
            filterBar.addView(run)
        }
    }

    /**
     * Ready-made marathons: what the filters show, this week in cinemas, coming soon, a horror night,
     * laughs, new on each platform, Turkish films, the best scored. Only those with two trailers or more.
     */
    private fun chooseMarathon() {
        fun withTrailer(l: List<Pick>) = l.filter { it.trailer.isNotEmpty() }.take(20)
        val themes = ArrayList<Pair<String, List<Pick>>>()
        themes.add("Bu seçimdeki yapımlar" to withTrailer(all.filter { matches(it) }))
        themes.add("Bu hafta sinemada" to withTrailer(all.filter { from(it, "cinema") }))
        themes.add("Yakında vizyonda" to withTrailer(all.filter { from(it, "soon") }.sortedBy { days(it) ?: 999 }))
        themes.add("Korku gecesi" to withTrailer(all.filter { "Korku" in it.genres }))
        themes.add("Gülme garantili" to withTrailer(all.filter { "Komedi" in it.genres }))
        themes.add("Aksiyon dolu" to withTrailer(all.filter { "Aksiyon" in it.genres }))
        val plats = all.flatMap { platformsOf(it) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3)
        for ((name, _) in plats) themes.add("${TrailerActivity.locative(name)} yeni" to withTrailer(all.filter { name in platformsOf(it) }))
        themes.add("Yerli yapımlar" to withTrailer(all.filter { from(it, "local") }))
        themes.add("En yüksek puanlılar" to withTrailer(all.filter { it.imdb >= 8.0 }))
        val ok = themes.filter { it.second.size >= 2 }
        if (ok.isEmpty()) {
            Glass.toast(this, "Şu an fragmanı olan yapım yok")
            return
        }
        Dialogs.list(this, "Fragman maratonu", ok.map { it.first to "${it.second.size} fragman" },
            subtitle = "Fragmanlar arka arkaya; ▼ ile sıradakine geç.", icon = R.drawable.ic_film) { i ->
            TrailerActivity.marathon(this, ok[i].second)
        }
    }

    /** The matching titles as a grid of posters, best scored first. */
    /** Order of the page: "" best scored, "new" newest out, "soon" coming soonest, "az" by name. */
    private var sort = ""
    /** What the filters found, in [sort] order, and how much of it is on the page. */
    private var found: List<Pick> = emptyList()
    private var shown = 0
    private var row: LinearLayout? = null
    private var more: View? = null

    private fun ordered(list: List<Pick>): List<Pick> = when (sort) {
        "new" -> list.sortedWith(compareBy<Pick> { val d = days(it); if (d == null || d > 0) Int.MAX_VALUE else -d }.thenByDescending { it.imdb })
        "soon" -> list.sortedWith(compareBy<Pick> { val d = days(it); if (d == null || d <= 0) Int.MAX_VALUE else d }.thenByDescending { it.imdb })
        "az" -> java.text.Collator.getInstance(tr).let { c -> list.sortedWith { x, y -> c.compare(x.title, y.title) } }
        else -> list
    }

    private fun show() {
        gen++
        found = ordered(all.filter { matches(it) })
        shown = 0
        row = null
        more = null
        grid.removeAllViews()
        if (found.isEmpty()) {
            countText.text = "Bu seçimde yapım yok"
            val hint = ui.text("Filtrelerden birini gevşet; ya da yarın yeni liste gelince tekrar bak.", 24, C.TEXT2, 400)
            grid.addView(hint, ui.ll(ui.wrap, ui.wrap, top = 40, left = 10))
            return
        }
        addMore()
    }

    /** The next 30 onto the page (30 at first: quick on any TV), then a "30 daha göster" card if more are left. */
    private fun addMore() {
        more?.let { (it.parent as? ViewGroup)?.removeView(it) }
        more = null
        val cols = 7
        val from = shown
        val to = minOf(found.size, shown + MAX)
        var first: View? = null
        for (i in from until to) {
            if (i % cols == 0) {
                row = ui.hbox().also {
                    it.clipChildren = false
                    it.clipToPadding = false
                    grid.addView(it, ui.ll(ui.match, ui.wrap, bottom = 30))
                }
            }
            val c = card(found[i])
            row!!.addView(c)
            if (first == null) first = c.tag as? View
        }
        shown = to
        countText.text = if (found.size > shown) "$shown / ${found.size} yapım" else "${found.size} yapım"
        if (found.size > shown) {
            if (shown % cols == 0) {
                row = ui.hbox().also { grid.addView(it, ui.ll(ui.match, ui.wrap, bottom = 30)) }
            }
            val m = moreCard(minOf(MAX, found.size - shown))
            row!!.addView(m)
            more = m
        }
        // Coming from the "daha göster" card: go on with the first new one.
        if (from > 0) first?.requestFocus()
    }

    private fun moreCard(n: Int): View {
        val cw = 200
        val col = ui.vbox()
        col.clipChildren = false
        val box = FrameLayout(this)
        box.isFocusable = true
        box.isClickable = true
        box.background = ui.round(Color.argb(28, 255, 255, 255), D.CARD, 2, Color.argb(60, 255, 255, 255))
        val inner = ui.vbox()
        inner.gravity = Gravity.CENTER
        val plus = ui.text("+", 64, C.TEXT, 300)
        plus.gravity = Gravity.CENTER
        inner.addView(plus)
        val t = ui.text("$n daha göster", 21, C.TEXT, 600)
        t.gravity = Gravity.CENTER
        inner.addView(t, ui.ll(ui.wrap, ui.wrap, top = 6))
        box.addView(inner, ui.fl(ui.match, ui.match, Gravity.CENTER))
        box.setOnFocusChangeListener { _, f ->
            ui.lift(box, f, 1.08f, 30)
            box.background = if (f) ui.round(Color.WHITE, D.CARD) else ui.round(Color.argb(28, 255, 255, 255), D.CARD, 2, Color.argb(60, 255, 255, 255))
            plus.setTextColor(if (f) C.INK else C.TEXT)
            t.setTextColor(if (f) C.INK else C.TEXT)
        }
        box.setOnClickListener { addMore() }
        col.addView(box, ui.ll(ui.u(cw), ui.u(cw * 3 / 2)))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 28)
        col.tag = box
        return col
    }

    /** "28 Kasım'da vizyonda", "Yarın vizyonda", "3 Kasım'da çıktı". */
    private fun dateText(p: Pick): String {
        val raw = p.release.ifEmpty { p.digital }
        val d = days(p) ?: return p.year
        val c = java.util.Calendar.getInstance()
        try {
            c.time = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw)!!
        } catch (e: Exception) {
            return p.year
        }
        // "14 Kasım'da", "3 Aralık'ta": the suffix follows the month's last sound.
        val sfx = arrayOf("'ta", "'ta", "'ta", "'da", "'ta", "'da", "'da", "'ta", "'de", "'de", "'da", "'ta")
        val day = java.text.SimpleDateFormat("d MMMM", tr).format(c.time) + sfx[c.get(java.util.Calendar.MONTH)]
        return when {
            d == 0 -> "Bugün çıkıyor"
            d == 1 -> "Yarın çıkıyor"
            d > 0 -> "$day çıkıyor"
            else -> "$day çıktı"
        }
    }

    private fun card(p: Pick): View {
        val cw = 200
        val col = ui.vbox()
        col.clipChildren = false
        val poster = FrameLayout(this)
        poster.isFocusable = true
        poster.isClickable = true
        poster.background = ui.round(C.CARD, D.CARD)
        poster.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        poster.addView(img, ui.fl(ui.match, ui.match))
        img.setImageDrawable(Fx.Shimmer(C.CARD, 0f))
        val g = gen
        pool.execute {
            // A filter changed meanwhile: this card is gone, skip its poster.
            if (isFinishing || g != gen) return@execute
            val b = Net.bitmap("https://image.tmdb.org/t/p/w342" + p.poster, ui.u(cw), true)
            runOnUiThread { if (b != null && !isFinishing && g == gen) Fx.fadeIn(img, b) }
        }
        val fx = Fx.decorate(ui, poster, D.CARD)
        val title = ui.text(p.title, 19, C.TEXT2, 500)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        val bits = ArrayList<String>()
        val d = days(p)
        if (d != null && d > 0) {
            // Not out yet: when, instead of the score.
            bits.add(dateText(p))
        } else {
            if (p.imdb > 0) bits.add(String.format(tr, "IMDb %.1f", p.imdb))
            when {
                from(p, "cinema") -> bits.add("Sinemada")
                platformsOf(p).isNotEmpty() -> bits.add(platformsOf(p).first())
                d != null && d >= -30 -> bits.add(dateText(p))
                p.year.isNotEmpty() -> bits.add(p.year)
            }
        }
        val sub = ui.text(bits.joinToString("  ·  "), 16, C.TEXT3, 500)
        sub.maxLines = 1
        poster.setOnFocusChangeListener { _, f ->
            ui.lift(poster, f, 1.08f, 30)
            fx(f)
            title.setTextColor(if (f) C.TEXT else C.TEXT2)
        }
        poster.setOnClickListener { DetailActivity.open(this, p) }
        col.addView(poster, ui.ll(ui.u(cw), ui.u(cw * 3 / 2)))
        col.addView(title, ui.ll(ui.u(cw), ui.wrap, top = 12))
        col.addView(sub, ui.ll(ui.u(cw), ui.wrap, top = 2))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 28)
        col.tag = poster
        return col
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
