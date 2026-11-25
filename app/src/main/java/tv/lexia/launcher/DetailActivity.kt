package tv.lexia.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale

/** Full-screen film page: picture, story, cast and similar films. */
class DetailActivity : Activity() {

    companion object {
        /** Keys on pick.json's "cinema" and "soon" lists, read once per run. */
        @Volatile private var cinemaKeys: Pair<Set<String>, Set<String>>? = null
        @Volatile private var cinemaKeysAt = 0L

        private const val EXTRA = "film"
        private const val SURPRISE = "surprise"
        private const val WHY = "why"

        /** Keys on pick.json's "Sinemada" and "Yakında" lists; call off the main thread (kept until the file changes). */
        fun cinemaLists(ctx: android.content.Context): Pair<Set<String>, Set<String>>? {
            val file = java.io.File(ctx.filesDir, "pick.json")
            if (cinemaKeysAt != file.lastModified()) cinemaKeys = null
            return cinemaKeys ?: try {
                val o = JSONObject(file.readText())
                fun keys(name: String): Set<String> {
                    val a = o.optJSONArray(name) ?: return emptySet()
                    return (0 until a.length()).map { Pick.from(a.getJSONObject(it)).key }.toSet()
                }
                (keys("cinema") to keys("soon")).also { cinemaKeys = it; cinemaKeysAt = file.lastModified() }
            } catch (_: Exception) {
                null
            }
        }

        /** The films of the row the page was opened from, for ◀ ▶ (set by [open]). */
        @Volatile private var siblings: List<Pick> = emptyList()

        /** The films in the row around [view] (posters carry their film under R.id.lexia_pick). */
        fun rowAround(view: View?): List<Pick> {
            var row: View? = view
            repeat(3) {
                val parent = row?.parent as? android.view.ViewGroup ?: return emptyList()
                val found = ArrayList<Pick>()
                fun collect(v: View, depth: Int) {
                    (v.getTag(R.id.lexia_pick) as? Pick)?.let { found.add(it); return }
                    if (depth > 0 && v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), depth - 1)
                }
                for (i in 0 until parent.childCount) collect(parent.getChildAt(i), 2)
                if (found.size > 1) return found.distinctBy { it.key }
                row = parent
            }
            return emptyList()
        }

