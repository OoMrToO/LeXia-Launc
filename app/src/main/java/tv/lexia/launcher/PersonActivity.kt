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
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/**
 * An actor's or a director's page: photo, a few lines about them, and the films and series they
 * made (TMDB, in Turkish), best known first. A poster opens that film's page.
 */
class PersonActivity : Activity() {

    companion object {
        private const val NAME = "name"
        private const val DIRECTING = "directing"

        fun open(activity: Activity, name: String, directing: Boolean = false) {
            activity.startActivity(Intent(activity, PersonActivity::class.java).putExtra(NAME, name).putExtra(DIRECTING, directing))
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private lateinit var ui: Ui
    private val tr = Locale("tr", "TR")
    private lateinit var grid: LinearLayout
    private val playback by lazy { MediaPlaybackStore(this) }
    /** Titles watched to the end (İzlediklerim and the "İzledim" mark on a film's page). */
    private var watched: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val name = intent.getStringExtra(NAME).orEmpty()
        val directing = intent.getBooleanExtra(DIRECTING, false)
        val m = ui.match
        val w = ui.wrap
        val frame = FrameLayout(this)
        frame.background = ui.gradient(GradientDrawable.Orientation.TL_BR, Color.parseColor("#1A1F29"), Color.parseColor("#0A0B0E"), Color.BLACK)
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipChildren = false
        scroll.clipToPadding = false
        val page = ui.vbox()
        page.clipChildren = false
        page.clipToPadding = false
        page.setPadding(ui.u(110), ui.u(70), ui.u(110), ui.u(80))
        scroll.addView(page)
        frame.addView(scroll, ui.fl(m, m))

        val head = ui.hbox()
        head.clipChildren = false
        val photo = FrameLayout(this)
        photo.background = ui.round(C.CARD, 22)
        photo.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        photo.addView(img, ui.fl(m, m))
        head.addView(photo, ui.ll(ui.u(240), ui.u(360)))
        val info = ui.vbox()
        val title = ui.text(name, 54, C.TEXT, 700)
        title.maxLines = 2
        info.addView(title)
        val facts = ui.text(if (directing) "Yönetmen" else "Oyuncu", 21, C.TEXT2, 600)
        info.addView(facts, ui.ll(w, w, top = 10))
        val bio = ui.text("Hazırlanıyor…", 21, C.TEXT2, 400)
        bio.maxLines = 9
        bio.ellipsize = TextUtils.TruncateAt.END
        bio.setLineSpacing(0f, 1.25f)
        info.addView(bio, ui.ll(m, w, top = 22))
        head.addView(info, ui.ll(0, w, weight = 1f, left = 48))
        page.addView(head, ui.ll(m, w))

        val label = ui.text("FİLMLERİ VE DİZİLERİ", 16, C.TEXT3, 800)
        label.letterSpacing = 0.12f
        page.addView(label, ui.ll(w, w, top = 46, bottom = 18))
        grid = ui.vbox()
        grid.clipChildren = false
        grid.clipToPadding = false
        page.addView(grid, ui.ll(m, w, left = -10))
        setContentView(frame)
        Sound.attach(this)
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(D.MED).start()

        Thread {
            val person = try { TmdbDiscover.person(name, directing) } catch (e: Exception) { null }
            val works = person?.let { worksOf(it, directing) }.orEmpty()
            val seen = try {
                WatchHistory(applicationContext).entries().filter { it.finishedAt > 0 }.mapTo(HashSet()) { it.pick.key } + Prefs(applicationContext).watched
            } catch (e: Exception) { emptySet() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (person == null) {
                    bio.text = "Bu kişinin bilgisi şu an alınamadı."
                    return@runOnUiThread
                }
                person.optString("profile_path").takeIf { it.startsWith("/") }?.let { Img.load(this, "w500", it, ui.u(240), img) }
                facts.text = factsOf(person, directing)
                bio.text = person.optString("biography").ifBlank { "Hakkında bir yazı yok." }
                watched = seen
                show(works)
            }
        }.start()
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean =
        Pointer.handle(this, ev) || super.dispatchGenericMotionEvent(ev)

    /** "Oyuncu · 1974'te Los Angeles'ta doğdu" and the like. */
    private fun factsOf(o: JSONObject, directing: Boolean): String {
        val parts = ArrayList<String>()
        parts.add(when (o.optString("known_for_department")) {
            "Directing" -> "Yönetmen"
            "Writing" -> "Senarist"
            "Production" -> "Yapımcı"
            else -> if (directing) "Yönetmen" else "Oyuncu"
        })
        val born = o.optString("birthday").take(4)
        val place = o.optString("place_of_birth").substringBefore(",").trim()
        if (born.isNotBlank()) parts.add(if (place.isNotBlank()) "$born, $place" else "$born doğumlu")
        val died = o.optString("deathday").take(4)
        if (died.isNotBlank() && died != "null") parts.add("$died'de öldü")
        return parts.joinToString("  ·  ")
    }

    /** What they made (as an actor, or as a director), each title once, best known first, 42 at most. */
    private fun worksOf(o: JSONObject, directing: Boolean): List<Pick> {
        val credits = o.optJSONObject("combined_credits") ?: return emptyList()
        val list = ArrayList<Pair<Pick, Double>>()
        fun take(name: String, keep: (JSONObject) -> Boolean) {
            val a = credits.optJSONArray(name) ?: return
            for (i in 0 until a.length()) {
                val c = a.optJSONObject(i) ?: continue
                if (!keep(c)) continue
                // Talk shows and award nights are not what anyone means by their work.
                val ids = c.optJSONArray("genre_ids")
                if (ids != null && (0 until ids.length()).any { ids.optInt(it) in setOf(10763, 10767) }) continue
                val p = TmdbDiscover.pickOf(c, c.optString("media_type")) ?: continue
                list.add(p to c.optDouble("popularity", 0.0) * (1 + c.optInt("vote_count") / 1000.0))
            }
        }
        if (directing) take("crew") { it.optString("job") == "Director" || it.optString("job") == "Creator" }
        take("cast") { true }
        return list.sortedByDescending { it.second }.map { it.first }.distinctBy { "${it.kind}:${it.id}" }.take(42)
    }

    private fun show(works: List<Pick>) {
        grid.removeAllViews()
        if (works.isEmpty()) {
            grid.addView(ui.text("Film bulunamadı.", 22, C.TEXT2, 400))
            return
        }
        val cols = 7
        var row: LinearLayout? = null
        var first: View? = null
        works.forEachIndexed { i, p ->
            if (i % cols == 0) row = ui.hbox().also {
                it.clipChildren = false
                it.clipToPadding = false
                grid.addView(it, ui.ll(ui.match, ui.wrap, bottom = 30))
            }
            val c = card(p)
            if (first == null) first = c.tag as View
            row!!.addView(c)
        }
        first?.requestFocus()
    }

    private fun card(p: Pick): View {
        val cw = 200
        val col = ui.vbox()
        col.clipChildren = false
        val poster = FrameLayout(this)
        poster.isFocusable = true
        poster.isClickable = true
        poster.background = ui.round(C.CARD, D.CARD)
        poster.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        poster.addView(img, ui.fl(ui.match, ui.match))
        Img.load(this, "w342", p.poster, ui.u(cw), img)
        // Watched to the end: a small mark; left half way: the bar the home screen shows.
        val mark = WatchMarks.of(playback, p)
        if (p.key in watched || mark?.finished == true) addSeen(poster) else WatchMarks.add(ui, poster, mark)
        val fx = Fx.decorate(ui, poster, D.CARD)
        val title = ui.text(p.title, 19, C.TEXT2, 500)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        val score = TmdbDiscover.score["${p.kind}:${p.id}"]
        val sub = ui.text(listOfNotNull(p.year.takeIf { it.isNotBlank() }, if (p.isSeries) "Dizi" else null,
            score?.takeIf { it > 0 }?.let { String.format(tr, "TMDB %.1f", it) }).joinToString("  ·  "), 16, C.TEXT3, 500)
        sub.maxLines = 1
        poster.setOnFocusChangeListener { _, f ->
            ui.lift(poster, f, 1.08f, 30)
            fx(f)
            title.setTextColor(if (f) C.TEXT else C.TEXT2)
        }
        poster.setTag(R.id.lexia_pick, p)
        poster.setOnClickListener { DetailActivity.open(this, p, row = DetailActivity.rowAround(poster)) }
        col.addView(poster, ui.ll(ui.u(cw), ui.u(cw * 3 / 2)))
        col.addView(title, ui.ll(ui.u(cw), ui.wrap, top = 12))
        col.addView(sub, ui.ll(ui.u(cw), ui.wrap, top = 2))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 28)
        col.tag = poster
        return col
    }

    /** "İzledin", top right of the poster. */
    private fun addSeen(poster: FrameLayout) {
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = ui.round(Color.argb(215, 10, 12, 18), 8)
        pill.setPadding(ui.u(7), ui.u(3), ui.u(9), ui.u(3))
        val tick = ImageView(this)
        tick.setImageResource(R.drawable.ic_check)
        tick.setColorFilter(C.TEXT)
        pill.addView(tick, ui.ll(ui.u(14), ui.u(14)))
        pill.addView(ui.text("İzledin", 13, C.TEXT, 700), ui.ll(ui.wrap, ui.wrap, left = 4))
        val lp = ui.fl(ui.wrap, ui.wrap, Gravity.TOP or Gravity.END)
        lp.setMargins(0, ui.u(10), ui.u(10), 0)
        poster.addView(pill, lp)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
