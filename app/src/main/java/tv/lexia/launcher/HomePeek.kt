package tv.lexia.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.Locale

/**
 * The home screen's top half while the remote is down in the rows, as on Apple TV: the poster
 * under the remote fills the screen behind (its picture cross-fading in) and its logo, facts and
 * story stand at the top; on a platform's tile, what is new there. Rest on a poster a few seconds
 * and its picture turns into its trailer, without sound (Ayarlar › Görünüm's hero trailer setting
 * covers it too). Back on the film at the top, it all fades and the home screen's own film returns.
 */
internal class HomePeek(private val activity: Activity, private val ui: Ui, stage: FrameLayout, content: FrameLayout) {

    private val tr = Locale("tr", "TR")
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val backs = arrayOf(ImageView(activity), ImageView(activity))
    private var front = 0
    private var pictureFor = ""

    private val panel = LinearLayout(activity)
    private val kicker = ui.label("", C.TEXT2)
    private val logo = ImageView(activity)
    private val title = ui.text("", 60, C.TEXT, 800)
    private val meta = ui.text("", 22, C.TEXT2, 500)
    private val story = ui.text("", 22, Color.argb(215, 255, 255, 255), 400)
    /** A platform's newest, as small scene cards. */
    private val strip = LinearLayout(activity)

    var showing = false
        private set
    private var key = ""
    private var pending: Runnable? = null

    /** The silent trailer over the picture; it starts when the remote rests on one poster. */
    private val trailerStage = FrameLayout(activity)
    private val trailer = HeroTrailer(activity, trailerStage)
    private var trailerFilm: Pick? = null
    private val trailerSoon = Runnable { startTrailer() }

    init {
        backs.forEach {
            it.scaleType = ImageView.ScaleType.CENTER_CROP
            it.alpha = 0f
            stage.addView(it, ui.fl(ui.match, ui.match))
        }
        stage.addView(trailerStage, ui.fl(ui.match, ui.match))
        trailer.sound = false
        trailer.onShowing = { on ->
            backs.forEachIndexed { i, b ->
                b.animate().cancel()
                b.animate().alpha(if (on || i != front) 0f else 1f).setDuration(900).start()
            }
        }
        panel.orientation = LinearLayout.VERTICAL
        panel.alpha = 0f
        panel.visibility = View.GONE
        panel.addView(kicker)
        logo.scaleType = ImageView.ScaleType.FIT_START
        logo.adjustViewBounds = true
        logo.visibility = View.GONE
        panel.addView(logo, ui.ll(ui.u(600), ui.u(150), top = 12))
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        title.letterSpacing = -0.02f
        panel.addView(title, ui.ll(ui.u(1150), ui.wrap, top = 8))
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.END
        panel.addView(meta, ui.ll(ui.u(1150), ui.wrap, top = 14))
        story.maxLines = 3
        story.ellipsize = TextUtils.TruncateAt.END
        story.setLineSpacing(0f, 1.2f)
        panel.addView(story, ui.ll(ui.u(1000), ui.wrap, top = 12))
        strip.orientation = LinearLayout.HORIZONTAL
        strip.visibility = View.GONE
        panel.addView(strip, ui.ll(ui.wrap, ui.wrap, top = 22))
        val lp = ui.fl(ui.match, ui.wrap, Gravity.TOP or Gravity.START)
        lp.setMargins(ui.u(110), ui.u(84), ui.u(110), 0)
        content.addView(panel, lp)
    }

    /** The poster under the remote, from the row called [row]. */
    fun film(p: Pick, row: String) = later(p.key) {
        kicker.text = row.uppercase(tr)
        kicker.visibility = if (row.isBlank()) View.GONE else View.VISIBLE
        title.text = p.title
        title.visibility = View.VISIBLE
        logo.visibility = View.GONE
        meta.text = facts(p)
        meta.visibility = if (meta.text.isNullOrBlank()) View.GONE else View.VISIBLE
        story.text = p.overview
        story.visibility = if (p.overview.isBlank()) View.GONE else View.VISIBLE
        strip.visibility = View.GONE
        loadLogo(p)
        picture(p.backdrop, p.key)
        queueTrailer(p)
    }

