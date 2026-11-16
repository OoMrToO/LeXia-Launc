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
        private const val EXTRA = "film"
        private const val SURPRISE = "surprise"
        private const val WHY = "why"

        fun open(ctx: Context, p: Pick, surprise: Boolean = false, why: String = "") {
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


    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean =
        Pointer.handle(this, ev) || super.dispatchGenericMotionEvent(ev)

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
        Img.load(this, if (screenW > 1280) "original" else "w1280", film.backdrop, screenW, back) {
            back.animate().alpha(1f).setDuration(700).start()
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
            val reason = ui.text("Neden bu film:  $why", 20, C.ACCENT, 500)
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
            val ep = ui.text(Episodes.line(it), 21, C.ACCENT, 600)
            top.addView(ep, ui.ll(w, w, top = 16))
        }
        // Where it streams in Turkey ("Nerede izlenir"); nothing is played here.
        val platforms = (listOfNotNull(film.provider.takeIf { it.isNotEmpty() }) + film.where).distinct()
        if (platforms.isNotEmpty()) {
            val line = ui.text("İzlenebileceği yer:  " + platforms.joinToString("  ·  "), 21, C.TEXT2, 600)
            top.addView(line, ui.ll(w, w, top = 14))
        }
        if (film.overview.isNotBlank()) {
            val storyLabel = ui.text("HİKÂYE", 13, C.ACCENT, 800)
            storyLabel.letterSpacing = 0.14f
            top.addView(storyLabel, ui.ll(w, w, top = 24))
            val story = ui.text(film.overview, 21, C.TEXT, 400)
            story.maxWidth = ui.u(940)
            story.maxLines = 6
            story.ellipsize = TextUtils.TruncateAt.END
            story.setLineSpacing(0f, 1.35f)
            story.setPadding(ui.u(20), ui.u(16), ui.u(20), ui.u(16))
            story.background = GradientDrawable().apply {
                setColor(Color.argb(178, 13, 18, 27))
                cornerRadius = ui.uf(18)
                setStroke(ui.u(1), Color.argb(90, 255, 255, 255))
            }
            top.addView(story, ui.ll(w, w, top = 10))
        }
        if (film.director.isNotEmpty()) {
            val role = if (film.isSeries) "Yaratıcı" else "Yönetmen"
            val d = ui.text("$role: ${film.director}", 20, C.TEXT2, 500)
            top.addView(d, ui.ll(w, w, top = 16))
        }
        val buttons = ui.hbox()
        buttons.clipChildren = false
        playBtn = ui.pill("▶   Oynat", 64, 23, primary = true)
        playBtn.setOnClickListener {
            if (film.id <= 0) {
                Glass.toast(this, "Bu içerik için TMDB kimliği bulunamadı")
            } else {
                Media.play(this, film)
            }
        }
        val trailer = ui.pill("▶   Fragmanı İzle", 64, 23, primary = false)
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
        // New on a streaming platform whose app is on this TV: open that app (the film is found there).
        // The first of those platforms whose app is on this TV gets a button that opens it.
        val platformName = platforms.firstOrNull { Apps.forPlatform(this, it) != null }
        val platformApp = platformName?.let { Apps.forPlatform(this, it) }
        // The platform's own name ("Netflix'i aç"), never the app's label (some TVs show "com.mstar.netflixo").
        val openApp = ui.iconPill(platformName?.let { TrailerActivity.accusative(it) + " aç" } ?: "", R.drawable.ic_tv)
        openApp.setOnClickListener {
            platformApp?.let {
                prefs.markUsed(it.key)
                HomeButtonService.opened(it.pkg)
                try {
                    startActivity(Apps.launchIntent(it))
                } catch (e: Exception) {
                    Glass.toast(this, "Açılamadı")
                }
            }
        }
        // Not on this TV: find the platform's app in the store instead.
        if (platformApp == null) {
            val name = platforms.firstOrNull()
            if (name == null) openApp.visibility = View.GONE
            else {
                openApp.text = "$name uygulamasını bul"
                openApp.setOnClickListener {
                    HomeButtonService.pause()
                    Apps.findInStore(this, name)
                }
            }
        }
        val another = ui.pill("Başka öner", 64, 23)
        another.setOnClickListener {
            Surprise.next(this)
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        if (!surprise) another.visibility = View.GONE
        for (b in listOf(playBtn, trailer, openApp, listBtn, nightBtn, watchedBtn, likeBtn, notBtn, remindBtn, another)) {
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
        buttons.addView(trailer)
        buttons.addView(openApp)
        buttons.addView(listBtn)
        buttons.addView(nightBtn)
        buttons.addView(watchedBtn)
        buttons.addView(likeBtn)
        buttons.addView(notBtn)
        buttons.addView(remindBtn)
        buttons.addView(another)
        top.addView(buttons, ui.ll(w, w, top = 34))
        col.addView(top, ui.ll(m, screenH))

        if (film.cast.isNotEmpty()) {
            col.addView(label("OYUNCULAR"), ui.ll(w, w, top = 10))
            val row = ui.hbox()
            for (c in film.cast) row.addView(castCard(c))
            col.addView(rowOf(row), rowParams())
        }
        if (film.similar.isNotEmpty()) {
            col.addView(label(if (film.isSeries) "BENZER DİZİLER" else "BENZER FİLMLER"), ui.ll(w, w, top = 40))
            val row = ui.hbox()
            for (s in film.similar) row.addView(similarCard(s))
            col.addView(rowOf(row), rowParams())
        }

        // Scrolling down: the picture drifts slower than the page, and the name stays on top.
        stuck = stickyTitle()
        frame.addView(stuck, ui.fl(m, w, Gravity.TOP))
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            if (!Fx.lite) {
                back.translationY = -y * 0.22f
                val z = 1f + (y.toFloat() / screenH).coerceIn(0f, 1f) * 0.05f
                back.scaleX = z
                back.scaleY = z
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
    private fun stickyTitle(): View {
        val bar = ui.vbox()
        bar.setPadding(ui.u(110), ui.u(44), ui.u(110), ui.u(70))
        bar.background = ui.gradient(
            GradientDrawable.Orientation.TOP_BOTTOM,
            Color.argb(235, 0, 0, 0), Color.argb(160, 0, 0, 0), Color.argb(0, 0, 0, 0)
        )
        val t = ui.text(film.title, 34, C.TEXT, 700)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        bar.addView(t)
        val line = ui.text(film.details(2), 18, C.TEXT2, 500)
        bar.addView(line, ui.ll(ui.wrap, ui.wrap, top = 6))
        bar.alpha = 0f
        return bar
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
        Dialogs.list(this, "Film gecesi", listOf("Saati değiştir" to MovieNight.day(plan.at), "İptal et" to ""),
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

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            Fx.lastDir = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                android.view.KeyEvent.KEYCODE_DPAD_UP -> -2
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 2
                else -> Fx.lastDir
            }
        }
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
        listBtn.text = if (inList()) "✓   Listemde" else "+   Listeme Ekle"
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

    override fun onResume() {
        super.onResume()
        if (::frame.isInitialized) Fx.zoomReset(frame)
    }
}
