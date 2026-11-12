package tv.lexia.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/** "What's new" card shown once after an update; the list lives in assets/whatsnew.txt. */
object WhatsNew {
    /** Lines of the bundled list; blank lines and "#" comments are skipped. */
    fun items(a: Activity): List<String> = try {
        a.assets.open("whatsnew.txt").bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    } catch (e: Exception) {
        emptyList()
    }

    /** Fingerprint of the list build #102 showed, from before the fingerprint was saved. */
    private const val SHOWN_IN_102 = "-1589289731"

    private fun fingerprint(lines: List<String>) = lines.joinToString("\n").hashCode().toString()

    /**
     * Shows the card after an update, once per build and only when the list changed (a release with
     * only unseen fixes keeps the old list); quietly marks a fresh install as seen.
     */
    fun maybeShow(a: Activity, ui: Ui, prefs: Prefs) {
        val build = BuildConfig.BUILD_NO.toIntOrNull() ?: 0
        val before = prefs.seenBuild
        if (build <= 0 || before >= build) return
        // A fresh install has nothing "new"; an update (even from a build before this card) does.
        val fresh = try {
            val i = a.packageManager.getPackageInfo(a.packageName, 0)
            i.firstInstallTime == i.lastUpdateTime
        } catch (e: Exception) {
            false
        }
        prefs.seenBuild = build
        val key = fingerprint(items(a))
        val last = prefs.seenNews.ifEmpty { if (before >= 102) SHOWN_IN_102 else "" }
        prefs.seenNews = key
        if (!fresh && key != last) show(a, ui)
    }

    /** [vote]: right after an update, the card also asks whether the release is liked (once per build). */
    fun show(a: Activity, ui: Ui, vote: Boolean = false) {
        val lines = items(a)
        if (lines.isEmpty()) return
        card(a, ui, "YENİLİKLER", "Sürüm #${BuildConfig.BUILD_NO}", lines, vote)
    }

    /** Sends one like or dislike for this build; only the total per build is kept on the site. */
    private fun sendVote(a: Activity, up: Boolean) {
        val build = BuildConfig.BUILD_NO.toIntOrNull() ?: return
        val prefs = Prefs(a)
        if (prefs.votedBuild == build) return
        prefs.votedBuild = build
        Glass.toast(a, if (up) "Teşekkürler!" else "Teşekkürler, düzeltmeye çalışacağız")
    }

    /** A glass card over the screen: a small label, a title, a few lines and "Tamam". */
    fun card(a: Activity, ui: Ui, label: String, title: String, lines: List<String>, vote: Boolean = false) {
        val d = Dialog(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val root = FrameLayout(a)
        root.setBackgroundColor(Color.argb(170, 0, 0, 0))

        val card = ui.vbox()
        card.background = ui.round(Color.parseColor("#F21C1C21"), D.PANEL, 1)
        card.setPadding(ui.u(64), ui.u(54), ui.u(64), ui.u(48))
        val tag = ui.label(label, C.TEXT3)
        card.addView(tag)
        card.addView(ui.text(title, 46, C.TEXT, 700), ui.ll(ui.wrap, ui.wrap, top = 10))
        for (l in lines.take(9)) {
            val row = ui.hbox()
            row.gravity = Gravity.TOP
            val dot = View(a)
            dot.background = ui.round(C.ACCENT, 5)
            row.addView(dot, ui.ll(ui.u(10), ui.u(10), top = 12, right = 22))
            val t = ui.text(l, 23, C.TEXT2, 400)
            t.setLineSpacing(0f, 1.15f)
            row.addView(t, ui.ll(0, ui.wrap, weight = 1f))
            card.addView(row, ui.ll(ui.match, ui.wrap, top = 16))
        }
        val ok = ui.pill("Tamam", 64, 23, primary = true)
        ok.setOnClickListener { d.dismiss() }
        val bar = ui.hbox()
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.addView(ok)
        if (vote && Prefs(a).votedBuild != (BuildConfig.BUILD_NO.toIntOrNull() ?: -1)) {
            val q = ui.text("Bu sürümü beğendin mi?", 22, C.TEXT3, 500)
            bar.addView(q, ui.ll(ui.wrap, ui.wrap, left = 30, right = 18))
            val up = ui.iconPill("Beğendim", R.drawable.ic_heart)
            up.setOnClickListener { sendVote(a, true); d.dismiss() }
            val down = ui.iconPill("Beğenmedim", R.drawable.ic_not)
            down.setOnClickListener { sendVote(a, false); d.dismiss() }
            bar.addView(up)
            bar.addView(down)
        }
        card.addView(bar, ui.ll(ui.wrap, ui.wrap, top = 40))

        // Wide enough that most items take one line; and if a long list (or big text) is still taller
        // than the screen, it scrolls, so the buttons are never cut off at the bottom.
        val scroll = android.widget.ScrollView(a)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        val holder = FrameLayout(a)
        holder.setPadding(0, ui.u(40), 0, ui.u(40))
        holder.addView(card, FrameLayout.LayoutParams(ui.u(1400), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        scroll.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        d.setContentView(root)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        d.show()
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(D.MED).start()
        ok.requestFocus()
        // The pill's right margin is for rows of buttons; alone it should sit flush.
        (ok.layoutParams as? LinearLayout.LayoutParams)?.rightMargin = 0
    }
}
