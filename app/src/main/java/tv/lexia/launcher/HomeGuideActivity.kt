package tv.lexia.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Whether the Home button still goes somewhere else, so the guide is worth showing. */
object HomeGuide {
    private val googleHomes = setOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher")

    /**
     * True when pressing Home does not bring LeXia: the full build, the service is off, and either
     * LeXia is not the default home or Google's own home screen is still there to take the button.
     */
    fun needed(ctx: Context): Boolean {
        if (BuildConfig.SETUP || HomeButtonService.isOn(ctx)) return false
        val pm = ctx.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val default = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == ctx.packageName
        if (!default) return true
        val homes = try { pm.queryIntentActivities(home, 0).map { it.activityInfo.packageName } } catch (e: Exception) { emptyList() }
        return homes.any { it in googleHomes }
    }

    /** Android 13 and later hide accessibility for apps not from the store until it is allowed. */
    val restricted get() = Build.VERSION.SDK_INT >= 33

    fun open(a: Activity) {
        a.startActivity(Intent(a, HomeGuideActivity::class.java))
        a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}

/**
 * Step by step: how to make the remote's Home button open LeXia. Only the steps this TV needs;
 * coming back from the TV's settings it sees by itself when it is done.
 */
class HomeGuideActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var status: TextView
    private val marks = ArrayList<Pair<FrameLayout, TextView>>()
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.SETUP) {
            finish()
            return
        }
        ui = Ui(this)
        val m = ui.match
        val w = ui.wrap

        val frame = FrameLayout(this)
        val blur = Fx.backdrop
        frame.background = if (blur != null) {
            LayerDrawable(arrayOf(BitmapDrawable(resources, blur), ColorDrawable(Color.argb(ui.dimAlpha() + 70, 0, 0, 0))))
        } else {
            ui.gradient(GradientDrawable.Orientation.TL_BR, Color.parseColor("#1A1F29"), Color.parseColor("#0A0B0E"), Color.BLACK)
        }

        val page = ui.vbox()
        page.gravity = Gravity.CENTER_VERTICAL
        page.setPadding(ui.u(130), ui.u(70), ui.u(130), ui.u(70))
        page.clipChildren = false
        page.clipToPadding = false

        page.addView(ui.label("ANA SAYFA TUŞU"), ui.ll(w, w))
        page.addView(ui.text("Ana Sayfa tuşu LeXia'yı açsın", 56, C.TEXT, 700), ui.ll(m, w, top = 14))
        val why = ui.text(
            "Google TV, kumandadaki Ana Sayfa tuşunu kendi ekranına bağlar; LeXia'yı varsayılan yapmak bunu değiştirmez. " +
                "TV ayarlarında bir kez “LeXia Ana Sayfa tuşu”nu açınca tuş doğrudan LeXia'yı açar.",
            24, C.TEXT2, 400
        )
        why.setLineSpacing(0f, 1.25f)
        page.addView(why, ui.ll(ui.u(1300), w, top = 18))

        val steps = ArrayList<Pair<String, String>>()
        if (HomeGuide.restricted) {
            steps.add(
                "İzin ver" to "“Uygulama bilgisi”ne bas. Açılan sayfada “Kısıtlanmış ayarlara izin ver”i seç " +
                    "(bazı TV'lerde sağ üstteki ⋮ menüsünde). Bu satır yoksa bu adımı geç."
            )
        }
        steps.add(
            "Erişilebilirlik" to "“Ayarları aç”a bas. Açılmazsa: Ayarlar › Sistem › Erişilebilirlik " +
                "(bazı TV'lerde Cihaz Tercihleri › Erişilebilirlik)."
        )
        steps.add(
            "LeXia'yı aç" to "Listede “LeXia Ana Sayfa tuşu”nu seç, Aç'a bas ve onayla. " +
                "Sonra Geri tuşuyla buraya dön."
        )
        val row = ui.hbox()
        row.clipChildren = false
        for ((i, s) in steps.withIndex()) {
            row.addView(step(i + 1, s.first, s.second), ui.ll(0, w, weight = 1f, right = if (i < steps.size - 1) 28 else 0))
        }
        page.addView(row, ui.ll(m, w, top = 50))

        status = ui.text("", 24, C.TEXT2, 600)
        page.addView(status, ui.ll(m, w, top = 40))

        val buttons = ui.hbox()
        buttons.clipChildren = false
        buttons.clipToPadding = false
        val settings = ui.pill("Ayarları aç", 64, 23, primary = true)
        settings.setOnClickListener { SystemSettings.openAccessibility(this) }
        buttons.addView(settings)
        if (HomeGuide.restricted) {
            val info = ui.pill("Uygulama bilgisi", 64, 23)
            info.setOnClickListener {
                HomeButtonService.pause()
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    Glass.toast(this, "Ayarlar › Uygulamalar › LeXia Launcher", long = true)
                }
            }
            buttons.addView(info)
        }
        val later = ui.pill("Şimdi değil", 64, 23)
        later.setOnClickListener { finish() }
        buttons.addView(later)
        page.addView(buttons, ui.ll(m, w, top = 26))

        frame.addView(page, ui.fl(m, m))
        setContentView(frame)
        Sound.attach(this)
        settings.requestFocus()
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(D.MED).start()
    }

    /** A numbered glass card; the number turns into a check when the guide is done. */
    private fun step(n: Int, title: String, text: String): View {
        val card = ui.vbox()
        card.background = ui.glass(C.GLASS, D.PANEL, 2)
        card.setPadding(ui.u(34), ui.u(32), ui.u(34), ui.u(36))
        val dot = FrameLayout(this)
        dot.background = ui.round(Color.parseColor("#E6FFFFFF"), 26)
        val num = ui.text(n.toString(), 24, C.INK, 700)
        num.gravity = Gravity.CENTER
        dot.addView(num, ui.fl(ui.match, ui.match))
        card.addView(dot, ui.ll(ui.u(52), ui.u(52)))
        card.addView(ui.text(title, 30, C.TEXT, 700), ui.ll(ui.match, ui.wrap, top = 22))
        val body = ui.text(text, 21, C.TEXT2, 400)
        body.setLineSpacing(0f, 1.25f)
        card.addView(body, ui.ll(ui.match, ui.wrap, top = 10))
        marks.add(dot to num)
        return card
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.SETUP) return
        if (!HomeButtonService.isOn(this)) {
            status.text = "Henüz açık değil."
            return
        }
        if (done) return
        done = true
        for ((dot, num) in marks) {
            dot.background = ui.round(Color.parseColor("#34C759"), 26)
            num.text = ""
            val check = ImageView(this)
            check.setImageResource(R.drawable.ic_check)
            check.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            dot.addView(check, ui.fl(ui.u(28), ui.u(28), Gravity.CENTER))
        }
        status.setTextColor(C.TEXT)
        status.text = "✓  Hazır. Ana Sayfa tuşu artık LeXia'yı açıyor."
        status.postDelayed({ if (!isFinishing) finish() }, 1800)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
