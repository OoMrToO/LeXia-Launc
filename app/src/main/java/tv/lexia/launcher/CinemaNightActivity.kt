package tv.lexia.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Sinema Gecesi (the home screen's die, and Keşfet's): LeXia picks one film for tonight and gets it
 * ready. The film's picture fills the screen, its poster stands on the right, one short line tells
 * what it is about (its slogan, or the first sentence of its story); after a few seconds its trailer
 * plays behind and, with the remote resting, takes the whole screen as on the home screen (HeroCinema);
 * behind, its sources are looked up the way Listem's are, and "Gösterime hazır" shows only when one
 * this TV can play was really found. "Bu filmi izle" opens the source page, which starts at once from
 * what was found. Not watched, not turned down, Listem first, better scored more often; a film passed
 * over stays away for two weeks.
 */
class CinemaNightActivity : Activity() {

    companion object {
        private var pending: List<Pick> = emptyList()
        private var pendingListem = true

        /** [withListem]: Listem's films join (not when the choice was narrowed elsewhere, as in Keşfet). */
        fun open(a: Activity, films: List<Pick>, withListem: Boolean = true) {
            pending = films
            pendingListem = withListem
            a.startActivity(Intent(a, CinemaNightActivity::class.java))
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        /** A film shown stays away two days; one passed over ("Başka film seç") two weeks. */
        private const val SHOWN_MS = 2L * 24 * 60 * 60 * 1000
        private const val PASSED_MS = 14L * 24 * 60 * 60 * 1000
        /** The choice is made among this many of the best fitting films, so it is not always the same one. */
        private const val SHORTLIST = 12
        /** How long a film is on screen before its trailer starts (as on the home screen). */
        private const val TRAILER_DELAY_MS = 5_000L

        /**
         * Who can be offered: Listem's films first in line, then [given]; films only, out already,
         * with a poster, not watched, not turned down, not finished, not hidden.
         */
        internal fun candidates(
            saved: List<Pick>, given: List<Pick>, watched: Set<String>, down: Set<String>,
            finished: (String) -> Boolean, hidden: (Pick) -> Boolean = { false },
            out: (Pick) -> Boolean = { Surprise.watchable(it) }
        ): List<Pick> = (saved + given)
            .filter { !it.isSeries && it.poster.isNotEmpty() && out(it) }
            .distinctBy { it.key }
            .filter { it.key !in watched && it.key !in down && !finished(it.key) && !hidden(it) }

        /**
         * One film: how well it fits tonight is its score, three times as much from [listem], half again
         * in a [liked] genre, a little more when [ready] (it can be played at once). Then one of the best
         * [SHORTLIST], by chance. Not one shown on this page, nor one kept [away] (shown or passed over
         * lately) unless nothing else is left. Null when there is no film at all.
         */
        internal fun choose(
            pool: List<Pick>, shownNow: Set<String>, away: Set<String>, listem: Set<String>,
            liked: List<String>, ready: (Pick) -> Boolean, random: Random = Random
        ): Pick? {
            var left = pool.filter { it.key !in shownNow && it.key !in away }
            if (left.isEmpty()) left = pool.filter { it.key !in shownNow }
            if (left.isEmpty()) return null
            fun fit(p: Pick): Double {
                var w = ((if (p.imdb > 0) p.imdb else 6.5) - 5.5).coerceAtLeast(0.5)
                if (p.key in listem) w *= 3.0
                if (p.genres.any { it in liked }) w *= 1.5
                if (ready(p)) w *= 1.3
                return w
            }
            val shortlist = left.map { it to fit(it) }.sortedByDescending { it.second }.take(SHORTLIST)
            var r = random.nextDouble() * shortlist.sumOf { it.second }
            for ((p, w) in shortlist) {
                r -= w
                if (r <= 0) return p
            }
            return shortlist.last().first
        }
    }

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var store: MediaPlaybackStore
    private lateinit var frame: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var picture: ImageView
    private lateinit var shadeLeft: View
    private lateinit var shadeLow: View
    private lateinit var trailer: HeroTrailer
    private lateinit var cinema: HeroCinema
    private val handler = Handler(Looper.getMainLooper())
    private val trailerSoon = Runnable { startTrailer() }
    private var swallowUp = false
    private lateinit var poster: ImageView
    private lateinit var content: View
    private lateinit var logo: ImageView
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var reason: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusRow: LinearLayout
    private lateinit var playBtn: TextView

    private val tr = Locale("tr", "TR")
    private val worker = Executors.newSingleThreadExecutor()
    private var pool: List<Pick> = emptyList()
    private var listem: Set<String> = emptySet()
    private var liked: List<String> = emptyList()
    private var top: Set<String> = emptySet()
    private val shownNow = HashSet<String>()
    private var film: Pick? = null
    /** Which film the source search under way is for (a newer pick drops an older answer). */
    private var searchFor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        store = MediaPlaybackStore(this)
        val given = pending
        pending = emptyList()
        // Listem's films first in line, then what the page was given; films only, out already.
        val allSaved = prefs.watchlist.filter { !it.isSeries }
        listem = allSaved.map { it.key }.toSet()
        val saved = if (pendingListem) allSaved else emptyList()
        liked = prefs.likedGenres()
        top = given.take(10).map { it.key }.toSet()
        pool = candidates(saved, given, prefs.watched, prefs.disliked.keys, { store.isFinished(it) }, { Remote.isHidden(it) })

        frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        build()
        setContentView(frame)
        Sound.attach(this)
        showNext(first = true)
    }

