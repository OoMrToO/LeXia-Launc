package tv.lexia.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextSwitcher
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

/** Apple TV style touches shared by the screens. */
object Fx {
    private val accents = java.util.WeakHashMap<Bitmap, Int>()

    /** [a] moved toward [b] by [t] (0..1), channel by channel. */
    fun blend(a: Int, b: Int, t: Float): Int {
        fun m(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.argb(m(Color.alpha(a), Color.alpha(b)), m(Color.red(a), Color.red(b)), m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b)))
    }

    /**
     * The film's own light: the most colourful hue of a picture, bright and moderately saturated
     * (Dune: sand orange, Avatar: blue). 0 for pictures with no colour to speak of.
     */
    fun accent(b: Bitmap): Int {
        accents[b]?.let { return it }
        val c = try {
            val s = Bitmap.createScaledBitmap(b, 32, 18, false)
            val hsv = FloatArray(3)
            val bins = DoubleArray(24)
            val sats = DoubleArray(24)
            for (i in 0 until 32) for (j in 0 until 18) {
                Color.colorToHSV(s.getPixel(i, j), hsv)
                val w = (hsv[1] * hsv[2] * hsv[2]).toDouble()
                if (hsv[1] < 0.2f || hsv[2] < 0.2f) continue
                val k = (hsv[0] / 15f).toInt().coerceIn(0, 23)
                bins[k] += w
                sats[k] += hsv[1] * w
            }
            if (s !== b) s.recycle()
            val best = bins.indices.maxByOrNull { bins[it] + 0.5 * bins[(it + 1) % 24] + 0.5 * bins[(it + 23) % 24] } ?: -1
            if (best < 0 || bins[best] < 1.5) 0
            else Color.HSVToColor(floatArrayOf(best * 15f + 7.5f, (sats[best] / bins[best]).toFloat().coerceIn(0.4f, 0.7f), 1f))
        } catch (e: Exception) {
            0
        }
        accents[b] = c
        return c
    }

    /** Direction of the last D-pad move: -1 left, 1 right, -2 up, 2 down, 0 none. */
    @Volatile var lastDir = 0

    /** Blurred copy of the home screen, used behind panels and the settings screen. */
    @Volatile var backdrop: Bitmap? = null

    /** Light mode: skip the heavier touches (tilt, light sweep, blur) on slower TVs. */
    @Volatile var lite = false

    /** Last weather shown on the home screen, for previews elsewhere. */
    @Volatile var weatherTemp = ""
    @Volatile var weatherIcon = 0

    /**
     * Adds a light sweep and a thin bright edge to a card. Returns the focus handler to call
     * from the card's focus listener; it also tilts the card toward the move, then settles it.
     */
    fun decorate(ui: Ui, card: FrameLayout, radius: Int): (Boolean) -> Unit {
        val shine = View(card.context)
        shine.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255))
        )
        shine.alpha = 0f
        card.addView(shine, FrameLayout.LayoutParams(ui.u(120), FrameLayout.LayoutParams.MATCH_PARENT))
        val edge = View(card.context)
        val e = GradientDrawable()
        e.setColor(Color.TRANSPARENT)
        e.cornerRadius = ui.uf(radius)
        e.setStroke(maxOf(1, ui.u(2)), Color.argb(170, 255, 255, 255))
        edge.background = e
        edge.alpha = 0f
        card.addView(edge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        card.cameraDistance = 12000 * card.resources.displayMetrics.density

        return { focused ->
            edge.animate().alpha(if (focused) 1f else 0f).setDuration(D.FAST).start()
            // The card's own colour glows around it while focused (its shadow takes the film's light).
            if (focused && !lite && android.os.Build.VERSION.SDK_INT >= 28) {
                val bmp = (0 until card.childCount).asSequence().mapNotNull { (card.getChildAt(it) as? android.widget.ImageView)?.drawable }
                    .mapNotNull { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }.firstOrNull()
                val c = bmp?.let { accent(it) } ?: 0
                if (c != 0) {
                    card.outlineSpotShadowColor = c
                    card.outlineAmbientShadowColor = c
                }
            }
            if (focused && !lite) {
                // Tilt in from the side we came from, then settle with a small bounce.
                val ry = when (lastDir) { -1 -> 7f; 1 -> -7f; else -> 0f }
                val rx = when (lastDir) { -2 -> -6f; 2 -> 6f; else -> 0f }
                card.rotationY = ry
                card.rotationX = rx
                card.animate().rotationY(0f).rotationX(0f).setDuration(D.SLOW)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()
                val w = card.width.toFloat().coerceAtLeast(1f)
                shine.translationX = if (lastDir == -1) w else -ui.uf(120)
                shine.alpha = 1f
                shine.animate().translationX(if (lastDir == -1) -ui.uf(120) else w).setDuration(750)
                    .setInterpolator(DecelerateInterpolator(1.4f))
                    .withEndAction { shine.alpha = 0f }.start()
            } else {
                card.animate().rotationY(0f).rotationX(0f).setDuration(D.FAST).start()
                shine.animate().cancel()
                shine.alpha = 0f
            }
        }
    }

    /**
     * Opens a film page by growing the chosen card's picture to fill the screen and fading to
     * black, where the film page then fades in. [go] starts the page; [root] is the screen's
     * top frame. Light mode, or a card without a picture, just fades.
     */
    fun zoomOpen(root: FrameLayout, from: View?, picture: android.graphics.drawable.Drawable?, go: () -> Unit) {
        zoomReset(root, animate = false)
        val ctx = root.context
        val black = View(ctx)
        black.setBackgroundColor(Color.BLACK)
        black.alpha = 0f
        black.tag = ZOOM
        if (lite || from == null || picture == null || from.width == 0) {
            root.addView(black, FrameLayout.LayoutParams(-1, -1))
            black.animate().alpha(1f).setDuration(D.FAST).withEndAction { go() }.start()
            return
        }
        // Where the card is drawn now, including its focus lift.
        val at = IntArray(2)
        val rootAt = IntArray(2)
        from.getLocationInWindow(at)
        root.getLocationInWindow(rootAt)
        val w0 = from.width * from.scaleX
        val h0 = from.height * from.scaleY
        val x0 = at[0] - rootAt[0] + (from.width - w0) / 2f
        val y0 = at[1] - rootAt[1] + (from.height - h0) / 2f
        val img = android.widget.ImageView(ctx)
        img.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        img.setImageDrawable(picture.constantState?.newDrawable(ctx.resources) ?: picture)
        img.tag = ZOOM
        val lp = FrameLayout.LayoutParams(w0.toInt(), h0.toInt())
        root.addView(img, lp)
        root.addView(black, FrameLayout.LayoutParams(-1, -1))
        img.translationX = x0
        img.translationY = y0
        zoomFrom[root] = floatArrayOf(x0, y0, w0, h0)
        val fullW = root.width.toFloat()
        val fullH = root.height.toFloat()
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = 420
        a.interpolator = DecelerateInterpolator(2f)
        a.addUpdateListener {
            val t = it.animatedValue as Float
            lp.width = (w0 + (fullW - w0) * t).toInt()
            lp.height = (h0 + (fullH - h0) * t).toInt()
            img.layoutParams = lp
            img.translationX = x0 * (1 - t)
            img.translationY = y0 * (1 - t)
            // The last part fades to black, where the film page starts.
            black.alpha = ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                go()
            }
        })
        a.start()
    }

    /** Removes what [zoomOpen] left on [root], fading back to the screen when we return. */
    fun zoomReset(root: FrameLayout, animate: Boolean = true) {
        val left = (0 until root.childCount).map { root.getChildAt(it) }.filter { it.tag == ZOOM }
        val from = zoomFrom.remove(root)
        val pic = left.firstOrNull { it is android.widget.ImageView }
        if (animate && !lite && from != null && pic != null && root.width > 0) {
            zoomBack(root, pic, left, from)
            return
        }
        for (v in left) {
            if (animate) v.animate().alpha(0f).setDuration(D.MED).withEndAction { root.removeView(v) }.start()
            else root.removeView(v)
        }
    }

    private const val ZOOM = "zoom"
    private val zoomFrom = java.util.WeakHashMap<FrameLayout, FloatArray>()

    /**
     * Coming back from a film page: the picture shrinks from full screen into the card it came
     * from while the black lifts, the opening played backwards.
     */
    private fun zoomBack(root: FrameLayout, pic: View, all: List<View>, from: FloatArray) {
        val (x0, y0, w0, h0) = from.toList()
        val lp = pic.layoutParams as FrameLayout.LayoutParams
        val fullW = root.width.toFloat()
        val fullH = root.height.toFloat()
        val blacks = all.filter { it !== pic }
        for (b in blacks) b.animate().alpha(0f).setDuration(260).start()
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = 460
        a.interpolator = DecelerateInterpolator(2f)
        a.addUpdateListener {
            val t = it.animatedValue as Float
            lp.width = (fullW + (w0 - fullW) * t).toInt()
            lp.height = (fullH + (h0 - fullH) * t).toInt()
            pic.layoutParams = lp
            pic.translationX = x0 * t
            pic.translationY = y0 * t
            // The card underneath takes over at the very end.
            pic.alpha = if (t < 0.8f) 1f else (1f - (t - 0.8f) / 0.2f)
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                for (v in all) root.removeView(v)
            }
        })
        a.start()
    }

    /** Average lightness (0..1) of the part of [b] between fractions [l]..[r] across and [t]..[btm] down. */
    fun brightness(b: Bitmap, l: Float, t: Float, r: Float, btm: Float): Float = try {
        val s = Bitmap.createScaledBitmap(b, 40, 24, true)
        var sum = 0.0
        var n = 0
        for (x in (l * 40).toInt() until (r * 40).toInt()) for (y in (t * 24).toInt() until (btm * 24).toInt()) {
            val p = s.getPixel(x, y)
            sum += (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)) / 255.0
            n++
        }
        if (s !== b) s.recycle()
        if (n == 0) 0f else (sum / n).toFloat()
    } catch (e: Throwable) {
        0f
    }

    /**
     * A single thin band of light passes over [v] (a film's logo or title), only where it is drawn:
     * the band is painted on top with SRC_ATOP inside the view's own layer, so empty space stays empty.
     */
    fun shine(v: View) {
        if (lite || v.width == 0 || v.visibility != View.VISIBLE) return
        val w = v.width.toFloat()
        val band = object : android.graphics.drawable.Drawable() {
            var x = -w
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
            }
            override fun draw(canvas: Canvas) {
                val bw = w * 0.22f
                paint.shader = android.graphics.LinearGradient(
                    x, 0f, x + bw, bounds.height().toFloat() * 0.35f,
                    intArrayOf(Color.TRANSPARENT, Color.argb(200, 255, 248, 232), Color.TRANSPARENT), null,
                    android.graphics.Shader.TileMode.CLAMP
                )
                canvas.drawRect(bounds, paint)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
        band.setBounds(0, 0, v.width, v.height)
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        v.overlay.add(band)
        val a = android.animation.ValueAnimator.ofFloat(-w * 0.3f, w * 1.1f)
        a.duration = 1100
        a.startDelay = 350
        a.interpolator = DecelerateInterpolator(1.2f)
        a.addUpdateListener {
            band.x = it.animatedValue as Float
            band.invalidateSelf()
            v.invalidate()
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                v.overlay.remove(band)
                v.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        })
        a.start()
    }

    /** A small picture melted into soft colour, shown while the sharp one loads. */
    fun soften(b: Bitmap): Bitmap? = try {
        val w = (b.width / 4).coerceAtLeast(8)
        val h = (b.height / 4).coerceAtLeast(5)
        val s = Bitmap.createScaledBitmap(b, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
        repeat(2) { boxBlur(s, 2) }
        s
    } catch (e: Throwable) {
        null
    }

    /**
     * Placeholder for a picture still on its way: the card's dark colour with a soft band of light
     * passing over it now and then, instead of an empty grey box. Still in light mode.
     */
    /**
     * A placeholder with a slow light passing over it while a picture loads. Kept light: one gradient
     * made once and moved, about 30 frames a second, and it settles after 6 s (a picture that never
     * comes must not keep the screen redrawing).
     */
    class Shimmer(private val base: Int, private val radius: Float) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = base }
        private val band = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = android.graphics.RectF()
        private val start = android.os.SystemClock.uptimeMillis()
        private val move = android.graphics.Matrix()
        private var madeFor = 0
        private val tick = Runnable { invalidateSelf() }

        override fun draw(canvas: Canvas) {
            val b = bounds
            rect.set(b)
            canvas.drawRoundRect(rect, radius, radius, paint)
            val age = android.os.SystemClock.uptimeMillis() - start
            if (lite || age > 6000L || !isVisible) return
            val w = b.width().toFloat()
            if (madeFor != b.width() || band.shader == null) {
                madeFor = b.width()
                band.shader = android.graphics.LinearGradient(
                    0f, 0f, w * 0.8f, b.height().toFloat(),
                    intArrayOf(Color.TRANSPARENT, Color.argb(26, 255, 255, 255), Color.TRANSPARENT),
                    null, android.graphics.Shader.TileMode.CLAMP
                )
            }
            // One pass every 1.6 s, from left to right, slanted.
            val t = (age % 1600L) / 1600f
            move.setTranslate(b.left - w + t * w * 3f, b.top.toFloat())
            band.shader.setLocalMatrix(move)
            canvas.drawRoundRect(rect, radius, radius, band)
            scheduleSelf(tick, android.os.SystemClock.uptimeMillis() + 33)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Puts [bmp] on [into] with a short cross-fade from what was there (a shimmer, a soft picture). */
    fun fadeIn(into: android.widget.ImageView, bmp: Bitmap, ms: Int = 320) {
        val old = into.drawable
        if (old == null || lite) {
            into.setImageBitmap(bmp)
            return
        }
        val td = android.graphics.drawable.TransitionDrawable(
            arrayOf(old, android.graphics.drawable.BitmapDrawable(into.resources, bmp))
        )
        td.isCrossFadeEnabled = true
        into.setImageDrawable(td)
        td.startTransition(ms)
        // A plain picture afterwards, so whoever copies it (the zoom into a film page) gets the picture.
        into.postDelayed({ if (into.drawable === td) into.setImageBitmap(bmp) }, ms + 40L)
    }

    /**
     * A soft, cheap blur of [v], good enough to sit behind dark glass. A larger [scale] and more
     * [passes] melt the picture into smooth colour with no shapes left, as on the settings screen.
     */
    fun snapshotBlur(v: View, scale: Int = 12, passes: Int = 2): Bitmap? {
        if (lite || v.width == 0 || v.height == 0) return null
        return try {
            val w = (v.width / scale).coerceAtLeast(1)
            val h = (v.height / scale).coerceAtLeast(1)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.scale(1f / scale, 1f / scale)
            v.draw(c)
            repeat(passes) { boxBlur(b, 3) }
            b
        } catch (e: Throwable) {
            null
        }
    }

    /** [src] as frosted glass: shrunk [scale] times and blurred [passes] times (cheap on any TV). */
    fun blurred(src: Bitmap, scale: Int = 16, passes: Int = 2): Bitmap? {
        if (lite) return null
        return try {
            val w = (src.width / scale).coerceAtLeast(1)
            val h = (src.height / scale).coerceAtLeast(1)
            val b = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
            repeat(passes) { boxBlur(b, 3) }
            b
        } catch (e: Throwable) {
            null
        }
    }

    private fun boxBlur(b: Bitmap, r: Int) {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        fun pass(src: IntArray, dst: IntArray, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            for (l in 0 until lines) {
                for (i in 0 until len) {
                    var rr = 0
                    var gg = 0
                    var bb = 0
                    var n = 0
                    for (k in -r..r) {
                        val j = (i + k).coerceIn(0, len - 1)
                        val p = if (horizontal) src[l * w + j] else src[j * w + l]
                        rr += Color.red(p)
                        gg += Color.green(p)
                        bb += Color.blue(p)
                        n++
                    }
                    val idx = if (horizontal) l * w + i else i * w + l
                    dst[idx] = Color.rgb(rr / n, gg / n, bb / n)
                }
            }
        }
        pass(px, out, true)
        pass(out, px, false)
        b.setPixels(px, 0, w, 0, 0, w, h)
    }
}

