package tv.lexia.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Shared colors. White focus plates on dark glass, like tvOS. */
object C {
    val INK = Color.parseColor("#0B0B0D")
    val TEXT = Color.WHITE
    val TEXT2 = Color.parseColor("#B8FFFFFF")
    val TEXT3 = Color.parseColor("#78FFFFFF")
    // Dark glass reads on bright and dark pictures alike.
    val GLASS = Color.parseColor("#6B1C1C22")
    val STROKE = Color.parseColor("#33FFFFFF")
    val CARD = Color.parseColor("#1C1C1F")
    /** Small highlighted words: soft white, as on Apple TV (the film's own colour is Fx.accent). */
    val ACCENT = Color.parseColor("#E6FFFFFF")
    val INK2 = Color.parseColor("#99000000")
}

/**
 * One set of measures for every screen, so corners, timings and labels match everywhere.
 * Sizes are in design units (1920 px wide); times in milliseconds.
 */
object D {
    /** Small badges: IMDb, age rating, dates. */
    const val BADGE = 6
    /** Every picture card: app tiles, posters, film cards. */
    const val CARD = 16
    /** Focus plates on lists (settings rows). */
    const val PLATE = 18
    /** Glass panels and cards that float over the screen. */
    const val PANEL = 34

    /** Focus changes and small reactions. */
    const val FAST = 200L
    /** A focused card growing with a small spring. */
    const val SPRING = 320L
    /** Rows and pages fading in or out. */
    const val MED = 300L
    /** Text and pictures settling after a change. */
    const val SLOW = 650L
}

/** Builds views sized against a 1920px wide screen. */
class Ui(private val ctx: Context) {
    companion object {
        private val fonts = HashMap<String, Typeface>()
    }

    private val scale = ctx.resources.displayMetrics.widthPixels / 1920f
    private val prefs = Prefs(ctx)
    /** Ayarlar › Görünüm › Büyük yazı: every text 12% larger. */
    private val textScale get() = if (prefs.bigText) 1.12f else 1f
    /** Ayarlar › Görünüm › Cam: how much of the picture shows through panels and menus. */
    private val glass get() = prefs.glass

    /** Alpha of a glass panel (side panels, menus). */
    fun panelAlpha(): Int = when (glass) { 0 -> 240; 2 -> 165; else -> 219 }

    /** Alpha of the dimming layer behind a panel. */
    fun dimAlpha(): Int = when (glass) { 0 -> 150; 2 -> 60; else -> 110 }

    val match = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    fun u(n: Number): Int = (n.toFloat() * scale).roundToInt()
    fun uf(n: Number): Float = n.toFloat() * scale

    /** Inter, bundled in assets; falls back to the system font. */
    fun font(weight: Int): Typeface {
        val file = when {
            weight >= 700 -> "Inter_800ExtraBold"
            weight >= 600 -> "Inter_600SemiBold"
            weight >= 500 -> "Inter_500Medium"
            else -> "Inter_400Regular"
        }
        return fonts.getOrPut(file) {
            try {
                Typeface.createFromAsset(ctx.assets, "fonts/$file.ttf")
            } catch (e: Exception) {
                Typeface.DEFAULT
            }
        }
    }