    override fun onResume() {
        super.onResume()
        if (::trailer.isInitialized && !trailer.active) queueTrailer()
    }

    override fun onPause() {
        super.onPause()
        stopTrailer()
    }

    override fun onDestroy() {
        searchFor++
        handler.removeCallbacksAndMessages(null)
        if (::cinema.isInitialized) cinema.release()
        if (::trailer.isInitialized) trailer.release()
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------- The choice ----------

    /** Films shown or passed over lately, and until when they stay away. */
    private fun away(): MutableMap<String, Long> {
        val now = System.currentTimeMillis()
        val out = HashMap<String, Long>()
        try {
            val o = JSONObject(prefs.cinemaSeen.ifBlank { "{}" })
            for (k in o.keys()) o.optLong(k).takeIf { it > now }?.let { out[k] = it }
        } catch (_: Exception) {
        }
        return out
    }

    private fun keepAway(key: String, ms: Long) {
        val all = away()
        val until = System.currentTimeMillis() + ms
        if ((all[key] ?: 0L) < until) all[key] = until
        // Kept small: the 200 that stay away longest.
        val kept = all.entries.sortedByDescending { it.value }.take(200)
        prefs.cinemaSeen = JSONObject().apply { kept.forEach { put(it.key, it.value) } }.toString()
    }

    private fun choose(): Pick? = choose(pool, shownNow, away().keys, listem, liked,
        { it.where.isNotEmpty() || it.provider.isNotEmpty() || store.isAvailable(it.key) })

    /**
     * One short line that makes you want it: the film's slogan, else the first sentence of its
     * story (a second one joins a very short first); with neither, why it was chosen.
     */
    private fun hook(p: Pick): String {
        p.tagline.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val story = p.overview.trim().replace(Regex("\\s+"), " ")
        if (story.isEmpty()) return why(p)
        val sentences = Regex("(?<=[.!?…])\\s+(?=[\\p{Lu}\"“'‘])").split(story).filter { it.isNotBlank() }
        var line = sentences.firstOrNull().orEmpty()
        if (line.length < 60 && sentences.size > 1 && line.length + sentences[1].length < 170) line += " " + sentences[1]
        return line
    }

    /** One plain sentence on why this film, from what is true about it. */
    private fun why(p: Pick): String {
        val parts = ArrayList<String>()
        if (p.key in listem) parts.add("Listende bekliyordu")
        p.genres.firstOrNull { it in liked }?.let { parts.add("sevdiğin ${it.lowercase(tr)} türünden") }
        if (parts.size < 2 && p.key in top) parts.add("bu haftanın öne çıkanlarından")
        val where = p.provider.ifEmpty { p.where.firstOrNull().orEmpty() }
        if (parts.size < 2 && where.isNotEmpty()) parts.add("${TrailerActivity.locative(where)} izlenebilir")
        // The score is on the line above: not said twice.
        if (parts.isEmpty()) parts.add(if (p.imdb >= 7.5) "Çok sevilmiş, bu akşam için iyi bir seçim" else "Bu akşam için iyi bir seçim")
        val text = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " ve " + parts.last()
        return text.replaceFirstChar { it.titlecase(tr) } + "."
    }

    /** "2024  ·  Bilim Kurgu, Dram  ·  2 sa 12 dk  ·  23:40'ta biter". */
    private fun facts(p: Pick): String {
        val bits = ArrayList<String>()
        if (p.year.isNotEmpty()) bits.add(p.year.take(4))
        if (p.genres.isNotEmpty()) bits.add(p.genres.take(2).joinToString(", "))
        if (p.runtime > 0) {
            bits.add(if (p.runtime >= 60) "${p.runtime / 60} sa ${p.runtime % 60} dk" else "${p.runtime} dk")
            val end = Calendar.getInstance().apply { add(Calendar.MINUTE, p.runtime) }
            bits.add(String.format(tr, "şimdi başlarsan %02d:%02d gibi biter", end.get(Calendar.HOUR_OF_DAY), end.get(Calendar.MINUTE)))
        }
        if (p.imdb > 0) bits.add(String.format(tr, "IMDb %.1f", p.imdb))
        return bits.joinToString("  ·  ")
    }

    // ---------- The screen ----------

    private fun build() {
        val m = ui.match
        val w = ui.wrap
        // The picture and, over it, its trailer: they move closer together when the trailer takes the screen.
        stage = FrameLayout(this)
        picture = ImageView(this)
        picture.scaleType = ImageView.ScaleType.CENTER_CROP
        stage.addView(picture, ui.fl(m, m))
        frame.addView(stage, ui.fl(m, m))
        // Shade for the words: dark on the left and at the bottom, the picture free on the right.
        val left = View(this)
        left.background = ui.gradient(GradientDrawable.Orientation.LEFT_RIGHT,
            Color.argb(240, 0, 0, 0), Color.argb(170, 0, 0, 0), Color.argb(40, 0, 0, 0))
        frame.addView(left, ui.fl(m, m))
        shadeLeft = left
        val low = View(this)
        low.background = ui.gradient(GradientDrawable.Orientation.BOTTOM_TOP,
            Color.BLACK, Color.argb(200, 0, 0, 0), Color.TRANSPARENT)
        frame.addView(low, ui.fl(m, ui.u(520), Gravity.BOTTOM))
        shadeLow = low

        val row = ui.hbox()
        row.gravity = Gravity.CENTER_VERTICAL
        row.clipChildren = false
        row.clipToPadding = false
        row.setPadding(ui.u(110), ui.u(70), ui.u(110), ui.u(70))
        content = row

        val col = ui.vbox()
        col.clipChildren = false
        col.clipToPadding = false
        val kicker = ui.label("SİNEMA GECESİ")
        col.addView(kicker)
        logo = ImageView(this)
        logo.adjustViewBounds = true
        logo.maxHeight = ui.u(190)
        logo.maxWidth = ui.u(760)
        logo.scaleType = ImageView.ScaleType.FIT_START
        logo.visibility = View.GONE
        col.addView(logo, ui.ll(w, w, top = 22))
        title = ui.text("", 76, C.TEXT, 800)
        title.letterSpacing = -0.025f
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        title.setShadowLayer(ui.uf(30), 0f, ui.uf(4), Color.argb(130, 0, 0, 0))
        col.addView(title, ui.ll(m, w, top = 16))
        meta = ui.text("", 22, C.TEXT2, 600)
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.END
        col.addView(meta, ui.ll(m, w, top = 18))
        reason = ui.text("", 26, C.TEXT, 500)
        reason.maxLines = 2
        reason.ellipsize = TextUtils.TruncateAt.END
        reason.setLineSpacing(0f, 1.15f)
        col.addView(reason, ui.ll(m, w, top = 26))

        statusRow = ui.hbox()
        statusRow.gravity = Gravity.CENTER_VERTICAL
        statusDot = View(this)
        statusRow.addView(statusDot, ui.ll(ui.u(12), ui.u(12), right = 14))
        statusText = ui.text("", 22, C.TEXT2, 600)
        statusRow.addView(statusText)
        statusRow.visibility = View.INVISIBLE
        col.addView(statusRow, ui.ll(w, w, top = 30))

        val buttons = ui.hbox()
        buttons.clipChildren = false
        buttons.clipToPadding = false
        buttons.setPadding(ui.u(4), ui.u(10), 0, ui.u(10))
        playBtn = ui.pill("▶   Bu filmi izle", 72, 25, primary = true)
        playBtn.setOnClickListener { play() }
        val other = ui.pill("Başka film seç", 72, 25)
        other.setOnClickListener {
            film?.let { keepAway(it.key, PASSED_MS) }
            showNext(first = false)
        }
        val details = ui.pill("Detaylar", 72, 25)
        details.setOnClickListener { film?.let { DetailActivity.open(this, it) } }
        val duel = ui.pill("Düello", 72, 25)
        duel.setOnClickListener { DuelActivity.open(this, pool) }
        buttons.addView(playBtn)
        buttons.addView(other)
        buttons.addView(details)
        if (pool.size >= 2) buttons.addView(duel)
        col.addView(buttons, ui.ll(w, w, top = 34, left = -4))
        row.addView(col, ui.ll(0, w, weight = 1f, right = 80))

        // The poster, large, on the right.
        poster = ImageView(this)
        poster.scaleType = ImageView.ScaleType.CENTER_CROP
        poster.background = ui.round(C.CARD, 22)
        poster.clipToOutline = true
        poster.elevation = ui.uf(30)
        row.addView(poster, ui.ll(ui.u(440), ui.u(660)))
        frame.addView(row, ui.fl(m, m))

        trailer = HeroTrailer(this, stage).also { t ->
            t.height = if (prefs.heroTrailerHd && !Fx.lite) 1080 else 720
            t.onStutter = {
                prefs.heroTrailerHd = false
                t.height = 720
            }
            t.onShowing = { on ->
                if (::cinema.isInitialized) cinema.trailerShowing(on)
                trailerLight(on)
            }
            t.onCues = { if (::cinema.isInitialized) cinema.cues(it) }
        }
        // HeroCinema's four shades: this page has only the side and the bottom ones.
        val none1 = View(this)
        val none2 = View(this)
        cinema = HeroCinema(
            this, ui, frame, row, stage, listOf(none1, shadeLeft, shadeLow, none2),
            film = { film },
            logo = { logo.drawable?.takeIf { logo.visibility == View.VISIBLE } },
            // A trailer not known to be dubbed or original may carry Turkish subtitles in its picture.
            wordsInPicture = {
                val key = trailer.where()?.first
                val label = film?.trailers?.firstOrNull { it.key == key }?.label?.lowercase(tr).orEmpty()
                !("dubla" in label || "orijinal" in label || "original" in label) || "altyaz" in label
            },
            captionsAllowed = {
                val key = trailer.where()?.first
                val label = film?.trailers?.firstOrNull { it.key == key }?.label?.lowercase(tr).orEmpty()
                "dubla" !in label && "altyaz" !in label && "türkçe" !in label
            }
        )
    }

    // ---------- The trailer ----------

    /** While the trailer plays the shades lighten, so it shows in its own colours. */
    private fun trailerLight(on: Boolean) {
        val time = if (on) 1400L else 600L
        shadeLeft.animate().alpha(if (on) 0.7f else 1f).setDuration(time).start()
        shadeLow.animate().alpha(if (on) 0.8f else 1f).setDuration(time).start()
    }

    private fun queueTrailer() {
        handler.removeCallbacks(trailerSoon)
        if (prefs.heroTrailer && !Fx.lite) handler.postDelayed(trailerSoon, TRAILER_DELAY_MS)
    }

    private fun startTrailer() {
        val p = film ?: return
        if (!prefs.heroTrailer || Fx.lite || !hasWindowFocus() || isFinishing) return
        if (p.homeTrailer.isBlank()) return
        trailer.sound = prefs.heroTrailerSound
        trailer.start(p.homeTrailer)
    }

    private fun stopTrailer() {
        handler.removeCallbacks(trailerSoon)
        if (::cinema.isInitialized) cinema.wake()
        if (::trailer.isInitialized) trailer.stop()
    }

    /**
     * The trailer had the whole screen: the first key brings the page back. OK opens the film's
     * page (as the screen says), Back only wakes; the arrows go on and move as usual.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && swallowUp) {
            swallowUp = false
            return true
        }
        if (::cinema.isInitialized && event.action == KeyEvent.ACTION_DOWN) {
            val wasOn = cinema.on
            cinema.wake()
            if (wasOn) when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    film?.let { DetailActivity.open(this, it) }
                    swallowUp = true
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    swallowUp = true
                    return true
                }
            }
        }
        if (::cinema.isInitialized && event.action == KeyEvent.ACTION_UP && ::trailer.isInitialized) {
            cinema.rest(trailer.active)
        }
        return super.dispatchKeyEvent(event)
    }

    /** No film left to offer: said plainly, with the way back. */
    private fun showEmpty() {
        stopTrailer()
        frame.removeAllViews()
        val box = ui.vbox()
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.addView(ui.label("SİNEMA GECESİ"))
        box.addView(ui.text("Bu akşam önerecek film kalmadı", 52, C.TEXT, 800), ui.ll(ui.wrap, ui.wrap, top = 18))
        val sub = ui.text("İzlediğin ve geçtiğin filmler bir süre gelmez. Listem'e film ekleyebilirsin; yeni filmler her sabah gelir.", 24, C.TEXT2, 500)
        sub.gravity = Gravity.CENTER
        sub.maxWidth = ui.u(1100)
        box.addView(sub, ui.ll(ui.wrap, ui.wrap, top = 16))
        val close = ui.pill("Kapat", 72, 25, primary = true)
        close.setOnClickListener { finish() }
        box.addView(close, ui.ll(ui.wrap, ui.u(72), top = 44))
        frame.addView(box, ui.fl(ui.match, ui.wrap, Gravity.CENTER))
        close.requestFocus()
    }

    private fun showNext(first: Boolean) {
        val p = choose()
        if (p == null) {
            showEmpty()
            return
        }
        shownNow.add(p.key)
        keepAway(p.key, SHOWN_MS)
        film = p
        stopTrailer()
        queueTrailer()
        val fade = if (Fx.lite || first) 0L else D.FAST
        content.animate().cancel()
        content.animate().alpha(if (first) 1f else 0f).setDuration(fade).withEndAction {
            fill(p)
            content.animate().alpha(1f).setDuration(if (Fx.lite) 0L else D.MED).start()
            if (first || currentFocus == null) playBtn.requestFocus()
        }.start()
        prepare(p)
    }

    private fun fill(p: Pick) {
        title.text = p.title
        title.visibility = View.VISIBLE
        logo.visibility = View.GONE
        logo.setImageDrawable(null)
        meta.text = facts(p)
        reason.text = hook(p)
        val width = resources.displayMetrics.widthPixels
        picture.setImageDrawable(null)
        Img.load(this, if (width > 1280) "original" else "w1280", p.backdrop.ifEmpty { p.poster }, width, picture)
        poster.setImageDrawable(null)
        Img.load(this, "w780", p.poster, ui.u(440), poster)
        if (p.logo.isNotEmpty()) Img.load(this, "w500", p.logo, ui.u(760), logo, opaque = false) { ok ->
            if (ok && film?.key == p.key) {
                logo.visibility = View.VISIBLE
                title.visibility = View.GONE
            }
        }
    }

    // ---------- Getting it ready ----------

    private fun status(text: String, dot: Int, pulse: Boolean) {
        statusRow.visibility = View.VISIBLE
        statusText.text = text
        statusDot.background = ui.round(dot, 6)
        statusDot.animate().cancel()
        statusDot.alpha = 1f
        if (pulse && !Fx.lite) pulseDot()
    }

    private fun pulseDot() {
        statusDot.animate().alpha(0.25f).setDuration(700).withEndAction {
            statusDot.animate().alpha(1f).setDuration(700).withEndAction {
                if (statusText.text.endsWith("…")) pulseDot()
            }.start()
        }.start()
    }

    /**
     * Its sources, looked up behind as for Listem: what the screen says is what was found. With no
     * source add-on, nothing is said (the source page tells how to add one).
     */
    private fun prepare(p: Pick) {
        val ticket = ++searchFor
        statusRow.visibility = View.INVISIBLE
        val tv = TvAbility.of(this)
        try {
            worker.submit {
                val any = try { Housekeeping.hasSources(applicationContext) } catch (_: Exception) { false }
                if (!any) return@submit
                runOnUiThread {
                    if (!isFinishing && ticket == searchFor) status("Kaynaklar hazırlanıyor…", Color.argb(170, 255, 255, 255), pulse = true)
                }
                val found = try { Housekeeping.findSources(applicationContext, p) } catch (_: Exception) { emptyList() }
                val min = prefs.playMinRes
                val playable = found.count { it.tags.troubleOn(tv).isEmpty() && !it.tags.cinemaCopy && !it.tags.belowMin(min) }
                runOnUiThread {
                    if (isFinishing || isDestroyed || ticket != searchFor) return@runOnUiThread
                    when {
                        playable > 0 -> status("Gösterime hazır  ·  $playable kaynak", Color.parseColor("#34C759"), pulse = false)
                        found.any { it.tags.belowMin(min) } && found.all { it.tags.belowMin(min) || it.tags.troubleOn(tv).isNotEmpty() } ->
                            status("Yalnızca düşük kaliteli kaynak bulundu", Color.parseColor("#FFB020"), pulse = false)
                        found.isNotEmpty() -> status("Bulunan kaynaklar bu TV'de sorun çıkarabilir", Color.parseColor("#FFB020"), pulse = false)
                        else -> status("Şu an kaynak bulunamadı  ·  “Bu filmi izle”ye basınca yeniden aranır", Color.argb(140, 255, 255, 255), pulse = false)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** The source page plays it: with the sources just found it starts at once, from where it was left. */
    private fun play() {
        val p = film ?: return
        MediaSourcePickerActivity.open(this, p)
        finish()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
