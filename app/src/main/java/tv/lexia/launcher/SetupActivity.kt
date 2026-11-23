package tv.lexia.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * First-run guide: one task per screen, OK to go on. Steps that are already done are left out,
 * so a set-up TV never sees it and a half-done one picks up where it stopped.
 */
class SetupActivity : Activity() {

    private enum class Step { WELCOME, PERMISSION, UPDATE, HOME, PERSONAL, TASTE, DONE }

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var dots: LinearLayout
    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var status: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var page: LinearLayout

    private var steps: List<Step> = emptyList()
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.background = ui.gradient(
            GradientDrawable.Orientation.TL_BR,
            Color.parseColor("#1D2633"), Color.parseColor("#0B0D12"), Color.BLACK
        )
        page = ui.vbox()
        page.gravity = Gravity.CENTER_HORIZONTAL
        page.clipChildren = false
        dots = ui.hbox()
        page.addView(dots, ui.ll(ui.wrap, ui.wrap, bottom = 50))
        icon = ImageView(this)
        page.addView(icon, ui.ll(ui.u(150), ui.u(150)))
        title = ui.text("", 52, C.TEXT, 800)
        title.gravity = Gravity.CENTER
        page.addView(title, ui.ll(ui.wrap, ui.wrap, top = 36))
        body = ui.text("", 25, C.TEXT2, 400)
        body.gravity = Gravity.CENTER
        body.maxWidth = ui.u(1150)
        body.setLineSpacing(0f, 1.35f)
        page.addView(body, ui.ll(ui.wrap, ui.wrap, top = 22))
        status = ui.text("", 23, C.TEXT2, 600)
        status.gravity = Gravity.CENTER
        page.addView(status, ui.ll(ui.wrap, ui.wrap, top = 22))
        buttons = ui.hbox()
        buttons.gravity = Gravity.CENTER
        buttons.clipChildren = false
        page.addView(buttons, ui.ll(ui.wrap, ui.wrap, top = 46))
        root.addView(page, ui.ll(ui.match, ui.wrap))
        setContentView(root)