/**
 * Clock whose digits slide up when they change, like on Apple TV.
 * Follows the 12/24 hour setting through [use24].
 */
class RollingClock(ctx: Context, private val ui: Ui, size: Int, weight: Int, color: Int) : LinearLayout(ctx) {
    private val digits = ArrayList<TextSwitcher>()
    private val suffix: TextView
    private val handler = Handler(Looper.getMainLooper())
    var use24 = true
        set(v) {
            field = v
            update(false)
        }
    private val tick = object : Runnable {
        override fun run() {
            update(true)
            val now = System.currentTimeMillis()
            handler.postDelayed(this, 60000L - now % 60000L + 50)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (i in 0 until 5) {
            val s = TextSwitcher(ctx)
            s.setFactory {
                val t = ui.text("", size, color, weight)
                t.gravity = Gravity.CENTER
                // Equal-width digits so the time doesn't jiggle when it changes.
                t.fontFeatureSettings = "tnum"
                t
            }
            s.inAnimation = AnimationUtils.loadAnimation(ctx, android.R.anim.fade_in).also { it.duration = 420 }
            s.outAnimation = AnimationUtils.loadAnimation(ctx, android.R.anim.fade_out).also { it.duration = 300 }
            addView(s)
            digits.add(s)
        }
        suffix = ui.text("", (size * 0.5f).toInt(), color, weight)
        addView(suffix, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also { it.leftMargin = ui.u(8) })
        update(false)
    }

    private fun text(): Pair<String, String> {
        val c = Calendar.getInstance()
        val m = c.get(Calendar.MINUTE)
        return if (use24) {
            String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), m) to ""
        } else {
            val h = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            String.format(Locale.US, "%2d:%02d", h, m) to (if (c.get(Calendar.AM_PM) == Calendar.AM) "ÖÖ" else "ÖS")
        }
    }

    private fun update(animate: Boolean) {
        val (t, s) = text()
        for (i in 0 until 5) {
            val ch = t.getOrNull(i)?.toString()?.trim() ?: ""
            val cur = (digits[i].currentView as? TextView)?.text?.toString() ?: ""
            if (ch == cur) continue
            if (animate) {
                // Slide the new digit up into place.
                val next = digits[i].nextView
                next.translationY = ui.uf(18)
                next.animate().translationY(0f).setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
                digits[i].setText(ch)
            } else {
                digits[i].setCurrentText(ch)
            }
        }
        suffix.text = s
        suffix.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(tick)
        update(false)
        val now = System.currentTimeMillis()
        handler.postDelayed(tick, 60000L - now % 60000L + 50)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    fun setShadow(radius: Float, dy: Float, color: Int) {
        for (d in digits) for (i in 0 until d.childCount) (d.getChildAt(i) as? TextView)?.setShadowLayer(radius, 0f, dy, color)
        suffix.setShadowLayer(radius, 0f, dy, color)
    }
}

