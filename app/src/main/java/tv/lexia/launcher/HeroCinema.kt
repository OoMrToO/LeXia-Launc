package tv.lexia.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The home screen's trailer takes the stage: a few seconds after it starts, with the remote
 * resting, the apps, buttons and words fade away, black cinema bands slide in at the top and
 * bottom, the shade lifts and the picture slowly moves closer. Only the film's logo and a quiet
 * "OK: Detaylar" stay. The first key brings everything back; the trailer goes on playing.
 */
internal class HeroCinema(
    private val activity: Activity,
    private val ui: Ui,
    frame: FrameLayout,
    /** Everything on the screen that is not the picture: rows, buttons, top bar. */
    private val content: View,
    /** The picture and the trailer, which move closer. */
    private val stage: View,
    /** Vignette, left, bottom, top shades. */
    private val scrims: List<View>,
    /** Layers over the picture that are the home screen's, not the film's: they fade away and come back. */
    private val veils: List<View> = emptyList(),
    private val film: () -> Pick?,
    private val logo: () -> Drawable?,
    /**
     * The trailer playing may have words written into its picture (a subtitled one): then no
     * bands and no slow zoom, which would cut the lines at the bottom.
     */
    private val wordsInPicture: () -> Boolean = { false }
) {
    private val handler = Handler(Looper.getMainLooper())
    private val bandH = ui.u(BAND)
    private val top = View(activity)
    private val bottom = View(activity)
    private val caption = LinearLayout(activity)
    /** A soft dark rise from the bottom, so the words read on any picture. */
    private val shade = View(activity)
    private val logoView = ImageView(activity)
    private val title = ui.text("", 46, C.TEXT, 800)

    /** The trailer has the whole screen. */
    var on = false
        private set

    private val enterSoon = Runnable { enter() }
    private val veilAlpha = HashMap<View, Float>()

    init {
        for (b in listOf(top, bottom)) {
            b.setBackgroundColor(Color.BLACK)
            b.visibility = View.GONE
        }
        shade.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Color.argb(190, 0, 0, 0), Color.argb(70, 0, 0, 0), Color.argb(0, 0, 0, 0))
        )
        shade.alpha = 0f
        shade.visibility = View.GONE
        frame.addView(shade, ui.fl(ui.match, ui.u(460), Gravity.BOTTOM))
        frame.addView(top, ui.fl(ui.match, bandH, Gravity.TOP))
        frame.addView(bottom, ui.fl(ui.match, bandH, Gravity.BOTTOM))

        caption.orientation = LinearLayout.VERTICAL
        caption.visibility = View.GONE
        logoView.adjustViewBounds = true
        logoView.maxHeight = ui.u(120)
        logoView.maxWidth = ui.u(480)
        logoView.scaleType = ImageView.ScaleType.FIT_START
        caption.addView(logoView, ui.ll(ui.wrap, ui.wrap))
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        caption.addView(title, ui.ll(ui.u(900), ui.wrap))
        val hint = ui.text("OK: Detaylar", 19, C.TEXT2, 600)
        hint.letterSpacing = 0.06f
        caption.addView(hint, ui.ll(ui.wrap, ui.wrap, top = 14))
        title.setShadowLayer(ui.uf(14), 0f, ui.uf(2), Color.argb(150, 0, 0, 0))
        hint.setShadowLayer(ui.uf(10), 0f, ui.uf(1), Color.argb(150, 0, 0, 0))
        val lp = ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.START)
        lp.setMargins(ui.u(110), 0, 0, bandH + ui.u(44))
        frame.addView(caption, lp)
    }

    /** The trailer's picture came (true) or went (false). */
    fun trailerShowing(showing: Boolean) {
        handler.removeCallbacks(enterSoon)
        if (showing) handler.postDelayed(enterSoon, AFTER_MS) else exit(trailerGoesOn = false)
    }

    /** Any key, a menu, another page: the remote is back, so is the home screen. */
    fun wake() {
        handler.removeCallbacks(enterSoon)
        if (on) exit(trailerGoesOn = true)
    }

    /** The remote rested again with the trailer still playing: the stage comes back after a while. */
    fun rest(trailerPlaying: Boolean) {
        handler.removeCallbacks(enterSoon)
        if (trailerPlaying && !on) handler.postDelayed(enterSoon, AFTER_MS)
    }

    private fun enter() {
        if (on || activity.isFinishing || !activity.hasWindowFocus() || Fx.lite) return
        on = true
        val p = film()
        val l = logo()
        if (l != null) {
            logoView.setImageDrawable(l)
            logoView.visibility = View.VISIBLE
            title.visibility = View.GONE
        } else {
            logoView.visibility = View.GONE
            title.text = p?.title.orEmpty()
            title.visibility = if (title.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val ease = DecelerateInterpolator(2f)
        content.animate().cancel()
        content.animate().alpha(0f).setDuration(1200).setInterpolator(ease).start()
        // The film in its own colours: no weather, no tint over it.
        veilAlpha.clear()
        for (v in veils) {
            veilAlpha[v] = v.alpha
            v.animate().cancel()
            // The rain stops drawing too: the TV has the 1080p trailer to show.
            v.animate().alpha(0f).setDuration(900).withEndAction { if (on) (v as? WeatherLayer)?.stop() }.start()
        }
        if (scrims.size >= 4) {
            val (vignette, left, low, high) = scrims
            vignette.animate().alpha(0f).setDuration(1400).start()
            left.animate().alpha(0.15f).setDuration(1400).start()
            low.animate().alpha(0.35f).setDuration(1400).start()
            high.animate().alpha(0f).setDuration(1400).start()
        }
        val clear = wordsInPicture()
        if (!clear) {
            for ((band, from) in listOf(top to -bandH.toFloat(), bottom to bandH.toFloat())) {
                band.animate().cancel()
                band.translationY = from
                band.visibility = View.VISIBLE
                band.animate().translationY(0f).setStartDelay(300).setDuration(1100).setInterpolator(ease).start()
            }
            // The picture moves slowly closer, as a camera would.
            stage.animate().cancel()
            stage.animate().scaleX(ZOOM).scaleY(ZOOM).setDuration(20_000).setInterpolator(DecelerateInterpolator()).start()
        }
        // The name sits above the band, or just above the picture's own subtitle line.
        (caption.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.bottomMargin = if (clear) ui.u(150) else bandH + ui.u(44)
            caption.layoutParams = it
        }
        shade.animate().cancel()
        shade.visibility = View.VISIBLE
        shade.animate().alpha(1f).setStartDelay(600).setDuration(1200).start()
        caption.animate().cancel()
        caption.alpha = 0f
        caption.translationY = ui.uf(20)
        caption.visibility = View.VISIBLE
        caption.animate().alpha(1f).translationY(0f).setStartDelay(1100).setDuration(800).setInterpolator(ease).start()
    }

    /**
     * Back to the home screen. With the trailer still playing, the shades go back to the
     * trailer's lighter ones; when it ended, the home screen's own light takes over afterwards.
     */
    private fun exit(trailerGoesOn: Boolean) {
        if (!on) return
        on = false
        val ease = DecelerateInterpolator(2f)
        content.animate().cancel()
        content.animate().alpha(1f).setDuration(450).setInterpolator(ease).start()
        for ((v, a) in veilAlpha) {
            v.animate().cancel()
            (v as? WeatherLayer)?.start()
            v.animate().alpha(a).setDuration(450).start()
        }
        veilAlpha.clear()
        if (trailerGoesOn && scrims.size >= 4) {
            // The same light as while the trailer plays (MainActivity.trailerLight).
            val (vignette, left, low, high) = scrims
            vignette.animate().alpha(0f).setDuration(450).start()
            left.animate().alpha(0.7f).setDuration(450).start()
            low.animate().alpha(0.8f).setDuration(450).start()
            high.animate().alpha(0.3f).setDuration(450).start()
        }
        top.animate().cancel()
        top.animate().translationY(-bandH.toFloat()).setStartDelay(0).setDuration(450).withEndAction { if (!on) top.visibility = View.GONE }.start()
        bottom.animate().cancel()
        bottom.animate().translationY(bandH.toFloat()).setStartDelay(0).setDuration(450).withEndAction { if (!on) bottom.visibility = View.GONE }.start()
        stage.animate().cancel()
        stage.animate().scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(ease).start()
        shade.animate().cancel()
        shade.animate().alpha(0f).setStartDelay(0).setDuration(300).withEndAction { if (!on) shade.visibility = View.GONE }.start()
        caption.animate().cancel()
        caption.animate().alpha(0f).setStartDelay(0).setDuration(300).withEndAction { if (!on) caption.visibility = View.GONE }.start()
    }

    fun release() = handler.removeCallbacksAndMessages(null)

    private companion object {
        /** How long the trailer plays with the home screen on it before it takes the stage. */
        const val AFTER_MS = 4_000L
        /** Cinema bands (2.39:1 on a 16:9 screen is about 138 of 1080). */
        const val BAND = 120
        const val ZOOM = 1.05f
    }
}
