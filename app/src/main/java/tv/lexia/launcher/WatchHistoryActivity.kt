package tv.lexia.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import java.util.Locale

/**
 * İzlediklerim: everything watched in LeXia in three tabs. Yarım kalanlar (a press goes on where
 * it stopped), Diziler (where each series is, the next episode), Bitirdiklerim (with the day).
 * Above them, the month in one line: "Bu ay 6 film, 14 bölüm izledin".
 */
class WatchHistoryActivity : Activity() {

    companion object {
        fun open(context: Activity) {
            context.startActivity(Intent(context, WatchHistoryActivity::class.java))
            context.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        private val EPISODE = Regex(":(\\d+):(\\d+)$")
    }

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var grid: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var summary: TextView
    private val tr = Locale("tr", "TR")
    private val store by lazy { MediaPlaybackStore(this) }
    private val history by lazy { WatchHistory(this) }
    /** "left" (yarım kalanlar), "series", "done". */
    private var tab = "left"
    private var entries: List<WatchHistory.Entry> = emptyList()
    private var seenChanges = -1

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
        page.addView(ui.text("İzlediklerim", 54, C.TEXT, 700))
        summary = ui.text("", 22, C.TEXT2, 500)
        page.addView(summary, ui.ll(w, w, top = 6))
        tabBar = ui.hbox()
        tabBar.clipChildren = false
        tabBar.clipToPadding = false
        tabBar.setPadding(ui.u(6), ui.u(10), ui.u(6), ui.u(10))
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
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(D.MED).start()
    }

    override fun onResume() {
        super.onResume()
        // Back from the player: where things stand changed.
        if (seenChanges == MediaPlaybackStore.changes) return
        seenChanges = MediaPlaybackStore.changes
        Thread {
            // Titles played before the history was kept, while still in the day's list.
            try {
                val o = org.json.JSONObject(java.io.File(filesDir, "pick.json").readText())
                val picks = ArrayList<Pick>()
                for (n in listOf("picks", "platforms", "top10", "top10tv", "cinema", "local", "history")) {
                    val a = o.optJSONArray(n) ?: continue
                    for (i in 0 until a.length()) picks.add(Pick.from(a.getJSONObject(i)))
                }
                history.adopt(picks + prefs.recent + prefs.watchlist, store)
            } catch (_: Exception) {
            }
            val list = history.entries()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                entries = list
                summary.text = monthLine()
                val spot = FocusKeep.save(this)
                buildTabs()
                show()
                if (!FocusKeep.restore(this, spot) && spot != null) FocusKeep.first(grid)
            }
        }.start()
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean =
        Pointer.handle(this, ev) || super.dispatchGenericMotionEvent(ev)

    /** "Bu ay 6 film, 14 bölüm izledin." */
    private fun monthLine(): String {
        val start = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val month = history.finishes().filter { it.at >= start }
        val episodes = month.count { EPISODE.containsMatchIn(it.mediaKey) }
        val films = month.size - episodes
        val name = java.text.SimpleDateFormat("MMMM", tr).format(java.util.Date())
        val parts = listOfNotNull("$films film".takeIf { films > 0 }, "$episodes bölüm".takeIf { episodes > 0 })
        return if (parts.isEmpty()) "${name.replaceFirstChar { it.titlecase(tr) }} ayında henüz bir şey bitirmedin."
        else "Bu ay ${parts.joinToString(", ")} izledin."
    }

    private fun stateOf(e: WatchHistory.Entry) = WatchMarks.of(store, e.pick)

    private fun leftOff() = entries.filter { !it.pick.isSeries && stateOf(it)?.fraction != null }
    private fun series() = entries.filter { it.pick.isSeries }
    private fun finished() = entries.filter { !it.pick.isSeries && it.finishedAt > 0 && stateOf(it)?.fraction == null }
        .sortedByDescending { it.finishedAt }

    private fun buildTabs() {
        tabBar.removeAllViews()
        fun add(label: String, value: String, count: Int) {
            val b = ui.pill(if (count > 0) "$label  ·  $count" else label, 58, 22, primary = tab == value)
            b.setOnClickListener {
                if (tab == value) return@setOnClickListener
                tab = value
                buildTabs()
                show()
                tabBar.getChildAt(listOf("left", "series", "done").indexOf(value))?.requestFocus()
            }
            tabBar.addView(b)
        }
        add("Yarım kalanlar", "left", leftOff().size)
        add("Diziler", "series", series().size)
        add("Bitirdiklerim", "done", finished().size)
    }

    private fun show() {
        grid.removeAllViews()
        val list = when (tab) {
            "series" -> series()
            "done" -> finished()
            else -> leftOff()
        }
        if (list.isEmpty()) {
            val hint = when (tab) {
                "series" -> "İzlediğin diziler burada, kaldığın bölümle birlikte görünür."
                "done" -> "Sonuna kadar izlediğin filmler burada, izlediğin günle birlikte görünür."
                else -> "Yarıda bıraktığın filmler burada görünür; birine bas, kaldığın yerden sürsün."
            }
            grid.addView(ui.text(hint, 24, C.TEXT2, 400), ui.ll(ui.wrap, ui.wrap, top = 40, left = 10))
            return
        }
        val cols = 7
        var row: LinearLayout? = null
        list.forEachIndexed { i, e ->
            if (i % cols == 0) row = ui.hbox().also {
                it.clipChildren = false
                it.clipToPadding = false
                grid.addView(it, ui.ll(ui.match, ui.wrap, bottom = 30))
            }
            row!!.addView(card(e))
        }
    }