    /** A platform's tile: its name, how many new, and its newest few as scene cards. */
    fun platform(name: String, films: List<Pick>) = later("platform:$name") {
        kicker.text = "${TrailerActivity.locative(name)} yeni".uppercase(tr)
        kicker.visibility = View.VISIBLE
        title.text = name
        title.visibility = View.VISIBLE
        logo.visibility = View.GONE
        val films2 = films.count { it.isSeries }
        val movies = films.size - films2
        meta.text = listOfNotNull(
            "$movies film".takeIf { movies > 0 },
            "$films2 dizi".takeIf { films2 > 0 }
        ).joinToString("  ·  ")
        meta.visibility = View.VISIBLE
        story.visibility = View.GONE
        strip.removeAllViews()
        films.filter { it.backdrop.isNotEmpty() }.take(4).forEach { strip.addView(mini(it)) }
        strip.visibility = if (strip.childCount > 0) View.VISIBLE else View.GONE
        films.firstOrNull { it.backdrop.isNotEmpty() }?.let { picture(it.backdrop, it.key) }
        stopTrailer()
    }

    /** A new poster under the remote: whatever was playing goes; this one's trailer comes after a rest. */
    private fun queueTrailer(p: Pick) {
        stopTrailer()
        if (p.trailer.isBlank() || Fx.lite || !Prefs(activity).heroTrailer) return
        trailerFilm = p
        handler.postDelayed(trailerSoon, TRAILER_AFTER_MS)
    }

    private fun startTrailer() {
        val p = trailerFilm ?: return
        if (!showing || key != p.key || activity.isFinishing || !activity.hasWindowFocus()) return
        trailer.sound = false
        trailer.start(p.trailer)
    }

    private fun stopTrailer() {
        handler.removeCallbacks(trailerSoon)
        trailerFilm = null
        if (trailer.active || trailerStage.childCount > 0) trailer.stop()
    }

    /** The home screen is left or the screensaver starts: the trailer stops, the picture stays. */
    fun pause() = stopTrailer()

