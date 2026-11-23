package tv.lexia.launcher

import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * What the trailer screen shows around the trailer itself: the film's facts top left as it starts
 * (age, genre, language, content warnings: as the film player does), a green card before the first
 * trailer, as in the cinema, and the film's colours glowing in the black bands of a wide picture.
 */
internal object TrailerAmbience {

    /**
     * Top left, a line in the film's colour grows downwards and the rows come one by one; after a
     * few seconds they go in reverse. The film player's content warnings, in LeXia's views.
     */
    class Guide(private val ui: Ui, parent: FrameLayout) {
        private val box = LinearLayout(parent.context)
        private val line = View(parent.context)
        private val rows = LinearLayout(parent.context)
        private var hideAt: Runnable? = null
        var showing = false
            private set

        init {
            box.orientation = LinearLayout.HORIZONTAL
            box.alpha = 0f
            box.visibility = View.GONE
            rows.orientation = LinearLayout.VERTICAL
            box.addView(line, ui.ll(ui.u(4), 0))
            box.addView(rows, ui.ll(ui.wrap, ui.wrap, left = 16))
            val lp = ui.fl(ui.wrap, ui.wrap, Gravity.TOP or Gravity.START)
            lp.setMargins(ui.u(110), ui.u(70), 0, 0)
            parent.addView(box, lp)
        }

        /** [items]: label to value ("Şiddet" to "Orta"); [accent]: the film's colour. */
        fun show(items: List<Pair<String, String>>, accent: Int) {
            if (items.isEmpty()) return
            hide(quick = true)
            showing = true
            rows.removeAllViews()
            val rowHeight = ui.u(34)
            items.forEach { (label, value) ->
                val row = LinearLayout(box.context)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.alpha = 0f
                row.addView(ui.text(label, 20, Color.argb(220, 255, 255, 255), 600))
                row.addView(ui.text("  ·  ", 20, Color.argb(100, 255, 255, 255), 400))
                row.addView(ui.text(value, 20, Color.argb(150, 255, 255, 255), 500))
                rows.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, rowHeight))
            }
            line.background = ui.round(accent, 2)
            line.layoutParams = ui.ll(ui.u(4), rowHeight * items.size)
            line.pivotY = 0f
            line.scaleY = 0f
            box.visibility = View.VISIBLE
            box.animate().cancel()
            box.animate().alpha(1f).setDuration(300).withEndAction {
                if (!showing) return@withEndAction
                line.animate().scaleY(1f).setDuration(400).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).withEndAction {
                    for (i in 0 until rows.childCount) {
                        rows.getChildAt(i).animate().alpha(1f).setStartDelay(80L * (i + 1)).setDuration(200).start()
                    }
                }.start()
            }.start()
            val later = Runnable { hide(quick = false) }
            hideAt = later
            box.postDelayed(later, 300 + 400 + 80L * items.size + 200 + HOLD_MS)
        }

        /** Reverse order, then the line, then the box; [quick]: at once (the controls came up). */
        fun hide(quick: Boolean) {
            hideAt?.let { box.removeCallbacks(it) }
            hideAt = null
            if (!showing) return
            showing = false
            if (quick) {
                box.animate().cancel()
                box.alpha = 0f
                box.visibility = View.GONE
                return
            }
            val n = rows.childCount
            for (i in n - 1 downTo 0) {
                rows.getChildAt(i).animate().alpha(0f).setStartDelay(60L * (n - i)).setDuration(150).start()
            }
            line.animate().scaleY(0f).setStartDelay(60L * n + 100).setDuration(300).start()
            box.animate().alpha(0f).setStartDelay(60L * n + 400).setDuration(200)
                .withEndAction { if (!showing) box.visibility = View.GONE }.start()
        }
    }

    /** The green card before the first trailer: what is coming, and for whom. */
    class Card(private val ui: Ui, parent: FrameLayout) {
        private val card = LinearLayout(parent.context)
        private val title: TextView
        private val age: TextView
        private var shownAt = 0L
        val showing: Boolean get() = card.visibility == View.VISIBLE && shownAt > 0

        init {
            card.orientation = LinearLayout.VERTICAL
            card.gravity = Gravity.CENTER
            card.setPadding(ui.u(70), ui.u(40), ui.u(70), ui.u(40))
            card.background = ui.round(GREEN, 10, 3, Color.argb(230, 255, 255, 255))
            card.visibility = View.GONE
            card.alpha = 0f
            val top = ui.text("ŞİMDİ GÖSTERİLECEK FRAGMAN", 18, Color.argb(225, 255, 255, 255), 700)
            top.letterSpacing = 0.22f
            top.gravity = Gravity.CENTER
            card.addView(top)
            title = ui.text("", 44, Color.WHITE, 800)
            title.gravity = Gravity.CENTER
            title.maxLines = 2
            card.addView(title, ui.ll(ui.wrap, ui.wrap, top = 18))
            age = ui.text("", 20, Color.argb(225, 255, 255, 255), 600)
            age.letterSpacing = 0.12f
            age.gravity = Gravity.CENTER
            card.addView(age, ui.ll(ui.wrap, ui.wrap, top = 18))
            parent.addView(card, ui.fl(ui.u(1000), ui.wrap, Gravity.CENTER))
        }

        fun show(name: String, cert: String) {
            if (name.isBlank()) return
            title.text = name
            age.text = when {
                cert == "Genel" -> "GENEL İZLEYİCİ İÇİN UYGUNDUR"
                cert.endsWith("+") -> "${cert.dropLast(1)} YAŞ VE ÜZERİ İÇİN UYGUNDUR"
                else -> ""
            }
            age.visibility = if (age.text.isEmpty()) View.GONE else View.VISIBLE
            shownAt = System.currentTimeMillis()
            card.visibility = View.VISIBLE
            card.scaleX = 0.96f
            card.scaleY = 0.96f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(500).start()
        }

        /** How long before it may go: it stays at least [MIN_MS] so it can be read. */
        fun waitMs(): Long = (MIN_MS - (System.currentTimeMillis() - shownAt)).coerceAtLeast(0L)

        fun hide() {
            if (shownAt == 0L) return
            shownAt = 0L
            card.animate().cancel()
            card.animate().alpha(0f).setDuration(350).withEndAction { card.visibility = View.GONE }.start()
        }
    }

    /**
     * The picture behind the trailer, so a wide trailer's bands glow in the film's colours: its
     * backdrop shrunk to a few pixels and spread out again (a soft blur that costs nothing).
     */
    fun glow(backdrop: Bitmap): Bitmap? = try {
        val tiny = Bitmap.createScaledBitmap(backdrop, 16, 9, true)
        Bitmap.createScaledBitmap(tiny, 96, 54, true)
    } catch (e: Exception) {
        null
    }

    fun glowView(ui: Ui, parent: FrameLayout): ImageView {
        val v = ImageView(parent.context)
        v.scaleType = ImageView.ScaleType.FIT_XY
        v.alpha = 0f
        parent.addView(v, 0, ui.fl(ui.match, ui.match))
        return v
    }

    private const val HOLD_MS = 5_000L
    private const val MIN_MS = 1_800L
    private val GREEN = Color.rgb(28, 98, 52)
}
