package tv.lexia.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * The sounds of LeXia, like on Apple TV: a soft tick when focus moves, a whoosh when a film page
 * opens, a lower tone going back and a small "ding" when something goes into Listem.
 * Ayarlar › Görünüm › Gezinme sesi: Yumuşak / Klasik / Kapalı.
 */
object Sound {
    const val OPEN = 1
    const val BACK = 2
    const val ADD = 3

    private var pool: SoundPool? = null
    private var tick = 0
    private var soft = 0
    private var whoosh = 0
    private var back = 0
    private var ding = 0
    private val loaded = HashSet<Int>()
    private var last = 0L
    private var app: Context? = null

    private fun load(a: Activity) {
        if (pool != null) return
        app = a.applicationContext
        val p = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        p.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) }
        val c = a.applicationContext
        tick = p.load(c, R.raw.tick, 1)
        soft = p.load(c, R.raw.tick_soft, 1)
        whoosh = p.load(c, R.raw.whoosh, 1)
        back = p.load(c, R.raw.back, 1)
        ding = p.load(c, R.raw.ding, 1)
        pool = p
    }

    /** Ticks on every focus move inside [a] while the setting is on; OK presses get a small push. */
    fun attach(a: Activity) {
        try {
            load(a)
        } catch (e: Exception) {
            return
        }
        val prefs = Prefs(a)
        a.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(
            ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
                if (old != null && new != null && prefs.navSound) play()
            }
        )
        Press.attach(a)
    }

    private fun on(): Boolean {
        val c = app ?: return false
        return Prefs(c).navSound && !Remote.isOff("sound")
    }

    fun play() {
        val p = pool ?: return
        if (!on()) return
        // Holding a direction key repeats fast; keep it from turning into a buzz.
        val now = SystemClock.uptimeMillis()
        if (now - last < 45) return
        last = now
        val classic = Prefs(app!!).soundSet == "classic"
        val id = if (classic) tick else soft
        if (id in loaded) p.play(id, if (classic) 0.35f else 0.45f, if (classic) 0.35f else 0.45f, 1, 0, 1f)
    }

    /** One of [OPEN], [BACK], [ADD]. */
    fun event(kind: Int) {
        val p = pool ?: return
        if (!on()) return
        val (id, vol) = when (kind) {
            OPEN -> whoosh to 0.32f
            BACK -> back to 0.3f
            else -> ding to 0.35f
        }
        if (id in loaded) p.play(id, vol, vol, 2, 0, 1f)
    }
}

/**
 * The feel of pressing: OK pushes the focused card or button in for a moment, and holding OK on
 * something with a menu fills a small ring until the menu opens.
 */
object Press {
    private val handler = Handler(Looper.getMainLooper())
    private var ring: RingDrawable? = null
    private var ringOn: View? = null
    private var ringAnim: ValueAnimator? = null
    private var pending: Runnable? = null

    fun attach(a: Activity) {
        val orig = a.window.callback ?: return
        if (orig is Wrap) return
        a.window.callback = Wrap(a, orig)
    }

    private class Wrap(val a: Activity, val orig: Window.Callback) : Window.Callback by orig {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            key(a, event)
            return orig.dispatchKeyEvent(event)
        }
    }

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

    private fun key(a: Activity, e: KeyEvent) {
        if (!isOk(e.keyCode)) return
        val v = a.currentFocus
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            if (v == null || !(v.isClickable || v.isLongClickable)) return
            push(v)
            if (v.isLongClickable) {
                val r = Runnable { startRing(a, v) }
                pending = r
                handler.postDelayed(r, 170)
            }
        } else if (e.action == KeyEvent.ACTION_UP) {
            stopRing()
        }
    }

    /** In by 5% and back, from whatever size the focus gave it. */
    private fun push(v: View) {
        val s = v.scaleX
        v.animate().cancel()
        v.animate().scaleX(s * 0.95f).scaleY(s * 0.95f).setDuration(80).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                v.animate().scaleX(s).scaleY(s).setDuration(180).setInterpolator(DecelerateInterpolator(1.5f)).start()
            }.start()
    }

    private fun startRing(a: Activity, v: View) {
        pending = null
        if (!v.isAttachedToWindow || !v.isFocused) return
        stopRing()
        val root = a.window.decorView
        val at = IntArray(2)
        v.getLocationInWindow(at)
        val w = v.width * v.scaleX
        val h = v.height * v.scaleY
        val cx = at[0] + (v.width - w) / 2f + w / 2f
        val cy = at[1] + (v.height - h) / 2f + h / 2f
        val size = (minOf(w, h) * 0.42f).coerceIn(60f, 150f)
        val d = RingDrawable(size / 14f)
        d.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
        root.overlay.add(d)
        ring = d
        ringOn = root
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = (ViewConfiguration.getLongPressTimeout() - 170L).coerceAtLeast(200L)
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener { d.sweep = it.animatedValue as Float; d.invalidateSelf() }
        anim.start()
        ringAnim = anim
        // The menu opens at the end; the ring goes away a moment later in any case.
        handler.postDelayed({ stopRing() }, anim.duration + 160)
    }

    private fun stopRing() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        ringAnim?.cancel()
        ringAnim = null
        val d = ring ?: return
        ringOn?.overlay?.remove(d)
        ring = null
        ringOn = null
    }

    private class RingDrawable(val stroke: Float) : Drawable() {
        var sweep = 0f
        private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = Color.argb(70, 255, 255, 255)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = Color.WHITE; strokeCap = Paint.Cap.ROUND
        }
        private val r = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() / 2f, back)
            val inset = stroke * 1.6f
            r.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
            canvas.drawOval(r, track)
            canvas.drawArc(r, -90f, 360f * sweep, false, fill)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
