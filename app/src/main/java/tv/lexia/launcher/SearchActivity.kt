package tv.lexia.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Search, as on Apple TV: one box at the top, results changing as the words are typed, films and
 * series as posters in one row, people as round photos in another, the focused title's picture
 * blurred behind everything. Before anything is typed, today's popular titles fill the page.
 */
class SearchActivity : Activity() {

    companion object {
        private const val QUERY = "query"

        /** Today's list, so its titles keep their richer data (trailer, IMDb) and fill the empty page. */
        @Volatile internal var pool: List<Pick> = emptyList()

        fun open(ctx: Context, query: String = "", local: List<Pick> = emptyList()) {
            if (local.isNotEmpty()) pool = local
            ctx.startActivity(Intent(ctx, SearchActivity::class.java).putExtra(QUERY, query))
            (ctx as? Activity)?.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        /** How long the typing must pause before TMDB is asked. */
        private const val TYPE_PAUSE_MS = 450L
    }

    private lateinit var ui: Ui
    private val tr = Locale("tr", "TR")
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val pictures = Executors.newSingleThreadExecutor()

    private lateinit var box: EditText
    private lateinit var status: android.widget.TextView
    private lateinit var titlesLabel: android.widget.TextView
    private lateinit var titlesRow: LinearLayout
    private lateinit var titlesScroll: HorizontalScrollView
    private lateinit var peopleLabel: android.widget.TextView
    private lateinit var peopleRow: LinearLayout
    private lateinit var peopleScroll: HorizontalScrollView
    private val backs = arrayOfNulls<ImageView>(2)
    private var front = 0

    /** Bumped on every search, so an answer that comes late is dropped. */
    private var token = 0
    private var lastQuery: String? = null
    private var backFor = ""
    /** Pressing OK on the keyboard: the remote goes down to the first result once they are in. */
    private var jumpWhenReady = false

    private val searchSoon = Runnable { search(box.text.toString()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val m = ui.match
        val w = ui.wrap

        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        for (i in 0..1) {
            val b = ImageView(this)
            b.scaleType = ImageView.ScaleType.CENTER_CROP
            b.alpha = 0f
            frame.addView(b, ui.fl(m, m))
            backs[i] = b
        }
        val shade = View(this)
        shade.background = ui.gradient(
            GradientDrawable.Orientation.TOP_BOTTOM,
            Color.argb(200, 6, 7, 9), Color.argb(150, 6, 7, 9), Color.argb(235, 0, 0, 0)
        )
        frame.addView(shade, ui.fl(m, m))

        val page = ui.vbox()
        page.clipChildren = false
        page.clipToPadding = false
        page.setPadding(ui.u(110), ui.u(70), 0, 0)
        frame.addView(page, ui.fl(m, m))

        // The search box: a magnifier, the words, a white edge while the remote is on it.
        val field = ui.hbox()
        field.gravity = Gravity.CENTER_VERTICAL
        val idle = ui.glass(Color.argb(34, 255, 255, 255), 44, 2, 0.9f)
        val on = ui.round(Color.argb(52, 255, 255, 255), 44, 2, Color.WHITE)
        field.background = idle
        field.setPadding(ui.u(34), 0, ui.u(34), 0)
        val glass = ImageView(this)
        glass.setImageResource(R.drawable.ic_search)
        glass.setColorFilter(C.TEXT2, PorterDuff.Mode.SRC_IN)
        field.addView(glass, ui.ll(ui.u(36), ui.u(36), right = 20))
        box = EditText(this)
        box.background = null
        box.isSingleLine = true
        box.hint = "Film, dizi ya da oyuncu"
        box.setTextColor(C.TEXT)
        box.setHintTextColor(C.TEXT3)
        box.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.uf(34))
        box.typeface = ui.font(500)
        box.setPadding(0, 0, 0, 0)
        box.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        // The keyboard keeps the page in view (no full-screen typing box), so results show as they come.
        box.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        field.addView(box, ui.ll(0, w, weight = 1f))
        box.setOnFocusChangeListener { _, f -> field.background = if (f) on else idle }
        page.addView(field, ui.ll(ui.u(1160), ui.u(96)))

        status = ui.text("", 21, C.TEXT3, 500)
        page.addView(status, ui.ll(w, w, left = 2, top = 18))

        titlesLabel = ui.label("BUGÜN POPÜLER", C.TEXT3)
        page.addView(titlesLabel, ui.ll(w, w, top = 34, bottom = 6))
        titlesRow = row()
        titlesScroll = scroller(titlesRow)
        page.addView(titlesScroll, ui.ll(m, ui.u(420)))

        peopleLabel = ui.label("KİŞİLER", C.TEXT3)
        peopleLabel.visibility = View.GONE
        page.addView(peopleLabel, ui.ll(w, w, top = 4, bottom = 6))
        peopleRow = row()
        peopleScroll = scroller(peopleRow)
        peopleScroll.visibility = View.GONE
        page.addView(peopleScroll, ui.ll(m, ui.u(270)))

        setContentView(frame)
        Sound.attach(this)
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(D.MED).start()

        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(searchSoon)
                handler.postDelayed(searchSoon, TYPE_PAUSE_MS)
            }
        })
        box.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE) {
                handler.removeCallbacks(searchSoon)
                jumpWhenReady = true
                hideKeyboard()
                search(box.text.toString())
                true
            } else false
        }

        val start = intent.getStringExtra(QUERY).orEmpty().trim()
        if (start.isNotEmpty()) {
            // Said aloud or typed elsewhere: the results come straight away, the remote on the first.
            box.setText(start)
            box.setSelection(start.length)
            handler.removeCallbacks(searchSoon)
            jumpWhenReady = true
            search(start)
        } else {
            showPopular()
            box.requestFocus()
            handler.postDelayed({ showKeyboard() }, 250)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        pictures.shutdownNow()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean =
        Pointer.handle(this, ev) || super.dispatchGenericMotionEvent(ev)

    /** OK on the box brings the keyboard back (after it was put away to look at the results). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && box.hasFocus()) {
            showKeyboard()
            return true
        }
        Fx.edgeNudge(this, event, ui)
        return super.dispatchKeyEvent(event)
    }

    // ---------- Searching ----------

    private fun search(raw: String) {
        val q = raw.trim()
        if (q == lastQuery && titlesRow.childCount + peopleRow.childCount > 0) {
            if (jumpWhenReady) jumpToResults()
            return
        }
        lastQuery = q
        val mine = ++token
        if (q.length < 2) {
            showPopular()
            return
        }
        status.text = "Aranıyor…"
        if (isDestroyed) return
        worker.execute {
            val local = pool
            val found: List<TmdbDiscover.Found>? = if (BuildConfig.TMDB_API_KEY.isBlank()) {
                // No TMDB key in this build: today's list only.
                val n = norm(q)
                local.filter { norm(it.title).contains(n) }.map { TmdbDiscover.Found(it) }
            } else {
                try { TmdbDiscover.search(q) } catch (e: Exception) { null }
            }
            // Titles of today's list keep their richer data (trailer, IMDb score).
            if (found == null) {
                runOnUiThread {
                    if (mine == token && !isFinishing) status.text = "Arama yapılamadı. İnternet bağlantısını kontrol et."
                }
                return@execute
            }
            val byId = local.filter { it.id > 0 }.associateBy { "${it.kind}:${it.id}" }
            val titles = found.mapNotNull { f -> f.pick?.let { p -> byId["${p.kind}:${p.id}"] ?: p } }
                .filter { it.poster.isNotBlank() }.take(30)
            val people = found.filter { it.pick == null && it.person.isNotBlank() }.take(16)
            runOnUiThread {
                if (mine != token || isFinishing) return@runOnUiThread
                showResults(q, titles, people)
            }
        }
    }

    private fun showPopular() {
        token++
        val list = pool.filter { it.poster.isNotBlank() }.distinctBy { it.key }.take(20)
        titlesLabel.text = "BUGÜN POPÜLER"
        status.text = if (list.isEmpty()) "Bir film, dizi ya da oyuncu adı yaz" else ""
        fill(titlesRow, list.map { titleCard(it) })
        titlesScroll.scrollTo(0, 0)
        peopleLabel.visibility = View.GONE
        peopleScroll.visibility = View.GONE
        peopleRow.removeAllViews()
        list.firstOrNull()?.let { backdrop(it) }
    }

    private fun showResults(q: String, titles: List<Pick>, people: List<TmdbDiscover.Found>) {
        val total = titles.size + people.size
        status.text = if (total == 0) "“$q” için sonuç bulunamadı" else "“$q”  ·  $total sonuç"
        titlesLabel.text = "FİLMLER VE DİZİLER"
        titlesLabel.visibility = if (titles.isEmpty()) View.GONE else View.VISIBLE
        titlesScroll.visibility = titlesLabel.visibility
        fill(titlesRow, titles.map { titleCard(it) })
        titlesScroll.scrollTo(0, 0)
        peopleLabel.visibility = if (people.isEmpty()) View.GONE else View.VISIBLE
        peopleScroll.visibility = peopleLabel.visibility
        fill(peopleRow, people.map { personCard(it) })
        peopleScroll.scrollTo(0, 0)
        titles.firstOrNull()?.let { backdrop(it) }
        if (jumpWhenReady) jumpToResults()
    }

    private fun jumpToResults() {
        jumpWhenReady = false
        val first = (titlesRow.getChildAt(0)?.tag as? View) ?: (peopleRow.getChildAt(0)?.tag as? View) ?: return
        hideKeyboard()
        first.post { first.requestFocus() }
    }

    private fun fill(row: LinearLayout, cards: List<View>) {
        row.removeAllViews()
        cards.forEachIndexed { i, c ->
            row.addView(c)
            // The new results rise in one after another.
            if (!Fx.lite && i < 8) {
                c.alpha = 0f
                c.translationY = ui.uf(24)
                c.animate().alpha(1f).translationY(0f).setStartDelay(i * 35L).setDuration(D.MED)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            }
        }
    }

    // ---------- Cards ----------

    private fun row(): LinearLayout {
        val r = ui.hbox()
        r.clipChildren = false
        r.clipToPadding = false
        r.setPadding(0, ui.u(24), ui.u(110), ui.u(24))
        return r
    }

    private fun scroller(row: LinearLayout): HorizontalScrollView {
        val s = HorizontalScrollView(this)
        s.isHorizontalScrollBarEnabled = false
        s.overScrollMode = View.OVER_SCROLL_NEVER
        s.clipChildren = false
        s.clipToPadding = false
        s.isSmoothScrollingEnabled = true
        Fx.softEdges(s, ui)
        s.addView(row)
        return s
    }

    /** A film or series: its poster, the name and year under it; OK opens its page. */
    private fun titleCard(p: Pick): View {
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
        Img.load(this, "w500", p.poster, ui.u(cw), img)
        val fx = Fx.decorate(ui, poster, D.CARD)
        val title = ui.text(p.title, 19, C.TEXT2, 500)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        val score = TmdbDiscover.score["${p.kind}:${p.id}"]
        val sub = ui.text(
            listOfNotNull(
                p.year.takeIf { it.isNotBlank() },
                if (p.isSeries) "Dizi" else "Film",
                score?.takeIf { it > 0 }?.let { String.format(tr, "TMDB %.1f", it) }
            ).joinToString("  ·  "),
            16, C.TEXT3, 500
        )
        sub.maxLines = 1
        poster.setOnFocusChangeListener { _, f ->
            ui.lift(poster, f, 1.08f, 30)
            fx(f)
            title.setTextColor(if (f) C.TEXT else C.TEXT2)
            if (f) backdrop(p)
        }
        poster.setTag(R.id.lexia_pick, p)
        poster.setOnClickListener { DetailActivity.open(this, p, row = DetailActivity.rowAround(poster)) }
        col.addView(poster, ui.ll(ui.u(cw), ui.u(cw * 3 / 2)))
        col.addView(title, ui.ll(ui.u(cw), ui.wrap, top = 14))
        col.addView(sub, ui.ll(ui.u(cw), ui.wrap, top = 2))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 28)
        col.tag = poster
        return col
    }

    /** A person: a round photo (or their initials) and the name; OK opens their page. */
    private fun personCard(f: TmdbDiscover.Found): View {
        val size = 160
        val col = ui.vbox()
        col.clipChildren = false
        col.gravity = Gravity.CENTER_HORIZONTAL
        val photo = FrameLayout(this)
        photo.isFocusable = true
        photo.isClickable = true
        val disc = GradientDrawable()
        disc.shape = GradientDrawable.OVAL
        disc.setColor(Color.argb(40, 255, 255, 255))
        photo.background = disc
        photo.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        photo.clipToOutline = true
        val initials = ui.text(
            f.person.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(tr),
            44, C.TEXT2, 700
        )
        initials.gravity = Gravity.CENTER
        photo.addView(initials, ui.fl(ui.match, ui.match))
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        photo.addView(img, ui.fl(ui.match, ui.match))
        if (f.photo.isNotBlank()) Img.load(this, "w185", f.photo, ui.u(size), img)
        // A white ring while the remote is on it.
        val ring = View(this)
        val r = GradientDrawable()
        r.shape = GradientDrawable.OVAL
        r.setColor(Color.TRANSPARENT)
        r.setStroke(maxOf(1, ui.u(4)), Color.WHITE)
        ring.background = r
        ring.alpha = 0f
        photo.addView(ring, ui.fl(ui.match, ui.match))
        val name = ui.text(f.person, 19, C.TEXT2, 500)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        name.gravity = Gravity.CENTER
        val sub = ui.text(if (f.directing) "Yönetmen" else "Oyuncu", 16, C.TEXT3, 500)
        sub.gravity = Gravity.CENTER
        photo.setOnFocusChangeListener { _, on ->
            ui.lift(photo, on, 1.1f, 30)
            ring.animate().alpha(if (on) 1f else 0f).setDuration(D.FAST).start()
            name.setTextColor(if (on) C.TEXT else C.TEXT2)
        }
        photo.setOnClickListener { PersonActivity.open(this, f.person, f.directing) }
        col.addView(photo, ui.ll(ui.u(size), ui.u(size)))
        col.addView(name, ui.ll(ui.u(size + 40), ui.wrap, top = 14))
        col.addView(sub, ui.ll(ui.u(size + 40), ui.wrap, top = 2))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 24)
        col.tag = photo
        return col
    }

    // ---------- The picture behind ----------

    /** The focused title's picture, blurred, cross-fading in behind the page. */
    private fun backdrop(p: Pick) {
        val path = p.backdrop
        if (path.isBlank() || path == backFor || Fx.lite || isDestroyed || isFinishing) return
        backFor = path
        pictures.execute {
            val b: Bitmap? = try {
                Net.bitmap("https://image.tmdb.org/t/p/w780$path", ui.u(780), true)?.let { Fx.blurred(it, 8, 2) ?: it }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (b == null || isFinishing || backFor != path) return@runOnUiThread
                val next = 1 - front
                val nv = backs[next] ?: return@runOnUiThread
                val fv = backs[front] ?: return@runOnUiThread
                nv.setImageBitmap(b)
                nv.animate().cancel()
                nv.alpha = 0f
                nv.animate().alpha(0.75f).setDuration(700).start()
                fv.animate().alpha(0f).setDuration(700).start()
                front = next
            }
        }
    }

    // ---------- Keyboard ----------

    private fun showKeyboard() {
        box.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(box, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(box.windowToken, 0)
    }

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(tr), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('ı', 'i').trim()
}
