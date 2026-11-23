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
import android.widget.TextView
import java.util.Locale

/**
 * Keşfet's top half, Apple TV style: the film's own picture filling the page behind everything
 * (it follows the remote), and over it a showcase of a few chosen films (logo, facts, story,
 * Oynat · Fragman · Ayrıntılar) turning every few seconds, its trailer playing without sound
 * after a moment. When the remote goes down into the rows, the showcase shrinks and shows the
 * poster under the remote instead.
 */
internal class ExploreStage(private val activity: Activity, private val ui: Ui, frame: FrameLayout) {

    private val tr = Locale("tr", "TR")
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // ---------- The picture behind ----------

    private val back = FrameLayout(activity)
    private val pictures = arrayOf(ImageView(activity), ImageView(activity))
    private var front = 0
    private val trailerStage = FrameLayout(activity)
    private val trailer = HeroTrailer(activity, trailerStage)
    private var pictureFor = ""

    /** The showcase itself: sits at the top of the page, above the rows. */
    val panel = FrameLayout(activity)
    private val logo = ImageView(activity)
    private val title = ui.text("", 64, C.TEXT, 800)
    private val titleBig = title.textSize
    private val kicker = ui.label("", C.TEXT2)
    private val meta = ui.text("", 22, C.TEXT2, 500)
    private val story = ui.text("", 22, Color.argb(215, 255, 255, 255), 400)
    /** Oynat · Fragman · Ayrıntılar: only while the showcase has the remote. */
    val buttons = LinearLayout(activity)
    private val dots = LinearLayout(activity)

    private var featured: List<Pick> = emptyList()
    private var at = 0
    /** The film the showcase is about now (a featured one, or the poster under the remote). */
    var current: Pick? = null
        private set
    /** The showcase has the remote (its buttons, or the bar above it). */
    var big = true
        private set

    var onPlay: ((Pick) -> Unit)? = null
    var onTrailer: ((Pick) -> Unit)? = null
    var onDetails: ((Pick) -> Unit)? = null

    private val turn = Runnable { if (big && featured.size > 1 && !trailer.active) showFeatured((at + 1) % featured.size) }
    private val trailerSoon = Runnable { startTrailer() }
    private val followFocus = Runnable { pending?.let { show(it, compact = true) } }
    private var pending: Pick? = null

    init {
        // Behind: two pictures that cross-fade, the silent trailer over them, then shade for the text.
        pictures.forEach {
            it.scaleType = ImageView.ScaleType.CENTER_CROP
            it.alpha = 0f
            back.addView(it, ui.fl(ui.match, ui.match))
        }
        back.addView(trailerStage, ui.fl(ui.match, ui.match))
        val left = View(activity)
        left.background = ui.gradient(GradientDrawable.Orientation.LEFT_RIGHT,
            Color.argb(235, 0, 0, 0), Color.argb(150, 0, 0, 0), Color.argb(20, 0, 0, 0))
        back.addView(left, ui.fl(ui.match, ui.match))
        val low = View(activity)
        low.background = ui.gradient(GradientDrawable.Orientation.BOTTOM_TOP,
            Color.BLACK, Color.argb(235, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.TRANSPARENT)
        back.addView(low, ui.fl(ui.match, ui.u(760), Gravity.BOTTOM))
        val top = View(activity)
        top.background = ui.gradient(GradientDrawable.Orientation.TOP_BOTTOM, Color.argb(170, 0, 0, 0), Color.TRANSPARENT)
        back.addView(top, ui.fl(ui.match, ui.u(220), Gravity.TOP))
        frame.addView(back, 0, ui.fl(ui.match, ui.match))
        trailer.onShowing = { on -> pictures.forEach { it.animate().alpha(if (on) 0f else if (it == pictures[front]) 1f else 0f).setDuration(900).start() } }
        trailer.onFinished = { if (big) handler.postDelayed(turn, 1_500) }

        // The showcase: bottom left of its space, as on Apple TV.
        val text = ui.vbox()
        text.addView(kicker)
        logo.scaleType = ImageView.ScaleType.FIT_START
        logo.adjustViewBounds = true
        logo.visibility = View.GONE
        text.addView(logo, ui.ll(ui.u(620), ui.u(170), top = 12))
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        title.letterSpacing = -0.02f
        text.addView(title, ui.ll(ui.u(1150), ui.wrap, top = 8))
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.END
        text.addView(meta, ui.ll(ui.u(1150), ui.wrap, top = 14))
        story.maxLines = 2
        story.ellipsize = TextUtils.TruncateAt.END
        story.setLineSpacing(0f, 1.2f)
        text.addView(story, ui.ll(ui.u(1000), ui.wrap, top = 12))
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.clipChildren = false
        buttons.clipToPadding = false
        text.addView(buttons, ui.ll(ui.wrap, ui.wrap, top = 26))
        text.clipChildren = false
        text.clipToPadding = false
        panel.clipChildren = false
        panel.clipToPadding = false
        panel.addView(text, ui.fl(ui.match, ui.wrap, Gravity.BOTTOM or Gravity.START))
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER_VERTICAL
        val dlp = ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.END)
        dlp.bottomMargin = ui.u(22)
        panel.addView(dots, dlp)
        buildButtons()
    }

