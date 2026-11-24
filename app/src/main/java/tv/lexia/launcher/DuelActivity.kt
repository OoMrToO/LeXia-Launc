package tv.lexia.launcher

import android.app.Activity
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
import java.util.Locale
import kotlin.random.Random

/**
 * Film düellosu: two films side by side, pick one; the winner meets the next film. After five
 * rounds the last one standing is tonight's film. For when "Ne izlesem?" is not enough fun.
 */
class DuelActivity : Activity() {

    companion object {
        private const val ROUNDS = 5
        private var pending: List<Pick> = emptyList()

        fun open(a: Activity, films: List<Pick>) {
            pending = films
            a.startActivity(Intent(a, DuelActivity::class.java))
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private lateinit var ui: Ui
    private lateinit var frame: FrameLayout
    private lateinit var stage: LinearLayout
    private lateinit var roundText: TextView
    private lateinit var dots: LinearLayout
    private var all: List<Pick> = emptyList()
    private var line: List<Pick> = emptyList()
    private var champion: Pick? = null
    private var round = 0
    private val tr = Locale("tr", "TR")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        all = pending.filter { it.backdrop.isNotEmpty() && !it.isSeries }.distinctBy { it.key }
        pending = emptyList()
        if (all.size < 2) {
            finish()
            return
        }
        frame = FrameLayout(this)
        frame.background = ui.gradient(GradientDrawable.Orientation.TL_BR, Color.parseColor("#1A1020"), Color.parseColor("#08090E"), Color.parseColor("#0A1220"))
        setContentView(frame)
        Sound.attach(this)
        start()
    }

    /** Six films, better scored ones a little more likely, in random order. */
    private fun start() {
        val left = all.toMutableList()
        val chosen = ArrayList<Pick>()
        while (chosen.size < minOf(ROUNDS + 1, all.size) && left.isNotEmpty()) {
            val weights = left.map { (it.imdb.coerceAtLeast(5.0) - 4.5) }
            var r = Random.nextDouble() * weights.sum()
            var i = 0
            while (i < left.size - 1 && r > weights[i]) {
                r -= weights[i]
                i++
            }
            chosen.add(left.removeAt(i))
        }
        champion = chosen.first()
        line = chosen.drop(1)
        round = 0
        build()
        next()
    }

    private fun build() {
        frame.removeAllViews()
        val head = ui.vbox()
        head.gravity = Gravity.CENTER_HORIZONTAL
        roundText = ui.label("")
        head.addView(roundText)
        head.addView(ui.text("Hangisini izlemek istersin?", 60, C.TEXT, 800), ui.ll(ui.wrap, ui.wrap, top = 12))
        val hl = ui.fl(ui.match, ui.wrap, Gravity.TOP)
        hl.topMargin = ui.u(80)
        frame.addView(head, hl)
        stage = ui.hbox()
        stage.gravity = Gravity.CENTER
        stage.clipChildren = false
        stage.clipToPadding = false
        frame.addView(stage, ui.fl(ui.match, ui.match, Gravity.CENTER))
        val foot = ui.vbox()
        foot.gravity = Gravity.CENTER_HORIZONTAL
        dots = ui.hbox()
        foot.addView(dots)
        foot.addView(ui.text("◀ ▶  seç   ·   OK  kazansın   ·   Kazanan bir sonraki filmle karşılaşır", 21, C.TEXT3, 500), ui.ll(ui.wrap, ui.wrap, top = 28))
        val fl = ui.fl(ui.match, ui.wrap, Gravity.BOTTOM)
        fl.bottomMargin = ui.u(80)
        frame.addView(foot, fl)
    }

    private fun next() {
        val a = champion ?: return
        val b = line.getOrNull(round)
        if (b == null) {
            winner(a)
            return
        }
        roundText.text = "FİLM DÜELLOSU  ·  ${round + 1}. TUR / ${line.size}"
        dots.removeAllViews()
        for (i in line.indices) {
            val d = View(this)
            d.background = ui.round(
                when {
                    i < round -> Color.WHITE
                    i == round -> Color.parseColor("#FF4D8D")
                    else -> Color.argb(50, 255, 255, 255)
                }, 3
            )
            dots.addView(d, ui.ll(ui.u(40), ui.u(6), left = 6, right = 6))
        }
        stage.removeAllViews()
        val left = card(a)
        stage.addView(left)
        stage.addView(ui.text("VS", 40, Color.argb(130, 255, 255, 255), 800), ui.ll(ui.wrap, ui.wrap, left = 40, right = 40))
        val right = card(b)
        stage.addView(right)
        (left.tag as View).setOnClickListener { pick(a) }
        (right.tag as View).setOnClickListener { pick(b) }
        for ((v, i) in listOf(left to 0, right to 1)) {
            v.alpha = 0f
            v.translationY = ui.uf(30)
            v.animate().alpha(1f).translationY(0f).setStartDelay(i * 90L).setDuration(D.SLOW).start()
        }
        (left.tag as View).requestFocus()
    }

    private fun pick(p: Pick) {
        champion = p
        round++
        next()
    }

    private fun card(p: Pick): View {
        val col = ui.vbox()
        col.clipChildren = false
        val box = FrameLayout(this)
        box.isFocusable = true
        box.isClickable = true
        box.background = ui.round(C.CARD, 22)
        box.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        box.addView(img, ui.fl(ui.match, ui.match))
        Img.load(this, "w780", p.backdrop, ui.u(620), img)
        val ring = View(this)
        ring.background = ui.round(Color.TRANSPARENT, 22, 5, Color.WHITE)
        ring.alpha = 0f
        box.addView(ring, ui.fl(ui.match, ui.match))
        col.addView(box, ui.ll(ui.u(620), ui.u(350)))
        val t = ui.text(p.title, 36, C.TEXT, 800)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        col.addView(t, ui.ll(ui.u(620), ui.wrap, top = 24))
        val bits = ArrayList<String>()
        if (p.imdb > 0) bits.add(String.format(tr, "IMDb %.1f", p.imdb))
        if (p.runtime > 0) bits.add(if (p.runtime >= 60) "${p.runtime / 60} sa ${p.runtime % 60} dk" else "${p.runtime} dk")
        if (p.genres.isNotEmpty()) bits.add(p.genres.first())
        col.addView(ui.text(bits.joinToString("  ·  "), 20, C.TEXT2, 500), ui.ll(ui.wrap, ui.wrap, top = 8))
        col.alpha = 0.75f
        box.setOnFocusChangeListener { _, f ->
            ui.lift(col, f, 1.05f, 24)
            ring.animate().alpha(if (f) 1f else 0f).setDuration(D.FAST).start()
            col.animate().alpha(if (f) 1f else 0.75f).setDuration(D.FAST).start()
        }
        // The picture takes focus and the press; the column only carries it.
        col.tag = box
        return col
    }

    /** The last one standing: its picture full screen and what to do now. */
    private fun winner(p: Pick) {
        frame.removeAllViews()
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.alpha = 0f
        frame.addView(img, ui.fl(ui.match, ui.match))
        val w = resources.displayMetrics.widthPixels
        Img.load(this, if (w > 1280) "original" else "w1280", p.backdrop, w, img) { img.animate().alpha(1f).setDuration(D.SLOW).start() }
        val shade = View(this)
        shade.background = ui.gradient(GradientDrawable.Orientation.LEFT_RIGHT, Color.argb(235, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.argb(20, 0, 0, 0))
        frame.addView(shade, ui.fl(ui.match, ui.match))
        val col = ui.vbox()
        col.clipChildren = false
        col.clipToPadding = false
        col.addView(ui.label("BU AKŞAMIN FİLMİ  ·  DÜELLONUN KAZANANI", Color.parseColor("#FFD9A8")))
        val t = ui.text(p.title, 88, C.TEXT, 800)
        t.letterSpacing = -0.02f
        t.maxLines = 2
        col.addView(t, ui.ll(ui.u(1200), ui.wrap, top = 14))
        col.addView(ui.text(p.details(), 23, C.TEXT2, 500), ui.ll(ui.wrap, ui.wrap, top = 14))
        val row = ui.hbox()
        row.clipChildren = false
        row.clipToPadding = false
        if (p.trailer.isNotEmpty()) {
            val tb = ui.pill("▶   Fragmanı izle", 64, 23, primary = true)
            tb.setOnClickListener { TrailerActivity.open(this, p) }
            row.addView(tb)
        }
        val info = ui.pill("Detaylar", 64, 23, primary = p.trailer.isEmpty())
        info.setOnClickListener { DetailActivity.open(this, p) }
        row.addView(info)
        val again = ui.pill("Yeniden oyna", 64, 23)
        again.setOnClickListener { start() }
        row.addView(again)
        col.addView(row, ui.ll(ui.wrap, ui.wrap, top = 40))
        val lp = ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.START)
        lp.leftMargin = ui.u(130)
        lp.bottomMargin = ui.u(120)
        frame.addView(col, lp)
        col.alpha = 0f
        col.translationY = ui.uf(30)
        col.animate().alpha(1f).translationY(0f).setDuration(D.SLOW).start()
        row.getChildAt(0)?.requestFocus()
        Sound.event(Sound.ADD)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
