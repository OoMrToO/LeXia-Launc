package tv.lexia.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Every app on the box, TV apps and phone-style ones alike, in a grid. A heart marks the ones
 * on the home screen; holding OK (or the menu key) adds or removes one.
 */
class AppsActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var grid: LinearLayout
    private lateinit var countText: TextView
    private val cols = 6
    private var sig = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        val m = ui.match
        val w = ui.wrap

        val frame = FrameLayout(this)
        val blur = Fx.backdrop
        frame.background = if (blur != null) {
            // How much of the picture shows through follows Ayarlar › Görünüm › Cam.
            LayerDrawable(arrayOf(BitmapDrawable(resources, blur), ColorDrawable(Color.argb(ui.dimAlpha() + 60, 0, 0, 0))))
        } else {
            ui.gradient(
                GradientDrawable.Orientation.TL_BR,
                Color.parseColor("#1A1F29"), Color.parseColor("#0A0B0E"), Color.BLACK
            )
        }

        val page = ui.vbox()
        page.setPadding(ui.u(110), ui.u(70), ui.u(110), 0)
        page.clipChildren = false
        page.clipToPadding = false

        val head = ui.hbox()
        head.gravity = Gravity.BOTTOM
        val title = ui.text("Tüm uygulamalar", 54, C.TEXT, 700)
        head.addView(title, ui.ll(0, w, weight = 1f))
        countText = ui.text("", 22, C.TEXT3, 500)
        head.addView(countText, ui.ll(w, w, bottom = 8))
        page.addView(head, ui.ll(m, w))
        val hint = ui.text("OK ile aç  ·  OK'e basılı tut: ana ekrana ekle ya da çıkar", 22, C.TEXT2, 500)
        page.addView(hint, ui.ll(m, w, top = 8, bottom = 34))

        grid = ui.vbox()
        grid.clipChildren = false
        grid.clipToPadding = false
        grid.setPadding(ui.u(24), ui.u(24), ui.u(24), ui.u(80))
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.addView(grid, FrameLayout.LayoutParams(m, w))
        page.addView(scroll, ui.ll(m, 0, left = -24, right = -24, weight = 1f))

        frame.addView(page, ui.fl(m, m))
        setContentView(frame)
        Sound.attach(this)
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(D.MED).start()
    }

    override fun onResume() {
        super.onResume()
        HomeButtonService.resume()
        build()
    }

    /** Rebuilds the grid when apps were installed or removed, keeping focus where it was. */
    private fun build() {
        val apps = Apps.all(this).sortedBy { it.label.lowercase() }
        val fav = Apps.favorites(prefs, Apps.all(this))
        val s = apps.joinToString("|") { it.key } + "#" + fav.sorted().joinToString(",")
        if (s == sig) return
        sig = s
        val focused = currentFocus?.tag as? String
        grid.removeAllViews()
        var rowBox: LinearLayout? = null
        var first: View? = null
        var again: View? = null
        for ((i, app) in apps.withIndex()) {
            if (i % cols == 0) {
                rowBox = ui.hbox()
                rowBox.clipChildren = false
                rowBox.clipToPadding = false
                grid.addView(rowBox, ui.ll(ui.match, ui.wrap, bottom = 30))
            }
            val t = tile(app, app.key in fav)
            rowBox!!.addView(t.first)
            if (first == null) first = t.second
            if (app.key == focused) again = t.second
        }
        val n = fav.count { k -> apps.any { it.key == k } }
        countText.text = "${apps.size} uygulama  ·  $n tanesi ana ekranda"
        (again ?: first)?.requestFocus()
    }

    /** A card with the app's banner (or icon) and its name under it; returns the column and the card. */
    private fun tile(app: AppEntry, onHome: Boolean): Pair<View, View> {
        val pm = packageManager
        val cw = 250
        val col = ui.vbox()
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.clipChildren = false
        col.clipToPadding = false

        val card = FrameLayout(this)
        card.tag = app.key
        card.isFocusable = true
        card.isClickable = true
        card.isLongClickable = true
        card.setOnHoverListener { h, e ->
            if (e.action == android.view.MotionEvent.ACTION_HOVER_ENTER && !h.isFocused) h.requestFocusFromTouch()
            false
        }
        val img = ImageView(this)
        val banner = try { app.info.loadBanner(pm) } catch (e: Exception) { null }
        if (banner != null) {
            card.background = ui.round(Color.BLACK, D.CARD)
            img.setImageDrawable(banner)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            Apps.iconCard(ui, card, app.key, try { app.info.loadIcon(pm) } catch (e: Exception) { null }, app.label, D.CARD, cw * 9 / 16)
        }
        card.clipToOutline = true
        if (banner != null) card.addView(img, ui.fl(ui.match, ui.match))
        if (onHome) {
            val mark = FrameLayout(this)
            mark.background = ui.round(Color.argb(200, 0, 0, 0), 15)
            val heart = ImageView(this)
            heart.setImageResource(R.drawable.ic_heart)
            heart.setColorFilter(Color.parseColor("#FF4D8D"), PorterDuff.Mode.SRC_IN)
            mark.addView(heart, ui.fl(ui.u(18), ui.u(18), Gravity.CENTER))
            val lp = ui.fl(ui.u(30), ui.u(30), Gravity.TOP or Gravity.END)
            lp.topMargin = ui.u(8)
            lp.rightMargin = ui.u(8)
            card.addView(mark, lp)
        }
        val fx = Fx.decorate(ui, card, D.CARD)

        val label = ui.text(app.label, 19, C.TEXT2, 500)
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END

        card.setOnFocusChangeListener { _, f ->
            ui.lift(card, f, 1.1f, 30)
            fx(f)
            label.setTextColor(if (f) C.TEXT else C.TEXT2)
            label.animate().translationY(if (f) ui.uf(10) else 0f).setDuration(D.FAST).start()
        }
        card.setOnClickListener {
            prefs.markUsed(app.key)
            HomeButtonService.opened(app.pkg)
            try {
                startActivity(Apps.launchIntent(app))
            } catch (e: Exception) {
                Glass.toast(this, "Açılamadı")
            }
        }
        card.setOnLongClickListener {
            menu(app)
            true
        }
        card.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP) {
                menu(app)
                true
            } else false
        }

        col.addView(card, ui.ll(ui.u(cw), ui.u(cw * 9 / 16)))
        col.addView(label, ui.ll(ui.u(cw), ui.wrap, top = 14))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 30)
        return col to card
    }

    private fun menu(app: AppEntry) {
        val onHome = app.key in (prefs.favorites ?: emptySet())
        val items = listOf(
            (if (onHome) "Ana ekrandan çıkar" else "Ana ekrana ekle") to "",
            "Uygulama bilgisi" to "",
            "Kaldır" to ""
        )
        Dialogs.list(this, app.label, items, subtitle = if (onHome) "Ana ekranda" else "Yalnızca burada") { i ->
            when (i) {
                0 -> {
                    prefs.toggleFavorite(app.key)
                    Glass.toast(this, if (onHome) "Ana ekrandan çıkarıldı" else "Ana ekrana eklendi")
                    build()
                }
                1 -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                2 -> Dialogs.pin(this, { open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))) })
            }
        }
    }

    private fun open(i: Intent) {
        HomeButtonService.pause()
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Glass.toast(this, "Açılamadı")
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