        fun open(ctx: Context, p: Pick, surprise: Boolean = false, why: String = "", row: List<Pick>? = null) {
            if (row != null) siblings = row
            ctx.startActivity(
                Intent(ctx, DetailActivity::class.java)
                    .putExtra(EXTRA, p.toJson().toString())
                    .putExtra(SURPRISE, surprise)
                    .putExtra(WHY, why)
            )
        }
    }

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var film: Pick
    private lateinit var listBtn: TextView
    private lateinit var trailerBtn: TextView
    private lateinit var playBtn: TextView
    private lateinit var remindBtn: TextView
    private lateinit var nightBtn: TextView
    private lateinit var watchedBtn: TextView
    private lateinit var likeBtn: TextView
    private lateinit var notBtn: TextView
    private lateinit var scroll: ScrollView
    private lateinit var hero: View
    private lateinit var frame: FrameLayout
    private lateinit var back: ImageView
    private lateinit var stuck: View
    /** Frosted glass: the film's picture blurred, coming up as the page scrolls down (Apple TV). */
    private lateinit var frost: ImageView
    private lateinit var frostShade: View


    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean =
        Pointer.handle(this, ev) || super.dispatchGenericMotionEvent(ev)

    /** The film's own colour, read from its picture (0 until it is in). */
    private var filmAccent = 0
    /** The "watched so far" bars on episode cards. */
    private val progressFills = ArrayList<View>()
    /** Lines that take the film's colour ("3. sezon, 5. bölüm"). */
    private val accentTexts = ArrayList<android.widget.TextView>()

    /** The bar's colour: the film's light, lifted a little; soft white before the picture is in. */
    private fun barColor(): Int =
        if (filmAccent != 0) Fx.blend(filmAccent, Color.WHITE, 0.3f) else Color.argb(230, 255, 255, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        film = try {
            Pick.from(JSONObject(intent.getStringExtra(EXTRA) ?: "{}"))
        } catch (e: Exception) {
            finish()
            return
        }
        // "Fragmanı İzle" opens at once with the trailer add-on's Turkish trailer, asked now in the background.
        StreailerTrailers.prefetch(film)
        val m = ui.match
        val w = ui.wrap
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        back = ImageView(this)
        back.scaleType = ImageView.ScaleType.CENTER_CROP
        back.alpha = 0f
        frame.addView(back, ui.fl(m, m))
        // The picture turns into the trailer, without sound, after a few seconds (as on the home screen).
        trailerStage = FrameLayout(this)
        frame.addView(trailerStage, ui.fl(m, m))
        pageTrailer = HeroTrailer(this, trailerStage)
        // The film's own colour, softly over the shade (sand for Dune, green for The Matrix).
        val tint = View(this)
        tint.alpha = 0f
        frost = ImageView(this)
        frost.scaleType = ImageView.ScaleType.CENTER_CROP
        frost.alpha = 0f
        frostShade = View(this)
        frostShade.setBackgroundColor(Color.argb(120, 8, 10, 14))
        frostShade.alpha = 0f
        Img.load(this, if (screenW > 1280) "original" else "w1280", film.backdrop, screenW, back) {
            back.animate().alpha(1f).setDuration(700).start()
            val bmp = (back.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            bmp?.let { Fx.blurred(it) }?.let { frost.setImageBitmap(it) }
            val c = bmp?.let { Fx.accent(it) } ?: 0
            if (c != 0) {
                filmAccent = c
                // Episode cards drawn before the picture came in take the film's colour now.
                progressFills.forEach { it.setBackgroundColor(barColor()) }
                accentTexts.forEach { it.setTextColor(barColor()) }
                tint.background = ui.gradient(
                    GradientDrawable.Orientation.BL_TR,
                    Color.argb(95, Color.red(c), Color.green(c), Color.blue(c)),
                    Color.argb(35, Color.red(c), Color.green(c), Color.blue(c)),
                    Color.argb(0, 0, 0, 0)
                )
                tint.animate().alpha(1f).setDuration(900).start()
            }
        }
        val left = View(this)
        left.background = ui.gradient(
            GradientDrawable.Orientation.LEFT_RIGHT,
            Color.argb(235, 0, 0, 0), Color.argb(140, 0, 0, 0), Color.argb(10, 0, 0, 0)
        )
        frame.addView(left, ui.fl(m, m))
        val bottom = View(this)
        bottom.background = ui.gradient(
            GradientDrawable.Orientation.TOP_BOTTOM,
            Color.argb(0, 0, 0, 0), Color.argb(200, 0, 0, 0), Color.argb(250, 0, 0, 0)
        )
        frame.addView(bottom, ui.fl(m, (screenH * 0.55f).toInt(), Gravity.BOTTOM))
        frame.addView(tint, ui.fl(m, m))
        frame.addView(frost, ui.fl(m, m))
        frame.addView(frostShade, ui.fl(m, m))

        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isSmoothScrollingEnabled = true
        val col = ui.vbox()
        col.clipChildren = false
        col.clipToPadding = false
        col.setPadding(ui.u(110), 0, ui.u(110), ui.u(70))
        scroll.addView(col)
        frame.addView(scroll, ui.fl(m, m))

        // First screen: everything about the film, sitting low like a poster.
        val top = ui.vbox()
        top.gravity = Gravity.BOTTOM
        top.clipChildren = false
        top.setPadding(0, 0, 0, ui.u(60))
        hero = top
        val surprise = intent.getBooleanExtra(SURPRISE, false)
        val kindName = if (film.isSeries) "DİZİ" else "FİLM"
        val upcoming = isUpcoming()
        val tagText = when {
            surprise -> "BU AKŞAM İÇİN SEÇTİM"
            upcoming -> "YAKINDA  ·  " + releaseText().uppercase(Locale("tr", "TR"))
            else -> kindName
        }
        val tag = ui.label(tagText)
        top.addView(tag)
        val why = intent.getStringExtra(WHY) ?: ""
        if (surprise && why.isNotEmpty()) {
            val reason = ui.text("Neden bu film:  $why", 20, C.TEXT2, 500)
            top.addView(reason, ui.ll(w, w, top = 10))
        }
        val logo = ImageView(this)
        logo.adjustViewBounds = true
        logo.maxHeight = ui.u(200)
        logo.maxWidth = ui.u(720)
        logo.scaleType = ImageView.ScaleType.FIT_START
        logo.visibility = View.GONE
        val title = ui.text(film.title, 80, C.TEXT, 800)
        title.letterSpacing = -0.025f
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        title.maxWidth = ui.u(1000)
        title.setShadowLayer(ui.uf(30), 0f, ui.uf(4), Color.argb(130, 0, 0, 0))
        top.addView(logo, ui.ll(w, w, top = 20))
        top.addView(title, ui.ll(w, w, top = 12))
        if (film.logo.isNotEmpty()) {
            Img.load(this, "w500", film.logo, ui.u(720), logo, opaque = false, skipDark = true) { ok ->
                if (ok) {
                    logo.visibility = View.VISIBLE
                    title.visibility = View.GONE
                }
            }
        }
        if (film.tagline.isNotEmpty()) {
            val t = ui.text(film.tagline, 25, C.TEXT2, 500)
            t.maxWidth = ui.u(900)
            top.addView(t, ui.ll(w, w, top = 14))
        }
        val meta = ui.hbox()
        if (film.imdb > 0) {
            val imdb = ui.text("IMDb", 15, Color.BLACK, 800)
            imdb.setPadding(ui.u(9), ui.u(3), ui.u(9), ui.u(3))
            imdb.background = ui.round(Color.parseColor("#F5C518"), D.BADGE)
            meta.addView(imdb)
            meta.addView(
                ui.text(String.format(Locale("tr", "TR"), "%.1f", film.imdb), 22, C.TEXT, 600),
                ui.ll(w, w, left = 12, right = 26)
            )
        }
        if (film.cert.isNotEmpty()) meta.addView(ui.certBadge(film.cert), ui.ll(w, w, right = 24))
        meta.addView(ui.text(film.details(), 21, C.TEXT2, 500))
        top.addView(meta, ui.ll(w, w, top = 22))
        Episodes.of(film)?.let {
            val ep = ui.text(Episodes.line(it), 21, C.TEXT, 600)
            // The film's own colour once its picture is in.
            accentTexts.add(ep)
            if (filmAccent != 0) ep.setTextColor(barColor())
            top.addView(ep, ui.ll(w, w, top = 16))
        }
        // Tonight's plan: when it ends if started now (from where it was left), and by when to start.
        tonight = ui.text("", 20, C.TEXT, 500)
        tonight.alpha = 0.9f
        top.addView(tonight, ui.ll(w, w, top = 14))
        updateTonight()
        // Where it streams in Turkey ("Nerede izlenir"); nothing is played here.
        val platforms = (listOfNotNull(film.provider.takeIf { it.isNotEmpty() }) + film.where).distinct()
        if (platforms.isNotEmpty()) {
            val line = ui.text("İzlenebileceği yer:  " + platforms.joinToString("  ·  "), 21, C.TEXT2, 600)
            top.addView(line, ui.ll(w, w, top = 14))
        }
        // Free / ad-supported services are shown separately so subscription availability isn't confused with free viewing.
        val freePlatforms = film.free.distinct()
        if (freePlatforms.isNotEmpty()) {
            val freeCard = ui.vbox()
            freeCard.setPadding(ui.u(18), ui.u(12), ui.u(18), ui.u(12))
            freeCard.background = GradientDrawable().apply {
                setColor(Color.argb(150, 22, 34, 29))
                cornerRadius = ui.uf(16)
                setStroke(ui.u(1), Color.argb(100, 100, 220, 160))
            }
            val freeTitle = ui.text("ÜCRETSİZ İZLEME", 14, Color.parseColor("#9BE6B9"), 800)
            freeTitle.letterSpacing = 0.08f
            freeCard.addView(freeTitle)
            val freeLine = ui.text(freePlatforms.joinToString("  ·  "), 20, C.TEXT, 600)
            freeLine.maxLines = 2
            freeCard.addView(freeLine, ui.ll(w, w, top = 6))
            top.addView(freeCard, ui.ll(w, w, top = 12))
        }
        if (film.overview.isNotBlank()) {
            val storyLabel = ui.text("HİKÂYE", 13, C.TEXT3, 800)
            storyLabel.letterSpacing = 0.14f
            top.addView(storyLabel, ui.ll(w, w, top = 24))
            val story = ui.text(film.overview, 21, C.TEXT, 400)
            story.maxWidth = ui.u(940)
            story.maxLines = 6
            story.ellipsize = TextUtils.TruncateAt.END
            story.setLineSpacing(0f, 1.35f)
            story.setPadding(ui.u(20), ui.u(16), ui.u(20), ui.u(16))
            story.isFocusable = true
            story.isClickable = true
            story.contentDescription = "Hikâye. Açmak veya kapatmak için OK tuşuna bas"
            fun styleStory(focused: Boolean) {
                // Dark glass; selected, the white plate with dark text, as everywhere.
                story.background = if (focused) ui.round(Color.WHITE, 18)
                else ui.round(Color.argb(30, 255, 255, 255), 18, 1, Color.argb(30, 255, 255, 255))
                story.setTextColor(if (focused) C.INK else C.TEXT)
                story.scaleX = if (focused) 1.015f else 1f
                story.scaleY = if (focused) 1.015f else 1f
            }
            styleStory(false)
            story.setOnFocusChangeListener { v, focused ->
                styleStory(focused)
                ui.lift(v, focused, 1.015f, 14)
            }
            story.setOnClickListener {
                val expanded = story.maxLines == Integer.MAX_VALUE
                story.maxLines = if (expanded) 6 else Integer.MAX_VALUE
                story.ellipsize = if (expanded) TextUtils.TruncateAt.END else null
                story.contentDescription = if (expanded) "Hikâye kısaltıldı. Açmak için OK tuşuna bas" else "Hikâye tamamı gösteriliyor. Kapatmak için OK tuşuna bas"
                story.text = film.overview
            }
            top.addView(story, ui.ll(w, w, top = 10))
        }
        if (film.director.isNotEmpty()) {
            val role = if (film.isSeries) "Yaratıcı" else "Yönetmen"
            val d = ui.text("$role: ${film.director}", 20, C.TEXT2, 500)
            top.addView(d, ui.ll(w, w, top = 16))
        }
        val buttons = ui.hbox()
        heroButtons = buttons
        buttons.clipChildren = false
        playBtn = ui.pill("▶   Oynat", 64, 23, primary = true)
        playBtn.setOnFocusChangeListener { _, f ->
            stylePlay(f)
            ui.lift(playBtn, f, 1.08f, 18)
        }
        if (!film.isSeries && isUpcoming()) cinemaState = "soon"
        playBtn.setOnClickListener {
            if (cinemaState.isNotEmpty() && MediaPlaybackStore(this).last(film.key).isBlank()) {
                Glass.toast(
                    this,
                    if (cinemaState == "now") "Film şu an sinemalarda. Dijitale çıkınca burada Oynat görünecek"
                    else "Henüz vizyona girmedi. Hatırlat'a basarsan o gün haber veririm",
                    long = true
                )
            } else if (film.id <= 0) {
                Glass.toast(this, "Bu içerik için TMDB kimliği bulunamadı")
            } else {
                // A series continues on the episode it was left at (or the one after a finished one).
                MediaSourcePickerActivity.open(this, film, resumeEpisode = continueEpisode)
            }
        }
        updatePlayBtn()
        if (!film.isSeries && cinemaState.isEmpty()) loadCinemaState()
        val trailer = ui.pill("▶   Fragmanı izle", 64, 23, primary = false)
        trailer.setOnClickListener {
            if (film.trailer.isEmpty()) {
                Glass.toast(this, "Fragman bulunamadı")
            } else {
                try {
                    TrailerActivity.open(this, film)
                } catch (e: Exception) {
                    Glass.toast(this, "Açılamadı")
                }
            }
        }
        if (film.trailer.isEmpty()) trailer.visibility = View.GONE
        trailerBtn = trailer
        // A title from TMDB (Keşfet's "Daha fazla", a person's films) comes without its trailer.
        if (film.trailer.isEmpty() && film.id > 0) completeFromTmdb()
        listBtn = ui.pill("", 64, 23)
        listBtn.setOnClickListener { toggleList() }
        updateListBtn()
        watchedBtn = ui.iconPill("İzledim", R.drawable.ic_eye)
        watchedBtn.setOnClickListener { toggleWatched() }
        likeBtn = ui.iconPill("Beğendim", R.drawable.ic_heart_outline)
        likeBtn.setOnClickListener { toggleLike() }
        notBtn = ui.iconPill("Bana önerme", R.drawable.ic_not)
        notBtn.setOnClickListener { toggleNot() }
        updateMarks()
        if (upcoming) {
            // Not out yet in Turkey: nothing to have watched.
            watchedBtn.visibility = View.GONE
            likeBtn.visibility = View.GONE
            notBtn.visibility = View.GONE
        }
        remindBtn = ui.pill("", 64, 23)
        remindBtn.setOnClickListener { toggleReminder() }
        updateRemindBtn()
        if (!upcoming) remindBtn.visibility = View.GONE
        // Film gecesi: plan this one for a time tonight.
        nightBtn = ui.iconPill("", R.drawable.ic_moon)
        nightBtn.setOnClickListener { planNight() }
        updateNightBtn()
        if (upcoming || Remote.isOff("night")) nightBtn.visibility = View.GONE
        val another = ui.pill("Başka öner", 64, 23)
        another.setOnClickListener {
            Surprise.next(this)
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        if (!surprise) another.visibility = View.GONE
        for (b in listOf(playBtn, trailer, listBtn, nightBtn, watchedBtn, likeBtn, notBtn, remindBtn, another)) {
            val own = b.onFocusChangeListener
            b.setOnFocusChangeListener { v, f ->
                own?.onFocusChange(v, f)
                if (f && scroll.scrollY > 0) {
                    scroll.smoothScrollTo(0, 0)
                    hero.animate().alpha(1f).setDuration(D.MED).start()
                }
            }
        }
        buttons.addView(playBtn)
        // A film LeXia picked for tonight: "Başka öner" right beside "Oynat", no scrolling to it.
        if (surprise) buttons.addView(another)
        buttons.addView(trailer)
        buttons.addView(listBtn)
        buttons.addView(nightBtn)
        buttons.addView(watchedBtn)
        buttons.addView(likeBtn)
        buttons.addView(notBtn)
        buttons.addView(remindBtn)
        top.addView(buttons, ui.ll(w, w, top = 34))
        // Opened from a row: a quiet line says the row's other films are a press away.
        val at = siblings.indexOfFirst { it.key == film.key }
        if (at >= 0 && siblings.size > 1) {
            val before = siblings.getOrNull(at - 1)?.title
            val after = siblings.getOrNull(at + 1)?.title
            val hint = listOfNotNull(before?.let { "◀  $it" }, after?.let { "$it  ▶" }).joinToString("        ")
            val line = ui.text(hint, 17, C.TEXT3, 500)
            line.maxLines = 1
            line.ellipsize = TextUtils.TruncateAt.END
            top.addView(line, ui.ll(w, w, top = 18))
        }
        col.addView(top, ui.ll(m, screenH))

        // A series: its seasons and episodes on this page; an episode opens its sources.
        val hasEpisodes = film.isSeries && film.id > 0
        // Apple TV: under the first screen, the page's parts as tabs; each scrolls to its part.
        val tabs = ui.hbox()
        tabs.clipChildren = false
        tabs.clipToPadding = false
        tabs.setPadding(ui.u(6), ui.u(10), ui.u(6), ui.u(10))
        col.addView(tabs, ui.ll(m, w, top = 0, bottom = 24, left = -6))
        val parts = ArrayList<Pair<String, View>>()
        if (hasEpisodes) {
            val episodesLabel = label("BÖLÜMLER")
            parts.add("Bölümler" to episodesLabel)
            col.addView(episodesLabel, ui.ll(w, w, top = 10))
            episodeSummary = ui.text("", 19, C.TEXT2, 500)
            episodeSummary.visibility = View.GONE
            col.addView(episodeSummary, ui.ll(w, w, top = 6))
            seasonRow = ui.hbox()
            col.addView(rowOf(seasonRow), rowParams())
            episodeRow = ui.hbox()
            episodeNote = ui.text("Bölümler yükleniyor…", 19, C.TEXT2, 500)
            episodeRow.addView(episodeNote)
            col.addView(rowOf(episodeRow), rowParams())
            loadEpisodes()
        }
        if (film.cast.isNotEmpty() || film.director.isNotEmpty()) {
            val castLabel = label(if (film.director.isNotEmpty()) "YÖNETMEN VE OYUNCULAR" else "OYUNCULAR")
            parts.add("Oyuncular" to castLabel)
            col.addView(castLabel, ui.ll(w, w, top = if (hasEpisodes) 40 else 10))
            val row = ui.hbox()
            // The director first (each name of a few), then the cast.
            film.director.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(2).forEach { d ->
                row.addView(castCard(CastMember(d, if (film.isSeries) "Yaratıcı" else "Yönetmen", "")))
            }
            for (c in film.cast) row.addView(castCard(c))
            col.addView(rowOf(row), rowParams())
        }
        if (film.similar.isNotEmpty()) {
            val similarLabel = label(if (film.isSeries) "BENZER DİZİLER" else "BENZER FİLMLER")
            parts.add("Benzerleri" to similarLabel)
            col.addView(similarLabel, ui.ll(w, w, top = 40))
            val row = ui.hbox()
            for (s in film.similar) row.addView(similarCard(s))
            col.addView(rowOf(row), rowParams())
        }
        val about = aboutBlock()
        parts.add("Hakkında" to about)
        col.addView(about, ui.ll(m, w, top = 46))
        for ((name, target) in parts) {
            val chip = ui.pill(name, 54, 20)
            chip.setOnClickListener {
                val y = (target.top - ui.u(170)).coerceAtLeast(0)
                scroll.smoothScrollTo(0, y)
            }
            tabs.addView(chip, ui.ll(w, ui.u(54), right = 14))
        }

        // Scrolling down: the picture drifts slower than the page, and the name stays on top.
        stuck = stickyTitle()
        frame.addView(stuck, ui.fl(m, w, Gravity.TOP))
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            // Down to the cast and similar films: the trailer gives the screen back.
            if (y > 0) stopPageTrailer() else queuePageTrailer()
            if (!Fx.lite) {
                back.translationY = -y * 0.22f
                val z = 1f + (y.toFloat() / screenH).coerceIn(0f, 1f) * 0.05f
                back.scaleX = z
                back.scaleY = z
            }
            // Frosted glass rises as the page goes down: the film stays behind, softly.
            val g = (y / (screenH * 0.45f)).coerceIn(0f, 1f)
            frost.alpha = g
            frostShade.alpha = g
            if (!Fx.lite) {
                frost.translationY = back.translationY
                frost.scaleX = back.scaleX
                frost.scaleY = back.scaleY
            }
            val t = ((y - screenH * 0.42f) / (screenH * 0.18f)).coerceIn(0f, 1f)
            stuck.alpha = t
            stuck.translationY = -ui.uf(18) * (1 - t)
        }

        setContentView(frame)
        Sound.attach(this)
        Sound.event(Sound.OPEN)
        prefs.addRecent(film)
        playBtn.requestFocus()

        col.alpha = 0f
        col.translationY = ui.uf(20)
        col.animate().alpha(1f).translationY(0f).setDuration(600).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    /** The film's name and line in a slim bar at the top, once the page has scrolled to the rows. */
    /**
     * Scrolled down: a thin strip of dark glass at the top with the film's logo (or name) and its
     * facts, so the page never loses whose it is (as on Apple TV).
     */
    private fun stickyTitle(): View {
        val bar = ui.hbox()
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(ui.u(110), ui.u(24), ui.u(110), ui.u(24))
        bar.background = android.graphics.drawable.LayerDrawable(
            arrayOf(
                android.graphics.drawable.ColorDrawable(Color.argb(215, 14, 14, 18)),
                ui.gradient(GradientDrawable.Orientation.BOTTOM_TOP, Color.argb(40, 255, 255, 255), Color.TRANSPARENT)
            )
        ).also { it.setLayerHeight(1, maxOf(1, ui.u(2))); it.setLayerGravity(1, Gravity.BOTTOM) }
        val t = ui.text(film.title, 30, C.TEXT, 700)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        if (film.logo.isNotEmpty()) {
            val mark = ImageView(this)
            mark.adjustViewBounds = true
            mark.maxHeight = ui.u(58)
            mark.maxWidth = ui.u(300)
            mark.scaleType = ImageView.ScaleType.FIT_START
            mark.visibility = View.GONE
            bar.addView(mark, ui.ll(ui.wrap, ui.wrap, right = 30))
            Img.load(this, "w500", film.logo, ui.u(300), mark, opaque = false, skipDark = true) { ok ->
                if (ok) {
                    mark.visibility = View.VISIBLE
                    t.visibility = View.GONE
                }
            }
        }
        bar.addView(t, ui.ll(ui.wrap, ui.wrap, right = 30))
        val line = ui.text(film.details(2), 19, C.TEXT2, 500)
        line.maxLines = 1
        bar.addView(line, ui.ll(0, ui.wrap, weight = 1f))
        bar.alpha = 0f
        return bar
    }

    /** "Hakkında": the whole story and the facts, in two columns, as on Apple TV's film page. */
    private fun aboutBlock(): View {
        val box = ui.vbox()
        box.addView(label("HAKKINDA"))
        val body = ui.hbox()
        val story = ui.text(film.overview.ifBlank { "Bu yapımın Türkçe özeti yok." }, 22, Color.argb(225, 255, 255, 255), 400)
        story.setLineSpacing(0f, 1.3f)
        body.addView(story, ui.ll(0, ui.wrap, weight = 1.4f, right = 70))
        val facts = ui.vbox()
        fun fact(name: String, value: String) {
            if (value.isBlank()) return
            facts.addView(ui.text(name.uppercase(Locale("tr", "TR")), 15, C.TEXT3, 700).also { it.letterSpacing = 0.14f }, ui.ll(ui.wrap, ui.wrap, top = if (facts.childCount == 0) 0 else 18))
            facts.addView(ui.text(value, 21, C.TEXT, 500), ui.ll(ui.wrap, ui.wrap, top = 4))
        }
        fact("Tür", film.genres.joinToString(", "))
        fact(if (film.isSeries) "Yaratıcı" else "Yönetmen", film.director)
        fact("Yıl", film.year)
        if (film.runtime > 0) fact("Süre", if (film.runtime >= 60) "${film.runtime / 60} sa ${film.runtime % 60} dk" else "${film.runtime} dk")
        if (film.imdb > 0) fact("IMDb", String.format(Locale("tr", "TR"), "%.1f", film.imdb))
        fact("Yaş sınırı", film.cert)
        fact("Başroller", film.cast.take(4).joinToString(", ") { it.name })
        body.addView(facts, ui.ll(0, ui.wrap, weight = 1f))
        box.addView(body, ui.ll(ui.match, ui.wrap, top = 18))
        return box
    }

    private fun label(s: String): TextView {
        return ui.label(s)
    }

    private fun rowOf(r: LinearLayout): HorizontalScrollView {
        r.clipChildren = false
        r.clipToPadding = false
        val s = HorizontalScrollView(this)
        s.isHorizontalScrollBarEnabled = false
        s.overScrollMode = View.OVER_SCROLL_NEVER
        s.clipChildren = false
        s.clipToPadding = false
        s.setPadding(ui.u(30), ui.u(26), ui.u(30), ui.u(10))
        Fx.softEdges(s, ui)
        s.addView(r)
        return s
    }

    private fun rowParams(): LinearLayout.LayoutParams {
        val p = ui.ll(ui.match, ui.wrap)
        p.leftMargin = -ui.u(30)
        p.rightMargin = -ui.u(30)
        return p
    }

    /** Pulls the cast and similar rows into view; the hero fades while they are focused. */
    private fun onRowFocus(v: View) {
        hero.animate().alpha(0.25f).setDuration(D.MED).start()
        v.post {
            val r = android.graphics.Rect()
            v.getDrawingRect(r)
            scroll.offsetDescendantRectToMyCoords(v, r)
            val target = (r.bottom - scroll.height + ui.u(140)).coerceAtLeast(0)
            scroll.smoothScrollTo(0, target)
        }
    }

    private fun castCard(c: CastMember): View {
        val col = ui.vbox()
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.clipChildren = false
        val photo = FrameLayout(this)
        photo.isFocusable = true
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#2A2B30"))
        photo.background = bg
        photo.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        photo.addView(img, ui.fl(ui.match, ui.match))
        if (c.photo.isNotEmpty()) {
            Img.load(this, "w185", c.photo, ui.u(150), img)
        } else {
            val initials = c.name.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }
            val t = ui.text(initials, 40, C.TEXT2, 600)
            t.gravity = Gravity.CENTER
            photo.addView(t, ui.fl(ui.match, ui.match))
        }
        photo.setOnFocusChangeListener { v, f ->
            ui.lift(v, f, 1.1f, 24)
            if (f) onRowFocus(v)
        }
        // Their page: photo, a few lines, everything they made.
        photo.isClickable = true
        photo.setOnClickListener { PersonActivity.open(this, c.name, directing = c.role == "Yönetmen" || c.role == "Yaratıcı") }
        col.addView(photo, ui.ll(ui.u(140), ui.u(140)))
        val name = ui.text(c.name, 19, C.TEXT, 600)
        name.gravity = Gravity.CENTER
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        col.addView(name, ui.ll(ui.u(170), ui.wrap, top = 16))
        if (c.role.isNotEmpty()) {
            val role = ui.text(c.role, 16, C.TEXT3, 400)
            role.gravity = Gravity.CENTER
            role.maxLines = 1
            role.ellipsize = TextUtils.TruncateAt.END
            col.addView(role, ui.ll(ui.u(170), ui.wrap, top = 4))
        }
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 24)
        return col
    }

    private lateinit var seasonRow: LinearLayout
    private lateinit var episodeRow: LinearLayout
    private lateinit var episodeNote: TextView
    private lateinit var episodeSummary: TextView
    private var episodeList: List<MediaEpisode> = emptyList()
    private var shownSeason = -1
    private val turkishSeasons = HashSet<Int>()
    private val playback by lazy { MediaPlaybackStore(this) }

    /** The episodes from Nuvio's details source (and TMDB), off the main thread. */
    private fun loadEpisodes() {
        Thread {
            val repository = MediaAddonRepository(this)
            val imdb = film.imdbId.ifBlank {
                try { repository.imdbId("tv", film.id) } catch (_: Exception) { "" }
            }
            val found = try { repository.fetchEpisodes(film.id, imdb) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                episodeList = found
                if (found.isEmpty()) {
                    episodeNote.text = "Bölüm listesi bulunamadı. Oynat ile kaynak sayfasından seçebilirsin."
                    return@runOnUiThread
                }
                // The season of the episode last watched, else the first real season.
                val lastSeason = Regex(":(\\d+):\\d+$").find(playback.last(film.key))?.groupValues?.get(1)?.toIntOrNull()
                val seasons = seasonsOf(found)
                shownSeason = lastSeason?.takeIf { it in seasons } ?: seasons.first()
                showEpisodeSummary()
                renderSeasons()
                renderEpisodes()
            }
        }.start()
    }

    /**
     * Under "BÖLÜMLER": what is left to watch and about how long it takes, and the next new
     * episode's day ("Kalan 14 bölüm · yaklaşık 11 saat · Yeni bölüm Perşembe").
     */
    private fun showEpisodeSummary() {
        val real = episodeList.filter { it.season > 0 }
        val aired = real.filter { !isUnaired(it) }
        if (aired.isEmpty()) return
        val left = aired.filter { !playback.isFinished(it.id) }
        val known = aired.filter { it.runtime > 0 }
        val usual = if (known.isNotEmpty()) known.sumOf { it.runtime } / known.size else film.runtime.takeIf { it in 10..120 } ?: 45
        val parts = ArrayList<String>()
        if (left.isEmpty()) {
            parts.add("Hepsini izledin")
        } else {
            val minutes = left.sumOf { if (it.runtime > 0) it.runtime else usual }
            parts.add(if (left.size == aired.size) "${aired.size} bölüm" else "Kalan ${left.size} bölüm")
            parts.add(if (minutes < 60) "$minutes dk" else "yaklaşık ${(minutes + 30) / 60} saat")
        }
        real.filter { isUnaired(it) }.minByOrNull { it.released.take(10) }?.let { parts.add("Yeni bölüm " + dayName(it.released.take(10))) }
        episodeSummary.text = parts.joinToString("  ·  ")
        episodeSummary.visibility = View.VISIBLE
    }

    /** "bugün", "yarın", "Perşembe" within the week, else "27 Kasım". */
    private fun dayName(day: String): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val at = try { fmt.parse(day) } catch (_: Exception) { null } ?: return day
        val today = fmt.parse(fmt.format(java.util.Date()))?.time ?: return day
        val days = ((at.time - today + 3_600_000L) / 86_400_000L).toInt()
        val tr = Locale.forLanguageTag("tr-TR")
        return when {
            days <= 0 -> "bugün"
            days == 1 -> "yarın"
            days < 7 -> java.text.SimpleDateFormat("EEEE", tr).format(at)
            else -> java.text.SimpleDateFormat("d MMMM", tr).format(at)
        }
    }

    /** Real seasons first, specials last. */
    private fun seasonsOf(list: List<MediaEpisode>) =
        list.map { it.season }.distinct().sortedWith(compareBy({ it <= 0 }, { it }))

    private fun renderSeasons() {
        seasonRow.removeAllViews()
        val seasons = seasonsOf(episodeList)
        if (seasons.size < 2) return
        seasons.forEach { number ->
            val chosen = number == shownSeason
            val pill = ui.pill(if (number > 0) "$number. Sezon" else "Özel bölümler", 54, 19, primary = chosen)
            val style = pill.onFocusChangeListener
            pill.setOnFocusChangeListener { v, f ->
                style?.onFocusChange(v, f)
                if (f) onRowFocus(v)
            }
            pill.setOnClickListener {
                if (shownSeason == number) return@setOnClickListener
                shownSeason = number
                renderSeasons()
                renderEpisodes()
                seasonRow.findViewWithTag<View>("season:$number")?.requestFocus()
            }
            pill.tag = "season:$number"
            seasonRow.addView(pill, ui.ll(ui.wrap, ui.wrap, right = 16))
        }
    }

    private fun renderEpisodes() {
        if (!::episodeRow.isInitialized || episodeList.isEmpty()) return
        val focusedTag = (currentFocus?.tag as? String)?.takeIf { it.startsWith("episode:") }
        episodeRow.removeAllViews()
        progressFills.clear()
        episodeList.filter { it.season == shownSeason }.forEach { episodeRow.addView(episodeCard(it)) }
        focusedTag?.let { tag -> episodeRow.findViewWithTag<View>(tag)?.requestFocus() }
        enrichSeason(shownSeason)
    }

    /** The shown season's names, summaries and stills in Turkish from TMDB, once. */
    private fun enrichSeason(number: Int) {
        if (!turkishSeasons.add(number)) return
        Thread {
            val turkish = try { MediaAddonRepository(this).tmdbSeason(film.id, number) } catch (_: Exception) { emptyMap() }
            if (turkish.isEmpty()) return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                episodeList = episodeList.map { e ->
                    val tr = turkish[e.episode]
                    if (e.season != number || tr == null) e else e.copy(
                        title = tr.title.takeIf { it.isNotBlank() && !Regex("^(\\d+\\.\\s*)?(bölüm|episode)(\\s*\\d+)?$", RegexOption.IGNORE_CASE).matches(it.trim()) } ?: e.title,
                        overview = tr.overview.ifBlank { e.overview },
                        thumbnail = e.thumbnail.ifBlank { tr.thumbnail },
                        released = e.released.ifBlank { tr.released },
                        runtime = if (e.runtime > 0) e.runtime else tr.runtime
                    )
                }
                if (shownSeason == number) renderEpisodes()
            }
        }.start()
    }

    private fun isUnaired(e: MediaEpisode): Boolean {
        val day = e.released.take(10)
        return day.length == 10 && day > java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date())
    }

    /** An episode: its still (with how much was watched), number and name, air date or length. */
    private fun episodeCard(e: MediaEpisode): View {
        val col = ui.vbox()
        col.clipChildren = false
        val card = FrameLayout(this)
        card.isFocusable = true
        card.isClickable = true
        card.tag = "episode:${e.id}"
        card.background = ui.round(C.CARD, D.CARD)
        card.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        card.addView(img, ui.fl(ui.match, ui.match))
        Img.load(this, "w780", e.thumbnail.ifBlank { film.backdrop }, ui.u(360), img)
        val progress = playback.progress(e.id)
        val finished = progress == null && playback.isFinished(e.id)
        if (progress != null) {
            val track = FrameLayout(this)
            track.setBackgroundColor(Color.argb(110, 0, 0, 0))
            val fill = View(this)
            fill.setBackgroundColor(barColor())
            progressFills.add(fill)
            track.addView(fill, ui.fl(0, ui.match))
            card.addView(track, ui.fl(ui.match, ui.u(6), Gravity.BOTTOM))
            track.post { fill.layoutParams = fill.layoutParams.apply { width = (track.width * progress.coerceAtLeast(0.03f)).toInt() } }
        }
        if (finished) {
            img.alpha = 0.55f
            val mark = ui.text("✓ İzlendi", 16, C.TEXT, 700)
            mark.setPadding(ui.u(12), ui.u(5), ui.u(12), ui.u(5))
            mark.background = ui.round(Color.argb(200, 0, 0, 0), 14)
            card.addView(mark, ui.fl(ui.wrap, ui.wrap, Gravity.TOP or Gravity.END).apply { setMargins(0, ui.u(10), ui.u(10), 0) })
        }
        val fx = Fx.decorate(ui, card, D.CARD)
        // "TBA" (a name not given yet): just its number.
        val noName = e.title.isBlank() || e.title.trim().equals("TBA", true) || e.title.trim().equals("TBD", true)
        val title = ui.text(if (noName) "${e.episode}. Bölüm" else (if (e.episode > 0) "${e.episode}. " else "") + e.title, 19, C.TEXT, 600)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.alpha = 0.75f
        val when_ = when {
            isUnaired(e) -> "Henüz yayınlanmadı"
            e.runtime > 0 -> "${e.runtime} dk"
            else -> ""
        }
        val sub = ui.text(when_, 16, C.TEXT3, 500)
        if (isUnaired(e)) col.alpha = 0.6f
        card.setOnFocusChangeListener { v, f ->
            ui.lift(v, f, 1.08f, 30)
            fx(f)
            title.animate().alpha(if (f) 1f else 0.75f).setDuration(D.FAST).start()
            if (f) onRowFocus(v)
        }
        card.setOnClickListener { MediaSourcePickerActivity.open(this, film, resumeEpisode = e.id) }
        col.addView(card, ui.ll(ui.u(360), ui.u(203)))
        col.addView(title, ui.ll(ui.u(360), ui.wrap, top = 14))
        if (when_.isNotBlank()) col.addView(sub, ui.ll(ui.u(360), ui.wrap, top = 4))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 30)
        return col
    }

    private fun similarCard(p: Pick): View {
        val col = ui.vbox()
        col.clipChildren = false
        val card = FrameLayout(this)
        card.isFocusable = true
        card.isClickable = true
        card.background = ui.round(C.CARD, D.CARD)
        card.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        card.addView(img, ui.fl(ui.match, ui.match))
        Img.load(this, "w780", p.backdrop, ui.u(300), img)
        val fx = Fx.decorate(ui, card, D.CARD)
        val title = ui.text(p.title, 19, C.TEXT, 500)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.alpha = 0.7f
        card.setOnFocusChangeListener { v, f ->
            ui.lift(v, f, 1.1f, 30)
            fx(f)
            title.animate().alpha(if (f) 1f else 0.7f).setDuration(D.FAST).start()
            if (f) onRowFocus(v)
        }
        card.setOnClickListener {
            Fx.zoomOpen(frame, card, img.drawable) {
                try {
                    open(this, p)
                    overridePendingTransition(0, 0)
                } catch (e: Exception) {
                    Fx.zoomReset(frame)
                }
            }
        }
        col.addView(card, ui.ll(ui.u(300), ui.u(169)))
        col.addView(title, ui.ll(ui.u(300), ui.wrap, top = 14))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 30)
        return col
    }

    private fun releaseDay(): java.util.Date? = try {
        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(film.release)
    } catch (e: Exception) {
        null
    }

    private fun isUpcoming(): Boolean {
        val d = releaseDay() ?: return false
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        return java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d) > today
    }

    private fun releaseText(): String =
        releaseDay()?.let { java.text.SimpleDateFormat("d MMMM", Locale("tr", "TR")).format(it) } ?: ""

    private fun reminded() = prefs.reminders.any { it.key == film.key }

    private fun toggleReminder() {
        val list = prefs.reminders
        if (reminded()) {
            prefs.reminders = list.filter { it.key != film.key }
        } else {
            prefs.reminders = list + film
            Glass.toast(this, "${releaseText()} günü ana ekranda hatırlatacağım", long = true)
        }
        updateRemindBtn()
    }

    private fun nightPlan(): MovieNight.Plan? = MovieNight.get(prefs)?.takeIf { it.film.key == film.key }

    private fun updateNightBtn() {
        val plan = nightPlan()
        nightBtn.text = if (plan != null) MovieNight.day(plan.at) else "Bu akşam izle"
    }

    /** Plans the film for tonight; if it is already planned, change the time or cancel. */
    private fun planNight() {
        val plan = nightPlan()
        if (plan == null) {
            MovieNight.choose(this, film) { updateNightBtn() }
            return
        }
        Dialogs.list(this, "Akşam planı", listOf("Saati değiştir" to MovieNight.day(plan.at), "İptal et" to ""),
            subtitle = film.title, icon = R.drawable.ic_moon) { i ->
            if (i == 0) MovieNight.choose(this, film) { updateNightBtn() }
            else {
                MovieNight.clear(prefs)
                updateNightBtn()
            }
        }
    }

    private fun updateRemindBtn() {
        remindBtn.text = if (reminded()) "✓   Hatırlatılacak" else "Hatırlat"
    }

    private var heroButtons: LinearLayout? = null

    /** The film [step] places along the row it was opened from; the page swaps to it softly. */
    private fun moveInRow(step: Int): Boolean {
        val list = siblings
        val at = list.indexOfFirst { it.key == film.key }
        if (at < 0 || list.size < 2) return false
        val next = list.getOrNull(at + step) ?: return false
        stopPageTrailer()
        Sound.event(Sound.OPEN)
        open(this, next)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        super.finish()
        return true
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            // ◀ on the first button, ▶ on the last (or the channel keys): the row's other films.
            val k = event.keyCode
            if (k == android.view.KeyEvent.KEYCODE_CHANNEL_UP || k == android.view.KeyEvent.KEYCODE_CHANNEL_DOWN) {
                if (moveInRow(if (k == android.view.KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)) return true
            }
            if ((k == android.view.KeyEvent.KEYCODE_DPAD_LEFT || k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) && event.repeatCount == 0) {
                val row = heroButtons
                val focus = currentFocus
                if (row != null && focus != null && focus.parent === row && (!::scroll.isInitialized || scroll.scrollY == 0)) {
                    val dir = if (k == android.view.KeyEvent.KEYCODE_DPAD_LEFT) View.FOCUS_LEFT else View.FOCUS_RIGHT
                    val target = focus.focusSearch(dir)
                    if ((target == null || target.parent !== row) && moveInRow(if (dir == View.FOCUS_LEFT) -1 else 1)) return true
                }
            }
            Fx.lastDir = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                android.view.KeyEvent.KEYCODE_DPAD_UP -> -2
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 2
                else -> Fx.lastDir
            }
        }
        Fx.edgeNudge(this, event, ui)
        return super.dispatchKeyEvent(event)
    }

    private fun watched() = film.key in prefs.watched
    private fun liked() = prefs.liked.containsKey(film.key)
    private fun notForMe() = prefs.disliked.containsKey(film.key)

    private fun updateMarks() {
        watchedBtn.text = if (watched()) "İzlendi" else "İzledim"
        ui.setPillIcon(watchedBtn, if (watched()) R.drawable.ic_check else R.drawable.ic_eye)
        likeBtn.text = if (liked()) "Beğendin" else "Beğendim"
        ui.setPillIcon(likeBtn, if (liked()) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        notBtn.text = if (notForMe()) "Önerilmiyor" else "Bana önerme"
    }

    /** Never suggested again; its genres move back. Liking it undoes this, and the other way round. */
    private fun toggleNot() {
        if (notForMe()) {
            prefs.disliked = prefs.disliked - film.key
            Glass.toast(this, "Yine önerilebilir")
        } else {
            prefs.disliked = prefs.disliked + (film.key to film.genres)
            if (liked()) prefs.liked = prefs.liked - film.key
            Glass.toast(this, "Bu film bir daha önerilmez; benzerleri geride kalır", long = true)
        }
        updateMarks()
    }

    /** Watched films leave the suggestions; they also leave Listem, which is for films to watch. */
    private fun setWatched(on: Boolean) {
        prefs.watched = if (on) prefs.watched + film.key else prefs.watched - film.key
        if (on && inList()) {
            prefs.watchlist = prefs.watchlist.filter { it.key != film.key }
            updateListBtn()
            Glass.toast(this, "Listem'den çıkarıldı")
        }
    }

    private fun toggleWatched() {
        val on = !watched()
        setWatched(on)
        // A film you liked is one you watched; un-watching drops the like too.
        if (!on && liked()) prefs.liked = prefs.liked - film.key
        updateMarks()
    }

    private fun toggleLike() {
        if (liked()) {
            prefs.liked = prefs.liked - film.key
        } else {
            prefs.liked = prefs.liked + (film.key to film.genres)
            prefs.disliked = prefs.disliked - film.key
            if (!watched()) setWatched(true)
            Glass.toast(this, "Buna benzeyen filmler artık önde")
        }
        updateMarks()
    }

    private fun inList() = prefs.watchlist.any { it.key == film.key }

    private fun toggleList() {
        val list = prefs.watchlist
        val adding = !inList()
        prefs.watchlist = if (!adding) list.filter { it.key != film.key } else listOf(film) + list
        if (adding) Sound.event(Sound.ADD)
        updateListBtn()
    }

    private fun updateListBtn() {
        listBtn.text = if (film.isSeries) {
            // A series: the list follows it for new-episode notices.
            if (inList()) "✓   Takipte" else "+   Takibe Al"
        } else {
            if (inList()) "✓   Listemde" else "+   Listeme ekle"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back from the rows first returns to the top of the page.
        if (scroll.scrollY > 0) {
            scroll.smoothScrollTo(0, 0)
            hero.animate().alpha(1f).setDuration(D.MED).start()
            playBtn.requestFocus()
            return
        }
        Sound.event(Sound.BACK)
        @Suppress("DEPRECATION")
        super.onBackPressed()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private lateinit var tonight: TextView

    private fun timeOfDay(at: Long): String =
        java.text.SimpleDateFormat(if (prefs.clock24) "HH:mm" else "h:mm a", Locale("tr", "TR")).format(java.util.Date(at))

    /**
     * "Şimdi başlarsan bitiş 23:12", "Kaldığın yerden 42 dk · bitiş 21:54", for a series "Bir bölüm
     * 47 dk · bitiş 21:54 · iki bölüm 22:41"; late in the evening also by when to start for it to
     * end before midnight. Nothing for a film not out yet or of unknown length.
     */
    private fun updateTonight() {
        if (!::tonight.isInitialized) return
        val minutes = film.runtime
        if (minutes <= 0 || isUpcoming() || cinemaState.isNotEmpty()) {
            tonight.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        val store = MediaPlaybackStore(this)
        val last = store.last(film.key)
        val left = if (!film.isSeries && last.isNotBlank() && store.resumePositionMs(last) > 0) {
            val dur = store.durationMs(last).takeIf { it > 0 } ?: minutes * 60_000L
            (dur - store.resumePositionMs(last)).coerceAtLeast(60_000L)
        } else minutes * 60_000L
        val parts = ArrayList<String>()
        if (film.isSeries) {
            parts.add("Bir bölüm $minutes dk")
            parts.add("bitiş ${timeOfDay(now + left)}")
            parts.add("iki bölüm ${timeOfDay(now + 2 * left)}")
        } else {
            parts.add(if (left < minutes * 60_000L) "Kaldığın yerden ${left / 60_000L} dk" else "Şimdi başlarsan")
            parts.add("bitiş ${timeOfDay(now + left)}")
        }
        // From six in the evening: if it would run past midnight, the latest start that ends by then.
        val c = java.util.Calendar.getInstance()
        val hour = c.get(java.util.Calendar.HOUR_OF_DAY)
        if (hour >= 18 && !film.isSeries) {
            c.add(java.util.Calendar.DAY_OF_YEAR, 1)
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0)
            val midnight = c.timeInMillis
            if (now + left > midnight) {
                parts.add("gece yarısını geçer")
            } else if (midnight - (now + left) < 45 * 60_000L) {
                parts.add("gece yarısından önce bitmesi için en geç başlangıç ${timeOfDay(midnight - left)}")
            }
        }
        tonight.text = "◷  " + parts.joinToString("  ·  ")
        tonight.visibility = View.VISIBLE
    }

    /** The trailer, length and IMDb id of a title that came from TMDB without them; the button appears. */
    private fun completeFromTmdb() {
        val before = film
        Thread {
            val full = try { TmdbDiscover.complete(before) } catch (e: Exception) { null } ?: return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed || film !== before) return@runOnUiThread
                film = full
                if (full.trailer.isNotEmpty()) {
                    trailerBtn.visibility = View.VISIBLE
                    queuePageTrailer()
                }
                updateTonight()
            }
        }.start()
    }

    private lateinit var trailerStage: FrameLayout
    private var pageTrailer: HeroTrailer? = null
    private val trailerSoon = Runnable {
        if (!isFinishing && hasWindowFocus() && (!::scroll.isInitialized || scroll.scrollY == 0)) pageTrailer?.start(film.trailer)
    }

    /** After five quiet seconds on the page, the silent trailer (Ayarlar › Görünüm › sessiz fragman). */
    private fun queuePageTrailer() {
        if (!::frame.isInitialized || pageTrailer?.active == true) return
        frame.removeCallbacks(trailerSoon)
        if (prefs.heroTrailer && !Fx.lite && film.trailer.isNotEmpty()) frame.postDelayed(trailerSoon, 5000L)
    }

    private fun stopPageTrailer() {
        if (::frame.isInitialized) frame.removeCallbacks(trailerSoon)
        if (pageTrailer?.active == true) pageTrailer?.stop()
    }

    override fun onPause() {
        super.onPause()
        stopPageTrailer()
    }

    override fun onDestroy() {
        super.onDestroy()
        pageTrailer?.release()
    }

    override fun onResume() {
        super.onResume()
        queuePageTrailer()
        updateTonight()
        if (::frame.isInitialized) Fx.zoomReset(frame)
        // Back from the player: "Oynat" says where it was left, the episodes how far they were watched.
        updatePlayBtn()
        renderEpisodes()
        if (episodeList.isNotEmpty()) showEpisodeSummary()
    }
    /** The episode "Devam et" opens ("tt…:2:5"); "" for a film or a series not started. */
    private var continueEpisode = ""

    /** "now" while the film is in Turkish cinemas, "soon" before its release, "" otherwise. */
    private var cinemaState = ""

    /** Whether the film is on pick.json's "Sinemada" or "Yakında" list (read once, off the main thread). */
    private fun loadCinemaState() {
        Thread {
            val lists = cinemaLists(this) ?: return@Thread
            val state = when (film.key) {
                in lists.first -> "now"
                in lists.second -> "soon"
                else -> ""
            }
            if (state.isEmpty()) return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                cinemaState = state
                updatePlayBtn()
                updateTonight()
            }
        }.start()
    }

    /** "14 Kasım'dan beri": the Turkish ablative suffix follows the month's last vowel and consonant. */
    private fun sinceText(): String {
        val day = releaseText()
        if (day.isEmpty()) return ""
        val suffix = when (day.substringAfterLast(' ')) {
            "Ocak", "Şubat", "Mart", "Mayıs", "Temmuz", "Ağustos", "Aralık" -> "'tan"
            "Eylül", "Ekim" -> "'den"
            else -> "'dan"
        }
        return "$day$suffix beri"
    }

    /** The play pill: white as usual; warm (in cinemas) or blue (coming soon) when it shows the release. */
    private fun stylePlay(focused: Boolean) {
        val cinema = cinemaState.isNotEmpty() && MediaPlaybackStore(this).last(film.key).isBlank()
        if (!cinema) {
            playBtn.background = if (focused) ui.round(Color.WHITE, 32)
            else ui.round(Color.argb(64, 255, 255, 255), 32, 2, Color.argb(150, 255, 255, 255))
            playBtn.setTextColor(if (focused) C.INK else C.TEXT)
            return
        }
        val colors = if (cinemaState == "now") intArrayOf(Color.parseColor("#FF9A3C"), Color.parseColor("#E8364F"))
        else intArrayOf(Color.parseColor("#8F7BFF"), Color.parseColor("#3D8BFF"))
        val plate = ui.gradient(GradientDrawable.Orientation.LEFT_RIGHT, *colors, radius = 32)
        if (focused) plate.setStroke(ui.u(3), Color.WHITE)
        playBtn.background = plate
        playBtn.setTextColor(Color.WHITE)
    }

    /** "Oynat", "Devam et · 42:10", "Devam et · 2. Sezon 5. Bölüm", "Sonraki · 2. Sezon 6. Bölüm". */
    private fun updatePlayBtn() {
        if (!::playBtn.isInitialized) return
        val store = MediaPlaybackStore(this)
        val last = store.last(film.key)
        stylePlay(playBtn.isFocused)
        if (cinemaState.isNotEmpty() && last.isBlank()) {
            continueEpisode = ""
            playBtn.text = if (cinemaState == "now") {
                sinceText().let { if (it.isEmpty()) "Şu an sinemalarda" else "Vizyonda  ·  $it" }
            } else {
                releaseText().let { if (it.isEmpty()) "Yakında vizyonda" else "Yakında vizyonda  ·  $it" }
            }
            return
        }
        val position = if (last.isNotBlank()) store.resumePositionMs(last) else 0L
        val episode = Regex(":(\\d+):(\\d+)$").find(last)
        continueEpisode = ""
        playBtn.text = when {
            last.isBlank() -> "▶   Oynat"
            film.isSeries && episode != null -> {
                val season = episode.groupValues[1].toInt()
                val number = episode.groupValues[2].toInt()
                if (position > 0L || !store.isFinished(last)) {
                    continueEpisode = last
                    "▶   Devam et  ·  $season. Sezon $number. Bölüm"
                } else {
                    continueEpisode = last.substring(0, episode.range.first) + ":$season:${number + 1}"
                    "▶   Sonraki  ·  $season. Sezon ${number + 1}. Bölüm"
                }
            }
            position > 0L -> "▶   Devam et  ·  ${clock(position)}"
            store.isFinished(last) -> "▶   Yeniden izle"
            else -> "▶   Oynat"
        }
    }

    /** 42:10 or 1:12:30. */
    private fun clock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}