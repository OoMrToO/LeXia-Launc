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
 * Keşfet: films and series in two tabs, as rows to scroll (where you left off, new, most watched,
 * best scored, Turkish, one row per genre; a row's end brings more from TMDB). "Süz" narrows them
 * to a grid by genre, years, score and order.
 */
class ExploreActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var grid: LinearLayout
    private lateinit var countText: TextView
    private lateinit var filterBar: LinearLayout
    private val tr = Locale("tr", "TR")
    private var all: List<Pick> = emptyList()

    /** The tab: "movie" or "tv". */
    private var kind = "movie"
    // "Süz": "" = all.
    private var genre = ""
    private var minScore = 0.0
    /** Years: "" any, "2020" the 2020s, "2010", "2000", "old" before 2000. */
    private var years = ""
    private var searchTerm = ""
    private var origin = ""
    private var country = ""
    /** A platform's name ("Netflix"); "" anywhere. */
    private var where = ""
    /** "short" under 90 min, "mid" 90–150, "long" over 150; "" any. */
    private var length = ""
    /** "unwatched", "half" (left half way), "done"; "" all. */
    private var state = ""
    /** "Akıllı bul": a ready-made search and what TMDB found for it. */
    private var smart: Pair<String, List<Pick>>? = null
    /** Which lists each title came from (cinema, soon, platforms, local...). */
    private val sources = HashMap<String, MutableSet<String>>()
    private lateinit var tabBar: LinearLayout
    private lateinit var stage: ExploreStage
    private lateinit var scroll: ScrollView
    /** A genre's own page ("Korku"): its showcase and rows; "" for Keşfet's. */
    private var genrePage = ""
    /** The quick filters over the rows (Tür · Yıl · Puan…); rebuilt with the page. */
    private var chips: LinearLayout? = null
    private val playback by lazy { MediaPlaybackStore(this) }
    /** Posters come in four at a time, not all at once. */
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    @Volatile private var gen = 0

    companion object {
        private const val EXTRA_WHERE = "where"
        private const val MOOD = "mood:"
        private const val PLAT = "plat:"
        private const val EXTRA_SERIES = "series"

        /** pick.json read and turned into films, with the lists each came from; kept until the file changes. */
        @Volatile private var cache: Triple<Long, List<Pick>, Map<String, Set<String>>>? = null
        private val cacheLock = Any()

        internal fun parsed(ctx: android.content.Context): Pair<List<Pick>, Map<String, Set<String>>>? = synchronized(cacheLock) {
            val file = java.io.File(ctx.filesDir, "pick.json")
            val stamp = file.lastModified()
            cache?.takeIf { it.first == stamp && stamp != 0L }?.let { return it.second to it.third }
            val o = try { JSONObject(file.readText()) } catch (e: Exception) { return null }
            val out = ArrayList<Pick>()
            val from = HashMap<String, MutableSet<String>>()
            fun add(name: String) {
                val a = o.optJSONArray(name) ?: return
                for (i in 0 until a.length()) {
                    val p = try { Pick.from(a.getJSONObject(i)) } catch (e: Exception) { continue }
                    out.add(p)
                    from.getOrPut(p.key) { HashSet() }.add(name)
                }
            }
            for (n in listOf("picks", "platforms", "top10", "top10tv", "cinema", "soon", "local", "history")) add(n)
            val g = o.optJSONArray("genres")
            for (i in 0 until (g?.length() ?: 0)) {
                val f = g!!.optJSONObject(i)?.optJSONArray("films") ?: continue
                for (j in 0 until f.length()) try { out.add(Pick.from(f.getJSONObject(j))) } catch (e: Exception) { }
            }
            cache = Triple(stamp, out, from)
            out to from
        }

        /** Reads the list in the background ahead of time, so Keşfet opens without "Hazırlanıyor". */
        fun warm(ctx: android.content.Context) {
            val app = ctx.applicationContext
            Thread { try { parsed(app) } catch (e: Exception) { } }.start()
        }

        /** Keşfet narrowed to one platform ("Netflix"): from the home screen's platform tiles. */
        fun openPlatform(a: Activity, name: String, series: Boolean) {
            a.startActivity(android.content.Intent(a, ExploreActivity::class.java).putExtra(EXTRA_WHERE, name).putExtra(EXTRA_SERIES, series))
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        private const val MAX = 30
        /** A wide (scene) card, in design units. */
        private const val WIDE_W = 420
        private const val WIDE_H = 236
    }

    override fun onDestroy() {
        super.onDestroy()
        pool.shutdownNow()
        if (::stage.isInitialized) stage.release()
    }

    override fun onPause() {
        super.onPause()
        if (::stage.isInitialized) stage.pause()
    }

    private var resumedOnce = false

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A genre's own page: back goes to Keşfet's.
        if (genrePage.isNotEmpty()) {
            openGenre("")
            return
        }
        super.onBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        intent.getStringExtra(EXTRA_WHERE)?.takeIf { it.isNotBlank() }?.let {
            where = it
            if (intent.getBooleanExtra(EXTRA_SERIES, false)) kind = "tv"
        }
        val m = ui.match
        val w = ui.wrap

        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        frame.clipChildren = false
        // Behind everything, the film's own picture; on top of the page, the showcase.
        stage = ExploreStage(this, ui, frame)
        stage.onPlay = { p -> MediaSourcePickerActivity.open(this, p) }
        stage.onTrailer = { p ->
            if (p.trailer.isNotBlank()) TrailerActivity.open(this, p, stage.trailerAt()) else Glass.toast(this, "Bu yapımın fragmanı yok")
        }
        stage.onDetails = { p -> DetailActivity.open(this, p) }

        val page = ui.vbox()
        page.setPadding(ui.u(110), ui.u(46), ui.u(110), 0)
        page.clipChildren = false
        page.clipToPadding = false
        // Top bar: Keşfet · Filmler · Diziler, then Ara, Rastgele, Maraton; the count on the right.
        val head = ui.hbox()
        head.gravity = Gravity.CENTER_VERTICAL
        head.clipChildren = false
        head.clipToPadding = false
        head.addView(ui.text("Keşfet", 34, C.TEXT, 800), ui.ll(w, w, right = 34))
        tabBar = ui.hbox()
        tabBar.clipChildren = false
        tabBar.clipToPadding = false
        tabBar.setPadding(ui.u(6), ui.u(8), ui.u(6), ui.u(8))
        filterBar = ui.hbox()
        filterBar.clipChildren = false
        filterBar.clipToPadding = false
        tabBar.addView(filterBar)
        head.addView(tabBar, ui.ll(0, w, weight = 1f))
        countText = ui.text("", 20, C.TEXT3, 500)
        head.addView(countText, ui.ll(w, w))
        page.addView(head, ui.ll(m, w))

        page.addView(stage.panel, ui.ll(m, ui.u(ExploreStage.BIG)))

        grid = ui.vbox()
        grid.clipChildren = false
        grid.clipToPadding = false
        grid.setPadding(ui.u(20), ui.u(6), ui.u(20), ui.u(80))
        scroll = ScrollView(this)
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
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                all = list
                buildFilters()
                show()
                stage.buttons.getChildAt(0)?.requestFocus()
            }
        }.start()
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(D.MED).start()
    }

    private var seenChanges = MediaPlaybackStore.changes

    override fun onResume() {
        super.onResume()
        if (resumedOnce && ::stage.isInitialized) stage.resume()
        resumedOnce = true
        // Back from the player: "Kaldığın yerden" and the bars show the new place.
        if (MediaPlaybackStore.changes != seenChanges && all.isNotEmpty()) {
            seenChanges = MediaPlaybackStore.changes
            val focus = currentFocus
            if (!filtering()) {
                // The same poster keeps the remote, not the page's top or a tab.
                val spot = FocusKeep.save(this)
                show()
                if (!FocusKeep.restore(this, spot) && focus != null) filterBar.getChildAt(if (kind == "movie") 0 else 1)?.requestFocus()
            }
        }
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean =
        Pointer.handle(this, ev) || super.dispatchGenericMotionEvent(ev)

    /** Every titled film and series in the saved list, once; hidden and turned-down ones left out. */
    private fun load(): List<Pick> {
        // Read once and kept (the home screen warms it up): the page opens at once after that.
        val parsed = parsed(applicationContext) ?: return emptyList()
        Remote.loadSaved(this)
        val out = parsed.first
        for ((k, v) in parsed.second) sources.getOrPut(k) { HashSet() }.addAll(v)
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

    private fun yearOf(p: Pick): Int = p.year.take(4).toIntOrNull() ?: 0

    private fun matchesYears(p: Pick): Boolean {
        val y = yearOf(p)
        return when (years) {
            "" -> true
            "old" -> y in 1..1999
            else -> years.toIntOrNull()?.let { y in it..it + 9 } == true
        }
    }

    /** Anything chosen under "Süz": the page is a grid then, not rows. */
    private fun filtering() = genre.isNotEmpty() || minScore > 0 || years.isNotEmpty() || sort.isNotEmpty() ||
        where.isNotEmpty() || length.isNotEmpty() || state.isNotEmpty() || smart != null

    private fun ofKind(p: Pick) = (kind == "tv") == p.isSeries

    private fun matchesSearch(p: Pick): Boolean {
        if (searchTerm.isBlank()) return true
        val q = searchTerm.trim().lowercase(tr)
        return p.title.lowercase(tr).contains(q) || p.originalTitle.lowercase(tr).contains(q) ||
            p.overview.lowercase(tr).contains(q) || p.genres.any { it.lowercase(tr).contains(q) } ||
            p.director.lowercase(tr).contains(q) || p.cast.any { it.name.lowercase(tr).contains(q) }
    }

    private fun matches(p: Pick): Boolean =
        ofKind(p) && matchesSearch(p) &&
            (genre.isEmpty() || genre.split(',').all { it in p.genres }) &&
            (minScore <= 0 || p.imdb >= minScore) && matchesYears(p) &&
            (origin.isEmpty() || (origin == "local") == from(p, "local")) &&
            (country.isEmpty() || country in p.countries || TmdbDiscover.hasCountry(kind, p.id, country)) &&
            (where.isEmpty() || where in platformsOf(p)) &&
            matchesLength(p) &&
            matchesState(p)

    private fun matchesLength(p: Pick): Boolean = when (length) {
        "short" -> p.runtime in 1..90
        "mid" -> p.runtime in 91..150
        "long" -> p.runtime > 150
        else -> true
    }

    private fun matchesState(p: Pick): Boolean {
        if (state.isEmpty()) return true
        val mark = WatchMarks.of(playback, p)
        val done = p.key in prefs.watched || mark?.finished == true
        return when (state) {
            "unwatched" -> !done && mark == null
            "half" -> mark != null && (mark.fraction != null || mark.episode != null) && !done
            "done" -> done
            else -> true
        }
    }

    /** The tabs, "Süz" (with what is chosen) and the marathon. */
    private fun countryName(code: String) = when (code) {
        "TR" -> "Türkiye"; "US" -> "ABD"; "GB" -> "Birleşik Krallık"; "FR" -> "Fransa"; "DE" -> "Almanya"
        "IT" -> "İtalya"; "ES" -> "İspanya"; "JP" -> "Japonya"; "KR" -> "Güney Kore"; "IN" -> "Hindistan"
        "CA" -> "Kanada"; "AU" -> "Avustralya"; else -> code
    }

    private fun buildFilters() {
        filterBar.removeAllViews()
        fun keepsShowcase(b: View) {
            val style = b.onFocusChangeListener
            b.setOnFocusChangeListener { v, f ->
                style?.onFocusChange(v, f)
                if (f) stage.enterShowcase()
            }
        }
        if (genrePage.isNotEmpty()) {
            // A genre's page: the way back, and its name.
            val back = ui.pill(if (genrePage.contains(':')) "‹   Keşfet" else "‹   Tüm türler", 58, 21)
            back.setOnClickListener { openGenre("") }
            keepsShowcase(back)
            filterBar.addView(back)
        }
        fun tab(label: String, value: String) {
            val b = ui.pill(label, 58, 22, primary = kind == value)
            b.setOnClickListener {
                if (kind == value) return@setOnClickListener
                kind = value
                buildFilters()
                show()
                filterBar.findViewWithTag<View>("tab:$value")?.requestFocus()
            }
            b.tag = "tab:$value"
            keepsShowcase(b)
            filterBar.addView(b)
        }
        tab("Filmler", "movie")
        tab("Diziler", "tv")
        val search = ui.iconPill("Ara", R.drawable.ic_search)
        search.setOnClickListener { SearchAll.ask(this, all) }
        keepsShowcase(search)
        filterBar.addView(search, ui.ll(ui.wrap, ui.u(64), left = 22, right = 18))
        val random = ui.iconPill("Rastgele", R.drawable.ic_dice)
        random.setOnClickListener { randomPick() }
        keepsShowcase(random)
        filterBar.addView(random)
        // Trailers of what is shown, one after another, in LeXia's own player.
        if (prefs.trailerInApp) {
            val run = ui.iconPill("Maraton", R.drawable.ic_film)
            run.setOnClickListener { chooseMarathon() }
            keepsShowcase(run)
            filterBar.addView(run)
        }
    }

    /**
     * Over the rows: Tür · Yıl · Puan · Nerede · Sırala · Süre, each opening its choice at once
     * (what is chosen shows on it, lit), then "Tüm süzgeçler" and "Temizle".
     */
    private fun addChips() {
        val bar = ui.hbox()
        bar.clipChildren = false
        bar.clipToPadding = false
        bar.setPadding(ui.u(4), ui.u(8), ui.u(4), ui.u(8))
        fun chip(name: String, value: String?, index: Int) {
            val on = value != null
            val b = ui.pill(if (on) value!! else "$name  ▾", 54, 20, primary = on)
            b.tag = "chip:$index"
            b.setOnClickListener { openFilter(index, reopen = false) }
            val style = b.onFocusChangeListener
            b.setOnFocusChangeListener { v, f ->
                style?.onFocusChange(v, f)
                if (f) stage.enterShowcase()
            }
            bar.addView(b, ui.ll(ui.wrap, ui.u(54), right = 14))
        }
        chip("Tür", genre.takeIf { it.isNotEmpty() }?.replace(",", " + "), 0)
        chip("Yıl", yearsName().takeIf { years.isNotEmpty() }, 3)
        chip("Puan", "IMDb ${minScore.toInt()}+".takeIf { minScore > 0 }, 4)
        chip("Nerede", where.takeIf { it.isNotEmpty() }, 1)
        chip("Sırala", sortName().takeIf { sort.isNotEmpty() }, 6)
        chip("Süre", lengthName().takeIf { length.isNotEmpty() }, 2)
        val all = ui.iconPill("Tüm süzgeçler", R.drawable.ic_search, 54, 20)
        all.tag = "chip:all"
        all.setOnClickListener { openFilters() }
        val style = all.onFocusChangeListener
        all.setOnFocusChangeListener { v, f ->
            style?.onFocusChange(v, f)
            if (f) stage.enterShowcase()
        }
        bar.addView(all, ui.ll(ui.wrap, ui.u(54), right = 14))
        if (filtering() || searchTerm.isNotEmpty() || origin.isNotEmpty() || country.isNotEmpty()) {
            val clear = ui.pill("Temizle", 54, 20)
            clear.setOnClickListener {
                genre = ""; minScore = 0.0; years = ""; sort = ""; where = ""; length = ""; state = ""; smart = null; searchTerm = ""; origin = ""; country = ""
                buildFilters()
                show()
                chips?.findViewWithTag<View>("chip:0")?.requestFocus()
            }
            bar.addView(clear, ui.ll(ui.wrap, ui.u(54), right = 14))
        }
        val holder = android.widget.HorizontalScrollView(this)
        holder.isHorizontalScrollBarEnabled = false
        holder.clipChildren = false
        holder.clipToPadding = false
        holder.addView(bar)
        grid.addView(holder, ui.ll(ui.match, ui.wrap, bottom = 22))
        chips = bar
    }

    private fun randomPick() {
        val candidates = (if (filtering()) all.filter { matches(it) } else all.filter { ofKind(it) }).filter { !Remote.isHidden(it) && it.key !in prefs.disliked.keys }
        if (candidates.isEmpty()) { Glass.toast(this, "Mevcut seçimde rastgele öneri bulunamadı"); return }
        // The film page's "Başka öner" draws again from the same choice, without going back.
        Surprise.start(this, candidates, "Keşfet seçimlerine göre")
    }

    private fun yearsName() = when (years) {
        "2020" -> "2020'ler"
        "2010" -> "2010'lar"
        "2000" -> "2000'ler"
        "old" -> "2000'den önce"
        else -> "Bütün yıllar"
    }

    private fun sortName() = when (sort) {
        "new" -> "En yeni"
        "az" -> "A–Z"
        "score" -> "En yüksek puan"
        else -> "Önerilen"
    }

    private fun lengthName() = when (length) {
        "short" -> "90 dakikadan kısa"
        "mid" -> "90–150 dakika"
        "long" -> "2,5 saatten uzun"
        else -> "Süre fark etmez"
    }

    private fun stateName() = when (state) {
        "unwatched" -> "İzlemediklerim"
        "half" -> "Yarım kalanlar"
        "done" -> "İzlediklerim"
        else -> "Hepsi"
    }

    /** "Süz": genres (several at once), platform, length, years, score, watched or not, order. */
    private fun openFilters() {
        val genres = all.filter { ofKind(it) }.flatMap { it.genres }.distinct().sortedBy { it.lowercase(tr) }
        val platforms = all.filter { ofKind(it) }.flatMap { platformsOf(it) }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
        val rows = listOf<Pair<String, String>>(
            "Tür" to genre.ifEmpty { "Bütün türler" }.replace(",", " + "),
            "Nerede" to where.ifEmpty { "Her yerde" },
            "Süre" to lengthName(),
            "Yıl" to yearsName(),
            "Puan" to if (minScore > 0) "IMDb ${minScore.toInt()} ve üstü" else "Bütün puanlar",
            "Durum" to stateName(),
            "Sırala" to sortName(),
            "Yerli / yabancı" to when (origin) { "local" -> "Yerli listesi"; "foreign" -> "Yerli listesi dışında"; else -> "Tümü" },
            "Yapım ülkesi" to (country.takeIf { it.isNotEmpty() }?.let { countryName(it) } ?: "Tüm ülkeler"),
            // What "Akıllı bul" was: ready-made searches, now inside Süz (one button less on the page).
            "Hazır seçimler" to (smart?.first ?: "Bu akşam kısa, 8+ bilim kurgu…")
        )
        Dialogs.list(this, "Süz", rows, subtitle = if (kind == "tv") "Diziler" else "Filmler", icon = R.drawable.ic_search) { i ->
            if (i == rows.lastIndex) chooseSmart() else openFilter(i, reopen = true)
        }
    }

    /**
     * One filter's choice ([i]: 0 Tür, 1 Nerede, 2 Süre, 3 Yıl, 4 Puan, 5 Durum, 6 Sırala,
     * 7 Yerli/yabancı, 8 Yapım ülkesi). [reopen]: back to the Süz list after (from there), else
     * the remote stays on the chip.
     */
    private fun openFilter(i: Int, reopen: Boolean) {
        val genres = all.filter { ofKind(it) }.flatMap { it.genres }.distinct().sortedBy { it.lowercase(tr) }
        val platforms = all.filter { ofKind(it) }.flatMap { platformsOf(it) }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
        fun apply() {
            smart = null
            buildFilters()
            show()
            if (reopen) openFilters() else chips?.findViewWithTag<View>("chip:$i")?.requestFocus()
        }
        run {
            when (i) {
                0 -> {
                    // Several genres: each pick adds or removes one; "Bütün türler" clears.
                    val chosen = genre.split(',').filter { it.isNotEmpty() }
                    val labels = listOf("Bütün türler") + genres.map { if (it in chosen) "✓  $it" else it }
                    Dialogs.chooseOne(this, "Tür (birden çok seçilebilir)", labels, 0) {
                        genre = if (it == 0) "" else {
                            val g = genres[it - 1]
                            (if (g in chosen) chosen - g else chosen + g).joinToString(",")
                        }
                        apply()
                    }
                }
                1 -> Dialogs.chooseOne(this, "Nerede", listOf("Her yerde") + platforms, (platforms.indexOf(where) + 1).coerceAtLeast(0)) {
                    where = if (it == 0) "" else platforms[it - 1]; apply()
                }
                2 -> {
                    val v = listOf("", "short", "mid", "long")
                    Dialogs.chooseOne(this, "Süre", listOf("Süre fark etmez", "90 dakikadan kısa", "90–150 dakika", "2,5 saatten uzun"), v.indexOf(length).coerceAtLeast(0)) {
                        length = v[it]; apply()
                    }
                }
                3 -> {
                    val v = listOf("", "2020", "2010", "2000", "old")
                    Dialogs.chooseOne(this, "Yıl", listOf("Bütün yıllar", "2020'ler", "2010'lar", "2000'ler", "2000'den önce"), v.indexOf(years).coerceAtLeast(0)) {
                        years = v[it]; apply()
                    }
                }
                4 -> {
                    val v = listOf(0.0, 6.0, 7.0, 8.0)
                    Dialogs.chooseOne(this, "Puan", listOf("Bütün puanlar", "IMDb 6 ve üstü", "IMDb 7 ve üstü", "IMDb 8 ve üstü"), v.indexOf(minScore).coerceAtLeast(0)) {
                        minScore = v[it]; apply()
                    }
                }
                5 -> {
                    val v = listOf("", "unwatched", "half", "done")
                    Dialogs.chooseOne(this, "Durum", listOf("Hepsi", "İzlemediklerim", "Yarım kalanlar", "İzlediklerim"), v.indexOf(state).coerceAtLeast(0)) {
                        state = v[it]; apply()
                    }
                }
                7 -> Dialogs.chooseOne(this, "Yerli / yabancı", listOf("Tümü", "Yerli listesinde", "Yerli listesi dışında"), listOf("", "local", "foreign").indexOf(origin).coerceAtLeast(0)) { origin = listOf("", "local", "foreign")[it]; apply() }
                8 -> {
                    val codes = listOf("", "TR", "US", "GB", "FR", "DE", "IT", "ES", "JP", "KR", "IN", "CA", "AU")
                    val names = listOf("Tüm ülkeler", "Türkiye", "ABD", "Birleşik Krallık", "Fransa", "Almanya", "İtalya", "İspanya", "Japonya", "Güney Kore", "Hindistan", "Kanada", "Avustralya")
                    Dialogs.chooseOne(this, "Yapım ülkesi", names, codes.indexOf(country).coerceAtLeast(0)) { selected ->
                        country = codes[selected]
                        if (country.isEmpty()) apply() else Thread {
                            all.filter { ofKind(it) && it.id > 0 }.forEach { item -> runCatching { TmdbDiscover.countries(kind, item.id) } }
                            runOnUiThread { if (!isFinishing && !isDestroyed) apply() }
                        }.start()
                    }
                }
                else -> {
                    val v = listOf("", "score", "new", "az")
                    Dialogs.chooseOne(this, "Sırala", listOf("Önerilen", "En yüksek puan", "En yeni", "Ada göre (A–Z)"), v.indexOf(sort).coerceAtLeast(0)) {
                        sort = v[it]; apply()
                    }
                }
            }
        }
    }

    /**
     * "Akıllı bul": ready-made searches over every film and series TMDB knows (not only today's
     * list): tonight's short ones, the best of a genre, little-known gems, ones like the last watched.
     */
    private fun chooseSmart() {
        if (BuildConfig.TMDB_API_KEY.isBlank()) {
            Glass.toast(this, "Bu sürümde TMDB anahtarı yok")
            return
        }
        val tv = kind == "tv"
        val noun = if (tv) "dizi" else "film"
        val last = WatchHistory(this).entries().firstOrNull { it.pick.isSeries == tv && it.pick.id > 0 }?.pick
        val good = mapOf("vote_average.gte" to "7", "vote_count.gte" to "300", "sort_by" to "vote_average.desc")
        val options = ArrayList<Triple<String, String, (Int) -> List<Pick>>>()
        if (!tv) options.add(Triple("Bu akşam: 2 saatten kısa", "IMDb 7 ve üstü, en çok sevilenler", { pg ->
            TmdbDiscover.discover("movie", pg, mapOf("with_runtime.lte" to "120", "with_runtime.gte" to "75", "vote_average.gte" to "7", "vote_count.gte" to "1000", "sort_by" to "popularity.desc"))
        }))
        if (last != null) options.add(Triple("${last.title} gibi", "Son izlediğine benzeyenler", { pg -> TmdbDiscover.similar(last, pg) }))
        options.add(Triple("Puanı 8 ve üstü bilim kurgu", "Türün en iyileri", { pg ->
            TmdbDiscover.discover(kind, pg, mapOf("vote_average.gte" to "8", "vote_count.gte" to "500", "sort_by" to "vote_average.desc"), genre = if (tv) "Bilim Kurgu & Fantazi" else "Bilim-Kurgu")
        }))
        options.add(Triple("Az bilinen ama çok iyi gerilimler", "Az oy almış, puanı yüksek", { pg ->
            TmdbDiscover.discover(kind, pg, mapOf("vote_average.gte" to "7.2", "vote_count.gte" to "150", "vote_count.lte" to "1500", "sort_by" to "vote_average.desc"), genre = if (tv) "Suç" else "Gerilim")
        }))
        options.add(Triple("Kafa yormayan komediler", "Hafif, keyifli", { pg -> TmdbDiscover.discover(kind, pg, good + ("sort_by" to "popularity.desc"), genre = "Komedi") }))
        options.add(Triple("Duygusal ${noun}ler", "Dram, iyi bir hikâye", { pg -> TmdbDiscover.discover(kind, pg, good + ("sort_by" to "popularity.desc"), genre = "Dram") }))
        options.add(Triple("Ailece izlemelik", "Animasyon ve aile", { pg -> TmdbDiscover.discover(kind, pg, good + ("sort_by" to "popularity.desc"), genre = "Aile") }))
        options.add(Triple("Tüm zamanların en iyileri", "En çok oy alanlar arasında en yüksek puanlılar", { pg ->
            TmdbDiscover.discover(kind, pg, mapOf("vote_count.gte" to "5000", "sort_by" to "vote_average.desc"))
        }))
        options.add(Triple("Yerli yapımlar", "Türkiye'den, en sevilenler", { pg ->
            TmdbDiscover.discover(kind, pg, mapOf("with_origin_country" to "TR", "vote_count.gte" to "30", "sort_by" to "popularity.desc"))
        }))
        Dialogs.list(this, "Akıllı bul", options.map { it.first to it.second }, if (tv) "Diziler" else "Filmler", R.drawable.ic_dice) { i ->
            val (title, _, fetch) = options[i]
            Glass.toast(this, "$title aranıyor…")
            Thread {
                val list = (1..2).flatMap { pg -> try { fetch(pg) } catch (e: Exception) { emptyList() } }
                    .filter { !Remote.isHidden(it) && it.key !in prefs.disliked.keys }.distinctBy { it.key }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (list.isEmpty()) {
                        Glass.toast(this, "Sonuç bulunamadı")
                        return@runOnUiThread
                    }
                    smart = title to list
                    buildFilters()
                    show()
                    FocusKeep.first(grid)
                }
            }.start()
        }
    }

    /**
     * Ready-made marathons: what the filters show, this week in cinemas, coming soon, a horror night,
     * laughs, new on each platform, Turkish films, the best scored. Only those with two trailers or more.
     */
    private fun chooseMarathon() {
        fun withTrailer(l: List<Pick>) = l.filter { it.trailer.isNotEmpty() }.take(20)
        val themes = ArrayList<Pair<String, List<Pick>>>()
        val here = if (filtering()) "Bu seçimdeki yapımlar" else if (kind == "tv") "Diziler" else "Filmler"
        themes.add(here to withTrailer(all.filter { matches(it) }))
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

    /** Order under "Süz": "" recommended (best scored first), "score", "new" newest out, "az" by name. */
    private var sort = ""
    /** What the filters found, in [sort] order, and how much of it is on the page. */
    private var found: List<Pick> = emptyList()
    private var shown = 0
    private var row: LinearLayout? = null
    private var more: View? = null

    private fun ordered(list: List<Pick>): List<Pick> = when (sort) {
        "new" -> list.sortedWith(compareBy<Pick> { val d = days(it); if (d == null || d > 0) Int.MAX_VALUE else -d }
            .thenByDescending { yearOf(it) }.thenByDescending { it.imdb })
        "az" -> java.text.Collator.getInstance(tr).let { c -> list.sortedWith { x, y -> c.compare(x.title, y.title) } }
        else -> list.sortedByDescending { it.imdb }
    }

    private fun show() {
        gen++
        shown = 0
        row = null
        more = null
        grid.removeAllViews()
        addChips()
        if (!filtering()) {
            showRows()
            return
        }
        found = smart?.second ?: ordered(all.filter { matches(it) && inGenrePage(it) })
        stage.feature(found.filter { it.imdb >= 6.5 }.take(6).ifEmpty { found.take(6) },
            if (where.isNotEmpty() && genre.isEmpty() && years.isEmpty() && minScore <= 0) "${TrailerActivity.locative(where)} yeni".uppercase(tr) else "SEÇİMİNE GÖRE")
        if (found.isEmpty()) {
            countText.text = "Bu seçimde yapım yok"
            val hint = ui.text("Süz'deki seçimlerden birini gevşet.", 24, C.TEXT2, 400)
            grid.addView(hint, ui.ll(ui.wrap, ui.wrap, top = 40, left = 10))
            return
        }
        addMore()
    }

    // ---------- Rows ----------

    /** What was played and not finished (a series: also the episode after a finished one), last played first. */
    private fun leftOff(): List<Pick> = all.filter { ofKind(it) }
        .mapNotNull { p -> WatchMarks.of(playback, p)?.takeIf { it.fraction != null || it.episode != null }?.let { p to it.at } }
        .sortedByDescending { it.second }
        .map { it.first }

    /**
     * The page Keşfet narrows to: a genre ("Dram"), a mood ("mood:kafa") or a platform
     * ("plat:Netflix"); empty is Keşfet itself.
     */
    private fun inGenrePage(p: Pick): Boolean = when {
        genrePage.isEmpty() -> true
        genrePage.startsWith(MOOD) -> moods.firstOrNull { it.id == genrePage.removePrefix(MOOD) }?.fits?.invoke(p) ?: true
        genrePage.startsWith(PLAT) -> genrePage.removePrefix(PLAT) in platformsOf(p)
        else -> genrePage in p.genres
    }

    /** The page's name as it is shown: the genre, the mood's title or the platform's name. */
    private fun pageName(): String = when {
        genrePage.startsWith(MOOD) -> moods.firstOrNull { it.id == genrePage.removePrefix(MOOD) }?.title.orEmpty()
        genrePage.startsWith(PLAT) -> genrePage.removePrefix(PLAT)
        else -> genrePage
    }

    /** A mood: what fits it, by genre (and length or age band), and its two colours. */
    private class Mood(val id: String, val title: String, val colors: Pair<Int, Int>, val fits: (Pick) -> Boolean)

    private fun has(p: Pick, vararg words: String) = p.genres.any { g -> val l = g.lowercase(tr); words.any { it in l } }

    private val moods: List<Mood> by lazy {
        fun c(a: String, b: String) = Color.parseColor(a) to Color.parseColor(b)
        listOf(
            Mood("kafa", "Kafa dağıtmalık", c("#7A4A00", "#F2B01E")) { p ->
                has(p, "komedi", "animasyon", "aile", "macera", "çocuk") && !has(p, "korku", "gerilim", "suç", "savaş")
            },
            Mood("gerilim", "Gerilim dolu bir gece", c("#1B1530", "#6741D9")) { p -> has(p, "gerilim", "korku", "gizem", "suç") },
            Mood("duygu", "Duygusal", c("#4A0D2A", "#E64980")) { p -> has(p, "dram", "romantik") && !has(p, "korku", "aksiyon") },
            Mood("aile", "Aileyle izlenir", c("#003C4F", "#15AABF")) { p ->
                !has(p, "korku", "gerilim", "suç", "savaş") && (has(p, "aile", "animasyon", "çocuk") || p.cert == "Genel" || p.cert == "7+")
            },
            Mood("dusun", "Düşündürenler", c("#06243F", "#339AF0")) { p ->
                p.imdb >= 7.5 && has(p, "bilim", "gizem", "belgesel", "tarih", "dram")
            },
            Mood("kisa", "Kısa ve keyifli", c("#0D3B2E", "#40C057")) { p ->
                p.runtime in 1..100 && p.imdb >= 6.5 && !p.isSeries
            }
        )
    }

    /** A genre's own page ([g]), or Keşfet's again (""): its showcase and rows. */
    private fun openGenre(g: String) {
        genrePage = g
        buildFilters()
        show()
        scroll.scrollTo(0, 0)
        stage.buttons.getChildAt(0)?.requestFocus()
    }

    /** What the showcase turns through: new and much-liked ones with a picture, the best first. */
    private fun showcaseOf(today: List<Pick>, mine: List<Pick>): List<Pick> {
        val fresh = today.filter { val d = days(it); d != null && d in -60..0 && it.imdb >= 6.5 }.sortedByDescending { it.imdb }
        val top = mine.filter { from(it, if (kind == "tv") "top10tv" else "top10") }
        val best = today.filter { it.imdb >= 7.5 }.sortedByDescending { it.imdb }
        return (fresh.take(3) + top.take(3) + best).filter { it.backdrop.isNotEmpty() }.distinctBy { it.key }.take(6)
    }

    private fun showRows() {
        val mine = all.filter { ofKind(it) && inGenrePage(it) }
        val noun = if (kind == "tv") "dizi" else "film"
        val sub = genrePage.isNotEmpty()
        // TMDB's own pages ("Daha fazla") only know genres; a mood's or a platform's page keeps to the list.
        val tmdb = !genrePage.contains(':')
        val g = if (tmdb) genrePage else ""
        val name = pageName()
        countText.text = if (sub) "$name  ·  ${mine.size} $noun" else "${mine.size} $noun"
        val today = mine.filter { (days(it) ?: 1) <= 0 || days(it) == null }
        stage.feature(showcaseOf(today, mine), if (sub) name.uppercase(tr) else "ÖNE ÇIKANLAR")
        addRow("Kaldığın yerden", leftOff().filter { inGenrePage(it) }, style = Style.WIDE)
        addRow("Yeni çıkanlar", today.filter { val d = days(it); d != null && d in -90..0 }.sortedBy { -(days(it) ?: -999) }, style = Style.WIDE,
            more = if (tmdb) { pg: Int -> TmdbDiscover.page(kind, pg, genre = g, newest = true) } else null)
        val top = if (!sub) mine.filter { from(it, if (kind == "tv") "top10tv" else "top10") }
        else today.sortedByDescending { it.imdb }
        addRow(
            when {
                !sub -> "Bu haftanın en çok izlenenleri"
                g.isNotEmpty() -> "En sevilen ${g.lowercase(tr)} ${noun}leri"
                else -> "En sevilenler"
            },
            top.take(10), style = Style.RANK
        )
        if (!sub) {
            addMoodTiles(mine, today)
            addGenreTiles(mine, today)
            addPlatformTiles(mine, today)
            if (kind == "tv") {
                // For a series to start now and see to the end.
                addRow("Tek sezonluk mini diziler", today.filter { it.seasons == 1 && it.ended }.sortedByDescending { it.imdb })
                addRow("Bitmiş diziler: hepsini izle", today.filter { it.ended && it.seasons > 1 }.sortedByDescending { it.imdb })
            }
        }
        addRow("IMDb 8 ve üstü", today.filter { it.imdb >= 8.0 }.sortedByDescending { it.imdb },
            more = if (tmdb) { pg: Int -> TmdbDiscover.page(kind, pg, genre = g, minScore = 8.0) } else null)
        addRow("Yakında çıkacaklar", mine.filter { (days(it) ?: 0) > 0 }.sortedBy { days(it) ?: 0 }, style = Style.WIDE,
            more = if (!sub) { pg: Int -> TmdbDiscover.upcoming(kind, pg) } else null)
        addRow(if (kind == "tv") "Yerli diziler" else "Yerli filmler", mine.filter { from(it, "local") })
        addRow(if (kind == "tv") "Yabancı diziler" else "Yabancı filmler", mine.filter { !from(it, "local") })
        val related = mine.flatMap { it.similar }.filter { it.isSeries == (kind == "tv") && inGenrePage(it) }.distinctBy { seenKey(it) }
            .filter { !Remote.isHidden(it) && it.key !in prefs.disliked.keys }
        addRow("Benzer yapımlar", related.sortedByDescending { it.imdb })
        if (sub) {
            if (g.isNotEmpty()) addRow("Daha fazla ${g.lowercase(tr)}", emptyList()) { TmdbDiscover.page(kind, it, genre = g) }
            return
        }
        val genres = mine.flatMap { it.genres }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }.take(6)
        for (name in genres) {
            addRow(name, today.filter { name in it.genres }.sortedByDescending { it.imdb }) { TmdbDiscover.page(kind, it, genre = name) }
        }
    }

    private enum class Style { POSTER, WIDE, RANK }

    /** "Türler": one tile per genre, the genre's best film behind its colour; a tile opens its page. */
    private fun addGenreTiles(mine: List<Pick>, today: List<Pick>) {
        val counts = mine.flatMap { it.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        if (counts.size < 3) return
        addRowTitle("Türler")
        val line = ui.hbox()
        line.clipChildren = false
        line.clipToPadding = false
        line.setPadding(ui.u(4), ui.u(10), ui.u(40), ui.u(10))
        counts.take(14).forEach { (name, n) ->
            val best = today.filter { name in it.genres && it.backdrop.isNotEmpty() }.maxByOrNull { it.imdb }
            line.addView(genreTile(name, n, best))
        }
        val hs = android.widget.HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.clipChildren = false
        hs.clipToPadding = false
        hs.addView(line)
        grid.addView(hs, ui.ll(ui.match, ui.wrap, bottom = 34))
    }

    private fun addRowTitle(title: String) {
        val label = ui.text(title, 26, C.TEXT, 700)
        grid.addView(label, ui.ll(ui.wrap, ui.wrap, top = 8, bottom = 14, left = 4))
    }

    /**
     * A row of posters (20 at first). [more], when given, brings the next TMDB page at the row's end:
     * titles already on the page are left out.
     */
    private fun addRow(title: String, items: List<Pick>, style: Style = Style.POSTER, more: ((Int) -> List<Pick>)? = null) {
        val online = more != null && BuildConfig.TMDB_API_KEY.isNotBlank()
        if (items.isEmpty() && !online) return
        addRowTitle(title)
        var rank = 0
        fun make(p: Pick): View = when (style) {
            Style.WIDE -> if (p.backdrop.isNotEmpty()) wideCard(p) else card(p)
            Style.RANK -> rankCard(p, ++rank)
            Style.POSTER -> card(p)
        }
        val line = ui.hbox()
        line.clipChildren = false
        line.clipToPadding = false
        line.setPadding(ui.u(4), ui.u(10), ui.u(40), ui.u(10))
        val scroll = android.widget.HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.addView(line)
        grid.addView(scroll, ui.ll(ui.match, ui.wrap, bottom = 34))
        val seen = HashSet<String>()
        val start = items.take(20)
        start.forEach { p -> seen.add(seenKey(p)); line.addView(make(p)) }
        if (more == null) return
        var page = 1
        var moreCard: View? = null
        fun addMoreCard() {
            moreCard = moreCard(0, wide = style == Style.WIDE).also { c ->
                (c.tag as View).setOnClickListener { _ ->
                    val g = gen
                    val box = c.tag as View
                    box.isEnabled = false
                    Thread {
                        val next = try { more(page) } catch (e: Exception) { emptyList() }
                        page++
                        runOnUiThread {
                            if (isFinishing || g != gen) return@runOnUiThread
                            val fresh = next.filter { seen.add(seenKey(it)) && !Remote.isHidden(it) && it.key !in prefs.disliked.keys }
                            line.removeView(c)
                            if (fresh.isEmpty() && next.isEmpty()) {
                                Glass.toast(this, "Bu satırda başka yapım bulunamadı")
                                return@runOnUiThread
                            }
                            var first: View? = null
                            fresh.forEach { p -> val v = make(p); line.addView(v); if (first == null) first = v.tag as View }
                            addMoreCard()
                            (first ?: (moreCard?.tag as? View))?.requestFocus()
                        }
                    }.start()
                }
                line.addView(c)
            }
        }
        if (!online) return
        addMoreCard()
        // Nothing of its own in today's list: the row starts with TMDB's first page.
        if (start.isEmpty()) (moreCard?.tag as? View)?.performClick()
    }

    /** One title once in a row, whether it came from today's list or from TMDB. */
    private fun seenKey(p: Pick) = if (p.id > 0) "${p.kind}:${p.id}" else p.key

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

    private fun moreCard(n: Int, wide: Boolean = false): View {
        val cw = if (wide) WIDE_W else 200
        val ch = if (wide) WIDE_H else cw * 3 / 2
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
        val t = ui.text(if (n > 0) "$n daha göster" else "Daha fazla", 21, C.TEXT, 600)
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
        col.addView(box, ui.ll(ui.u(cw), ui.u(ch)))
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
        WatchMarks.add(ui, poster, WatchMarks.of(playback, p))
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
            val tmdb = TmdbDiscover.score["${p.kind}:${p.id}"]
            if (p.imdb > 0) bits.add(String.format(tr, "IMDb %.1f", p.imdb))
            else if (tmdb != null && tmdb > 0) bits.add(String.format(tr, "TMDB %.1f", tmdb))
            when {
                from(p, "cinema") -> bits.add("Sinemada")
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
            if (f) stage.focusOn(p)
        }
        poster.setTag(R.id.lexia_pick, p)
        poster.setOnClickListener { DetailActivity.open(this, p, row = DetailActivity.rowAround(poster)) }
        col.addView(poster, ui.ll(ui.u(cw), ui.u(cw * 3 / 2)))
        col.addView(title, ui.ll(ui.u(cw), ui.wrap, top = 12))
        col.addView(sub, ui.ll(ui.u(cw), ui.wrap, top = 2))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 28)
        col.tag = poster
        return col
    }

    /** The image under [path] (TMDB), into [img], four at a time; skipped if the page changed meanwhile. */
    private fun loadInto(img: ImageView, size: String, path: String, width: Int) {
        img.setImageDrawable(Fx.Shimmer(C.CARD, 0f))
        val g = gen
        pool.execute {
            if (isFinishing || g != gen) return@execute
            val b = Net.bitmap("https://image.tmdb.org/t/p/$size$path", ui.u(width), true)
            runOnUiThread { if (b != null && !isFinishing && g == gen) Fx.fadeIn(img, b) }
        }
    }

    /** A scene card (16:9): the film's picture, its logo or name over the bottom, the bar where it was left. */
    private fun wideCard(p: Pick): View {
        val col = ui.vbox()
        col.clipChildren = false
        val box = FrameLayout(this)
        box.isFocusable = true
        box.isClickable = true
        box.background = ui.round(C.CARD, D.CARD)
        box.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        box.addView(img, ui.fl(ui.match, ui.match))
        loadInto(img, "w780", p.backdrop, WIDE_W)
        val shade = View(this)
        shade.background = ui.gradient(GradientDrawable.Orientation.BOTTOM_TOP, Color.argb(200, 0, 0, 0), Color.TRANSPARENT)
        box.addView(shade, ui.fl(ui.match, ui.u(130), Gravity.BOTTOM))
        val name = ui.text(p.title, 24, C.TEXT, 800)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        val nlp = ui.fl(ui.match, ui.wrap, Gravity.BOTTOM or Gravity.START)
        nlp.setMargins(ui.u(18), 0, ui.u(18), ui.u(22))
        box.addView(name, nlp)
        p.logo.takeIf { it.isNotEmpty() }?.let { path ->
            val logo = ImageView(this)
            logo.scaleType = ImageView.ScaleType.FIT_START
            logo.adjustViewBounds = true
            logo.visibility = View.GONE
            val llp = ui.fl(ui.u(220), ui.u(70), Gravity.BOTTOM or Gravity.START)
            llp.setMargins(ui.u(18), 0, 0, ui.u(22))
            box.addView(logo, llp)
            val g = gen
            pool.execute {
                if (isFinishing || g != gen) return@execute
                val b = Net.bitmap("https://image.tmdb.org/t/p/w300$path", ui.u(220), false)?.takeIf { !Img.isDark(it) }
                runOnUiThread {
                    if (b == null || isFinishing || g != gen) return@runOnUiThread
                    logo.setImageBitmap(b)
                    logo.visibility = View.VISIBLE
                    name.visibility = View.GONE
                }
            }
        }
        WatchMarks.add(ui, box, WatchMarks.of(playback, p))
        val fx = Fx.decorate(ui, box, D.CARD)
        val sub = ui.text(subline(p), 17, C.TEXT3, 500)
        sub.maxLines = 1
        sub.ellipsize = TextUtils.TruncateAt.END
        box.setOnFocusChangeListener { _, f ->
            ui.lift(box, f, 1.06f, 30)
            fx(f)
            sub.setTextColor(if (f) C.TEXT2 else C.TEXT3)
            if (f) stage.focusOn(p)
        }
        box.setTag(R.id.lexia_pick, p)
        box.setOnClickListener { DetailActivity.open(this, p, row = DetailActivity.rowAround(box)) }
        col.addView(box, ui.ll(ui.u(WIDE_W), ui.u(WIDE_H)))
        col.addView(sub, ui.ll(ui.u(WIDE_W), ui.wrap, top = 12))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 28)
        col.tag = box
        return col
    }

    /** The line under a card: the score and the year, or when it comes out. */
    private fun subline(p: Pick): String {
        val bits = ArrayList<String>()
        val d = days(p)
        if (d != null && d > 0) return dateText(p)
        val tmdb = TmdbDiscover.score["${p.kind}:${p.id}"]
        if (p.imdb > 0) bits.add(String.format(tr, "IMDb %.1f", p.imdb))
        else if (tmdb != null && tmdb > 0) bits.add(String.format(tr, "TMDB %.1f", tmdb))
        if (p.genres.isNotEmpty()) bits.add(p.genres.first())
        when {
            from(p, "cinema") -> bits.add("Sinemada")
            d != null && d >= -30 -> bits.add(dateText(p))
            p.year.isNotEmpty() -> bits.add(p.year)
        }
        return bits.joinToString("  ·  ")
    }

    /** "En çok izlenenler": its place as a big outlined number, the poster leaning on it. */
    private fun rankCard(p: Pick, n: Int): View {
        val col = ui.vbox()
        col.clipChildren = false
        val stageBox = FrameLayout(this)
        stageBox.clipChildren = false
        val number = ui.text(n.toString(), 250, Color.argb(210, 255, 255, 255), 800)
        number.paint.style = android.graphics.Paint.Style.STROKE
        number.paint.strokeWidth = ui.uf(4)
        number.includeFontPadding = false
        number.letterSpacing = -0.06f
        // Its foot level with the poster's (the title and score lines are below the poster).
        val numberLp = ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.START)
        numberLp.bottomMargin = ui.u(48)
        stageBox.addView(number, numberLp)
        val view = card(p)
        val poster = view.tag as View
        stageBox.addView(view, ui.fl(ui.wrap, ui.wrap, Gravity.TOP or Gravity.END))
        val numberStyle = poster.onFocusChangeListener
        poster.setOnFocusChangeListener { v, f ->
            numberStyle?.onFocusChange(v, f)
            number.setTextColor(if (f) Color.WHITE else Color.argb(210, 255, 255, 255))
            number.paint.style = if (f) android.graphics.Paint.Style.FILL_AND_STROKE else android.graphics.Paint.Style.STROKE
            number.invalidate()
        }
        // The number sits half under the poster: one digit 130 wide, two digits a little more.
        val width = 200 + if (n >= 10) 190 else 120
        col.addView(stageBox, ui.ll(ui.u(width), ui.wrap))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 20)
        col.tag = poster
        return col
    }

    /** A genre's tile: its best film's picture under the genre's colour, the name large. */
    private fun genreTile(name: String, count: Int, best: Pick?): View =
        tile(name, count, best, genreColors(name)) { openGenre(name) }

    /** "Ruh hâline göre": one tile per mood with at least three titles. */
    private fun addMoodTiles(mine: List<Pick>, today: List<Pick>) {
        val found = moods.map { m -> m to mine.count { m.fits(it) } }.filter { it.second >= 3 }
        if (found.size < 2) return
        addRowTitle("Ruh hâline göre")
        tileLine(found.map { (m, n) ->
            val best = today.filter { m.fits(it) && it.backdrop.isNotEmpty() }.maxByOrNull { it.imdb }
            tile(m.title, n, best, m.colors) { openGenre(MOOD + m.id) }
        })
    }

    /** "Platformlar": one tile per streaming platform in its own colours; a tile opens all of it. */
    private fun addPlatformTiles(mine: List<Pick>, today: List<Pick>) {
        val counts = mine.flatMap { platformsOf(it) }.groupingBy { it }.eachCount().entries
            .filter { it.value >= 3 }.sortedByDescending { it.value }
        if (counts.size < 2) return
        addRowTitle("Platformlar")
        tileLine(counts.take(10).map { (name, n) ->
            val best = today.filter { name in platformsOf(it) && it.backdrop.isNotEmpty() }.maxByOrNull { it.imdb }
            tile(name, n, best, HomePeek.colors(name)) { openGenre(PLAT + name) }
        })
    }

    private fun tileLine(tiles: List<View>) {
        val line = ui.hbox()
        line.clipChildren = false
        line.clipToPadding = false
        line.setPadding(ui.u(4), ui.u(10), ui.u(40), ui.u(10))
        tiles.forEach { line.addView(it) }
        val hs = android.widget.HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.clipChildren = false
        hs.clipToPadding = false
        hs.addView(line)
        grid.addView(hs, ui.ll(ui.match, ui.wrap, bottom = 34))
    }

    /** A big tile: its best film's picture behind its two colours, the name and how many; OK opens [open]. */
    private fun tile(name: String, count: Int, best: Pick?, colors: Pair<Int, Int>, open: () -> Unit): View {
        val col = ui.vbox()
        col.clipChildren = false
        val box = FrameLayout(this)
        box.isFocusable = true
        box.isClickable = true
        val (a, b) = colors
        box.background = ui.round(a, D.CARD)
        box.clipToOutline = true
        if (best != null) {
            val img = ImageView(this)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            box.addView(img, ui.fl(ui.match, ui.match))
            loadInto(img, "w500", best.backdrop, 320)
        }
        val wash = View(this)
        wash.background = ui.gradient(GradientDrawable.Orientation.BL_TR,
            Color.argb(245, Color.red(a), Color.green(a), Color.blue(a)),
            Color.argb(170, Color.red(b), Color.green(b), Color.blue(b)))
        box.addView(wash, ui.fl(ui.match, ui.match))
        val label = ui.vbox()
        label.addView(ui.text(name, 30, Color.WHITE, 800).also { it.maxLines = 1; it.ellipsize = TextUtils.TruncateAt.END })
        label.addView(ui.text("$count ${if (kind == "tv") "dizi" else "film"}", 17, Color.argb(200, 255, 255, 255), 600), ui.ll(ui.wrap, ui.wrap, top = 2))
        val lp = ui.fl(ui.match, ui.wrap, Gravity.BOTTOM or Gravity.START)
        lp.setMargins(ui.u(22), 0, ui.u(16), ui.u(18))
        box.addView(label, lp)
        val ring = View(this)
        ring.background = ui.round(Color.TRANSPARENT, D.CARD, 4, Color.WHITE)
        ring.alpha = 0f
        box.addView(ring, ui.fl(ui.match, ui.match))
        box.setOnFocusChangeListener { _, f ->
            ui.lift(box, f, 1.07f, 30)
            ring.animate().alpha(if (f) 1f else 0f).setDuration(D.FAST).start()
            if (f && best != null) stage.focusOn(best)
        }
        box.setOnClickListener { open() }
        col.addView(box, ui.ll(ui.u(320), ui.u(180)))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 24)
        col.tag = box
        return col
    }

    /** Each genre's own two colours (deep, then lighter); others get one of a few by their name. */
    private fun genreColors(name: String): Pair<Int, Int> {
        val n = name.lowercase(tr)
        fun c(x: String, y: String) = Color.parseColor(x) to Color.parseColor(y)
        return when {
            "korku" in n -> c("#3A0A0F", "#8E1B24")
            "gerilim" in n -> c("#1B1530", "#4B2E83")
            "komedi" in n -> c("#7A4A00", "#E0A21B")
            "aksiyon" in n -> c("#5A1405", "#D9480F")
            "macera" in n -> c("#0D3B2E", "#2F9E44")
            "bilim" in n -> c("#06243F", "#1C7ED6")
            "fantastik" in n || "fantazi" in n -> c("#2B0B46", "#9C36B5")
            "dram" in n -> c("#1F2A44", "#5C7CFA")
            "romantik" in n -> c("#4A0D2A", "#E64980")
            "animasyon" in n || "aile" in n || "çocuk" in n -> c("#003C4F", "#15AABF")
            "suç" in n -> c("#1A1A1A", "#5C5F66")
            "gizem" in n -> c("#14213D", "#3B5BDB")
            "belgesel" in n -> c("#2B2A1A", "#94812B")
            "tarih" in n || "savaş" in n -> c("#3B2A14", "#A0782C")
            "müzik" in n -> c("#3D0A3A", "#D6336C")
            else -> listOf(c("#1F2A44", "#5C7CFA"), c("#0D3B2E", "#2F9E44"), c("#3B2A14", "#A0782C"), c("#2B0B46", "#9C36B5"))[Math.floorMod(n.hashCode(), 4)]
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