    /** "42 dk kaldı", "2. Sezon 5. Bölüm · 20 dk kaldı", "Sırada: 2. Sezon 6. Bölüm", "12 Kasım'da izledin". */
    private fun lineOf(e: WatchHistory.Entry): String {
        val last = store.last(e.pick.key)
        fun left(key: String): String? {
            val pos = store.resumePositionMs(key)
            val dur = store.durationMs(key)
            if (pos <= 0 || dur <= pos) return null
            val minutes = ((dur - pos) / 60_000L).coerceAtLeast(1)
            return if (minutes >= 60) "${minutes / 60} sa ${minutes % 60} dk kaldı" else "$minutes dk kaldı"
        }
        return when (tab) {
            "done" -> java.text.SimpleDateFormat("d MMMM", tr).format(java.util.Date(e.finishedAt)) + suffix(e.finishedAt) + " izledin"
            "series" -> {
                val m = EPISODE.find(last) ?: return "Henüz bölüm izlenmedi"
                val s = m.groupValues[1].toInt()
                val n = m.groupValues[2].toInt()
                if (store.isFinished(last) && store.resumePositionMs(last) <= 0) "Sırada: $s. Sezon ${n + 1}. Bölüm"
                else listOfNotNull("$s. Sezon $n. Bölüm", left(last)).joinToString("  ·  ")
            }
            else -> left(last) ?: ""
        }
    }

    /** "'de", "'da", "'te", "'ta" after "12 Kasım" and the like: the month's last sound decides. */
    private fun suffix(at: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val sfx = arrayOf("'ta", "'ta", "'ta", "'da", "'ta", "'da", "'da", "'ta", "'de", "'de", "'da", "'ta")
        return sfx[c.get(Calendar.MONTH)]
    }

    /** Where a press goes: the film on (it opens where it stopped), a series on its next episode. */
    private fun play(e: WatchHistory.Entry) {
        val p = e.pick
        if (tab == "done") {
            DetailActivity.open(this, p)
            return
        }
        val last = store.last(p.key)
        val m = EPISODE.find(last)
        val resume = if (p.isSeries && m != null) {
            val s = m.groupValues[1].toInt()
            val n = m.groupValues[2].toInt()
            if (store.isFinished(last) && store.resumePositionMs(last) <= 0) last.substring(0, m.range.first) + ":$s:${n + 1}" else last
        } else ""
        MediaSourcePickerActivity.open(this, p, resumeEpisode = resume)
    }

    private fun card(e: WatchHistory.Entry): View {
        val p = e.pick
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
        Img.load(this, "w342", p.poster.ifEmpty { p.backdrop }, ui.u(cw), img)
        if (tab != "done") WatchMarks.add(ui, poster, stateOf(e))
        // A series with a new episode out this week, not watched yet.
        if (tab == "series") Episodes.recent(p)?.let { r ->
            val id = p.imdbId.ifBlank { if (p.id > 0) "tmdb:${p.id}" else "" }
            if (id.isNotEmpty() && !store.isFinished("$id:${r.season}:${r.episode}")) {
                val b = ui.text("Yeni bölüm çıktı", 13, C.INK, 800)
                b.background = ui.round(Color.argb(240, 255, 255, 255), D.BADGE)
                b.setPadding(ui.u(9), ui.u(4), ui.u(9), ui.u(4))
                val lp = ui.fl(ui.wrap, ui.wrap, Gravity.TOP or Gravity.START)
                lp.setMargins(ui.u(10), ui.u(10), 0, 0)
                poster.addView(b, lp)
            }
        }
        val fx = Fx.decorate(ui, poster, D.CARD)
        val title = ui.text(p.title, 19, C.TEXT2, 500)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        val sub = ui.text(lineOf(e), 16, C.TEXT3, 500)
        sub.maxLines = 1
        sub.ellipsize = TextUtils.TruncateAt.END
        poster.setOnFocusChangeListener { _, f ->
            ui.lift(poster, f, 1.08f, 30)
            fx(f)
            title.setTextColor(if (f) C.TEXT else C.TEXT2)
        }
        poster.setTag(R.id.lexia_pick, p)
        poster.setOnClickListener { play(e) }
        poster.isLongClickable = true
        poster.setOnLongClickListener {
            val rows = listOf("Film sayfasını aç" to "", "Listeden kaldır" to "Kaldığın yer de silinir")
            Dialogs.list(this, p.title, rows) { i ->
                if (i == 0) DetailActivity.open(this, p)
                else {
                    store.last(p.key).takeIf { it.isNotBlank() }?.let { store.clear(it) }
                    history.remove(p.key)
                    entries = history.entries()
                    buildTabs()
                    show()
                }
            }
            true
        }
        col.addView(poster, ui.ll(ui.u(cw), ui.u(cw * 3 / 2)))
        col.addView(title, ui.ll(ui.u(cw), ui.wrap, top = 12))
        col.addView(sub, ui.ll(ui.u(cw), ui.wrap, top = 2))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 28)
        return col
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
