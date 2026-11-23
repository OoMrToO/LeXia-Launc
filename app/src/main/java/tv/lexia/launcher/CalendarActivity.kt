package tv.lexia.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
 * The next two weeks in one place: films coming to Turkish cinemas and new episodes of
 * series, day by day. The chosen card's picture and details fill the top of the screen.
 */
class CalendarActivity : Activity() {

    private class Item(val pick: Pick, val date: String, val line: String, val mine: Boolean)

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private val tr = Locale("tr", "TR")
    private lateinit var backA: ImageView
    private lateinit var backB: ImageView
    private var front: ImageView? = null
    private lateinit var headTag: TextView
    private lateinit var headTitle: TextView
    private lateinit var headLine: TextView
    private lateinit var scroll: ScrollView


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
        val blp = ui.fl(m, (screenH * 0.7f).toInt(), Gravity.BOTTOM)
        frame.addView(bottom, blp)

        // Top: what the chosen card is.
        val head = ui.vbox()
        head.addView(ui.label("TAKVİM"))
        headTag = ui.text("", 22, C.TEXT2, 600)
        head.addView(headTag, ui.ll(w, w, top = 26))
        headTitle = ui.text("", 64, C.TEXT, 800)
        headTitle.letterSpacing = -0.025f
        headTitle.maxLines = 1
        headTitle.ellipsize = TextUtils.TruncateAt.END
        headTitle.maxWidth = ui.u(1200)
        head.addView(headTitle, ui.ll(w, w, top = 10))
        headLine = ui.text("", 22, C.TEXT2, 500)
        headLine.maxWidth = ui.u(1000)
        headLine.maxLines = 2
        headLine.ellipsize = TextUtils.TruncateAt.END
        head.addView(headLine, ui.ll(w, w, top = 12))
        val hlp = ui.fl(m, w, Gravity.TOP)
        hlp.setMargins(ui.u(110), ui.u(70), ui.u(110), 0)
        frame.addView(head, hlp)

        // Below: one row per day.
        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isSmoothScrollingEnabled = true
        scroll.clipChildren = false
        val col = ui.vbox()
        col.clipChildren = false
        col.clipToPadding = false
        col.setPadding(ui.u(110), 0, ui.u(110), ui.u(260))
        scroll.addView(col)
        val slp = ui.fl(m, m)
        slp.topMargin = (screenH * 0.42f).toInt()
        frame.addView(scroll, slp)

        val days = items().groupBy { it.date }.toSortedMap()
        var first: View? = null
        for ((date, list) in days) {
            col.addView(ui.label(dayTitle(date)), ui.ll(w, w, top = if (col.childCount == 0) 0 else 34))
            val row = ui.hbox()
            row.clipChildren = false
            row.clipToPadding = false
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
            hs.addView(row)
            val lp = ui.ll(m, w)
            lp.leftMargin = -ui.u(30)
            lp.rightMargin = -ui.u(30)
            col.addView(hs, lp)
        }
        if (days.isEmpty()) {
            headTag.text = "Önümüzdeki iki hafta"
            headTitle.text = "Takvim şimdilik boş"
            headLine.text = ""
            val e = ui.emptyState(R.drawable.ic_calendar, "İki hafta sessiz görünüyor",
                "Yakında vizyona girecek ve dijitale çıkacak filmler, takip ettiğin dizilerin yeni bölümleri burada görünür. " +
                    "Bir diziyi Listem'e eklersen bölüm günleri buraya gelir.")
            col.addView(e, ui.ll(m, w, top = 60))
            val back = ui.pill("Ana ekrana dön", 64, 23, primary = true)
            back.setOnClickListener { finish() }
            val bl = ui.ll(w, ui.u(64), top = 30)
            bl.gravity = Gravity.CENTER_HORIZONTAL
            col.addView(back, bl)
            if (first == null) first = back
        }

        setContentView(frame)
        Sound.attach(this)
        first?.requestFocus()
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(D.MED).start()
    }

    /** Films coming to Turkish cinemas and new episodes, the next 14 days, from the saved film list. */
    private fun items(): List<Item> {
        val out = ArrayList<Item>()
        val o = try {
            JSONObject(java.io.File(filesDir, "pick.json").readText())
        } catch (e: Exception) {
            return out
        }
        Remote.loadSaved(this)
        val reminded = prefs.reminders.map { it.key }.toSet()
        val soon = o.optJSONArray("soon")
        for (i in 0 until (soon?.length() ?: 0)) {
            val p = Pick.from(soon!!.getJSONObject(i))
            val d = Episodes.daysUntil(p.release) ?: continue
            if (d !in 0..14 || p.backdrop.isEmpty() || Remote.isHidden(p)) continue
            val mine = p.key in reminded
            out.add(Item(p, p.release, if (mine) "Vizyona giriyor  ·  Hatırlatılacak" else "Vizyona giriyor", mine))
        }
        // Coming out digitally in Turkey (to rent, buy or stream); the platform when it is known.
        val dig = o.optJSONArray("digital")
        for (i in 0 until (dig?.length() ?: 0)) {
            val p = Pick.from(dig!!.getJSONObject(i))
            val d = Episodes.daysUntil(p.digital) ?: continue
            if (d !in 0..14 || p.backdrop.isEmpty() || Remote.isHidden(p)) continue
            val where = if (p.where.isNotEmpty()) "Dijitalde  ·  " + p.where.joinToString(", ") else "Dijital çıkış"
            out.add(Item(p, p.digital, where, false))
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
            out.add(Item(p, s.next.date, if (mine) "$which  ·  Listem'de" else which, mine))
        }
        // Your own (Listem, reminders) first within a day.
        return out.sortedWith(compareBy({ it.date }, { if (it.mine) 0 else 1 }))
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
                // Keep the focused day's row in view under the picture.
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

    private fun showHead(item: Item) {
        val p = item.pick
        val n = Episodes.daysUntil(item.date) ?: 0
        val whenText = when (n) {
            0 -> "Bugün"
            1 -> "Yarın"
            else -> "$n gün sonra"
        }
        headTag.text = whenText + "  ·  " + item.line.substringBefore("  ·  ")
        headTitle.text = p.title
        headLine.text = p.overview.ifEmpty { p.details() }
        // Cross-fade the picture behind.
        val target = if (front === backA) backB else backA
        val old = front
        Img.load(this, "w1280", p.backdrop, resources.displayMetrics.widthPixels, target) { ok ->
            if (!ok) return@load
            // Both pictures sit under the shades; one fades in as the other fades out.
            target.animate().alpha(0.55f).setDuration(D.SLOW).start()
            old?.animate()?.alpha(0f)?.setDuration(D.SLOW)?.start()
            front = target
        }
    }
}
