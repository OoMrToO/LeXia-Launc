package tv.lexia.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A season watched to its last episode: a small celebration card. The series' poster, "SEZON
 * TAMAM", how many episodes and how long, and what comes next: the next season's first episode
 * when it is out ("2. Sezona başla"), or the day it comes ("2. Sezon 14 Mart'ta"), with "Haber ver"
 * (Listem: the bell counts down to it) when the series is not followed yet.
 */
internal object SeasonDone {

    fun show(
        a: Activity,
        film: Pick,
        season: Int,
        seasonEpisodes: List<MediaEpisode>,
        /** Starts the next season's first episode; null when it is not out. */
        startNext: (() -> Unit)?,
        done: () -> Unit
    ) {
        val ui = Ui(a)
        val prefs = Prefs(a)
        val tr = Locale("tr", "TR")
        val d = Dialog(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        var handled = false
        d.setOnDismissListener { if (!handled) done() }

        val root = FrameLayout(a)
        val blur = if (Fx.lite) null else try { Fx.snapshotBlur(a.window.decorView, 16, 3) } catch (e: Exception) { null }
        root.background = if (blur != null) android.graphics.drawable.LayerDrawable(
            arrayOf(android.graphics.drawable.BitmapDrawable(a.resources, blur), ColorDrawable(Color.argb(140, 0, 0, 0)))
        ) else ColorDrawable(Color.argb(170, 0, 0, 0))

        val card = ui.hbox()
        card.gravity = Gravity.CENTER_VERTICAL
        card.clipChildren = false
        card.clipToPadding = false
        card.background = ui.glass(Color.parseColor("#F21C1C21"), D.PANEL, 2)
        card.elevation = ui.uf(40)
        card.setPadding(ui.u(46), ui.u(46), ui.u(56), ui.u(46))

        val poster = ImageView(a)
        poster.scaleType = ImageView.ScaleType.CENTER_CROP
        poster.background = ui.round(C.CARD, 18)
        poster.clipToOutline = true
        poster.elevation = ui.uf(20)
        card.addView(poster, ui.ll(ui.u(250), ui.u(375), right = 46))
        Img.load(a, "w500", film.poster.ifEmpty { film.backdrop }, ui.u(250), poster)

        val col = ui.vbox()
        col.clipChildren = false
        col.clipToPadding = false
        col.addView(ui.label("SEZON TAMAM", Color.parseColor("#FFD36B")))
        val big = ui.text("$season. Sezon", 64, C.TEXT, 800)
        big.letterSpacing = -0.02f
        col.addView(big, ui.ll(ui.wrap, ui.wrap, top = 10))
        val name = ui.text(film.title, 26, C.TEXT2, 600)
        name.maxLines = 1
        col.addView(name, ui.ll(ui.u(620), ui.wrap, top = 4))
        val minutes = seasonEpisodes.sumOf { it.runtime }
        val count = seasonEpisodes.size
        val time = when {
            minutes >= 60 -> "${minutes / 60} sa ${minutes % 60} dk"
            minutes > 0 -> "$minutes dk"
            else -> ""
        }
        val stats = if (time.isNotEmpty()) "$count bölüm  ·  $time izledin" else "$count bölüm izledin"
        col.addView(ui.text(stats, 24, C.TEXT, 500), ui.ll(ui.wrap, ui.wrap, top = 22))

        // What comes next: out already, a known day, or nothing yet.
        val next = Episodes.of(film)?.takeIf { it.season > season }
        // "14 Mart'ta": the suffix follows the month's sound.
        val day = next?.let {
            try {
                val at = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it.date)!!
                val c = java.util.Calendar.getInstance()
                c.setTime(at)
                val sfx = arrayOf("'ta", "'ta", "'ta", "'da", "'ta", "'da", "'da", "'ta", "'de", "'de", "'da", "'ta")
                SimpleDateFormat("d MMMM", tr).format(at) + sfx[c.get(java.util.Calendar.MONTH)]
            } catch (_: Exception) {
                null
            }
        }
        val nextLine = when {
            startNext != null -> "${season + 1}. Sezon hazır"
            day != null -> "${season + 1}. Sezon $day geliyor"
            else -> ""
        }
        if (nextLine.isNotEmpty()) col.addView(ui.text(nextLine, 21, C.TEXT2, 500), ui.ll(ui.wrap, ui.wrap, top = 8))

        val buttons = ui.hbox()
        buttons.clipChildren = false
        buttons.clipToPadding = false
        var first: android.view.View? = null
        if (startNext != null) {
            val go = ui.pill("▶   ${season + 1}. Sezona başla", 64, 23, primary = true)
            go.setOnClickListener {
                handled = true
                d.dismiss()
                startNext()
            }
            buttons.addView(go)
            first = go
        } else if (day != null && prefs.watchlist.none { it.key == film.key }) {
            val tell = ui.pill("Haber ver", 64, 23, primary = true)
            tell.setOnClickListener {
                prefs.watchlist = listOf(film) + prefs.watchlist
                Glass.toast(a, "${season + 1}. Sezon yaklaşınca zilde geri sayım başlayacak")
                d.dismiss()
            }
            buttons.addView(tell)
            first = tell
        }
        val close = ui.pill("Kapat", 64, 23)
        close.setOnClickListener { d.dismiss() }
        buttons.addView(close)
        if (first == null) first = close
        col.addView(buttons, ui.ll(ui.wrap, ui.wrap, top = 36, left = -4))
        card.addView(col, ui.ll(ui.wrap, ui.wrap))

        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        d.setContentView(root)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        d.show()
        if (!Fx.lite) {
            card.alpha = 0f
            card.scaleX = 0.94f
            card.scaleY = 0.94f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
        }
        first.requestFocus()
    }
}