    private fun buildButtons() {
        buttons.removeAllViews()
        val play = ui.pill("▶   Oynat", 64, 23, primary = true)
        play.setOnClickListener { current?.let { onPlay?.invoke(it) } }
        val clip = ui.iconPill("Fragman", R.drawable.ic_film)
        clip.setOnClickListener { current?.let { onTrailer?.invoke(it) } }
        val more = ui.iconPill("Ayrıntılar", R.drawable.ic_info)
        more.setOnClickListener { current?.let { onDetails?.invoke(it) } }
        for (b in listOf(play, clip, more)) {
            val style = b.onFocusChangeListener
            b.setOnFocusChangeListener { v, f ->
                style?.onFocusChange(v, f)
                if (f) enterShowcase()
            }
            buttons.addView(b)
        }
    }

    /** Films chosen for the showcase (a picture and a story each); the first is shown at once. */
    fun feature(list: List<Pick>, label: String) {
        featured = list.filter { it.backdrop.isNotEmpty() }.take(6)
        kickerText = label
        dots.removeAllViews()
        if (featured.size > 1) featured.indices.forEach { _ ->
            val d = View(activity)
            dots.addView(d, ui.ll(ui.u(10), ui.u(10), right = 10))
        }
        if (featured.isEmpty()) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE
        showFeatured(0)
    }

    private var kickerText = ""

    private fun showFeatured(i: Int) {
        at = i
        val p = featured.getOrNull(i) ?: return
        show(p, compact = false)
        for (k in 0 until dots.childCount) {
            dots.getChildAt(k).background = ui.round(if (k == i) Color.WHITE else Color.argb(90, 255, 255, 255), 5)
        }
        handler.removeCallbacks(turn)
        handler.postDelayed(turn, TURN_MS)
        handler.removeCallbacks(trailerSoon)
        if (big) handler.postDelayed(trailerSoon, TRAILER_AFTER_MS)
    }

    /** The remote is on a poster below: after a breath, the picture and the facts are its. */
    fun focusOn(p: Pick) {
        if (big) leaveShowcase()
        pending = p
        handler.removeCallbacks(followFocus)
        handler.postDelayed(followFocus, 260)
    }

    /** The remote came up to the showcase: it grows and the featured films turn again. */
    fun enterShowcase() {
        if (big) return
        big = true
        handler.removeCallbacks(followFocus)
        setSize()
        if (featured.isNotEmpty()) showFeatured(at)
    }

    private fun leaveShowcase() {
        big = false
        handler.removeCallbacks(turn)
        handler.removeCallbacks(trailerSoon)
        trailer.stop()
        setSize()
    }

