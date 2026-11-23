package tv.lexia.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The next two weeks in one place: films coming to Turkish cinemas, coming out digitally and new
 * episodes of series, day by day, with the last seven days' "Kaçırdıkların" first. On top the
 * chosen card's logo, facts, "Hatırlat" and "Fragman", its silent trailer behind; under it a strip
 * of the fourteen days and chips to narrow the list (Listem, Sinema, Dijital, a platform). The
 * month view lays the same dates out as a calendar.
 */
class CalendarActivity : Activity() {

    /** [kind]: "cinema", "digital" or "episode"; [where] the platforms it comes to. */
    private class Item(
        val pick: Pick, val date: String, val line: String, val mine: Boolean,
        val kind: String, val where: List<String>
    )

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private val tr = Locale("tr", "TR")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var backA: ImageView
    private lateinit var backB: ImageView
    private var front: ImageView? = null
    private lateinit var trailerStage: FrameLayout
    private lateinit var trailer: HeroTrailer
    private val trailerSoon = Runnable { startTrailer() }
    private lateinit var headTag: TextView
    private lateinit var headLogo: ImageView
    private lateinit var headTitle: TextView
    private lateinit var headLine: TextView
    private lateinit var remindBtn: TextView
    private lateinit var trailerBtn: TextView
    private lateinit var strip: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var col: LinearLayout
    private lateinit var month: ScrollView
    private lateinit var monthGrid: LinearLayout

    private var all: List<Item> = emptyList()
    private var current: Item? = null
    /** The chip chosen: "" all, "mine", "cinema", "digital", "episode" or a platform's name. */
    private var filter = ""
    private var monthOn = false
    /** Each day's title in the list, to scroll to it from the strip or the month. */
    private val dayRows = HashMap<String, View>()
    private val dayChips = HashMap<String, TextView>()
    private var chosenDay = ""

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean =
        Pointer.handle(this, ev) || super.dispatchGenericMotionEvent(ev)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        val m = ui.match
        val w = ui.wrap
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        val frame = FrameLayout(this)
        frame.background = ui.gradient(
            GradientDrawable.Orientation.TL_BR,
            Color.parseColor("#1A1F29"), Color.parseColor("#0A0B0E"), Color.BLACK
        )
        backA = ImageView(this)
        backB = ImageView(this)
        for (b in listOf(backA, backB)) {
            b.scaleType = ImageView.ScaleType.CENTER_CROP
            b.alpha = 0f
            frame.addView(b, ui.fl(m, (screenH * 0.72f).toInt()))
        }
        // The chosen card's silent trailer, behind the shades like its picture.
        trailerStage = FrameLayout(this)
        trailerStage.alpha = 0.7f
        frame.addView(trailerStage, ui.fl(m, (screenH * 0.72f).toInt()))
        trailer = HeroTrailer(this, trailerStage)
        trailer.onShowing = { on -> if (on) for (b in listOf(backA, backB)) b.animate().alpha(0f).setDuration(900).start() }
        val left = View(this)
        left.background = ui.gradient(
            GradientDrawable.Orientation.LEFT_RIGHT,
            Color.argb(235, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.argb(0, 0, 0, 0)
        )
        frame.addView(left, ui.fl((screenW * 0.75f).toInt(), m))
        val bottom = View(this)
        bottom.background = ui.gradient(
            GradientDrawable.Orientation.TOP_BOTTOM,
            Color.argb(0, 0, 0, 0), Color.argb(215, 0, 0, 0), Color.BLACK
        )
        frame.addView(bottom, ui.fl(m, (screenH * 0.7f).toInt(), Gravity.BOTTOM))

        val page = ui.vbox()
        page.clipChildren = false
        page.clipToPadding = false
        page.setPadding(ui.u(110), ui.u(56), ui.u(110), 0)
        frame.addView(page, ui.fl(m, m))

        // Top: what the chosen card is, and what can be done with it.
        page.addView(ui.label("TAKVİM"))
        headTag = ui.text("", 22, C.TEXT2, 600)
        page.addView(headTag, ui.ll(w, w, top = 18))
        headLogo = ImageView(this)
        headLogo.adjustViewBounds = true
        headLogo.maxHeight = ui.u(110)
        headLogo.maxWidth = ui.u(560)
        headLogo.scaleType = ImageView.ScaleType.FIT_START
        headLogo.visibility = View.GONE
        page.addView(headLogo, ui.ll(w, w, top = 10))
        headTitle = ui.text("", 58, C.TEXT, 800)
        headTitle.letterSpacing = -0.025f
        headTitle.maxLines = 1
        headTitle.ellipsize = TextUtils.TruncateAt.END
        headTitle.maxWidth = ui.u(1200)
        page.addView(headTitle, ui.ll(w, w, top = 8))
        headLine = ui.text("", 21, C.TEXT2, 500)
        headLine.maxWidth = ui.u(1000)
        headLine.maxLines = 2
        headLine.ellipsize = TextUtils.TruncateAt.END
        page.addView(headLine, ui.ll(w, w, top = 10))
        val acts = ui.hbox()
        acts.clipChildren = false
        acts.clipToPadding = false
        remindBtn = ui.pill("", 56, 21)
        remindBtn.setOnClickListener { toggleRemind() }
        trailerBtn = ui.pill("▶   Fragman", 56, 21)
        trailerBtn.setOnClickListener { current?.pick?.let { p -> TrailerActivity.open(this, p, if (trailer.active) trailer.where() else null) } }
        acts.addView(remindBtn)
        acts.addView(trailerBtn)
        page.addView(acts, ui.ll(w, w, top = 18))

        // The fourteen days, and the chips to narrow the list.
        strip = ui.hbox()
        strip.clipChildren = false
        strip.clipToPadding = false
        page.addView(strip, ui.ll(m, w, top = 22))
        chips = ui.hbox()
        chips.clipChildren = false
        chips.clipToPadding = false
        val chipScroll = HorizontalScrollView(this)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.clipChildren = false
        chipScroll.clipToPadding = false
        chipScroll.addView(chips)
        page.addView(chipScroll, ui.ll(m, w, top = 14))

        // Below: one row per day (or the month).
        val below = FrameLayout(this)
        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isSmoothScrollingEnabled = true
        scroll.clipChildren = true
        scroll.clipToPadding = false
        col = ui.vbox()
        col.clipChildren = false
        col.clipToPadding = false
        col.setPadding(0, ui.u(20), 0, ui.u(260))
        scroll.addView(col)
        below.addView(scroll, ui.fl(m, m))
        month = ScrollView(this)
        month.isVerticalScrollBarEnabled = false
        month.visibility = View.GONE
        monthGrid = ui.vbox()
        monthGrid.setPadding(0, ui.u(20), 0, ui.u(120))
        month.addView(monthGrid)
        below.addView(month, ui.fl(m, m))
        page.addView(below, ui.ll(m, 0, weight = 1f, left = -30, right = -30))

        all = items()
        buildStrip()
        buildChips()
        val first = buildList()

        setContentView(frame)
        Sound.attach(this)
        first?.requestFocus()
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(D.MED).start()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(trailerSoon)
        trailer.stop()
    }