    /** Waits a breath, so moving quickly along a row does not flash every film. */
    private fun later(id: String, fill: () -> Unit) {
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (activity.isFinishing) return@Runnable
            if (id != key || !showing) {
                key = id
                fill()
                panel.animate().cancel()
                panel.visibility = View.VISIBLE
                panel.alpha = if (showing) 0.3f else 0f
                panel.animate().alpha(1f).setDuration(if (Fx.lite) 0 else 320).start()
            }
            showing = true
        }
        pending = r
        handler.postDelayed(r, if (showing) 220 else 120)
    }

    /** Back at the top: the panel and the picture go, the home screen's film comes back. */
    fun hide() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        stopTrailer()
        if (!showing) return
        showing = false
        key = ""
        pictureFor = ""
        panel.animate().cancel()
        panel.animate().alpha(0f).setDuration(D.MED).withEndAction { if (!showing) panel.visibility = View.GONE }.start()
        backs.forEach { it.animate().cancel(); it.animate().alpha(0f).setDuration(600).start() }
    }

    private fun facts(p: Pick): String {
        val parts = ArrayList<String>()
        p.details(2).takeIf { it.isNotBlank() }?.let { parts.add(it) }
        if (p.imdb > 0) parts.add(String.format(tr, "IMDb %.1f", p.imdb))
        if (p.cert.isNotEmpty()) parts.add(p.cert)
        return parts.joinToString("   ·   ")
    }

    private fun loadLogo(p: Pick) {
        val path = p.logo.takeIf { it.isNotEmpty() } ?: return
        val want = p.key
        Thread {
            val b = Net.bitmap("https://image.tmdb.org/t/p/w500$path", ui.u(600), false)?.takeIf { !Img.isDark(it) }
            activity.runOnUiThread {
                if (b == null || activity.isFinishing || key != want) return@runOnUiThread
                logo.setImageBitmap(b)
                logo.visibility = View.VISIBLE
                title.visibility = View.GONE
            }
        }.start()
    }

    private fun picture(path: String, id: String) {
        if (path.isEmpty() || pictureFor == id) return
        pictureFor = id
        Thread {
            val b: Bitmap? = Net.bitmap("https://image.tmdb.org/t/p/w1280$path", ui.u(1920), true)
            activity.runOnUiThread {
                if (b == null || activity.isFinishing || pictureFor != id || !showing) return@runOnUiThread
                val next = 1 - front
                backs[next].setImageBitmap(b)
                backs[next].animate().cancel()
                backs[next].alpha = 0f
                val ms = if (Fx.lite) 0L else 700L
                backs[next].animate().alpha(1f).setDuration(ms).start()
                backs[front].animate().alpha(0f).setDuration(ms).start()
                front = next
            }
        }.start()
    }

    /** One of a platform's newest, small: its picture and its name. */
    private fun mini(p: Pick): View {
        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        val box = FrameLayout(activity)
        box.background = ui.round(C.CARD, 12)
        box.clipToOutline = true
        val img = ImageView(activity)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        box.addView(img, ui.fl(ui.match, ui.match))
        val shade = View(activity)
        shade.background = ui.gradient(GradientDrawable.Orientation.BOTTOM_TOP, Color.argb(190, 0, 0, 0), Color.TRANSPARENT)
        box.addView(shade, ui.fl(ui.match, ui.u(70), Gravity.BOTTOM))
        val name = ui.text(p.title, 17, C.TEXT, 700)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        val nlp = ui.fl(ui.match, ui.wrap, Gravity.BOTTOM)
        nlp.setMargins(ui.u(12), 0, ui.u(12), ui.u(10))
        box.addView(name, nlp)
        Thread {
            val b = Net.bitmap("https://image.tmdb.org/t/p/w300" + p.backdrop, ui.u(260), true)
            activity.runOnUiThread { if (b != null && !activity.isFinishing) Fx.fadeIn(img, b) }
        }.start()
        col.addView(box, ui.ll(ui.u(260), ui.u(146)))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 16)
        return col
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        trailer.release()
    }

    companion object {
        /** How long the remote rests on a poster before its trailer starts. */
        private const val TRAILER_AFTER_MS = 3_500L

        /** A platform's colours for its tile: deep, then lighter (as its badge, Ui.setWhere). */
        fun colors(name: String): Pair<Int, Int> {
            val n = name.lowercase(Locale.ROOT)
            fun c(a: String, b: String) = Color.parseColor(a) to Color.parseColor(b)
            return when {
                "netflix" in n -> c("#3D0306", "#E50914")
                "disney" in n -> c("#06123F", "#113CCF")
                "prime" in n || "amazon" in n -> c("#00283A", "#00A8E1")
                "apple" in n || n == "tv+" -> c("#1A1A1C", "#5E5E66")
                "max" == n || "hbo" in n -> c("#020B3D", "#002BE7")
                "mubi" in n -> c("#0E0E0E", "#3A3A3A")
                "crunchyroll" in n -> c("#4A1D06", "#F47521")
                "exxen" in n -> c("#3D3000", "#E0B400")
                "tod" == n -> c("#1E0B40", "#6C2BD9")
                "blutv" in n -> c("#002A4A", "#0091FF")
                "gain" in n -> c("#2B0A26", "#C2187A")
                "tabii" in n -> c("#06302A", "#14A38B")
                else -> c("#1F1A40", "#8A6CFF")
            }
        }
    }
}