    private fun setSize() {
        val h = ui.u(if (big) BIG else SMALL)
        val lp = panel.layoutParams ?: return
        if (lp.height == h) return
        val from = panel.height.takeIf { it > 0 } ?: lp.height
        android.animation.ValueAnimator.ofInt(from, h).apply {
            duration = if (Fx.lite) 0 else 380
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                panel.layoutParams = panel.layoutParams.also { p -> p.height = it.animatedValue as Int }
            }
            start()
        }
        buttons.visibility = if (big) View.VISIBLE else View.GONE
        dots.visibility = if (big && featured.size > 1) View.VISIBLE else View.GONE
        story.maxLines = if (big) 2 else 1
        kicker.text = if (big) kickerText else ""
        kicker.visibility = if (big && kickerText.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun show(p: Pick, compact: Boolean) {
        val same = current?.key == p.key
        current = p
        if (!same) {
            val fade = if (Fx.lite) 0L else 260L
            panel.animate().cancel()
            panel.alpha = 0.35f
            panel.animate().alpha(1f).setDuration(fade).start()
        }
        kicker.text = if (compact) "" else kickerText
        kicker.visibility = if (!compact && kickerText.isNotEmpty()) View.VISIBLE else View.GONE
        title.text = p.title
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, if (compact) titleBig * 44f / 64f else titleBig)
        title.visibility = View.VISIBLE
        logo.visibility = View.GONE
        meta.text = facts(p)
        story.text = p.overview
        story.visibility = if (p.overview.isBlank()) View.GONE else View.VISIBLE
        loadLogo(p, compact)
        picture(p)
    }

    /** "2024 · 2 sa 12 dk · Bilim Kurgu, Macera · IMDb 8.1". */
    private fun facts(p: Pick): String {
        val parts = ArrayList<String>()
        p.details(2).takeIf { it.isNotBlank() }?.let { parts.add(it) }
        if (p.imdb > 0) parts.add(String.format(tr, "IMDb %.1f", p.imdb))
        if (p.cert.isNotEmpty()) parts.add(p.cert)
        return parts.joinToString("   ·   ")
    }

    private fun loadLogo(p: Pick, compact: Boolean) {
        val path = p.logo.takeIf { it.isNotEmpty() } ?: return
        val key = p.key
        Thread {
            val b = Net.bitmap("https://image.tmdb.org/t/p/w500$path", ui.u(620), false)?.takeIf { !Img.isDark(it) }
            activity.runOnUiThread {
                if (b == null || activity.isFinishing || current?.key != key) return@runOnUiThread
                logo.setImageBitmap(b)
                logo.layoutParams = logo.layoutParams.also { it.height = ui.u(if (compact) 110 else 170) }
                logo.visibility = View.VISIBLE
                title.visibility = View.GONE
            }
        }.start()
    }

    /** The film's picture fills the page, cross-fading from the last one. */
    private fun picture(p: Pick) {
        if (p.backdrop.isEmpty() || pictureFor == p.key) return
        pictureFor = p.key
        val key = p.key
        Thread {
            val b: Bitmap? = Net.bitmap("https://image.tmdb.org/t/p/w1280" + p.backdrop, ui.u(1920), true)
            activity.runOnUiThread {
                if (b == null || activity.isFinishing || pictureFor != key) return@runOnUiThread
                val next = 1 - front
                val inView = pictures[next]
                val outView = pictures[front]
                inView.setImageBitmap(b)
                inView.animate().cancel()
                inView.alpha = 0f
                val ms = if (Fx.lite) 0L else 700L
                inView.animate().alpha(if (trailer.active) 0f else 1f).setDuration(ms).start()
                outView.animate().alpha(0f).setDuration(ms).start()
                front = next
            }
        }.start()
    }

    private fun startTrailer() {
        val p = current ?: return
        val prefs = Prefs(activity)
        if (!big || Fx.lite || !prefs.heroTrailer || p.homeTrailer.isBlank()) return
        if (!activity.hasWindowFocus()) return
        handler.removeCallbacks(turn)
        trailer.sound = prefs.heroTrailerSound
        trailer.start(p.homeTrailer)
    }

    /** The trailer playing behind the showcase now, and where: "Fragman" carries on from there. */
    fun trailerAt(): Pair<String, Int>? = if (trailer.active) trailer.where() else null

    /** The page is left (another page on top): no trailer, no turning. */
    fun pause() {
        handler.removeCallbacks(turn)
        handler.removeCallbacks(trailerSoon)
        trailer.stop()
    }

    fun resume() {
        if (big && featured.isNotEmpty()) showFeatured(at)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        trailer.release()
    }

    companion object {
        /** The showcase's height with the remote on it, and while the rows have it (design units). */
        const val BIG = 560
        const val SMALL = 330
        private const val TURN_MS = 9_000L
        private const val TRAILER_AFTER_MS = 5_000L
    }
}