/** Very faint rain or snow over the picture, drawn with a few dozen particles. */
class WeatherLayer(ctx: Context) : View(ctx) {
    enum class Kind { NONE, RAIN, SNOW }

    private class P(var x: Float, var y: Float, var speed: Float, var size: Float, var drift: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val parts = ArrayList<P>()
    private var running = false
    var kind = Kind.NONE
        set(v) {
            if (field == v) return
            field = v
            parts.clear()
            visibility = if (v == Kind.NONE) GONE else VISIBLE
            invalidate()
        }

    fun start() {
        running = true
        invalidate()
    }

    fun stop() {
        running = false
    }

    private fun seed(w: Int, h: Int) {
        val n = if (kind == Kind.RAIN) 70 else 55
        for (i in 0 until n) parts.add(newP(w, h, true))
    }

    private fun newP(w: Int, h: Int, anywhere: Boolean): P {
        val rain = kind == Kind.RAIN
        return P(
            Random.nextFloat() * w,
            if (anywhere) Random.nextFloat() * h else -20f,
            if (rain) h / 55f + Random.nextFloat() * h / 70f else h / 900f + Random.nextFloat() * h / 700f,
            if (rain) h / 60f + Random.nextFloat() * h / 50f else 2f + Random.nextFloat() * h / 300f,
            if (rain) h / 400f else (Random.nextFloat() - 0.5f) * h / 1200f
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (kind == Kind.NONE || width == 0) return
        if (parts.isEmpty()) seed(width, height)
        val rain = kind == Kind.RAIN
        paint.color = Color.argb(if (rain) 55 else 120, 255, 255, 255)
        paint.strokeWidth = if (rain) height / 900f else 0f
        for (p in parts) {
            if (rain) canvas.drawLine(p.x, p.y, p.x - p.drift, p.y + p.size, paint)
            else canvas.drawCircle(p.x, p.y, p.size, paint)
            if (running) {
                p.y += p.speed
                p.x += if (rain) -p.drift / 6f else p.drift
                if (p.y > height + 20) {
                    val n = newP(width, height, false)
                    p.x = n.x; p.y = n.y; p.speed = n.speed; p.size = n.size; p.drift = n.drift
                }
            }
        }
        if (running) postInvalidateOnAnimation()
    }
}
