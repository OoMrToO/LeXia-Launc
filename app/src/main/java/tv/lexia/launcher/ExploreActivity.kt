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
    private val playback by lazy { MediaPlaybackStore(this) }
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

        // Filmler · Diziler, then Süz and the trailer marathon.
        tabBar = ui.hbox()
        tabBar.clipChildren = false
        tabBar.clipToPadding = false
        tabBar.setPadding(ui.u(6), ui.u(10), ui.u(6), ui.u(10))
        filterBar = ui.hbox()
        filterBar.clipChildren = false
        filterBar.clipToPadding = false
        tabBar.addView(filterBar)
        page.addView(tabBar, ui.ll(m, w, top = 18, bottom = 10, left = -6))

        grid = ui.vbox()
        grid.clipChildren = false
        grid.clipToPadding = false
        grid.setPadding(ui.u(20), ui.u(10), ui.u(20), ui.u(80))
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
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                all = list
                buildFilters()
                show()
            }
        }.start()
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(D.MED).start()
    }

    private var seenChanges = MediaPlaybackStore.changes

    override fun onResume() {
        super.onResume()
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
        fun tab(label: String, value: String) {
            val b = ui.pill(label, 58, 22, primary = kind == value)
            b.setOnClickListener {
                if (kind == value) return@setOnClickListener
                kind = value
                buildFilters()
                show()
                filterBar.getChildAt(if (value == "movie") 0 else 1)?.requestFocus()
            }
            filterBar.addView(b)
        }
        tab("Filmler", "movie")
        tab("Diziler", "tv")
        val chosen = listOfNotNull(
            genre.takeIf { it.isNotEmpty() },
            yearsName().takeIf { years.isNotEmpty() },
            "IMDb ${minScore.toInt()}+".takeIf { minScore > 0 },
            sortName().takeIf { sort.isNotEmpty() },
            where.takeIf { it.isNotEmpty() },
            lengthName().takeIf { length.isNotEmpty() },
            stateName().takeIf { state.isNotEmpty() },
            smart?.first,
            if (origin == "local") "Yerli" else if (origin == "foreign") "Yabancı" else null,
            country.takeIf { it.isNotEmpty() }?.let { "Ülke: " + countryName(it) }
        )
        // Count active filter dimensions; selected genres remain grouped in the compact bar.
        val activeCount = chosen.size
        val filterLabel = if (activeCount == 0) "Süz" else "Süz  ·  $activeCount seçili"
        val filter = ui.iconPill(filterLabel, R.drawable.ic_search)
        filter.setOnClickListener { openFilters() }
        filterBar.addView(filter, ui.ll(ui.wrap, ui.u(64), left = 22, right = 18))
        if (chosen.isNotEmpty()) {
            val clear = ui.pill("Temizle", 58, 21)
            clear.setOnClickListener {
                genre = ""; minScore = 0.0; years = ""; sort = ""; where = ""; length = ""; state = ""; smart = null; searchTerm = ""; origin = ""; country = ""
                buildFilters()
                show()
                filterBar.getChildAt(2)?.requestFocus()
            }
            filterBar.addView(clear)
        }
        val search = ui.iconPill("Ara", R.drawable.ic_search)
        search.setOnClickListener { SearchAll.ask(this, all) }
        filterBar.addView(search)
        val random = ui.iconPill("🎲 Rastgele", R.drawable.ic_film)
        random.setOnClickListener { randomPick() }
        filterBar.addView(random)
        val clever = ui.iconPill("Akıllı bul", R.drawable.ic_dice)
        clever.setOnClickListener { chooseSmart() }
        filterBar.addView(clever)
        val mine = ui.iconPill("İzlediklerim", R.drawable.ic_history)
        mine.setOnClickListener { WatchHistoryActivity.open(this) }
        filterBar.addView(mine)
        // Trailers of what is shown, one after another, in LeXia's own player.
        if (prefs.trailerInApp) {
            val run = ui.iconPill("Fragman maratonu", R.drawable.ic_film)
            run.setOnClickListener { chooseMarathon() }
            filterBar.addView(run)
        }
    }

    private fun randomPick() {
        val candidates = (if (filtering()) all.filter { matches(it) } else all.filter { ofKind(it) }).filter { !Remote.isHidden(it) && it.key !in prefs.disliked.keys }
        if (candidates.isEmpty()) { Glass.toast(this, "Mevcut seçimde rastgele öneri bulunamadı"); return }
        val picked = candidates.random()
        DetailActivity.open(this, picked, surprise = true, why = "Keşfet seçimlerine göre rastgele seçildi")
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
            "Yapım ülkesi" to (country.takeIf { it.isNotEmpty() }?.let { countryName(it) } ?: "Tüm ülkeler")
        )
        Dialogs.list(this, "Süz", rows, subtitle = if (kind == "tv") "Diziler" else "Filmler", icon = R.drawable.ic_search) { i ->
            fun apply() {
                smart = null
                buildFilters()
                show()
                openFilters()
            }
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
                            runOnUiThread { if (!isFinishing && !isDestroyed) { buildFilters(); show(); openFilters() } }
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
        if (!filtering()) {
            showRows()
            return
        }
        found = smart?.second ?: ordered(all.filter { matches(it) })
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

    private fun showRows() {
        val mine = all.filter { ofKind(it) }
        countText.text = "${mine.size} ${if (kind == "tv") "dizi" else "film"}"
        val today = mine.filter { (days(it) ?: 1) <= 0 || days(it) == null }
        addRow("Kaldığın yerden", leftOff())
        addRow("Yeni çıkanlar", today.filter { val d = days(it); d != null && d in -90..0 }.sortedBy { -(days(it) ?: -999) }) {
            TmdbDiscover.page(kind, it, newest = true)
        }
        addRow("En çok izlenenler", mine.filter { from(it, if (kind == "tv") "top10tv" else "top10") }) {
            TmdbDiscover.page(kind, it)
        }
        addRow("IMDb 8 ve üstü", today.filter { it.imdb >= 8.0 }.sortedByDescending { it.imdb }) {
            TmdbDiscover.page(kind, it, minScore = 8.0)
        }
        addRow("Yakında çıkacaklar", mine.filter { (days(it) ?: 0) > 0 }.sortedBy { days(it) ?: 0 }) {
            TmdbDiscover.upcoming(kind, it)
        }
        if (kind == "tv") {
            addRow("Yerli diziler", mine.filter { from(it, "local") })
            addRow("Yabancı diziler", mine.filter { !from(it, "local") })
        } else {
            addRow("Yerli filmler", mine.filter { from(it, "local") })
            addRow("Yabancı filmler", mine.filter { !from(it, "local") })
        }
        val related = mine.flatMap { it.similar }.filter { it.isSeries == (kind == "tv") }.distinctBy { seenKey(it) }
            .filter { !Remote.isHidden(it) && it.key !in prefs.disliked.keys }
        addRow("Benzer yapımlar", related.sortedByDescending { it.imdb })
        val genres = mine.flatMap { it.genres }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }.take(12)
        for (g in genres) {
            addRow(g, today.filter { g in it.genres }.sortedByDescending { it.imdb }) { TmdbDiscover.page(kind, it, genre = g) }
        }
    }

    /**
     * A row of posters (20 at first). [more], when given, brings the next TMDB page at the row's end:
     * titles already on the page are left out.
     */
    private fun addRow(title: String, items: List<Pick>, more: ((Int) -> List<Pick>)? = null) {
        val online = more != null && BuildConfig.TMDB_API_KEY.isNotBlank()
        if (items.isEmpty() && !online) return
        val label = ui.text(title, 26, C.TEXT, 700)
        grid.addView(label, ui.ll(ui.wrap, ui.wrap, top = 8, bottom = 14, left = 4))
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
        start.forEach { p -> seen.add(seenKey(p)); line.addView(card(p)) }
        if (more == null) return
        var page = 1
        var moreCard: View? = null
        fun addMoreCard() {
            moreCard = moreCard(0).also { c ->
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
                            fresh.forEach { p -> val v = card(p); line.addView(v); if (first == null) first = v.tag as View }
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

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
