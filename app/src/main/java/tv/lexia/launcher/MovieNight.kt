package tv.lexia.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Film gecesi: a film planned for a time tonight. The home screen counts down to it, and at that
 * time a full-screen card opens the film's platform, its trailer or its page.
 */
object MovieNight {
    class Plan(val film: Pick, val at: Long)

    private val hm = SimpleDateFormat("HH:mm", Locale.US)

    /** The plan, while it is still ahead or at most three hours past. */
    fun get(prefs: Prefs): Plan? {
        val s = prefs.movieNight
        if (s.isEmpty()) return null
        return try {
            val o = JSONObject(s)
            val at = o.getLong("at")
            if (System.currentTimeMillis() > at + 3 * 3600_000L) {
                prefs.movieNight = ""
                null
            } else Plan(Pick.from(o.getJSONObject("film")), at)
        } catch (e: Exception) {
            prefs.movieNight = ""
            null
        }
    }

    fun clear(prefs: Prefs) {
        prefs.movieNight = ""
    }

    fun time(at: Long): String = hm.format(Date(at))

    /** "Bu akşam 21:00" or "Yarın 21:00". */
    fun day(at: Long): String {
        val c = Calendar.getInstance()
        val today = c.get(Calendar.DAY_OF_YEAR)
        c.timeInMillis = at
        return (if (c.get(Calendar.DAY_OF_YEAR) == today) "Bu akşam " else "Yarın ") + time(at)
    }

    /** "2 sa 10 dk", "25 dk". */
    fun left(at: Long): String {
        val m = ((at - System.currentTimeMillis() + 59_999) / 60_000).coerceAtLeast(0)
        return if (m >= 60) "${m / 60} sa" + (if (m % 60 > 0) " ${m % 60} dk" else "") else "$m dk"
    }

    /** Asks the time; today's evening slots still ahead, otherwise tomorrow's. */
    fun choose(a: Activity, film: Pick, done: () -> Unit) {
        val prefs = Prefs(a)
        val slots = listOf(19 to 0, 20 to 0, 20 to 30, 21 to 0, 21 to 30, 22 to 0, 23 to 0)
        val now = System.currentTimeMillis()
        fun at(dayOffset: Int, h: Int, m: Int): Long {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, dayOffset)
            c.set(Calendar.HOUR_OF_DAY, h)
            c.set(Calendar.MINUTE, m)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        var times = slots.map { at(0, it.first, it.second) }.filter { it > now + 10 * 60_000L }
        if (times.isEmpty()) times = slots.map { at(1, it.first, it.second) }
        val names = times.map { day(it) }
        Dialogs.list(a, "Akşam planı", names.map { it to "" }, subtitle = film.title, icon = R.drawable.ic_moon) { i ->
            val o = JSONObject().put("at", times[i]).put("film", film.toJson())
            prefs.movieNight = o.toString()
            // The plan as a cinema ticket.
            Ticket.show(a, film, times[i])
            done()
        }
    }

    /** At the planned time: the film's picture full screen and what to do now. */
    fun card(a: Activity, ui: Ui, plan: Plan, openDetail: (Pick) -> Unit) {
        val prefs = Prefs(a)
        prefs.movieNightShown = plan.at
        val film = plan.film
        val d = Dialog(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val root = FrameLayout(a)
        root.setBackgroundColor(Color.BLACK)
        val img = ImageView(a)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.alpha = 0f
        root.addView(img, ui.fl(ui.match, ui.match))
        val shade = View(a)
        shade.background = ui.gradient(
            GradientDrawable.Orientation.LEFT_RIGHT,
            Color.argb(235, 0, 0, 0), Color.argb(150, 0, 0, 0), Color.argb(40, 0, 0, 0)
        )
        root.addView(shade, ui.fl(ui.match, ui.match))
        val low = View(a)
        low.background = ui.gradient(GradientDrawable.Orientation.BOTTOM_TOP, Color.argb(200, 0, 0, 0), Color.TRANSPARENT)
        root.addView(low, ui.fl(ui.match, ui.u(520), Gravity.BOTTOM))

        val col = ui.vbox()
        col.clipChildren = false
        col.clipToPadding = false
        col.addView(ui.label("AKŞAM PLANI  ·  ${time(plan.at)}", Color.parseColor("#FFD9A8")))
        val title = ui.text(film.title, 84, C.TEXT, 800)
        title.letterSpacing = -0.02f
        title.maxLines = 2
        col.addView(title, ui.ll(ui.u(860), ui.wrap, top = 14))
        val bits = ArrayList<String>()
        if (film.imdb > 0) bits.add(String.format(Locale("tr", "TR"), "IMDb %.1f", film.imdb))
        if (film.meta.isNotEmpty()) bits.add(film.meta)
        col.addView(ui.text(bits.joinToString("  ·  "), 24, C.TEXT2, 500), ui.ll(ui.wrap, ui.wrap, top = 14))
        col.addView(ui.text("Işıkları kıs, keyifli seyirler.", 28, C.TEXT, 400), ui.ll(ui.wrap, ui.wrap, top = 26))

        // The ticket waits at the bottom right; "Filmi başlat" tears its stub off and starts the film.
        val ticket = Ticket.build(a, ui, film, plan.at)
        val tlp = ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.END)
        tlp.rightMargin = ui.u(30)
        tlp.bottomMargin = ui.u(70)
        ticket.view.scaleX = 0.78f
        ticket.view.scaleY = 0.78f

        val buttons = ui.hbox()
        buttons.clipChildren = false
        buttons.clipToPadding = false
        val start = ui.pill("▶   Filmi başlat", 64, 23, primary = true)
        start.setOnClickListener {
            Ticket.tear(ticket.stub) {
                clear(prefs)
                d.dismiss()
                MediaSourcePickerActivity.open(a, film)
            }
        }
        buttons.addView(start)
        if (film.trailer.isNotEmpty()) {
            val tr = ui.pill("▶   Fragman", 64, 23)
            tr.setOnClickListener { TrailerActivity.open(a, film) }
            buttons.addView(tr)
        }
        val info = ui.pill("Detaylar", 64, 23)
        info.setOnClickListener {
            d.dismiss()
            openDetail(film)
        }
        buttons.addView(info)
        val close = ui.pill("Kapat", 64, 23)
        close.setOnClickListener {
            clear(prefs)
            d.dismiss()
        }
        buttons.addView(close)
        col.addView(buttons, ui.ll(ui.wrap, ui.wrap, top = 44))

        val clp = ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.START)
        clp.leftMargin = ui.u(130)
        clp.bottomMargin = ui.u(110)
        root.addView(col, clp)
        root.addView(ticket.view, tlp)
        d.setContentView(root)
        d.setOnCancelListener { clear(prefs) }
        d.show()
        buttons.getChildAt(0)?.requestFocus()
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(D.SLOW).start()
        if (film.backdrop.isNotEmpty()) {
            val w = a.resources.displayMetrics.widthPixels
            Thread {
                val b = Net.bitmap("https://image.tmdb.org/t/p/" + (if (w > 1280) "original" else "w1280") + film.backdrop, w, true)
                a.runOnUiThread {
                    if (b != null && d.isShowing) {
                        img.setImageBitmap(b)
                        img.scaleX = 1.06f
                        img.scaleY = 1.06f
                        img.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(2400).start()
                    }
                }
            }.start()
        }
    }
}
