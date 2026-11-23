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

        fun open(a: Activity) {
            Prefs(a).weekendSeen = weekKey()
            a.startActivity(Intent(a, WeekendActivity::class.java))
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private class Item(val film: Pick, val line: String)
    private class Section(val label: String, val title: String, val items: List<Item>)

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
        top.addView(heading, ui.ll(w, w, left = 30))
        page.addView(top, ui.ll(m, w))

        text = ui.vbox()
        text.gravity = Gravity.BOTTOM
        label = ui.label("", Color.parseColor("#FFD9A8"))
        text.addView(label, ui.ll(w, w))
        title = ui.text("", 68, C.TEXT, 800)
        title.letterSpacing = -0.02f
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        text.addView(title, ui.ll(ui.u(1100), w, top = 12))
        meta = ui.text("", 22, C.TEXT2, 500)
        text.addView(meta, ui.ll(w, w, top = 12))
        line = ui.text("", 24, C.TEXT, 600)
        text.addView(line, ui.ll(w, w, top = 10))
        about = ui.text("", 22, C.TEXT2, 400)
        about.maxLines = 2
        about.ellipsize = TextUtils.TruncateAt.END
        about.setLineSpacing(0f, 1.2f)
        text.addView(about, ui.ll(ui.u(980), w, top = 16))
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
        prevBtn = ui.pill("‹   Önceki", 64, 23)
        prevBtn.setOnClickListener { go(this.page - 1) }
        nextBtn = ui.pill("", 64, 23, primary = true)
        nextBtn.setOnClickListener { if (this.page < sections.size - 1) go(this.page + 1) else finish() }
        for (b in listOf(nextBtn, trailerBtn, listBtn, info, prevBtn)) buttons.addView(b)
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
                .filter { it.title.isNotEmpty() && it.backdrop.isNotEmpty() && !Remote.isHidden(it) }
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
        }
        if (episodes.isNotEmpty()) out.add(Section("HAFTA SONU YENİ BÖLÜMLER", "Dizilerde", episodes.take(10)))

        // LeXia's pick: the best rated of today's films not yet watched.
        val picks = list("picks").filter { it.key !in skip && !it.isSeries }.sortedByDescending { it.imdb }
            .map { Item(it, "LeXia'nın hafta sonu önerisi") }
        if (picks.isNotEmpty()) out.add(Section("HAFTA SONU ÖNERİMİZ", "Önerimiz", picks.take(6)))
        return out
    }

    /** Shows page [i]: the segments, the posters and the first film. */
    private fun go(i: Int) {
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
        nextBtn.text = if (i < sections.size - 1) "Sıradaki: " + sections[i + 1].title + "   ›" else "Bitir"
        posters.removeAllViews()
        for (item in s.items) posters.addView(poster(item))
        show(s.items.first(), force = true)
        text.alpha = 0f
        text.translationX = ui.uf(-30)
        text.animate().alpha(1f).translationX(0f).setDuration(D.SLOW).start()
        nextBtn.requestFocus()
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
        title.text = p.title
        val bits = ArrayList<String>()
        if (p.imdb > 0) bits.add(String.format(tr, "IMDb %.1f", p.imdb))
        if (p.meta.isNotEmpty()) bits.add(p.meta)
        meta.text = bits.joinToString("  ·  ")
        meta.visibility = if (bits.isEmpty()) View.GONE else View.VISIBLE
        line.text = item.line
        about.text = p.overview
        about.visibility = if (p.overview.isEmpty()) View.GONE else View.VISIBLE
        trailerBtn.visibility = if (p.trailer.isEmpty()) View.GONE else View.VISIBLE
        updateListBtn()
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

    private fun inList(): Boolean = shown?.let { s -> prefs.watchlist.any { it.key == s.film.key } } == true

    private fun updateListBtn() {
        if (!::listBtn.isInitialized) return
        listBtn.text = if (inList()) "✓   Listemde" else "+   Listeme Ekle"
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