    fun text(s: CharSequence, size: Number, color: Int = C.TEXT, weight: Int = 400): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(size) * textScale)
        t.setTextColor(color)
        t.typeface = font(weight)
        return t
    }

    fun round(color: Int, radius: Number, stroke: Number = 0, strokeColor: Int = C.STROKE): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = uf(radius)
        if (stroke.toFloat() > 0) d.setStroke(maxOf(1, u(stroke)), strokeColor)
        return d
    }

    fun gradient(o: GradientDrawable.Orientation, vararg colors: Int, radius: Number = 0): GradientDrawable {
        val d = GradientDrawable(o, colors)
        d.cornerRadius = uf(radius)
        return d
    }

    fun vbox(): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    fun hbox(): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    /** Linear layout params. Width/height are pixels (or match/wrap); margins are design units. */
    fun ll(w: Int, h: Int, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, weight)
        p.setMargins(u(left), u(top), u(right), u(bottom))
        return p
    }

    fun fl(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(w, h, gravity)

    /** Raise a focused view toward the viewer. */
    /**
     * Focus grows a card with a small spring, like Apple TV: it goes a touch past its size and
     * settles. Losing focus is a plain ease back. Light mode keeps the plain ease both ways.
     */
    fun lift(v: View, on: Boolean, s: Float = 1.1f, z: Number = 30) {
        val spring = on && !Fx.lite
        v.animate()
            .scaleX(if (on) s else 1f)
            .scaleY(if (on) s else 1f)
            .translationZ(if (on) uf(z) else 0f)
            .setDuration(if (spring) D.SPRING else if (on) D.FAST else 170L)
            .setInterpolator(if (spring) android.view.animation.OvershootInterpolator(1.3f) else DecelerateInterpolator(1.8f))
            .start()
    }

    /** Rounded text button. Primary is a light plate, the rest glass; focus turns both white. */
    fun pill(label: String, height: Int = 72, size: Int = 26, primary: Boolean = false): TextView {
        val t = text(label, size, C.TEXT, 600)
        t.gravity = Gravity.CENTER
        t.setPadding(u(height * 0.6f), 0, u(height * 0.6f), 0)
        t.isFocusable = true
        t.isClickable = true
        fun style(f: Boolean) {
            t.background = when {
                f -> round(Color.WHITE, height / 2)
                primary -> round(Color.parseColor("#E6FFFFFF"), height / 2)
                else -> round(C.GLASS, height / 2, 1)
            }
            t.setTextColor(if (f || primary) C.INK else C.TEXT)
        }
        style(false)
        t.setOnFocusChangeListener { _, f ->
            style(f)
            lift(t, f, 1.08f, 18)
        }
        t.layoutParams = ll(wrap, u(height), right = 18)
        return t
    }

    /** A glass pill with an icon before the label; the icon turns dark with the plate on focus. */
    fun iconPill(label: String, icon: Int, height: Int = 64, size: Int = 23): TextView {
        val t = pill(label, height, size)
        setPillIcon(t, icon)
        val style = t.onFocusChangeListener
        t.setOnFocusChangeListener { v, f ->
            style?.onFocusChange(v, f)
            t.compoundDrawables[0]?.setTint(if (f) C.INK else C.TEXT)
        }
        return t
    }

    fun setPillIcon(t: TextView, icon: Int) {
        val d = ctx.getDrawable(icon)?.mutate() ?: return
        d.setBounds(0, 0, u(26), u(26))
        d.setTint(if (t.isFocused) C.INK else C.TEXT)
        t.setCompoundDrawables(d, null, null, null)
        t.compoundDrawablePadding = u(12)
    }

    /** Small spaced capitals over a row or section: "BU HAFTANIN TOP 10 FİLMİ". */
    fun label(s: String, color: Int = C.TEXT2): TextView {
        val t = text(s, 17, color, 600)
        t.letterSpacing = 0.24f
        return t
    }

    /**
     * Where a film can be watched, as a coloured badge: the platform's own colour ("NETFLIX" on
     * red), or "SİNEMADA" for films in cinemas. Hidden when nothing is known.
     */
    fun whereBadge(): TextView {
        val t = text("", 16, Color.WHITE, 800)
        t.letterSpacing = 0.08f
        t.setPadding(u(14), u(5), u(14), u(5))
        t.includeFontPadding = false
        t.gravity = Gravity.CENTER
        t.minHeight = u(30)
        t.elevation = uf(4)
        return t
    }

    /** Shows [name] (a platform, or "" for cinemas) on the badge in its colour; [name] null hides it. */
    fun setWhere(t: TextView, name: String?) {
        if (name == null) {
            t.visibility = View.GONE
            return
        }
        t.visibility = View.VISIBLE
        val n = name.lowercase()
        // Each platform's own colour; text dark on light colours.
        val (bg, ink) = when {
            name.isEmpty() -> Color.parseColor("#FF4D8D") to Color.WHITE
            "netflix" in n -> Color.parseColor("#E50914") to Color.WHITE
            "disney" in n -> Color.parseColor("#113CCF") to Color.WHITE
            "prime" in n || "amazon" in n -> Color.parseColor("#00A8E1") to Color.WHITE
            "apple" in n || n == "tv+" -> Color.WHITE to Color.parseColor("#0B0B0D")
            "max" == n || "hbo" in n -> Color.parseColor("#002BE7") to Color.WHITE
            "mubi" in n -> Color.parseColor("#1C1C1C") to Color.WHITE
            "crunchyroll" in n -> Color.parseColor("#F47521") to Color.WHITE
            "exxen" in n -> Color.parseColor("#FFCC00") to Color.parseColor("#0B0B0D")
            "tod" == n -> Color.parseColor("#6C2BD9") to Color.WHITE
            "blutv" in n -> Color.parseColor("#0091FF") to Color.WHITE
            else -> Color.parseColor("#8A6CFF") to Color.WHITE
        }
        t.text = if (name.isEmpty()) "SİNEMADA" else "▶  " + name.uppercase(java.util.Locale("tr", "TR"))
        t.setTextColor(ink)
        t.background = round(bg, D.BADGE)
    }

    /** Age rating as a small outlined box ("13+"), next to the IMDb badge. */
    fun certBadge(text: String = ""): TextView {
        val t = text(text, 16, C.TEXT, 700)
        t.setPadding(u(9), u(2), u(9), u(2))
        t.background = round(Color.TRANSPARENT, D.BADGE, 2, Color.argb(200, 255, 255, 255))
        t.includeFontPadding = false
        t.gravity = Gravity.CENTER
        t.minHeight = u(28)
        return t
    }

    /**
     * What an empty list shows instead of a bare line: a soft glowing icon, a short title and one
     * sentence. Used by Listem, the bell and the calendar.
     */
    fun emptyState(icon: Int, title: String, sub: String, size: Int = 1): LinearLayout {
        val box = vbox()
        box.gravity = Gravity.CENTER_HORIZONTAL
        val d = if (size == 0) 76 else 104
        val halo = FrameLayout(ctx)
        val g = GradientDrawable()
        g.gradientType = GradientDrawable.RADIAL_GRADIENT
        g.colors = intArrayOf(Color.argb(70, 160, 140, 255), Color.argb(0, 160, 140, 255))
        g.gradientRadius = uf(d)
        halo.background = g
        val disc = FrameLayout(ctx)
        val ring = GradientDrawable()
        ring.shape = GradientDrawable.OVAL
        ring.setColor(Color.argb(40, 255, 255, 255))
        ring.setStroke(maxOf(1, u(2)), Color.argb(70, 255, 255, 255))
        disc.background = ring
        val img = ImageView(ctx)
        img.setImageResource(icon)
        img.setColorFilter(C.TEXT, PorterDuff.Mode.SRC_IN)
        disc.addView(img, fl(u(d * 0.42f), u(d * 0.42f), Gravity.CENTER))
        halo.addView(disc, fl(u(d), u(d), Gravity.CENTER))
        box.addView(halo, ll(u(d * 2), u(d * 1.6f)))
        val t = text(title, if (size == 0) 24 else 30, C.TEXT, 700)
        t.gravity = Gravity.CENTER
        box.addView(t, ll(wrap, wrap, top = 0))
        val s = text(sub, if (size == 0) 18 else 21, C.TEXT3, 400)
        s.gravity = Gravity.CENTER
        s.setLineSpacing(0f, 1.25f)
        s.maxWidth = u(if (size == 0) 480 else 760)
        box.addView(s, ll(wrap, wrap, top = 10))
        return box
    }

    /** Round glass button with a vector icon. */
    fun iconButton(res: Int, size: Int = 64): FrameLayout {
        val box = FrameLayout(ctx)
        box.isFocusable = true
        box.isClickable = true
        val img = ImageView(ctx)
        img.setImageResource(res)
        val pad = u(size * 0.24f)
        img.setPadding(pad, pad, pad, pad)
        box.addView(img, fl(match, match))
        fun style(f: Boolean) {
            box.background = if (f) round(Color.WHITE, size / 2) else round(C.GLASS, size / 2, 2)
            img.setColorFilter(if (f) C.INK else C.TEXT, PorterDuff.Mode.SRC_IN)
        }
        style(false)
        box.setOnFocusChangeListener { _, f ->
            style(f)
            lift(box, f, 1.12f, 16)
        }
        box.layoutParams = ll(u(size), u(size), left = 18)
        return box
    }
}