    override fun onResume() {
        super.onResume()
        updateButtons()
        if (current != null && !Fx.lite && prefs.heroTrailer) {
            handler.removeCallbacks(trailerSoon)
            handler.postDelayed(trailerSoon, 3_000L)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        trailer.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // The month view goes back to the list first.
        if (monthOn) {
            showMonth(false)
            return
        }
        super.onBackPressed()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ---------- What is coming ----------

    /**
     * Films coming to Turkish cinemas, coming out digitally and new episodes, the last seven days
     * and the next fourteen, from the saved film list.
     */
    private fun items(): List<Item> {
        val out = ArrayList<Item>()
        val o = try {
            JSONObject(java.io.File(filesDir, "pick.json").readText())
        } catch (e: Exception) {
            return out
        }
        Remote.loadSaved(this)
        val reminded = prefs.reminders.map { it.key }.toSet()
        val listedFilms = prefs.watchlist.map { it.key }.toSet()
        val seen = HashSet<String>()
        // In cinemas: coming soon, and out in the last week.
        for (name in listOf("soon", "cinema")) {
            val a = o.optJSONArray(name)
            for (i in 0 until (a?.length() ?: 0)) {
                val p = Pick.from(a!!.getJSONObject(i))
                val d = Episodes.daysUntil(p.release) ?: continue
                if (d !in -7..14 || p.backdrop.isEmpty() || Remote.isHidden(p) || !seen.add("c:" + p.key)) continue
                val mine = p.key in reminded || p.key in listedFilms
                val what = if (d < 0) "Vizyona girdi" else "Vizyona giriyor"
                out.add(Item(p, p.release, if (p.key in reminded && d >= 0) "$what  ·  Hatırlatılacak" else what, mine, "cinema", emptyList()))
            }
        }
        // Coming out digitally in Turkey (to rent, buy or stream); the platform when it is known.
        val dig = o.optJSONArray("digital")
        for (i in 0 until (dig?.length() ?: 0)) {
            val p = Pick.from(dig!!.getJSONObject(i))
            val d = Episodes.daysUntil(p.digital) ?: continue
            if (d !in -7..14 || p.backdrop.isEmpty() || Remote.isHidden(p)) continue
            val where = if (p.where.isNotEmpty()) "Dijitalde  ·  " + p.where.joinToString(", ") else "Dijital çıkış"
            out.add(Item(p, p.digital, where, p.key in reminded || p.key in listedFilms, "digital", p.where))
        }
        // Series: the full entries we have (Top 10, Listem), else a simple one from the calendar.
        Episodes.load(o)
        val listed = prefs.watchlist.filter { it.isSeries }
        val find = Episodes.finder(o, listed)
        val listedKeys = listed.map { it.backdrop }.toSet()
        for (s in Episodes.upcoming(14)) {
            if (s.backdrop.isEmpty()) continue
            val p = find(s)
            val which = when {
                s.next.kind == "finale" -> "Sezon finali" + if (s.next.season > 0) "  ·  ${s.next.season}. sezon" else ""
                s.next.kind == "premiere" -> "Yeni sezon" + if (s.next.season > 0) "  ·  ${s.next.season}. sezon başlıyor" else ""
                s.next.season > 0 && s.next.episode > 0 -> "${s.next.season}. sezon ${s.next.episode}. bölüm"
                else -> "Yeni bölüm"
            }
            val mine = s.backdrop in listedKeys
            val where = listOfNotNull(p.provider.takeIf { it.isNotEmpty() }) + p.where
            out.add(Item(p, s.next.date, if (mine) "$which  ·  Listem'de" else which, mine, "episode", where.distinct()))
        }
        // Your own (Listem, reminders) first within a day.
        return out.sortedWith(compareBy({ it.date }, { if (it.mine) 0 else 1 }))
    }

    private fun passes(item: Item): Boolean = when (filter) {
        "" -> true
        "mine" -> item.mine
        "cinema", "digital", "episode" -> item.kind == filter
        else -> filter in item.where
    }

    // ---------- The strip, the chips ----------

    /** The fourteen days from today: the short day name, the date, a dot where something comes. */
    private fun buildStrip() {
        strip.removeAllViews()
        dayChips.clear()
        val c = Calendar.getInstance()
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayName = SimpleDateFormat("EEE", tr)
        val dates = all.filter { passes(it) }.map { it.date }.toSet()
        for (i in 0 until 14) {
            val date = iso.format(c.time)
            val has = date in dates
            val box = ui.vbox()
            box.gravity = Gravity.CENTER_HORIZONTAL
            val name = if (i == 0) "Bugün" else dayName.format(c.time).replace(".", "").replaceFirstChar { it.titlecase(tr) }
            val chip = ui.text(name + "\n" + c.get(Calendar.DAY_OF_MONTH), 18, if (has) C.TEXT else C.TEXT3, 700)
            chip.gravity = Gravity.CENTER
            chip.setLineSpacing(0f, 1.05f)
            chip.setPadding(0, ui.u(8), 0, ui.u(8))
            chip.isFocusable = has
            chip.isClickable = has
            chip.background = ui.round(Color.argb(if (has) 26 else 10, 255, 255, 255), 18)
            chip.setOnFocusChangeListener { _, f ->
                styleDay(chip, date, f)
                ui.lift(chip, f, 1.08f, 16)
            }
            chip.setOnClickListener {
                if (monthOn) showMonth(false)
                jumpTo(date)
            }
            dayChips[date] = chip
            box.addView(chip, ui.ll(ui.u(96), ui.wrap))
            // A dot under the days with something coming.
            val dot = View(this)
            dot.background = ui.round(if (has) Color.WHITE else Color.TRANSPARENT, 3)
            box.addView(dot, ui.ll(ui.u(6), ui.u(6), top = 6))
            strip.addView(box, ui.ll(ui.wrap, ui.wrap, right = 10))
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        styleDays()
    }

    private fun styleDay(chip: TextView, date: String, focused: Boolean) {
        val on = focused || date == chosenDay
        val has = chip.isFocusable
        chip.background = ui.round(if (on) Color.WHITE else Color.argb(if (has) 26 else 10, 255, 255, 255), 18)
        chip.setTextColor(if (on) C.INK else if (has) C.TEXT else C.TEXT3)
    }

    private fun styleDays() {
        for ((d, chip) in dayChips) styleDay(chip, d, chip.isFocused)
    }

    /** Tümü, Listem, Sinema, Dijital, Yeni bölüm, the platforms that come up; and the month view. */
    private fun buildChips() {
        chips.removeAllViews()
        val platforms = all.flatMap { it.where }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }.take(5)
        val options = listOf("" to "Tümü", "mine" to "Listem", "cinema" to "Sinema", "digital" to "Dijital", "episode" to "Yeni bölüm") +
            platforms.map { it to it }
        for ((value, label) in options) {
            if (value.isNotEmpty() && value != "mine" && all.none { it.kind == value || value in it.where }) continue
            val chip = ui.pill(label, 50, 19, primary = filter == value)
            chip.setOnClickListener {
                if (filter == value) return@setOnClickListener
                filter = value
                buildChips()
                buildStrip()
                if (monthOn) buildMonth() else buildList()
                chips.findViewWithTag<View>("chip:$value")?.requestFocus()
            }
            chip.tag = "chip:$value"
            chips.addView(chip)
        }
        val monthBtn = ui.pill(if (monthOn) "‹   Gün gün" else "Ay görünümü", 50, 19)
        monthBtn.tag = "chip:month"
        monthBtn.setOnClickListener {
            showMonth(!monthOn)
            chips.findViewWithTag<View>("chip:month")?.requestFocus()
        }
        chips.addView(monthBtn)
    }

    // ---------- The list ----------

    /** Kaçırdıkların first, then one row per day; returns the first card. */
    private fun buildList(): View? {
        col.removeAllViews()
        dayRows.clear()
        val w = ui.wrap
        val m = ui.match
        val shown = all.filter { passes(it) }
        // The remote starts on what is coming (today's row), not on what was missed.
        var first: View? = null
        var missedFirst: View? = null
        val missed = shown.filter { (Episodes.daysUntil(it.date) ?: 0) < 0 }.sortedByDescending { it.date }
        if (missed.isNotEmpty()) {
            col.addView(ui.label("KAÇIRDIKLARIN  ·  SON 7 GÜNDE ÇIKANLAR"), ui.ll(w, w, left = 30))
            missedFirst = addRow(missed)
        }
        val days = shown.filter { (Episodes.daysUntil(it.date) ?: 0) >= 0 }.groupBy { it.date }.toSortedMap()
        for ((date, list) in days) {
            val title = ui.label(dayTitle(date))
            col.addView(title, ui.ll(w, w, left = 30, top = if (col.childCount == 0) 0 else 34))
            dayRows[date] = title
            val card = addRow(list)
            if (first == null) first = card
        }
        if (first == null) first = missedFirst
        if (shown.isEmpty()) {
            headTag.text = "Önümüzdeki iki hafta"
            headTitle.text = if (filter.isEmpty()) "Takvim şimdilik boş" else "Bu seçimde bir şey yok"
            headTitle.visibility = View.VISIBLE
            headLogo.visibility = View.GONE
            headLine.text = ""
            val e = ui.emptyState(R.drawable.ic_calendar, "İki hafta sessiz görünüyor",
                "Yakında vizyona girecek ve dijitale çıkacak filmler, takip ettiğin dizilerin yeni bölümleri burada görünür. " +
                    "Bir diziyi Listem'e eklersen bölüm günleri buraya gelir.")
            col.addView(e, ui.ll(m, w, top = 40))
        }
        scroll.scrollTo(0, 0)
        return first
    }

    /** One horizontal row of cards; returns its first card. */
    private fun addRow(list: List<Item>): View? {
        val row = ui.hbox()
        row.clipChildren = false
        row.clipToPadding = false
        var first: View? = null
        for (it in list) {
            val card = card(it)
            row.addView(card)
            if (first == null) first = (card as ViewGroupLike).focusable
        }
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.overScrollMode = View.OVER_SCROLL_NEVER
        hs.clipChildren = false
        hs.clipToPadding = false
        hs.setPadding(ui.u(30), ui.u(22), ui.u(30), ui.u(6))
        Fx.softEdges(hs, ui)
        hs.addView(row)
        col.addView(hs, ui.ll(ui.match, ui.wrap))
        return first
    }

    /** Scrolls the list to [date]'s row and puts the remote on its first card. */
    private fun jumpTo(date: String) {
        val title = dayRows[date] ?: return
        scroll.smoothScrollTo(0, (title.top - ui.u(10)).coerceAtLeast(0))
        val row = col.getChildAt(col.indexOfChild(title) + 1) as? HorizontalScrollView
        val firstCard = ((row?.getChildAt(0) as? LinearLayout)?.getChildAt(0) as? ViewGroupLike)?.focusable
        firstCard?.post { firstCard.requestFocus() }
    }

    /** "BUGÜN", "YARIN" or "PERŞEMBE · 13 KASIM". */
    private fun dayTitle(date: String): String {
        val n = Episodes.daysUntil(date) ?: 0
        val day = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)
        } catch (e: Exception) {
            null
        } ?: return date
        val text = SimpleDateFormat("EEEE  ·  d MMMM", tr).format(day).uppercase(tr)
        return when (n) {
            0 -> "BUGÜN  ·  " + SimpleDateFormat("d MMMM", tr).format(day).uppercase(tr)
            1 -> "YARIN  ·  " + SimpleDateFormat("d MMMM", tr).format(day).uppercase(tr)
            else -> text
        }
    }