        steps = buildSteps()
        index = 0
        show()
    }

    private fun buildSteps(): List<Step> {
        val s = ArrayList<Step>()
        if (!prefs.setupStarted) s.add(Step.WELCOME)
        if (!Updater.canInstall(this)) s.add(Step.PERMISSION)
        if (BuildConfig.SETUP) s.add(Step.UPDATE)
        else if (HomeGuide.needed(this)) s.add(Step.HOME)
        s.add(Step.PERSONAL)
        if (!prefs.tasteAsked && prefs.genres.isEmpty()) s.add(Step.TASTE)
        s.add(Step.DONE)
        return s
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the TV's settings: move on if that step is now done.
        val step = steps.getOrNull(index) ?: return
        if ((step == Step.PERMISSION && Updater.canInstall(this)) ||
            (step == Step.HOME && HomeButtonService.isOn(this))
        ) {
            status.text = "✓  Tamam"
            status.postDelayed({ next() }, 700)
        }
    }

    private fun next() {
        if (index < steps.size - 1) {
            index++
            show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (index > 0) {
            index--
            show()
        }
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit): TextView {
        val b = ui.pill(label, 72, 26, primary)
        b.setOnClickListener { action() }
        return b
    }

    private fun show() {
        val step = steps[index]
        prefs.setupStarted = true
        dots.removeAllViews()
        for (i in steps.indices) {
            val d = View(this)
            d.background = ui.round(if (i == index) Color.WHITE else Color.argb(70, 255, 255, 255), 5)
            dots.addView(d, ui.ll(ui.u(if (i == index) 34 else 10), ui.u(10), left = 6, right = 6))
        }
        status.text = ""
        buttons.removeAllViews()
        val skip = button("Atla", false) { next() }

        when (step) {
            Step.WELCOME -> {
                setTexts(
                    R.drawable.ic_launcher, "LeXia'ya hoş geldin",
                    "Birkaç kısa adımda her şeyi hazırlayalım. Her adımı atlayabilir, " +
                        "sonra ayarlardan değiştirebilirsin."
                )
                buttons.addView(button("Başla", true) { next() })
            }
            Step.PERMISSION -> {
                setTexts(
                    R.drawable.ic_gear, "Güncelleme izni",
                    "Uygulamanın kendini güncelleyebilmesi için bir kez izin gerekiyor:\n" +
                        "Ayarlar › Uygulamalar › Güvenlik ve kısıtlamalar › Bilinmeyen kaynaklar › " +
                        "LeXia Launcher'ı aç.\nİzni verip geri dönünce kendiliğinden devam eder."
                )
                buttons.addView(button("Ayarları aç", true) {
                    SystemSettings.open(this, SystemSettings.Screen.ALL)
                })
                buttons.addView(skip)
            }
            Step.UPDATE -> {
                setTexts(
                    R.drawable.ic_search, "Tam sürümü kur",
                    "Kurulum sürümü yüklendi. Şimdi Ana Sayfa tuşu özelliği de olan tam sürümü " +
                        "indirip kuralım. Kurulum bitince bu ekran kaldığı yerden devam eder."
                )
                buttons.addView(button("İndir ve kur", true) { installFull() })
                buttons.addView(skip)
            }
            Step.HOME -> {
                setTexts(
                    R.drawable.ic_tv, "Ana Sayfa tuşu",
                    "Google TV, kumandadaki Ana Sayfa tuşunu kendi ekranına bağlar. TV ayarlarında bir kez " +
                        "“LeXia Ana Sayfa tuşu”nu açınca tuş doğrudan bu ekranı açar. " +
                        (if (HomeGuide.restricted) "Üç" else "İki") + " kısa adım; birlikte yapalım."
                )
                buttons.addView(button("Adım adım göster", true) { HomeGuide.open(this) })
                buttons.addView(skip)
            }
            Step.PERSONAL -> {
                setTexts(R.drawable.ic_gear, "Seni tanıyalım", personalText())
                buttons.addView(button("Adın", false) {
                    Dialogs.input(this, "Adın", prefs.name) {
                        prefs.name = it
                        body.text = personalText()
                    }
                })
                buttons.addView(button("Şehir", false) {
                    CityPicker.choose(this, prefs) { body.text = personalText() }
                })
                buttons.addView(button("Devam", true) { next() })
            }
            Step.TASTE -> {
                prefs.tasteAsked = true
                setTexts(
                    R.drawable.ic_heart, "Ne tür filmler seversin?",
                    "Birkaç tür seç; ana ekrandaki öneriler ve “Sana göre” satırları ilk günden onlarla başlar. " +
                        "Sonra Ayarlar › Filmler'den değiştirebilirsin."
                )
                buttons.addView(genreGrid())
            }
            Step.DONE -> {
                setTexts(
                    R.drawable.ic_tv, "Her şey hazır",
                    "• Bir uygulamaya basılı tutarak yerini değiştirebilirsin.\n" +
                        "• Aşağı inince Top 10, sinema ve Listem satırları var.\n" +
                        "• Sağ üstteki dişliden tüm ayarlara ulaşırsın."
                )
                buttons.addView(button("Ana ekrana git", true) {
                    prefs.setupDone = true
                    finish()
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                })
            }
        }
        buttons.getChildAt(0)?.requestFocus()

        page.alpha = 0f
        page.translationY = ui.uf(24)
        page.animate().alpha(1f).translationY(0f).setDuration(450)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    /** Every genre as a button that turns on and off, and "Devam" at the end. */
    private fun genreGrid(): View {
        val grid = ui.vbox()
        grid.gravity = Gravity.CENTER_HORIZONTAL
        grid.clipChildren = false
        val chosen = prefs.genres.toMutableSet()
        var line: LinearLayout? = null
        val all = Prefs.GENRES
        for ((i, g) in all.withIndex()) {
            if (i % 6 == 0) {
                line = ui.hbox()
                line.gravity = Gravity.CENTER
                line.clipChildren = false
                grid.addView(line, ui.ll(ui.wrap, ui.wrap, bottom = 14))
            }
            val b = ui.pill(if (g in chosen) "✓  $g" else g, 60, 22)
            b.setOnClickListener {
                if (!chosen.remove(g)) chosen.add(g)
                prefs.genres = chosen
                b.text = if (g in chosen) "✓  $g" else g
                status.text = if (chosen.isEmpty()) "" else "${chosen.size} tür seçildi"
            }
            line!!.addView(b, ui.ll(ui.wrap, ui.wrap, left = 7, right = 7))
        }
        val go = button("Devam", true) { next() }
        grid.addView(go, ui.ll(ui.wrap, ui.wrap, top = 22))
        return grid
    }

    private fun setTexts(iconRes: Int, t: String, b: String) {
        icon.setImageResource(iconRes)
        // Line icons are drawn white; the colour emblem stays as it is.
        if (iconRes == R.drawable.ic_launcher) icon.clearColorFilter()
        else icon.setColorFilter(Color.argb(235, 255, 255, 255), PorterDuff.Mode.SRC_IN)
        title.text = t
        body.text = b
    }

    private fun personalText(): String {
        val name = prefs.name.ifEmpty { "—" }
        val city = prefs.city.ifEmpty { "Otomatik" }
        return "Karşılamada adınla seslenelim, hava durumu doğru şehirden gelsin.\n\n" +
            "Adın: $name     ·     Şehir: $city"
    }

    private fun installFull() {
        status.text = "Denetleniyor…"
        Updater.check(this) { newer ->
            if (newer != true) {
                status.text = if (newer == null) "Bağlantı yok, biraz sonra tekrar dene." else "Zaten en yeni sürüm."
                return@check
            }
            Updater.install(this) { status.text = it }
        }
    }
}
