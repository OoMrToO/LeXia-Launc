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
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * "Bu ay hangi platform?": which one subscription this month covers most of Listem, so people pay
 * for one platform at a time instead of all of them at once. Free-to-watch films need none.
 */
class PlatformMonthActivity : Activity() {

    companion object {
        fun open(a: Activity) {
            a.startActivity(Intent(a, PlatformMonthActivity::class.java))
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        /** Platform name to the Listem films found there; free ones under "" ; with nothing known left out. */
        fun tally(ctx: Context): Triple<List<Pair<String, List<Pick>>>, List<Pick>, Int> {
            val prefs = Prefs(ctx)
            val today = HashMap<String, Pick>()
            try {
                val o = JSONObject(java.io.File(ctx.filesDir, "pick.json").readText())
                for (n in listOf("picks", "platforms", "top10", "top10tv", "cinema", "soon", "local", "history")) {
                    val a = o.optJSONArray(n) ?: continue
                    for (i in 0 until a.length()) {
                        val p = Pick.from(a.getJSONObject(i))
                        if (!today.containsKey(p.key) || p.provider.isNotEmpty()) today[p.key] = p
                    }
                }
            } catch (e: Exception) {
            }
            val by = LinkedHashMap<String, MutableList<Pick>>()
            val free = ArrayList<Pick>()
            var unknown = 0
            for (saved in prefs.watchlist.filter { it.key !in prefs.watched }) {
                val p = today[saved.key] ?: saved
                val names = (listOfNotNull(p.provider.takeIf { it.isNotEmpty() }) + p.where).distinct()
                when {
                    names.isEmpty() -> unknown++
                    else -> for (n in names) by.getOrPut(n) { ArrayList() }.add(p)
                }
            }
            val ranked = by.entries.sortedWith(compareBy({ -it.value.size }, { it.key })).map { it.key to it.value.toList() }
            return Triple(ranked, free, unknown)
        }
    }

    private lateinit var ui: Ui
    private lateinit var body: LinearLayout
    private var ranked: List<Pair<String, List<Pick>>> = emptyList()
    private var free: List<Pick> = emptyList()
    private var unknown = 0
    private var at = 0
    private val tr = Locale("tr", "TR")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val t = tally(this)
        ranked = t.first
        free = t.second
        unknown = t.third
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.parseColor("#0A0B10"))
        body = ui.vbox()
        body.setPadding(ui.u(130), ui.u(80), ui.u(130), ui.u(60))
        body.clipChildren = false
        body.clipToPadding = false
        frame.addView(body, ui.fl(ui.match, ui.match))
        setContentView(frame)
        Sound.attach(this)
        show()
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(D.MED).start()
    }

    /** Each platform's own colour, as on the where badge. */
    private fun colorOf(name: String): Int {
        val t = TextView(this)
        ui.setWhere(t, name)
        return ((t.background as? GradientDrawable)?.color?.defaultColor) ?: Color.parseColor("#8A6CFF")
    }

    private fun show() {
        body.removeAllViews()
        (body.parent as View).background = null
        body.addView(ui.label("BU AY HANGİ PLATFORM?"))
        val total = ranked.flatMap { it.second }.distinctBy { it.key }.size + free.size + unknown
        if (ranked.isEmpty()) {
            body.addView(ui.text(if (free.isNotEmpty()) "Bu ay aboneliğe gerek yok." else "Henüz söyleyecek bir şey yok.", 68, C.TEXT, 800), ui.ll(ui.wrap, ui.wrap, top = 14))
            val why = if (total == 0) "Listem boş. Beğendiğin filmleri “Listeme Ekle” ile ekle; hangi platformun sana yeteceğini burada söylerim."
            else if (free.isNotEmpty()) "Listendeki ${free.size} film ücretsiz izlenebiliyor: " + free.joinToString(", ") { it.title } + "."
            else "Listendeki filmlerin hangi platformda olduğu henüz bilinmiyor (sinemada ya da henüz bir platformda değil)."
            val l = ui.text(why, 28, C.TEXT2, 400)
            l.maxWidth = ui.u(1400)
            body.addView(l, ui.ll(ui.wrap, ui.wrap, top = 14))
            val back = ui.pill("Ana ekrana dön", 64, 23, primary = true)
            back.setOnClickListener { finish() }
            body.addView(back, ui.ll(ui.wrap, ui.u(64), top = 40))
            back.requestFocus()
            return
        }
        val (name, films) = ranked[at]
        val accent = colorOf(name)
        (body.parent as View).background = ui.gradient(
            GradientDrawable.Orientation.TL_BR,
            Fx.blend(accent, Color.parseColor("#0A0B10"), 0.72f), Color.parseColor("#0A0B10"), Color.parseColor("#0A0B10")
        )
        val head = if (at == 0) "Bu ay ${name} yeter." else "Sonra: $name"
        body.addView(ui.text(head, 68, C.TEXT, 800).also { it.letterSpacing = -0.02f }, ui.ll(ui.wrap, ui.wrap, top = 14))
        body.addView(ui.text("Listendeki $total filmin ${films.size}'${suffix(films.size)} ${TrailerActivity.locative(name)}. Bir ay abone ol, izle, sonra öbürüne geç.", 28, C.TEXT2, 400), ui.ll(ui.wrap, ui.wrap, top = 14))

        val cols = ui.hbox()
        cols.gravity = Gravity.TOP
        cols.clipChildren = false
        // Left: the platform's films and what to do.
        val card = ui.vbox()
        card.clipChildren = false
        card.clipToPadding = false
        card.setPadding(ui.u(44), ui.u(40), ui.u(44), ui.u(40))
        card.background = ui.round(Color.argb(140, 28, 28, 34), D.PANEL, 1, Color.argb(40, 255, 255, 255))
        val badge = ui.whereBadge()
        ui.setWhere(badge, name)
        card.addView(badge, ui.ll(ui.wrap, ui.wrap))
        card.addView(ui.text("Listendeki ${films.size} film burada", 44, C.TEXT, 800), ui.ll(ui.wrap, ui.wrap, top = 20))
        val added = Prefs(this).listAdded
        films.minByOrNull { added[it.key] ?: Long.MAX_VALUE }?.let { first ->
            val weeks = added[first.key]?.let { ((System.currentTimeMillis() - it) / (7 * 86400_000L)).toInt() } ?: 0
            if (weeks >= 1) card.addView(ui.text("En çok bekleyen: ${first.title} ($weeks haftadır listende).", 23, C.TEXT2, 400), ui.ll(ui.wrap, ui.wrap, top = 10))
        }
        val posters = ui.hbox()
        for (p in films.take(4)) posters.addView(poster(p))
        if (films.size > 4) {
            val more = ui.text("+${films.size - 4}", 30, C.TEXT, 800)
            more.gravity = Gravity.CENTER
            more.background = ui.round(Color.argb(60, 255, 255, 255), D.CARD)
            posters.addView(more, ui.ll(ui.u(150), ui.u(225)))
        }
        card.addView(posters, ui.ll(ui.wrap, ui.wrap, top = 28))
        val btns = ui.hbox()
        btns.clipChildren = false
        btns.clipToPadding = false
        val app = Apps.forPlatform(this, name)
        val openApp = ui.pill(if (app != null) TrailerActivity.accusative(name) + " aç" else "$name uygulamasını bul", 64, 23, primary = true)
        openApp.setOnClickListener {
            if (app != null) {
                Prefs(this).markUsed(app.key)
                HomeButtonService.opened(app.pkg)
                try {
                    startActivity(Apps.launchIntent(app))
                } catch (e: Exception) {
                    Glass.toast(this, "Açılamadı")
                }
            } else {
                HomeButtonService.pause()
                Apps.findInStore(this, name)
            }
        }
        btns.addView(openApp)
        val list = ui.pill("Filmleri göster", 64, 23)
        list.setOnClickListener {
            Dialogs.list(this, name, films.map { it.title to it.year }, subtitle = "Listendeki filmler", icon = R.drawable.ic_list) { i ->
                DetailActivity.open(this, films[i])
            }
        }
        btns.addView(list)
        if (ranked.size > 1) {
            val next = ui.pill(if (at < ranked.size - 1) "Sonraki ay" else "Başa dön", 64, 23)
            next.setOnClickListener {
                at = (at + 1) % ranked.size
                show()
            }
            btns.addView(next)
        }
        card.addView(btns, ui.ll(ui.wrap, ui.wrap, top = 36))
        cols.addView(card, ui.ll(0, ui.wrap, weight = 1.15f))

        // Right: Listem by platform, the order to take them in, what is free.
        val side = ui.vbox()
        side.addView(ui.label("LİSTEN PLATFORMLARA GÖRE"))
        val maxN = maxOf(1, ranked.maxOf { it.second.size }, free.size)
        for ((n, fs) in ranked.take(5)) side.addView(bar(n, fs.size, maxN, colorOf(n), n == name), ui.ll(ui.match, ui.wrap, top = 16))
        if (free.isNotEmpty()) side.addView(bar("Ücretsiz", free.size, maxN, Color.parseColor("#3DD68C"), false), ui.ll(ui.match, ui.wrap, top = 16))
        val months = (0 until ranked.size.coerceAtMost(4)).map { i ->
            val c = Calendar.getInstance()
            c.add(Calendar.MONTH, i)
            c.getDisplayName(Calendar.MONTH, Calendar.LONG, tr) + " " + ranked[i].first
        }
        val plan = StringBuilder("Önerilen sıra: " + months.joinToString("  ·  ") + ".")
        if (free.isNotEmpty()) plan.append("\nÜcretsiz izlenebilen ${free.size} film için abonelik gerekmez: " + free.first().free.first() + ".")
        if (unknown > 0) plan.append("\n$unknown filmin nerede olduğu henüz bilinmiyor.")
        val pt = ui.text(plan.toString(), 22, C.TEXT2, 400)
        pt.setLineSpacing(0f, 1.35f)
        pt.background = ui.round(Color.argb(14, 255, 255, 255), 24)
        pt.setPadding(ui.u(28), ui.u(24), ui.u(28), ui.u(24))
        side.addView(pt, ui.ll(ui.match, ui.wrap, top = 34))
        if (ranked.size > 1) {
            val save = ui.text("Hepsine birden yerine sırayla birine: her ay ${ranked.size - 1} abonelik ücreti cebinde kalır.", 22, Color.parseColor("#3DD68C"), 600)
            side.addView(save, ui.ll(ui.match, ui.wrap, top = 20))
        }
        cols.addView(side, ui.ll(0, ui.wrap, left = 70, weight = 1f))
        body.addView(cols, ui.ll(ui.match, ui.wrap, top = 50))
        openApp.requestFocus()
    }

    /** "6'sı", "2'si", "9'u", "10'u": the Turkish ending after a number. */
    private fun suffix(n: Int): String {
        val ones = mapOf(1 to "i", 2 to "si", 3 to "ü", 4 to "ü", 5 to "i", 6 to "sı", 7 to "si", 8 to "i", 9 to "u")
        val tens = mapOf(1 to "u", 2 to "si", 3 to "u", 4 to "ı", 5 to "si", 6 to "ı", 7 to "i", 8 to "i", 9 to "ı")
        return when {
            n % 10 != 0 -> ones.getValue(n % 10)
            n % 100 != 0 -> tens.getValue((n / 10) % 10)
            else -> "ü"
        }
    }

    private fun poster(p: Pick): View {
        val box = FrameLayout(this)
        box.background = ui.round(C.CARD, D.CARD)
        box.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        box.addView(img, ui.fl(ui.match, ui.match))
        Img.load(this, "w342", p.poster, ui.u(150), img)
        box.layoutParams = ui.ll(ui.u(150), ui.u(225), right = 18)
        return box
    }

    private fun bar(name: String, n: Int, max: Int, color: Int, on: Boolean): View {
        val row = ui.hbox()
        val t = ui.text(name, 24, if (on) C.TEXT else C.TEXT2, if (on) 700 else 400)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        row.addView(t, ui.ll(ui.u(220), ui.wrap))
        val track = FrameLayout(this)
        track.background = ui.round(Color.argb(26, 255, 255, 255), 8)
        val fill = View(this)
        fill.background = ui.round(color, 8)
        fill.pivotX = 0f
        fill.scaleX = n.toFloat() / max
        track.addView(fill, ui.fl(ui.match, ui.match))
        row.addView(track, ui.ll(0, ui.u(16), weight = 1f, left = 20))
        row.addView(ui.text("$n film", 24, C.TEXT2, 600).also { it.gravity = Gravity.END }, ui.ll(ui.u(130), ui.wrap))
        return row
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