    /** The column a card sits in, and the part that takes focus. */
    private class ViewGroupLike(ctx: android.content.Context, val focusable: View) : LinearLayout(ctx)

    /** A card: the picture, its date ("27 KAS") and what it is (Sinema, Dijital, Yeni bölüm) as badges. */
    private fun card(item: Item): View {
        val p = item.pick
        val card = FrameLayout(this)
        card.isFocusable = true
        card.isClickable = true
        card.background = ui.round(C.CARD, D.CARD)
        card.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        card.addView(img, ui.fl(ui.match, ui.match))
        Img.load(this, "w780", p.backdrop, ui.u(320), img)
        val shade = View(this)
        shade.background = ui.gradient(GradientDrawable.Orientation.TOP_BOTTOM, Color.argb(120, 0, 0, 0), Color.TRANSPARENT)
        card.addView(shade, ui.fl(ui.match, ui.u(70), Gravity.TOP))
        val date = ui.text(shortDate(item.date), 15, C.TEXT, 800)
        date.letterSpacing = 0.06f
        date.setPadding(ui.u(9), ui.u(3), ui.u(9), ui.u(3))
        date.background = ui.round(Color.argb(150, 0, 0, 0), D.BADGE)
        val dlp = ui.fl(ui.wrap, ui.wrap, Gravity.TOP or Gravity.START)
        dlp.setMargins(ui.u(10), ui.u(10), 0, 0)
        card.addView(date, dlp)
        val (kindText, kindColor) = when (item.kind) {
            "cinema" -> "Sinema" to Color.parseColor("#FF4D8D")
            "digital" -> "Dijital" to Color.parseColor("#8A6CFF")
            else -> "Yeni bölüm" to Color.parseColor("#2FB344")
        }
        val kind = ui.text(kindText, 14, Color.WHITE, 800)
        kind.setPadding(ui.u(9), ui.u(3), ui.u(9), ui.u(3))
        kind.background = ui.round(kindColor, D.BADGE)
        val klp = ui.fl(ui.wrap, ui.wrap, Gravity.TOP or Gravity.END)
        klp.setMargins(0, ui.u(10), ui.u(10), 0)
        card.addView(kind, klp)
        val fx = Fx.decorate(ui, card, D.CARD)
        val col = ViewGroupLike(this, card)
        col.orientation = LinearLayout.VERTICAL
        col.clipChildren = false
        val title = ui.text(p.title, 20, C.TEXT, 600)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.alpha = 0.8f
        val line = ui.text(item.line, 16, if (item.mine) C.TEXT else C.TEXT3, if (item.mine) 600 else 500)
        line.maxLines = 1
        line.ellipsize = TextUtils.TruncateAt.END
        card.setOnFocusChangeListener { v, f ->
            ui.lift(v, f, 1.08f, 30)
            fx(f)
            title.animate().alpha(if (f) 1f else 0.8f).setDuration(D.FAST).start()
            if (f) {
                showHead(item)
                // Keep the focused day's row in view.
                val row = (col.parent?.parent as? View)
                row?.let { scroll.smoothScrollTo(0, (it.top - ui.u(60)).coerceAtLeast(0)) }
            }
        }
        card.setOnClickListener {
            DetailActivity.open(this, p)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        col.addView(card, ui.ll(ui.u(320), ui.u(180)))
        col.addView(title, ui.ll(ui.u(320), ui.wrap, top = 14))
        col.addView(line, ui.ll(ui.u(320), ui.wrap, top = 3))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 30)
        return col
    }

