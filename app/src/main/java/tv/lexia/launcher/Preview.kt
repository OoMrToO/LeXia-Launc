package tv.lexia.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Small live pictures for the left side of the settings: the screensaver as it will look,
 * and the home screen with the current look settings. Both follow changes at once.
 */
class Preview(private val a: Activity, private val ui: Ui, private val prefs: Prefs) {
    companion object {
        const val W = 600
        const val H = 338
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tr = Locale("tr", "TR")
    private var films: List<Pick> = emptyList()
    private var cinema: List<Pick> = emptyList()
    private var history: List<Pick> = emptyList()
    private var streaming: List<Pick> = emptyList()
    private var cycle: Runnable? = null
    /** Film the screensaver preview is on; kept across rebuilds. */
    private var saverAt = 0

    init {
        try {
            val f = java.io.File(a.filesDir, "pick.json")
            if (f.exists()) {
                val o = JSONObject(f.readText())
                fun list(k: String): List<Pick> {
                    val arr = o.optJSONArray(k) ?: return emptyList()
                    return (0 until arr.length()).map { Pick.from(arr.getJSONObject(it)) }.filter { it.backdrop.isNotEmpty() }
                }
                films = list("picks")
                val now = list("cinema")
                val soon = list("soon")
                val mix = ArrayList<Pick>()
                for (i in 0 until maxOf(now.size, soon.size)) {
                    now.getOrNull(i)?.let { mix.add(it) }
                    soon.getOrNull(i)?.let { mix.add(it) }
                }
                cinema = mix
                history = list("history")
                streaming = list("platforms")
            }
        } catch (e: Exception) {
        }
    }

    fun stop() {
        cycle?.let { handler.removeCallbacks(it) }
        cycle = null
    }

    private fun frame(): FrameLayout {
        val box = FrameLayout(a)
        box.background = ui.round(Color.parseColor("#0E1014"), 22)
        box.clipToOutline = true
        box.elevation = ui.uf(16)
        return box
    }

    private fun scrim(box: FrameLayout, top: Boolean) {
        val v = View(a)
        v.background = ui.gradient(
            GradientDrawable.Orientation.TOP_BOTTOM,
            *(if (top) intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(200, 0, 0, 0))
            else intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(40, 0, 0, 0), Color.argb(210, 0, 0, 0)))
        )
        box.addView(v, ui.fl(ui.match, ui.match))
    }

    private fun clock(size: Int): TextClock {
        val c = TextClock(a)
        val f = if (prefs.clock24) "HH:mm" else "h:mm a"
        c.format24Hour = f
        c.format12Hour = f
        c.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.uf(size))
        c.setTextColor(C.TEXT)
        c.typeface = ui.font(400)
        c.setShadowLayer(ui.uf(8), 0f, ui.uf(1), Color.argb(150, 0, 0, 0))
        return c
    }

    private fun weather(size: Int): View? {
        if (Fx.weatherTemp.isEmpty()) return null
        val row = ui.hbox()
        if (Fx.weatherIcon != 0) {
            val i = ImageView(a)
            i.setImageResource(Fx.weatherIcon)
            i.setColorFilter(C.TEXT, PorterDuff.Mode.SRC_IN)
            row.addView(i, ui.ll(ui.u(size), ui.u(size), right = 6))
        }
        row.addView(ui.text(Fx.weatherTemp, size, C.TEXT, 500))
        return row
    }

    /** The screensaver: films fading one into the next, name and slogan, clock and weather. */
    fun saver(): View {
        stop()
        val box = frame()
        val list = when (prefs.saverContent) {
            "cinema" -> cinema
            "history" -> history.ifEmpty { cinema }
            "platform" -> streaming.ifEmpty { cinema }
            // Cinema, history and platform films in turn.
            "mix" -> (0 until maxOf(cinema.size, history.size, streaming.size)).flatMap { i ->
                listOfNotNull(cinema.getOrNull(i), history.getOrNull(i), streaming.getOrNull(i))
            }
            else -> films
        }.ifEmpty { films }.take(8)
        val imgs = listOf(ImageView(a), ImageView(a))
        for (i in imgs) {
            i.scaleType = ImageView.ScaleType.CENTER_CROP
            i.alpha = 0f
            box.addView(i, ui.fl(ui.match, ui.match))
        }
        scrim(box, false)
        val col = ui.vbox()
        val title = ui.text("", 20, C.TEXT, 600)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.maxWidth = ui.u(330)
        val line = ui.text("", 12, C.TEXT2, 400)
        line.maxLines = 2
        line.ellipsize = TextUtils.TruncateAt.END
        line.maxWidth = ui.u(330)
        col.addView(title)
        col.addView(line, ui.ll(ui.wrap, ui.wrap, top = 4))
        val clp = ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.START)
        clp.setMargins(ui.u(26), 0, 0, ui.u(24))
        box.addView(col, clp)
        val right = ui.hbox()
        if (prefs.saverWeather && prefs.weather) weather(if (prefs.saverClock == 2) 20 else 15)?.let {
            right.addView(it, ui.ll(ui.wrap, ui.wrap, right = 14))
        }
        if (prefs.saverClock > 0) right.addView(clock(if (prefs.saverClock == 2) 46 else 28))
        val rlp = ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.END)
        rlp.setMargins(0, 0, ui.u(26), ui.u(20))
        box.addView(right, rlp)
        if (list.isEmpty()) {
            title.text = "Ekran koruyucu"
            return box
        }
        var front = 0
        fun show(instant: Boolean) {
            val p = list[saverAt % list.size]
            val img = imgs[front]
            val old = imgs[1 - front]
            Img.load(a, "w780", p.backdrop, ui.u(W), img) {
                if (instant) {
                    img.alpha = 1f
                    old.alpha = 0f
                } else {
                    img.animate().alpha(1f).setDuration(900).start()
                    old.animate().alpha(0f).setDuration(900).start()
                }
            }
            title.text = p.title
            line.text = if (p.years > 0) "${p.years} yıl önce bugün gösterime girdi" else p.tagline.ifEmpty { p.overview }
            front = 1 - front
        }
        show(instant = true)
        val r = object : Runnable {
            override fun run() {
                saverAt++
                show(instant = false)
                handler.postDelayed(this, 3500)
            }
        }
        cycle = r
        handler.postDelayed(r, 3500)
        return box
    }

    /** The home screen in small, with the background, greeting, clock, weather and card size. */
    fun home(): View {
        stop()
        val box = frame()
        val film = films.firstOrNull()
        if (prefs.background == "film" && film != null) {
            val img = ImageView(a)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            box.addView(img, ui.fl(ui.match, ui.match))
            Img.load(a, "w780", film.backdrop, ui.u(W), img)
        } else {
            val g = View(a)
            g.background = ui.gradient(
                GradientDrawable.Orientation.TL_BR,
                Color.parseColor("#1A1F29"), Color.parseColor("#0A0B0E"), Color.BLACK
            )
            box.addView(g, ui.fl(ui.match, ui.match))
        }
        scrim(box, true)

        val top = ui.hbox()
        val left = ui.vbox()
        if (prefs.greet) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val hello = when {
                h < 5 -> "İyi geceler"
                h < 12 -> "Günaydın"
                h < 18 -> "İyi günler"
                else -> "İyi akşamlar"
            }
            val g = ui.text(if (prefs.name.isEmpty()) hello else "$hello, ${prefs.name}", 15, C.TEXT, 600)
            g.maxLines = 1
            g.ellipsize = TextUtils.TruncateAt.END
            left.addView(g)
        }
        val date = ui.text(SimpleDateFormat("d MMMM EEEE", tr).format(Calendar.getInstance().time).uppercase(tr), 8, C.TEXT3, 600)
        date.letterSpacing = 0.18f
        left.addView(date, ui.ll(ui.wrap, ui.wrap, top = 2))
        top.addView(left, ui.ll(0, ui.wrap, weight = 1f))
        if (prefs.weather) weather(14)?.let { top.addView(it, ui.ll(ui.wrap, ui.wrap, right = 12)) }
        top.addView(clock(15))
        val tlp = ui.fl(ui.match, ui.wrap, Gravity.TOP)
        tlp.setMargins(ui.u(22), ui.u(18), ui.u(22), 0)
        box.addView(top, tlp)

        val bottom = ui.vbox()
        if (film != null && prefs.showPicks) {
            val t = ui.text(film.title, 22, C.TEXT, 800)
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.END
            t.maxWidth = ui.u(380)
            bottom.addView(t)
            val m = ui.text(film.details(2), 9, C.TEXT2, 500)
            m.maxLines = 1
            bottom.addView(m, ui.ll(ui.wrap, ui.wrap, top = 3, bottom = 12))
        }
        val apps = LinearLayout(a)
        apps.orientation = LinearLayout.HORIZONTAL
        val cw = when (prefs.cardSize) { 0 -> 72; 2 -> 107; else -> 89 }
        val pm = a.packageManager
        val icons = Apps.iconShape(prefs.appShape)
        val w = if (icons) cw * 9 / 16 else cw
        for (app in Apps.shown(a, prefs).take(if (icons) 12 else 8)) {
            val card = FrameLayout(a)
            card.background = ui.round(Color.parseColor("#2A2B30"), when (prefs.appShape) { "sharp" -> 1; "circle" -> w / 2; "square" -> w / 4; else -> 6 })
            card.clipToOutline = true
            val img = ImageView(a)
            val banner = if (icons) null else try { app.info.loadBanner(pm) } catch (e: Exception) { null }
            if (banner != null) {
                img.setImageDrawable(banner)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
            } else {
                Apps.iconCard(ui, card, app.key, app.info.loadIcon(pm), null,
                    when (prefs.appShape) { "sharp" -> 1; "circle" -> w / 2; "square" -> w / 4; else -> 6 }, cw * 9 / 16)
            }
            if (banner != null) card.addView(img, ui.fl(ui.match, ui.match))
            apps.addView(card, ui.ll(ui.u(w), ui.u(cw * 9 / 16), right = 10))
        }
        bottom.addView(apps)
        val blp = ui.fl(ui.match, ui.wrap, Gravity.BOTTOM)
        blp.setMargins(ui.u(22), 0, 0, ui.u(20))
        box.addView(bottom, blp)
        return box
    }
}