    /** "27 KAS". */
    private fun shortDate(date: String): String = try {
        SimpleDateFormat("d MMM", tr).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!).uppercase(tr).replace(".", "")
    } catch (e: Exception) {
        ""
    }

    // ---------- The month ----------

    private fun showMonth(on: Boolean) {
        monthOn = on
        if (on) buildMonth()
        month.visibility = if (on) View.VISIBLE else View.GONE
        scroll.visibility = if (on) View.GONE else View.VISIBLE
        buildChips()
    }

    /**
     * The month as a calendar: seven columns, Monday first; a day with something coming shows its
     * first picture and how many more. OK on a day opens the list at that day.
     */
    private fun buildMonth() {
        monthGrid.removeAllViews()
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val byDate = all.filter { passes(it) }.groupBy { it.date }
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        val title = ui.label(SimpleDateFormat("MMMM yyyy", tr).format(c.time).uppercase(tr))
        monthGrid.addView(title, ui.ll(ui.wrap, ui.wrap, left = 30, bottom = 14))
        val head = ui.hbox()
        for (d in listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")) {
            val t = ui.text(d, 17, C.TEXT3, 700)
            t.gravity = Gravity.CENTER
            head.addView(t, ui.ll(0, ui.wrap, weight = 1f))
        }
        monthGrid.addView(head, ui.ll(ui.match, ui.wrap, left = 30, right = 30, bottom = 8))
        // Monday first: blanks before the 1st.
        val lead = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val todayIso = iso.format(java.util.Date())
        var cell = 0
        var row = ui.hbox()
        fun flush() {
            monthGrid.addView(row, ui.ll(ui.match, ui.u(150), left = 30, right = 30, bottom = 10))
            row = ui.hbox()
        }
        repeat(lead) {
            row.addView(View(this), ui.ll(0, ui.match, weight = 1f, right = 10))
            cell++
        }
        var firstDay: View? = null
        for (day in 1..days) {
            c.set(Calendar.DAY_OF_MONTH, day)
            val date = iso.format(c.time)
            val list = byDate[date].orEmpty()
            val box = FrameLayout(this)
            box.background = ui.round(Color.argb(if (date == todayIso) 46 else 18, 255, 255, 255), 16)
            box.clipToOutline = true
            if (list.isNotEmpty()) {
                val img = ImageView(this)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.alpha = 0.85f
                box.addView(img, ui.fl(ui.match, ui.match))
                Img.load(this, "w300", list.first().pick.backdrop, ui.u(220), img)
                val shade = View(this)
                shade.background = ui.gradient(GradientDrawable.Orientation.BOTTOM_TOP, Color.argb(200, 0, 0, 0), Color.TRANSPARENT)
                box.addView(shade, ui.fl(ui.match, ui.match))
                val name = ui.text(list.first().pick.title + if (list.size > 1) "  +${list.size - 1}" else "", 15, C.TEXT, 600)
                name.maxLines = 1
                name.ellipsize = TextUtils.TruncateAt.END
                val nlp = ui.fl(ui.match, ui.wrap, Gravity.BOTTOM)
                nlp.setMargins(ui.u(10), 0, ui.u(10), ui.u(8))
                box.addView(name, nlp)
                box.isFocusable = true
                box.isClickable = true
                val ring = View(this)
                ring.background = ui.round(Color.TRANSPARENT, 16, 3, Color.WHITE)
                ring.alpha = 0f
                box.addView(ring, ui.fl(ui.match, ui.match))
                box.setOnFocusChangeListener { v, f ->
                    ring.animate().alpha(if (f) 1f else 0f).setDuration(D.FAST).start()
                    ui.lift(v, f, 1.05f, 20)
                    if (f) showHead(list.first())
                }
                box.setOnClickListener {
                    showMonth(false)
                    jumpTo(date)
                }
                if (firstDay == null) firstDay = box
            }
            val num = ui.text(day.toString(), 20, if (list.isEmpty()) C.TEXT3 else C.TEXT, 700)
            val lp = ui.fl(ui.wrap, ui.wrap, Gravity.TOP or Gravity.START)
            lp.setMargins(ui.u(10), ui.u(6), 0, 0)
            box.addView(num, lp)
            row.addView(box, ui.ll(0, ui.match, weight = 1f, right = 10))
            cell++
            if (cell % 7 == 0) flush()
        }
        if (cell % 7 != 0) {
            repeat(7 - cell % 7) { row.addView(View(this), ui.ll(0, ui.match, weight = 1f, right = 10)) }
            flush()
        }
        month.scrollTo(0, 0)
        firstDay?.post { firstDay?.requestFocus() }
    }

    // ---------- The top ----------

    private fun showHead(item: Item) {
        current = item
        val p = item.pick
        if (chosenDay != item.date) {
            chosenDay = item.date
            styleDays()
        }
        val n = Episodes.daysUntil(item.date) ?: 0
        val whenText = when {
            n == 0 -> "Bugün"
            n == 1 -> "Yarın"
            n < 0 -> "${-n} gün önce"
            else -> "$n gün sonra"
        }
        headTag.text = whenText + "  ·  " + item.line.substringBefore("  ·  ")
        headTitle.text = p.title
        headTitle.visibility = View.VISIBLE
        headLogo.visibility = View.GONE
        loadLogo(p)
        headLine.text = p.overview.ifEmpty { p.details() }
        updateButtons()
        // Cross-fade the picture behind; the trailer after a breath.
        handler.removeCallbacks(trailerSoon)
        trailer.stop()
        if (!Fx.lite && prefs.heroTrailer && p.homeTrailer.isNotBlank()) handler.postDelayed(trailerSoon, 3_000L)
        val target = if (front === backA) backB else backA
        val old = front
        Img.load(this, if (resources.displayMetrics.widthPixels > 1280) "original" else "w1280", p.backdrop, resources.displayMetrics.widthPixels, target) { ok ->
            if (!ok) return@load
            // Both pictures sit under the shades; one fades in as the other fades out.
            target.animate().alpha(0.55f).setDuration(D.SLOW).start()
            old?.animate()?.alpha(0f)?.setDuration(D.SLOW)?.start()
            front = target
        }
    }

    private fun loadLogo(p: Pick) {
        val path = p.logo.takeIf { it.isNotEmpty() } ?: return
        val want = p.key
        Thread {
            val b = Net.bitmap("https://image.tmdb.org/t/p/w500$path", ui.u(560), false)?.takeIf { !Img.isDark(it) }
            runOnUiThread {
                if (b == null || isFinishing || current?.pick?.key != want) return@runOnUiThread
                headLogo.setImageBitmap(b)
                headLogo.visibility = View.VISIBLE
                headTitle.visibility = View.GONE
            }
        }.start()
    }

    private fun startTrailer() {
        val p = current?.pick ?: return
        if (Fx.lite || !prefs.heroTrailer || !hasWindowFocus()) return
        trailer.sound = false
        trailer.start(p.homeTrailer)
    }

    /** "Hatırlat" for a film (the day it comes), "Listeme ekle" for a series (its episode days). */
    private fun updateButtons() {
        val item = current
        remindBtn.visibility = if (item == null) View.GONE else View.VISIBLE
        trailerBtn.visibility = if (item?.pick?.trailer.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (item == null) return
        val p = item.pick
        remindBtn.text = if (p.isSeries) {
            if (prefs.watchlist.any { it.key == p.key }) "✓   Listemde" else "+   Listeme ekle"
        } else {
            if (prefs.reminders.any { it.key == p.key }) "✓   Hatırlatılacak" else "+   Hatırlat"
        }
    }

    private fun toggleRemind() {
        val p = current?.pick ?: return
        if (p.isSeries) {
            val list = prefs.watchlist
            prefs.watchlist = if (list.any { it.key == p.key }) list.filter { it.key != p.key } else listOf(p) + list
        } else {
            val list = prefs.reminders
            val on = list.any { it.key == p.key }
            prefs.reminders = if (on) list.filter { it.key != p.key } else list + p
            Glass.toast(this, if (on) "Hatırlatma kapandı" else "Çıktığı gün haber vereceğim")
        }
        updateButtons()
    }
}
