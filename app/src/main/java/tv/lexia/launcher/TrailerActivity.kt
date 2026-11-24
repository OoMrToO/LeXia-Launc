package tv.lexia.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C as MediaC
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

/**
 * A trailer inside LeXia, full screen, run with the remote. Two players, tried in this order:
 *
 *  1. LeXia's own player on the trailer's streams ([YouTubeStreams]): nothing of YouTube's page on
 *     screen. Unofficial, so it can stop working when YouTube changes something
 *     (Ayarlar › Oynatma ve Takip › "Fragmanlar YouTube çerçevesi olmadan oynasın").
 *  2. YouTube's own embedded player in a web view, whenever the first finds nothing, is too slow
 *     or fails while playing; the trailer carries on from where it was.
 *
 * When neither plays (the studio turned embedding off, no web view), the YouTube app opens.
 * Can be switched off in Ayarlar › Filmler › "Fragmanlar LeXia'da oynasın".
 */
@OptIn(UnstableApi::class)
class TrailerActivity : Activity() {

    companion object {
        /** Where the subtitles sit with the controls away: a little above the bottom edge. */
        private const val SUBTITLE_LOW = 0.09f
        /** How strongly the film's colours glow around a wide trailer. */
        private const val GLOW_ALPHA = 0.45f
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_LIST = "list"
        private const val EXTRA_TITLES = "titles"

        /** LeXia's own player failed this many times in a row; after two it rests for half an hour. */
        private var nativeFailures = 0
        private var nativeOffUntil = 0L

        /** The films of the trailers being opened (same order), for the menu and the end card; read once. */
        private var pendingFilms: List<Pick> = emptyList()

        /** Opens the trailer of [p], with its film known (Listeme ekle, Çıkınca haber ver, Detaylar). */
        /**
         * The film's trailer (the dubbed one first). [at] is a trailer already playing behind the
         * page (its key and second): it carries on from there instead of starting again.
         */
        fun open(a: Activity, p: Pick, at: Pair<String, Int>? = null) {
            pendingFilms = listOf(p)
            val key = at?.first?.takeIf { ID.matches(it) } ?: bestTrailer(p)
            pendingStart = if (at != null && at.first == key) at.second else 0
            open(a, key, p.title)
        }

        /** The second the next trailer page starts at (a preview carried on); read once. */
        @Volatile private var pendingStart = 0

        /** A film's trailer to show first: Turkish-dubbed, then the film's main one. */
        /** The film's trailer in Turkish first: dubbed, then with Turkish subtitles in its picture, then its own. */
        fun bestTrailer(p: Pick): String = turkishTrailer(p) ?: p.trailer

        /** The film's Turkish-dubbed trailer, else its Turkish-subtitled one; null when it has neither. */
        fun turkishTrailer(p: Pick): String? {
            val tr = java.util.Locale("tr", "TR")
            fun label(v: Video) = v.label.lowercase(tr)
            return (p.trailers.firstOrNull { v -> "dubla" in label(v) && "altyaz" !in label(v) && ID.matches(v.key) }
                ?: p.trailers.firstOrNull { v -> "altyaz" in label(v) && ID.matches(v.key) })?.key
        }

        /** A marathon of these films' trailers (each film's dubbed one first). */
        fun marathon(a: Activity, films: List<Pick>) {
            val ok = films.filter { ID.matches(it.trailer) }
            pendingFilms = ok
            marathon(a, ok.map { bestTrailer(it) }, ok.map { it.title })
        }

        /** Several trailers one after another ("Fragman maratonu"); a trailer that cannot play is skipped. */
        fun marathon(a: Activity, ids: List<String>, titles: List<String>) {
            val ok = ids.indices.filter { ID.matches(ids[it]) }
            if (ok.isEmpty()) return
            a.startActivity(
                Intent(a, TrailerActivity::class.java)
                    .putExtra(EXTRA_ID, ids[ok[0]]).putExtra(EXTRA_TITLE, titles.getOrElse(ok[0]) { "" })
                    .putStringArrayListExtra(EXTRA_LIST, ArrayList(ok.map { ids[it] }))
                    .putStringArrayListExtra(EXTRA_TITLES, ArrayList(ok.map { titles.getOrElse(it) { "" } }))
            )
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        private val ID = Regex("^[A-Za-z0-9_-]{6,20}$")

        /** Opens a trailer: inside LeXia when the setting is on (Ayarlar › Filmler), otherwise in the YouTube app. */
        fun open(a: Activity, id: String, title: String) {
            if (!ID.matches(id)) {
                Glass.toast(a, "Fragman bulunamadı")
                return
            }
            val prefs = Prefs(a)
            if (prefs.trailerInApp) {
                a.startActivity(Intent(a, TrailerActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title))
                a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            } else {
                pendingFilms = emptyList()
                youtube(a, id)
            }
        }

        /** "Netflix'i", "Disney+'ı", "Prime Video'yu", "MUBI'yi", "Apple TV'yi": the name as an object. */
        fun accusative(name: String): String {
            val n = name.trim()
            if (n.endsWith("+")) return "$n'ı"
            if (n.uppercase().endsWith("TV")) return "$n'yi"
            val low = n.lowercase(java.util.Locale("tr", "TR"))
            val v = low.lastOrNull { it in "aıeiouöü" } ?: 'e'
            val ending = when (v) { 'a', 'ı' -> "ı"; 'e', 'i' -> "i"; 'o', 'u' -> "u"; else -> "ü" }
            return if (low.last() in "aıeiouöü") "$n'y$ending" else "$n'$ending"
        }

        /** "Netflix'te", "Prime Video'da", "Disney+'ta", "Apple TV'de", "MUBI'de": the name with -de/-da. */
        fun locative(name: String): String {
            val n = name.trim()
            val tr = java.util.Locale("tr", "TR")
            if (n.endsWith("+")) return "$n'ta"
            if (n.uppercase(tr).endsWith("TV")) return "$n'de"
            val low = n.lowercase(tr)
            val back = (low.lastOrNull { it in "aıeiouöü" } ?: 'e') in "aıou"
            val hard = low.last() in "çfhkpsştx"
            return "$n'" + (if (hard) "t" else "d") + (if (back) "a" else "e")
        }

        fun youtube(ctx: Context, id: String) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Glass.toast(ctx, "Fragman açılamadı")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var webLoaded = false
    /** LeXia's own player and its picture; made the first time a trailer plays in it. */
    private var exo: ExoPlayer? = null
    private var playerView: PlayerView? = null
    /** The controls' block (story, buttons, bar): the subtitles rise above it while it shows. */
    private var infoFoot: View? = null
    private var subtitleLift = SUBTITLE_LOW
    private var subtitleAnim: android.animation.ValueAnimator? = null
    /** The trailer on screen plays in LeXia's own player (not the web view). */
    private var nativeMode = false
    private var nativeStarted = false
    private var resumeOnReturn = false
    private var volumeFade: android.animation.ValueAnimator? = null
    /** Each search for streams gets a number; an answer to an older search is dropped. */
    private var findGeneration = 0
    private var pendingStart = 0
    private val finder = Executors.newCachedThreadPool()
    private val maxHeight by lazy {
        @Suppress("DEPRECATION")
        val tallest = windowManager.defaultDisplay.supportedModes.maxOfOrNull { it.physicalHeight } ?: 1080
        if (Fx.lite) 720 else if (tallest >= 2000) 2160 else 1080
    }
    /** HDR trailers on an HDR screen (the player draws on a surface, which shows HDR). */
    private val hdr by lazy { !Fx.lite && TrailerDecoders.screenHdr(this) }
    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var frame: FrameLayout

    // Controls, drawn by LeXia over YouTube's player (see the design: title top left, buttons and bar below).
    /** Everything shown on a key press or while paused; fades after four seconds of playing. */
    private lateinit var info: FrameLayout
    private lateinit var infoLabel: TextView
    private lateinit var infoTitle: TextView
    /** The film's title artwork, instead of its name when there is one. */
    private lateinit var infoLogo: ImageView
    /** Two lines of its story. */
    private lateinit var infoStory: TextView
    /** The trailer has subtitles (native player): "Altyazı" turns them on and off. */
    private var hasSubtitles = false
    private var subtitlesOn = true
    private lateinit var infoMeta: LinearLayout
    private lateinit var buttons: LinearLayout
    private lateinit var nextCard: LinearLayout
    private lateinit var nextThumb: ImageView
    private lateinit var nextTitle: TextView
    private lateinit var track: FrameLayout
    private lateinit var barFill: View
    private lateinit var knob: View
    private lateinit var tip: TextView
    private lateinit var timeLeft: TextView
    private lateinit var hint: TextView
    /** Big play sign in a glass circle while paused. */
    private lateinit var center: TextView
    /** Paused: the film's logo (or name) alone in the middle, no controls (as on the home screen). */
    private lateinit var pauseBox: LinearLayout
    private lateinit var pauseLogo: ImageView
    private lateinit var pauseTitle: TextView
    /** "+30 sn" while skipping with the arrows held. */
    private lateinit var bubble: TextView
    /** Seek target shown in the player HUD; real frame thumbnails need source-side indexes. */
    private var seekTargetSeconds = -1L
    private lateinit var status: TextView
    /** Black over the player until it plays and while paused: hides YouTube's title, logo and "more videos". */
    private lateinit var shield: View
    /** Two black halves that part like a cinema curtain when the first trailer starts, and close at the end. */
    private lateinit var curtainTop: View
    private lateinit var curtainLow: View
    private var curtainOpen = false
    /** The cast on the right, from "Oyuncular"; the trailer keeps playing. */
    private var castPanel: View? = null
    /** The film's own colour on the bar and knob. */
    private var barColor = Color.WHITE
    /** Top left as a trailer starts: age, genre, language, content warnings. */
    private lateinit var guide: TrailerAmbience.Guide
    /** The green card before the first trailer. */
    private lateinit var card: TrailerAmbience.Card
    /** The film's colours behind the picture, glowing in a wide trailer's black bands. */
    private lateinit var glow: ImageView
    /** What the guide says about the film on screen (found off the main thread), and for which trailer. */
    private var guideFacts: List<Pair<String, String>>? = null
    private var guideFactsFor = ""
    /** The trailer whose guide was shown (once per trailer), and until when it may still come. */
    private var guideShownFor = ""
    private var guideDueUntil = 0L
    /** "Filmi izle" closed the curtain: it opens again when the viewer comes back. */
    private var curtainClosedForFilm = false

    private var id = ""
    private var currentTitle = ""
    private var playing = false
    private var paused = false
    private var done = false
    /** Marathon: every trailer and its title, and which one is on. */
    private var list: List<String> = emptyList()
    private var titles: List<String> = emptyList()
    private var at = 0
    /** The films being played, when the opener gave them (same order as [list]). */
    private var films: List<Pick> = emptyList()
    /** The remote is on the buttons (▲); otherwise its keys run the trailer. */
    private var onButtons = false
    private var skipSum = 0
    /** Where the trailer is, 0..1, for the bar. */
    private var progress = 0f
    /** When a trailer ends: what to do next, instead of closing. */
    private var endCard: View? = null
    /** The end card's primary action follows the current film's accent colour. */
    private var accentAction: TextView? = null
    /** The controls' upper floor (story and buttons): it slides up with ▲ and down with ▼. */
    private lateinit var tray: LinearLayout
    /** On the lower floor, left above the bar: the film's small logo (or name) while the tray is down. */
    private lateinit var mini: FrameLayout
    private lateinit var miniLogo: ImageView
    private lateinit var miniTitle: TextView
    /** "︿" over the bar: there is more above. */
    private lateinit var chevron: View
    private var trayOpen = false
    /** Icon-only buttons: what they show while not selected (an icon, or a short sign such as "CC"). */
    private val compactGlyph = HashMap<TextView, String>()
    private val scrubDone = Runnable {
        knob.animate().alpha(0f).setDuration(D.MED).start()
        tip.animate().alpha(0f).setDuration(D.MED).start()
    }
    private var countdown: Runnable? = null

    private val skipEnd = Runnable { skipSum = 0; bubble.animate().alpha(0f).setDuration(D.MED).start() }
    /** Nothing played after this long: the YouTube app takes over (or the next trailer). */
    private val giveUp = Runnable {
        if (!playing) {
            if (nativeMode) giveUpOnNative() else if (list.size > 1) nextTrailer() else fallBack()
        }
    }
    /** The streams took too long to find: the embedded player starts instead. */
    private val nativeTooSlow = Runnable {
        nativeFailed()
        playInWeb(pendingStart)
    }
    private val nativeListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!nativeMode) return
            if (isPlaying) {
                if (!nativeStarted) {
                    nativeStarted = true
                    nativeFailures = 0
                    fadeVolume(1f, 1600)
                }
                onPlaying()
            } else {
                val p = exo ?: return
                if (!p.playWhenReady && p.playbackState == Player.STATE_READY) onPaused()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (nativeMode && playbackState == Player.STATE_ENDED) showEnd()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (nativeMode) giveUpOnNative()
        }
    }
    /**
     * The controls go down while the trailer plays; left on the buttons, they go too after a
     * longer while (TV photo, 24 Kasım: the panel stayed up), and the first key brings them back.
     */
    private val hideInfo = Runnable {
        if (endCard != null || castPanel != null || (paused && onButtons)) return@Runnable
        if (onButtons) {
            onButtons = false
            currentFocus?.clearFocus()
            setButtonsFocusable(false)
        }
        info.animate().alpha(0f).setDuration(D.MED).withEndAction { if (!onButtons) setTray(false) }.start()
        // Paused: the controls give the middle back to the film's logo.
        if (paused) showPauseLogo()
    }
    /** While the controls are on screen, the time and bar follow the trailer twice a second. */
    private val tick = object : Runnable {
        override fun run() {
            if (info.alpha > 0f) readTime()
            placeSubtitles()
            handler.postDelayed(this, 500)
        }
    }

    private fun shieldTo(a: Float) {
        shield.animate().cancel()
        shield.animate().alpha(a).setDuration(D.MED).start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra(EXTRA_ID) ?: ""
        prefs = Prefs(this)
        films = pendingFilms
        pendingFilms = emptyList()
        // Which films are only in cinemas: their "Filmi izle" does not show.
        Thread {
            cinemaNow = DetailActivity.cinemaLists(applicationContext)?.first ?: emptySet()
            runOnUiThread {
                if (!isFinishing && !isDestroyed && ::buttons.isInitialized && buttons.findFocus() == null) bindFilm()
            }
        }.start()
        // A film's main trailer asked for: its Turkish-dubbed one first, when it has one.
        films.firstOrNull { it.trailer == id }?.let { id = bestTrailer(it) }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        list = intent.getStringArrayListExtra(EXTRA_LIST)?.filter { ID.matches(it) } ?: emptyList()
        titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: emptyList()
        if (!ID.matches(id)) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = Ui(this)
        frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        frame.clipChildren = false
        glow = TrailerAmbience.glowView(ui, frame)

        shield = View(this)
        shield.setBackgroundColor(Color.BLACK)
        frame.addView(shield, ui.fl(ui.match, ui.match))
        val half = resources.displayMetrics.heightPixels / 2 + 2
        curtainTop = View(this)
        curtainTop.setBackgroundColor(Color.BLACK)
        frame.addView(curtainTop, ui.fl(ui.match, half, Gravity.TOP))
        curtainLow = View(this)
        curtainLow.setBackgroundColor(Color.BLACK)
        frame.addView(curtainLow, ui.fl(ui.match, half, Gravity.BOTTOM))

        center = ui.text("▶", 56, Color.WHITE, 400)
        center.gravity = Gravity.CENTER
        center.setPadding(ui.u(12), 0, 0, 0)
        center.background = ui.round(Color.argb(60, 255, 255, 255), 75, 1, Color.argb(70, 255, 255, 255))
        center.elevation = ui.uf(8)
        center.alpha = 0f
        val clp = ui.fl(ui.u(150), ui.u(150), Gravity.CENTER)
        clp.bottomMargin = ui.u(130)
        frame.addView(center, clp)

        pauseBox = ui.vbox()
        pauseBox.gravity = Gravity.CENTER_HORIZONTAL
        pauseLogo = ImageView(this)
        pauseLogo.adjustViewBounds = true
        pauseLogo.maxWidth = ui.u(900)
        pauseLogo.maxHeight = ui.u(330)
        pauseLogo.scaleType = ImageView.ScaleType.FIT_CENTER
        pauseBox.addView(pauseLogo, ui.ll(ui.wrap, ui.wrap))
        pauseTitle = ui.text("", 72, C.TEXT, 800)
        pauseTitle.gravity = Gravity.CENTER
        pauseTitle.maxWidth = ui.u(1300)
        pauseTitle.maxLines = 2
        pauseTitle.setShadowLayer(ui.uf(30), 0f, ui.uf(4), Color.argb(150, 0, 0, 0))
        pauseBox.addView(pauseTitle, ui.ll(ui.wrap, ui.wrap))
        val pauseHint = ui.text("OK: devam et   ·   ▲ seçenekler", 20, C.TEXT2, 600)
        pauseHint.letterSpacing = 0.04f
        pauseBox.addView(pauseHint, ui.ll(ui.wrap, ui.wrap, top = 34))
        pauseBox.alpha = 0f
        pauseBox.visibility = View.GONE
        frame.addView(pauseBox, ui.fl(ui.wrap, ui.wrap, Gravity.CENTER))

        bubble = ui.text("", 30, C.INK, 800)
        bubble.gravity = Gravity.CENTER
        bubble.setPadding(ui.u(26), ui.u(10), ui.u(26), ui.u(10))
        bubble.background = ui.round(Color.WHITE, 30)
        bubble.alpha = 0f
        frame.addView(bubble, ui.fl(ui.wrap, ui.wrap, Gravity.CENTER))

        status = ui.text(if (title.isEmpty()) "Fragman yükleniyor…" else "$title  ·  fragman yükleniyor…", 26, C.TEXT2, 500)
        frame.addView(status, ui.fl(ui.wrap, ui.wrap, Gravity.CENTER))

        currentTitle = title
        buildControls()
        guide = TrailerAmbience.Guide(ui, frame)
        card = TrailerAmbience.Card(ui, frame)
        setContentView(frame)
        // No card before the first trailer (the viewer asked): the guide at the top left says it all.
        bindFilm()
        showInfo()
        handler.post(tick)

        // Opened from a trailer already playing behind the page: it carries on, no question asked.
        val goOn = pendingStart.also { pendingStart = 0 }
        // A trailer left half way: carry on from there, or start again (a marathon always starts at the top).
        val saved = if (list.size <= 1) prefs.trailerAt[id] ?: 0 else 0
        if (goOn > 0 && savedInstanceState == null) {
            startTrailer(goOn)
        } else if (saved >= 8) {
            Dialogs.list(
                this, "Kaldığın yerden?", listOf("Kaldığın yerden devam et" to clock(saved.toDouble()), "Baştan başla" to ""),
                subtitle = title, icon = R.drawable.ic_film, cancel = { startTrailer(0) }
            ) { i -> startTrailer(if (i == 0) saved else 0) }
        } else {
            startTrailer(0)
        }
    }

    // ---------- Which player ----------

    private fun nativeAllowed() = prefs.trailerNative && System.currentTimeMillis() >= nativeOffUntil

    private fun nativeFailed() {
        if (++nativeFailures >= 2) {
            nativeFailures = 0
            nativeOffUntil = System.currentTimeMillis() + 30 * 60_000L
        }
    }

    /** Starts the trailer on screen ([id]) at [startSec]: LeXia's own player first, the embedded one otherwise. */
    private fun startTrailer(startSec: Int) {
        if (done || isFinishing) return
        handler.removeCallbacks(giveUp)
        handler.removeCallbacks(nativeTooSlow)
        // Another trailer was playing here (the next of a marathon, a second trailer of the film):
        // it goes quiet now, not when the new one has been found.
        if (nativeMode) {
            volumeFade?.cancel()
            exo?.stop()
        }
        if (!nativeAllowed()) {
            playInWeb(startSec)
            return
        }
        val wanted = id
        val mine = ++findGeneration
        pendingStart = startSec
        handler.postDelayed(nativeTooSlow, 8_000)
        finder.execute {
            val source = YouTubeStreams.find(wanted, maxHeight, TrailerDecoders::canDecode, hdr)
            runOnUiThread {
                if (mine != findGeneration || done || isFinishing || wanted != id) return@runOnUiThread
                handler.removeCallbacks(nativeTooSlow)
                if (source == null) {
                    nativeFailed()
                    playInWeb(startSec)
                } else {
                    playNative(source, startSec)
                }
            }
        }
    }

    /** LeXia's own player could not play this trailer: the embedded one carries on from the same place. */
    private fun giveUpOnNative() {
        val at = ((exo?.currentPosition ?: 0L) / 1000L).toInt()
        YouTubeStreams.forget(id)
        nativeFailed()
        playInWeb(if (nativeStarted) at else pendingStart)
    }

    /** What the trailer on screen was found to be, once its streams are known (its YouTube title, its dubs). */
    private var shownSource: TrailerSource? = null

    /**
     * The trailer's language: "dub" Turkish-dubbed, "sub" Turkish subtitles (in the picture),
     * "orig" original sound, "tr" Turkish but not known which, "" unknown. The video itself is
     * believed first (a Turkish sound track, its YouTube title), then the catalogue label: TMDB
     * sometimes calls a subtitled trailer dubbed, and "Türkçe altyazılı" is not a dub.
     */
    private fun trailerLanguage(): String {
        val tr = java.util.Locale("tr", "TR")
        val source = shownSource
        if (source?.turkishAudio == true) return "dub"
        val title = source?.title?.lowercase(tr).orEmpty()
        if ("dubla" in title) return "dub"
        if ("altyaz" in title || "subtitle" in title) return "sub"
        val label = film()?.trailers?.firstOrNull { it.key == id }?.label?.lowercase(tr) ?: return ""
        return when {
            "altyaz" in label || "subtitled" in label -> "sub"
            "dubla" in label -> "dub"
            "orijinal" in label || "original" in label -> "orig"
            "türkçe" in label || "turkish" in label -> "tr"
            else -> ""
        }
    }

    /** The trailer on screen is Turkish-dubbed: then no subtitles. */
    private fun currentTrailerDubbed(): Boolean = trailerLanguage() == "dub"

    /** Films whose trailer was already changed for a Turkish one (or chosen by hand): once is enough. */
    private val switchedFor = HashSet<String>()

    private fun playNative(source: TrailerSource, startSec: Int) {
        // Now the video itself is known: its badge says what it really is.
        val before = trailerLanguage()
        shownSource = source
        // An original trailer with no subtitles at all: the film's dubbed or Turkish-subtitled one
        // plays instead, once, with a short word.
        val f = film()
        val lang = trailerLanguage()
        if (source.turkishSubtitles == null && startSec == 0 && lang != "dub" && lang != "sub" && lang != "tr" &&
            f != null && switchedFor.add(f.key)) {
            val alt = turkishTrailer(f)
            if (alt != null && alt != id) {
                id = alt
                shownSource = null
                Glass.toast(this, "Türkçe fragmana geçildi")
                startTrailer(0)
                return
            }
        }
        if (trailerLanguage() != before && buttons.findFocus() == null) bindFilm()
        web?.let {
            it.evaluateJavascript("try{if(player)player.stopVideo();}catch(x){}", null)
            it.visibility = View.INVISIBLE
        }
        val view = playerView ?: PlayerView(this).also { v ->
            v.useController = false
            v.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            v.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            // Clear around the picture: the film's colours glow there (wide trailers' bands).
            v.setBackgroundColor(Color.TRANSPARENT)
            v.isFocusable = false
            v.descendantFocusability = FrameLayout.FOCUS_BLOCK_DESCENDANTS
            v.subtitleView?.apply {
                TrailerCaptions.style(this, ui)
                setBottomPaddingFraction(subtitleLift)
            }
            // Under the black shield and the controls, where the web view sits too.
            frame.addView(v, frame.indexOfChild(shield), ui.fl(ui.match, ui.match))
            playerView = v
        }
        view.visibility = View.VISIBLE
        val player = exo ?: ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(MediaC.USAGE_MEDIA).setContentType(MediaC.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .build().also { p ->
                p.addListener(nativeListener)
                // Subtitles only in Turkish: a Turkish track if the trailer has one, otherwise none.
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setPreferredTextLanguage("tr")
                    .setTrackTypeDisabled(MediaC.TRACK_TYPE_TEXT, !subtitlesOn).build()
                exo = p
            }
        view.player = player

        val hints = HashMap<String, SubtitleHint>()
        val item = MediaItem.Builder().setUri(source.video)
        if (source.hls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        // A Turkish-dubbed trailer needs no subtitles, even if YouTube offers a translated track.
        val subtitleAddress = source.turkishSubtitles?.takeUnless { currentTrailerDubbed() }
        val hadSubtitles = hasSubtitles
        hasSubtitles = subtitleAddress != null
        if (hadSubtitles != hasSubtitles && buttons.findFocus() == null) bindFilm()
        subtitleAddress?.let { address ->
            val uri = Uri.parse(address)
            hints[uri.toString()] = SubtitleHint("tr", SubtitleText.VTT)
            item.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(SubtitleText.VTT)
                        .setLanguage("tr")
                        .setSelectionFlags(MediaC.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        val sources = DefaultMediaSourceFactory(
            SubtitleFixDataSource.Factory(YouTubeChunkedDataSource.Factory(source.userAgent), hints)
        )
        val picture = sources.createMediaSource(item.build())
        val media = if (source.audio != null) {
            MergingMediaSource(picture, sources.createMediaSource(MediaItem.fromUri(source.audio)))
        } else {
            picture
        }
        nativeMode = true
        nativeStarted = false
        pendingStart = startSec
        volumeFade?.cancel()
        player.volume = 0f
        player.setMediaSource(media, startSec * 1000L)
        player.prepare()
        player.playWhenReady = true
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, 15000)
    }

    private fun stopNative() {
        nativeMode = false
        volumeFade?.cancel()
        exo?.let {
            it.stop()
            it.clearMediaItems()
        }
        playerView?.visibility = View.GONE
    }

    private fun fadeVolume(to: Float, ms: Long) {
        val p = exo ?: return
        volumeFade?.cancel()
        volumeFade = android.animation.ValueAnimator.ofFloat(p.volume, to).apply {
            duration = ms
            addUpdateListener { exo?.volume = it.animatedValue as Float }
            start()
        }
    }

    /** YouTube's embedded player takes the trailer on screen, from [startSec] when it is its first. */
    private fun playInWeb(startSec: Int) {
        // An answer of the stream search still on its way is no longer wanted.
        findGeneration++
        handler.removeCallbacks(nativeTooSlow)
        stopNative()
        val w = ensureWeb()
        if (w == null) {
            fallBack()
            return
        }
        w.visibility = View.VISIBLE
        if (!webLoaded) {
            load(w, startSec)
        } else {
            w.evaluateJavascript("noSubs=${currentTrailerDubbed()};play('$id')", null)
            handler.removeCallbacks(giveUp)
            handler.postDelayed(giveUp, 15000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb(): WebView? {
        web?.let { return it }
        // Some TVs have no working web view; then the YouTube app it is.
        val w = try {
            WebView(this)
        } catch (e: Throwable) {
            null
        }
        if (w == null) return null
        web = w
        w.setBackgroundColor(Color.BLACK)
        w.isFocusable = false
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.setSupportZoom(false)
        w.webViewClient = object : WebViewClient() {
            // Links inside the player (YouTube logo, "watch on YouTube") open the YouTube app instead.
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                fallBack()
                return true
            }
        }
        // The page reports back through its title: "p" playing, "pause", "end" finished, "e:<code>" error.
        w.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, t: String?) {
                // The trailer moved to LeXia's own player: what the page still reports is stale.
                if (nativeMode) return
                when {
                    t == "p" -> onPlaying()
                    t == "pause" -> onPaused()
                    t == "end" -> showEnd()
                    t != null && t.startsWith("e:") -> if (list.size > 1 && at < list.size - 1) nextTrailer() else fallBack()
                }
            }
        }
        frame.addView(w, 0, ui.fl(ui.match, ui.match))
        return w
    }

    /**
     * YouTube's own iframe player. The page address is LeXia's site, so YouTube knows where it is embedded.
     * The frame is taller than the screen (same width): the video keeps the full width with no zoom, while
     * YouTube's title (frame top) and logo and "more videos" (frame bottom) fall off the screen.
     * A marathon keeps a second, hidden player with the next trailer ready, so the next one starts at once.
     * The sound rises softly at the start and falls at the end.
     */
    private fun load(w: WebView, start: Int) {
        if (done || isFinishing) return
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}.f{position:fixed;left:0;top:-20vh;width:100vw;height:140vh;pointer-events:none}.f iframe{width:100%;height:100%;border:0}</style>
            </head><body><div class="f" id="w0"><div id="p0"></div></div><div class="f" id="w1" style="visibility:hidden"><div id="p1"></div></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
            var P=[null,null],cur=0,cued=['',''],ready=[false,false],player=null,fading=null,faded=false;
            var PV={autoplay:0,controls:0,rel:0,playsinline:1,iv_load_policy:3,modestbranding:1,fs:0,disablekb:1,cc_load_policy:0,cc_lang_pref:'tr',hl:'tr'};
            function mk(i,v,start){
              var pv={};for(var k in PV)pv[k]=PV[k];if(start>0)pv.start=start;
              P[i]=new YT.Player('p'+i,{videoId:v,playerVars:pv,
                events:{
                  onReady:function(e){ready[i]=true;subs(e.target);
                    if(i===cur){player=e.target;e.target.setVolume(0);e.target.playVideo();}
                    else if(cued[i]&&cued[i]!==v){e.target.cueVideoById(cued[i]);}},
                  onApiChange:function(e){subs(e.target);},
                  onStateChange:function(e){if(i!==cur)return;
                    if(e.data===1){document.title='p';if(!faded){faded=true;fadeTo(100,1600);}setTimeout(function(){subs(e.target);},400);setTimeout(function(){subs(e.target);},2500);}
                    else if(e.data===0)document.title='end';else if(e.data===2)document.title='pause';},
                  onError:function(e){if(i===cur)document.title='e:'+e.data;}
                }});
            }
            function onYouTubeIframeAPIReady(){mk(0,'$id',$start);}
            // Subtitles only in Turkish: a Turkish track if the video has one, otherwise none at all.
            // A Turkish-dubbed trailer: no subtitles at all (noSubs, set by the app for each trailer).
            var noSubs=${currentTrailerDubbed()};
            function subs(pl){try{if(!pl||!pl.getOption)return;var l=noSubs?[]:(pl.getOption('captions','tracklist')||[]);var t=null;
              for(var i=0;i<l.length;i++){if(String(l[i].languageCode||'').indexOf('tr')===0){t=l[i];break;}}
              if(t){pl.setOption('captions','track',{languageCode:t.languageCode});}
              else{pl.setOption('captions','track',{});pl.unloadModule('captions');pl.unloadModule('cc');}}catch(x){}}
            function fadeTo(t,ms){clearInterval(fading);var p=player;if(!p||!p.setVolume)return;var v=0;try{v=p.getVolume();}catch(x){}
              var n=Math.max(1,Math.round(ms/50)),d=(t-v)/n,k=0;
              fading=setInterval(function(){k++;v+=d;try{p.setVolume(Math.max(0,Math.min(100,Math.round(v))));}catch(x){}if(k>=n)clearInterval(fading);},50);}
            function toggle(){if(!player)return;if(player.getPlayerState()===1)player.pauseVideo();else player.playVideo();}
            function seek(s){if(!player)return;player.seekTo(Math.max(0,player.getCurrentTime()+s),true);}
            function play(v){document.title='';faded=false;clearInterval(fading);var o=1-cur;
              if(P[o]&&ready[o]&&cued[o]===v){try{player.stopVideo();}catch(x){}
                document.getElementById('w'+cur).style.visibility='hidden';document.getElementById('w'+o).style.visibility='visible';
                cur=o;player=P[o];cued[o]='';player.setVolume(0);player.playVideo();subs(player);}
              else{if(!player)return;player.setVolume(0);player.loadVideoById(v);}}
            function prep(v){var o=1-cur;if(cued[o]===v)return;cued[o]=v;if(!P[o]){mk(o,v,0);}else if(ready[o]){P[o].cueVideoById(v);}}
            </script></body></html>
        """.trimIndent()
        webLoaded = true
        w.loadDataWithBaseURL(Net.OLD_SITE + "/", html, "text/html", "utf-8", null)
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, 15000)
    }

    // ---------- Controls ----------

    private fun buildControls() {
        info = FrameLayout(this)
        info.clipChildren = false
        val top = View(this)
        top.background = ui.gradient(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, Color.argb(150, 0, 0, 0), Color.TRANSPARENT)
        info.addView(top, ui.fl(ui.match, ui.u(300), Gravity.TOP))
        val low = View(this)
        low.background = ui.gradient(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, Color.argb(215, 0, 0, 0), Color.TRANSPARENT)
        info.addView(low, ui.fl(ui.match, ui.u(470), Gravity.BOTTOM))

        // Bottom left, over the buttons: the film's logo (or name), facts and story, as on Apple TV.
        val head = ui.vbox()
        infoLabel = ui.label("")
        head.addView(infoLabel)
        infoLogo = ImageView(this)
        infoLogo.scaleType = ImageView.ScaleType.FIT_START
        infoLogo.adjustViewBounds = true
        infoLogo.visibility = View.GONE
        head.addView(infoLogo, ui.ll(ui.u(560), ui.u(150), top = 14))
        infoTitle = ui.text("", 58, C.TEXT, 800)
        infoTitle.letterSpacing = -0.02f
        infoTitle.maxLines = 1
        infoTitle.ellipsize = TextUtils.TruncateAt.END
        head.addView(infoTitle, ui.ll(ui.u(1300), ui.wrap, top = 10))
        infoMeta = ui.hbox()
        head.addView(infoMeta, ui.ll(ui.wrap, ui.wrap, top = 16))
        infoStory = ui.text("", 22, Color.argb(215, 255, 255, 255), 400)
        infoStory.maxLines = 2
        infoStory.ellipsize = TextUtils.TruncateAt.END
        infoStory.setLineSpacing(0f, 1.2f)
        head.addView(infoStory, ui.ll(ui.u(1050), ui.wrap, top = 14))

        // Bottom, two floors (as on Apple TV): above, the story and the buttons, which slide up with
        // ▲ and down with ▼; below, only the film's small logo, the bar and the times.
        val foot = ui.vbox()
        foot.clipChildren = false
        foot.clipToPadding = false
        tray = ui.vbox()
        tray.clipChildren = false
        tray.clipToPadding = false
        tray.addView(head, ui.ll(ui.match, ui.wrap, bottom = 30))
        val row = ui.hbox()
        row.clipChildren = false
        row.clipToPadding = false
        row.gravity = Gravity.CENTER_VERTICAL
        buttons = ui.hbox()
        buttons.clipChildren = false
        buttons.clipToPadding = false
        buttons.gravity = Gravity.CENTER_VERTICAL
        // A selected icon button opens to show its name: the row makes room smoothly.
        buttons.layoutTransition = android.animation.LayoutTransition().apply {
            enableTransitionType(android.animation.LayoutTransition.CHANGING)
            setDuration(220)
        }
        row.addView(buttons, ui.ll(0, ui.wrap, weight = 1f))
        nextCard = ui.hbox()
        nextCard.setPadding(ui.u(10), ui.u(10), ui.u(24), ui.u(10))
        nextCard.background = ui.glass(Color.argb(140, 20, 20, 26), D.PLATE, 2, 0.8f)
        nextThumb = ImageView(this)
        nextThumb.scaleType = ImageView.ScaleType.CENTER_CROP
        nextThumb.background = ui.round(Color.parseColor("#1C2A3A"), 10)
        nextThumb.clipToOutline = true
        nextCard.addView(nextThumb, ui.ll(ui.u(150), ui.u(84)))
        val nextText = ui.vbox()
        nextText.addView(ui.text("SIRADAKİ  ·  ▼", 15, Color.argb(160, 255, 255, 255), 600).also { it.letterSpacing = 0.2f })
        nextTitle = ui.text("", 22, C.TEXT, 700)
        nextTitle.maxLines = 1
        nextTitle.ellipsize = TextUtils.TruncateAt.END
        nextText.addView(nextTitle, ui.ll(ui.u(260), ui.wrap, top = 4))
        nextCard.addView(nextText, ui.ll(ui.wrap, ui.wrap, left = 16))
        nextCard.visibility = View.GONE
        row.addView(nextCard)
        tray.addView(row, ui.ll(ui.match, ui.wrap, bottom = 34))
        tray.visibility = View.GONE
        foot.addView(tray, ui.ll(ui.match, ui.wrap))

        // The lower floor's top line: the small logo on the left, "︿" in the middle.
        val floor = FrameLayout(this)
        floor.clipChildren = false
        mini = FrameLayout(this)
        miniLogo = ImageView(this)
        miniLogo.adjustViewBounds = true
        miniLogo.maxHeight = ui.u(60)
        miniLogo.maxWidth = ui.u(300)
        miniLogo.scaleType = ImageView.ScaleType.FIT_START
        mini.addView(miniLogo, ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.START))
        miniTitle = ui.text("", 28, C.TEXT, 700)
        miniTitle.maxLines = 1
        miniTitle.ellipsize = TextUtils.TruncateAt.END
        miniTitle.maxWidth = ui.u(700)
        mini.addView(miniTitle, ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.START))
        floor.addView(mini, ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.START))
        val up = ImageView(this)
        up.setImageDrawable(chevronUp())
        chevron = up
        floor.addView(up, ui.fl(ui.u(34), ui.u(18), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        foot.addView(floor, ui.ll(ui.match, ui.u(64)))

        // The bar: a thick track, white fill, a round knob with the time over it.
        val barBox = FrameLayout(this)
        barBox.clipChildren = false
        track = FrameLayout(this)
        track.background = ui.round(Color.argb(46, 255, 255, 255), 2)
        barFill = View(this)
        barFill.background = ui.round(Color.WHITE, 4)
        barFill.pivotX = 0f
        barFill.scaleX = 0f
        track.addView(barFill, ui.fl(ui.match, ui.match))
        val tlp = ui.fl(ui.match, ui.u(4), Gravity.CENTER_VERTICAL)
        barBox.addView(track, tlp)
        knob = View(this)
        knob.background = ui.round(Color.WHITE, 13, 6, Color.argb(50, 255, 255, 255))
        // The knob and the time over it show only while skipping.
        knob.alpha = 0f
        barBox.addView(knob, ui.fl(ui.u(20), ui.u(20), Gravity.CENTER_VERTICAL))
        tip = ui.text("0:00", 22, C.TEXT, 700)
        tip.setPadding(ui.u(12), ui.u(5), ui.u(12), ui.u(5))
        tip.background = ui.round(Color.argb(150, 0, 0, 0), 10)
        tip.alpha = 0f
        val tipLp = ui.fl(ui.wrap, ui.wrap, Gravity.TOP)
        tipLp.topMargin = -ui.u(60)
        barBox.addView(tip, tipLp)
        foot.addView(barBox, ui.ll(ui.match, ui.u(30), top = 14))
        val times = ui.hbox()
        times.addView(ui.text("0:00", 20, C.TEXT2, 600), ui.ll(0, ui.wrap, weight = 1f))
        timeLeft = ui.text("", 20, C.TEXT2, 600)
        times.addView(timeLeft)
        foot.addView(times, ui.ll(ui.match, ui.wrap, top = 8))
        // The keys line is gone: "︿" says there is more above.
        hint = ui.text("", 18, Color.argb(130, 255, 255, 255), 500)
        hint.visibility = View.GONE
        foot.addView(hint, ui.ll(ui.wrap, ui.wrap, top = 16))
        val flp = ui.fl(ui.match, ui.wrap, Gravity.BOTTOM)
        flp.setMargins(ui.u(110), 0, ui.u(110), ui.u(52))
        info.addView(foot, flp)
        infoFoot = foot
        frame.addView(info, ui.fl(ui.match, ui.match))
        track.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeKnob() }
    }

    /** Title, badges and buttons for the trailer on screen. */
    private fun bindFilm() {
        val p = film()
        infoLabel.text = if (list.size > 1) "FRAGMAN MARATONU  ·  ${at + 1} / ${list.size}" else "FRAGMAN"
        infoTitle.text = p?.title ?: currentTitle
        infoTitle.visibility = View.VISIBLE
        infoLogo.visibility = View.GONE
        infoStory.text = p?.overview.orEmpty()
        infoStory.visibility = if (infoStory.text.isNullOrBlank()) View.GONE else View.VISIBLE
        p?.logo?.takeIf { it.isNotEmpty() }?.let { path ->
            val want = id
            Thread {
                val b = Net.bitmap("https://image.tmdb.org/t/p/w500$path", ui.u(560), false)?.takeIf { !Img.isDark(it) }
                runOnUiThread {
                    if (b == null || isFinishing || id != want) return@runOnUiThread
                    infoLogo.setImageBitmap(b)
                    infoLogo.visibility = View.VISIBLE
                    infoTitle.visibility = View.GONE
                    fillMini()
                }
            }.start()
        }
        infoMeta.removeAllViews()
        if (p != null) {
            val where = ui.whereBadge()
            ui.setWhere(where, when {
                p.provider.isNotEmpty() -> p.provider
                upcoming(p) -> null
                p.where.isNotEmpty() -> p.where.first()
                else -> null
            })
            if (where.visibility == View.VISIBLE) infoMeta.addView(where, ui.ll(ui.wrap, ui.wrap, right = 18))
            if (p.imdb > 0) {
                val imdb = ui.text("IMDb", 15, Color.BLACK, 800)
                imdb.setPadding(ui.u(9), ui.u(3), ui.u(9), ui.u(3))
                imdb.background = ui.round(Color.parseColor("#F5C518"), D.BADGE)
                infoMeta.addView(imdb)
                infoMeta.addView(ui.text(String.format(java.util.Locale("tr", "TR"), "%.1f", p.imdb), 22, C.TEXT, 600), ui.ll(ui.wrap, ui.wrap, left = 12, right = 20))
            }
            val bits = ArrayList<String>()
            if (upcoming(p)) bits.add(releaseText(p))
            if (p.year.isNotEmpty() && !upcoming(p)) bits.add(p.year)
            if (p.genres.isNotEmpty()) bits.add(p.genres.take(2).joinToString(", "))
            infoMeta.addView(ui.text(bits.joinToString("   ·   "), 21, C.TEXT2, 500))
            // Identify both the trailer format and its language using the catalogue label.
            p.trailers.firstOrNull { it.key == id }?.let { v ->
                val l = v.label.lowercase(java.util.Locale("tr", "TR"))
                val language = when (trailerLanguage()) {
                    "dub" -> "TÜRKÇE DUBLAJ"
                    "sub" -> "TÜRKÇE ALTYAZILI"
                    "orig" -> "ORİJİNAL SES"
                    "tr" -> "TÜRKÇE"
                    else -> "DİL BELİRTİLMEMİŞ"
                }
                val kind = when {
                    "teaser" in l || "tanıtım" in l -> "TEASER"
                    "final" in l -> "FİNAL FRAGMANI"
                    "official" in l || "resmi" in l || "resmî" in l -> "RESMÎ FRAGMAN"
                    "trailer" in l || "fragman" in l -> "FRAGMAN"
                    else -> ""
                }
                val badgeText = listOf(kind, language).filter { it.isNotEmpty() }.joinToString("  ·  ")
                if (badgeText.isNotEmpty() && language != "DİL BELİRTİLMEMİŞ") {
                    val badge = ui.certBadge(badgeText)
                    badge.letterSpacing = 0.06f
                    infoMeta.addView(badge, ui.ll(ui.wrap, ui.wrap, left = 22))
                }
            }
            filmColor(p)
            loadGuideFacts(p)
        }
        // Buttons: list (or remind), details, the platform's app.
        buttons.removeAllViews()
        if (p != null) {
            // The film itself, from the trailer (not before it is out, nor while it is only in cinemas).
            if (!upcoming(p) && !inCinemas(p) && p.id > 0) {
                val watch = ui.pill(if (p.isSeries) "▶   Diziyi izle" else "▶   Filmi izle", 64, 23)
                styleAccentAction(watch)
                watch.setOnClickListener { toFilm(p) }
                buttons.addView(watch)
            }
            // The rest are round glass icons; the selected one opens to show its name.
            buttons.addView(compact(listButton(p, false), null))
            val more = ui.iconPill("Detaylar", R.drawable.ic_info)
            more.setOnClickListener { details(p) }
            buttons.addView(compact(more, ""))
            if (p.trailers.size > 1) {
                val pick = ui.iconPill("Fragmanlar · ${p.trailers.size}", R.drawable.ic_film)
                pick.setOnClickListener { chooseTrailer(p) }
                buttons.addView(compact(pick, ""))
            }
            if (hasSubtitles) {
                val subs = ui.pill("", 64, 23)
                compact(subs, "CC")
                relabel(subs, if (subtitlesOn) "Altyazı: Açık" else "Altyazı: Kapalı")
                subs.setOnClickListener {
                    subtitlesOn = !subtitlesOn
                    exo?.let { e ->
                        e.trackSelectionParameters = e.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(MediaC.TRACK_TYPE_TEXT, !subtitlesOn).build()
                    }
                    relabel(subs, if (subtitlesOn) "Altyazı: Açık" else "Altyazı: Kapalı")
                }
                buttons.addView(subs)
            }
            if (p.cast.isNotEmpty()) {
                val cast = ui.iconPill("Oyuncular", R.drawable.ic_eye)
                cast.setOnClickListener { toggleCast(p) }
                buttons.addView(compact(cast, ""))
            }
        }
        setButtonsFocusable(false)
        fillMini()
        if (!trayOpen) chevron.alpha = if (buttons.childCount > 0) 0.7f else 0f
        // The next trailer, in a marathon.
        val next = at + 1
        if (list.size > 1 && next < list.size) {
            nextCard.visibility = View.VISIBLE
            nextTitle.text = titles.getOrElse(next) { "" }
            nextThumb.setImageDrawable(null)
            films.firstOrNull { it.trailer == list[next] || it.trailers.any { v -> v.key == list[next] } }?.backdrop?.takeIf { it.isNotEmpty() }?.let { path ->
                Thread {
                    val b = Net.bitmap("https://image.tmdb.org/t/p/w300$path", ui.u(150), true)
                    runOnUiThread { if (b != null && !isFinishing) nextThumb.setImageBitmap(b) }
                }.start()
            }
        } else {
            nextCard.visibility = View.GONE
        }
        hint.text = listOfNotNull(
            "◀ ▶  sar",
            "▲  seçenekler".takeIf { buttons.childCount > 0 },
            "▼  sıradaki".takeIf { nextCard.visibility == View.VISIBLE }
        ).joinToString("     ")
    }

    private fun setButtonsFocusable(on: Boolean) {
        for (i in 0 until buttons.childCount) buttons.getChildAt(i).isFocusable = on
    }

    /** "14 Kasım'da vizyonda". */
    private fun releaseText(p: Pick): String {
        val c = java.util.Calendar.getInstance()
        try {
            c.time = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(p.release)!!
        } catch (e: Exception) {
            return "Yakında vizyonda"
        }
        val sfx = arrayOf("'ta", "'ta", "'ta", "'da", "'ta", "'da", "'da", "'ta", "'de", "'de", "'da", "'ta")
        return java.text.SimpleDateFormat("d MMMM", java.util.Locale("tr", "TR")).format(c.time) + sfx[c.get(java.util.Calendar.MONTH)] + " vizyonda"
    }

    private fun onPlaying() {
        playing = true
        paused = false
        if (card.showing) {
            // The card stays long enough to be read, then goes, and the curtain parts.
            handler.postDelayed({
                card.hide()
                openCurtain()
            }, card.waitMs())
        } else {
            openCurtain()
        }
        if (guideShownFor != id) {
            guideShownFor = id
            guideDueUntil = System.currentTimeMillis() + 12_000
            handler.removeCallbacks(showGuide)
            handler.postDelayed(showGuide, if (curtainOpen) 1_200 else card.waitMs() + 1_800)
        }
        glow.animate().alpha(GLOW_ALPHA).setDuration(1_200).start()
        // Marathon: the next trailer waits ready in the hidden player.
        if (list.size > 1 && at + 1 < list.size) {
            val next = list[at + 1]
            if (nativeMode) {
                val height = maxHeight
                val wantsHdr = hdr
                finder.execute { YouTubeStreams.find(next, height, TrailerDecoders::canDecode, wantsHdr) }
            }
            // The YouTube page gets no second hidden player any more: two players at once froze and
            // closed LeXia on the TV during marathons. The next one loads when its turn comes.
        }
        center.animate().alpha(0f).scaleX(1.25f).scaleY(1.25f).setDuration(D.MED).start()
        hidePauseLogo()
        status.animate().alpha(0f).setDuration(D.MED).start()
        // A moment after it starts: YouTube shows its title over the first frames.
        handler.postDelayed({ if (playing && !paused && endCard == null) shieldTo(0f) }, 600)
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 4000)
    }

    private fun onPaused() {
        paused = true
        guide.hide(quick = true)
        center.animate().cancel()
        center.alpha = 0f
        shieldTo(0.6f)
        if (onButtons) {
            showInfo()
            return
        }
        handler.removeCallbacks(hideInfo)
        info.animate().cancel()
        info.animate().alpha(0f).setDuration(D.MED).start()
        showPauseLogo()
    }

    /** The film's logo in the middle while paused; the controls come back with ▲ or the arrows. */
    private fun showPauseLogo() {
        if (!paused || endCard != null) return
        val logo = infoLogo.drawable?.takeIf { infoLogo.visibility == View.VISIBLE }
        pauseLogo.setImageDrawable(logo)
        pauseLogo.visibility = if (logo != null) View.VISIBLE else View.GONE
        pauseTitle.text = film()?.title ?: currentTitle
        pauseTitle.visibility = if (logo == null && pauseTitle.text.isNotBlank()) View.VISIBLE else View.GONE
        if (pauseBox.visibility == View.VISIBLE && pauseBox.alpha >= 1f) return
        pauseBox.animate().cancel()
        pauseBox.visibility = View.VISIBLE
        if (Fx.lite) {
            pauseBox.alpha = 1f
            return
        }
        pauseBox.alpha = 0f
        pauseBox.scaleX = 0.96f
        pauseBox.scaleY = 0.96f
        pauseBox.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(600)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
    }

    private fun hidePauseLogo() {
        if (pauseBox.visibility != View.VISIBLE) return
        pauseBox.animate().cancel()
        pauseBox.animate().alpha(0f).setDuration(D.FAST).withEndAction { pauseBox.visibility = View.GONE }.start()
    }

    /** Shows the controls; they fade after four seconds while the trailer plays. */
    private fun showInfo() {
        handler.removeCallbacks(hideInfo)
        info.animate().cancel()
        info.alpha = 1f
        hidePauseLogo()
        readTime()
        placeSubtitles()
        handler.postDelayed(hideInfo, if (onButtons) 8000L else 4000L)
    }

    /**
     * The subtitles never sit under the controls (TV photo, 24 Kasım): while the controls show they
     * rise softly above them, and come back down to their place when the controls fade.
     */
    private fun placeSubtitles() {
        val sv = playerView?.subtitleView ?: return
        val foot = infoFoot ?: return
        val h = frame.height
        val target = if (info.alpha > 0.05f && h > 0 && foot.height > 0)
            ((h - foot.top).toFloat() / h + 0.02f).coerceIn(SUBTITLE_LOW, 0.8f) else SUBTITLE_LOW
        if (kotlin.math.abs(target - subtitleLift) < 0.005f) return
        subtitleAnim?.cancel()
        subtitleAnim = android.animation.ValueAnimator.ofFloat(subtitleLift, target).apply {
            duration = if (Fx.lite) 0L else 350L
            addUpdateListener {
                subtitleLift = it.animatedValue as Float
                sv.setBottomPaddingFraction(subtitleLift)
            }
            start()
        }
    }

    /** Asks the player where it is ("72.4|150.2|1") and moves the bar, the knob and the times. */
    private fun readTime() {
        if (nativeMode) {
            val p = exo ?: return
            showTime(p.currentPosition / 1000.0, p.duration / 1000.0)
            return
        }
        val w = web ?: return
        w.evaluateJavascript("(player&&player.getDuration)?(player.getCurrentTime()+'|'+player.getDuration()+'|'+player.getPlayerState()):''") { r ->
            val parts = r?.trim('"')?.split('|') ?: return@evaluateJavascript
            if (parts.size < 3) return@evaluateJavascript
            val now = parts[0].toDoubleOrNull() ?: return@evaluateJavascript
            val all = parts[1].toDoubleOrNull() ?: return@evaluateJavascript
            showTime(now, all)
        }
    }

    private fun showTime(now: Double, all: Double) {
        if (all <= 0) return
        progress = (now / all).toFloat().coerceIn(0f, 1f)
        tip.text = clock(now)
        timeLeft.text = "−" + clock(all - now)
        placeKnob()
    }

    private fun placeKnob() {
        val wTrack = track.width.toFloat()
        if (wTrack <= 0f) return
        barFill.scaleX = progress
        val x = wTrack * progress
        knob.translationX = x - knob.width / 2f
        tip.translationX = (x - tip.width / 2f).coerceIn(0f, wTrack - tip.width)
    }

    private fun clock(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    // ---------- Keys ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val back = event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE
        // The end card has buttons: the remote moves between them; Geri closes.
        if (endCard != null) {
            if (back) {
                if (event.action == KeyEvent.ACTION_DOWN) close()
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) stopCountdown()
            return super.dispatchKeyEvent(event)
        }
        if (castPanel != null && back) {
            if (event.action == KeyEvent.ACTION_DOWN) hideCast()
            return true
        }
        // On the buttons (▲): left and right move between them, OK presses, ▼ or Geri go back to the trailer.
        if (onButtons) {
            if (back || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN) leaveButtons()
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) showInfo()
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) {
            return event.keyCode != KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(event)
        }
        // Held arrows skip faster: 10 seconds a press, 30 once the key has been held a moment.
        val step = if (event.repeatCount >= 6) 30 else 10
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_SPACE ->
                if (nativeMode) exo?.let { it.playWhenReady = !it.playWhenReady } else web?.evaluateJavascript("toggle()", null)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> skip(-step)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> skip(step)
            KeyEvent.KEYCODE_DPAD_UP -> if (buttons.childCount > 0) { enterButtons(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> if (list.size > 1 && at < list.size - 1) { nextTrailer(); return true } else return super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> return super.dispatchKeyEvent(event)
            else -> return super.dispatchKeyEvent(event)
        }
        showInfo()
        return true
    }

    private fun enterButtons() {
        guide.hide(quick = true)
        onButtons = true
        setButtonsFocusable(true)
        showInfo()
        setTray(true)
        buttons.getChildAt(0)?.requestFocus()
    }

    private fun leaveButtons() {
        onButtons = false
        currentFocus?.clearFocus()
        setButtonsFocusable(false)
        setTray(false)
        showInfo()
    }

    /**
     * Makes [t] a round glass icon that opens to show its name when selected. [glyph]: what it
     * shows closed when it has no icon ("CC"); null takes the sign its name starts with ("+", "✓").
     */
    private fun compact(t: TextView, glyph: String?): TextView {
        compactGlyph[t] = glyph ?: "\u0000"
        if (t.tag == null) t.tag = t.text.toString()
        val style = t.onFocusChangeListener
        t.setOnFocusChangeListener { v, f ->
            style?.onFocusChange(v, f)
            showCompact(t)
        }
        showCompact(t)
        return t
    }

    /** A button's name; an icon button keeps it for when it is selected. */
    private fun relabel(t: TextView, label: String) {
        t.tag = label
        if (compactGlyph.containsKey(t)) showCompact(t) else t.text = label
    }

    private fun showCompact(t: TextView) {
        val label = t.tag as? String ?: t.text.toString()
        val g = compactGlyph[t] ?: return
        val open = t.isFocused
        val hasIcon = t.compoundDrawables[0] != null
        t.text = when {
            open -> label
            hasIcon -> ""
            g == "\u0000" -> label.substringBefore(' ').ifBlank { label.take(1) }
            else -> g
        }
        t.compoundDrawablePadding = if (open) ui.u(12) else 0
        t.compoundDrawables[0]?.setTint(if (open) C.INK else C.TEXT)
        // Closed: a circle as wide as it is high.
        val side = if (open) ui.u(38) else if (hasIcon) ui.u(19) else ui.u(14)
        t.setPadding(side, 0, side, 0)
        t.minWidth = if (open) 0 else ui.u(64)
    }

    /** The upper floor (story and buttons) slides up or down. */
    private fun setTray(open: Boolean) {
        if (open == trayOpen) return
        trayOpen = open
        val ease = android.view.animation.DecelerateInterpolator(2.2f)
        tray.animate().cancel()
        if (open) {
            tray.visibility = View.VISIBLE
            tray.alpha = 0f
            tray.translationY = ui.uf(46)
            tray.animate().alpha(1f).translationY(0f).setDuration(if (Fx.lite) 0L else 380L).setInterpolator(ease).start()
            mini.animate().alpha(0f).setDuration(D.FAST).start()
            chevron.animate().alpha(0f).setDuration(D.FAST).start()
        } else {
            tray.animate().alpha(0f).translationY(ui.uf(34)).setDuration(if (Fx.lite) 0L else 260L)
                .withEndAction { if (!trayOpen) tray.visibility = View.GONE }.start()
            fillMini()
            mini.animate().alpha(1f).setDuration(D.MED).start()
            chevron.animate().alpha(if (buttons.childCount > 0) 0.7f else 0f).setDuration(D.MED).start()
        }
    }

    /** The small logo (or name) on the lower floor, from the film's. */
    private fun fillMini() {
        val logo = infoLogo.drawable?.takeIf { infoLogo.visibility == View.VISIBLE }
        miniLogo.setImageDrawable(logo)
        miniLogo.visibility = if (logo != null) View.VISIBLE else View.GONE
        miniTitle.text = film()?.title ?: infoTitle.text
        miniTitle.visibility = if (logo == null) View.VISIBLE else View.GONE
    }

    /** "︿", drawn: two thin white strokes. */
    private fun chevronUp(): android.graphics.drawable.Drawable = object : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = ui.uf(3)
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds
            val p = android.graphics.Path()
            p.moveTo(b.left + ui.uf(4), b.bottom - ui.uf(4))
            p.lineTo(b.exactCenterX(), b.top + ui.uf(4))
            p.lineTo(b.right - ui.uf(4), b.bottom - ui.uf(4))
            c.drawPath(p, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun skip(s: Int) {
        val target = if (nativeMode) {
            val p = exo ?: return
            val last = (p.duration - 1000L).coerceAtLeast(0L)
            val next = (p.currentPosition + s * 1000L).coerceIn(0L, last)
            p.seekTo(next)
            next / 1000L
        } else {
            val next = (seekTargetSeconds.takeIf { it >= 0L } ?: 0L) + s
            seekTargetSeconds = next.coerceAtLeast(0L)
            web?.evaluateJavascript("seek($s)", null)
            seekTargetSeconds
        }
        if (nativeMode) seekTargetSeconds = target
        skipSum += s
        bubble.text = (if (skipSum >= 0) "+" else "−") + Math.abs(skipSum) + " sn"
        bubble.animate().cancel()
        bubble.alpha = 1f
        handler.removeCallbacks(skipEnd)
        handler.postDelayed(skipEnd, 900)
        tip.text = clock(target.toDouble())
        tip.animate().cancel()
        tip.alpha = 1f
        knob.animate().cancel()
        knob.alpha = 1f
        handler.removeCallbacks(scrubDone)
        handler.postDelayed(scrubDone, 1500)
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 1800)
    }

    // ---------- Film actions ----------

    /** The film of the trailer on screen, when it is known. */
    private fun film(): Pick? = films.firstOrNull { it.trailer == id || it.trailers.any { v -> v.key == id } }

    private fun releasePhase(p: Pick): TrailerReleasePhase =
        trailerReleasePhase(p.release.takeIf { it.isNotEmpty() }?.let(Episodes::daysUntil))

    private fun upcoming(p: Pick) = releasePhase(p) == TrailerReleasePhase.UPCOMING

    /** Keys of the films in Turkish cinemas now (pick.json's "Sinemada"), read once off the main thread. */
    @Volatile private var cinemaNow: Set<String>? = null

    /**
     * The film is only in cinemas: on the "Sinemada" list, or out in the last two months with no
     * platform and no digital release yet; and never played here (no source was found for it).
     */
    private fun inCinemas(p: Pick): Boolean {
        if (p.isSeries) return false
        val store = MediaPlaybackStore(this)
        if (store.last(p.key).isNotBlank() || store.isAvailable(p.key)) return false
        if (p.key in (cinemaNow ?: emptySet())) return true
        val d = p.release.takeIf { it.isNotEmpty() }?.let(Episodes::daysUntil) ?: return false
        val digitalOut = p.digital.takeIf { it.isNotEmpty() }?.let(Episodes::daysUntil)?.let { it <= 0 } ?: false
        return d in -60..0 && p.provider.isEmpty() && p.where.isEmpty() && !digitalOut
    }

    /** "Listeme ekle" (or, before its release, "Çıkınca haber ver"), as a button that switches. */
    private fun listButton(p: Pick, primary: Boolean): TextView {
        val b = ui.pill("", 64, 23, primary = primary)
        if (upcoming(p)) ui.setPillIcon(b, R.drawable.ic_bell)
        fun label() {
            relabel(b, if (upcoming(p)) {
                if (prefs.reminders.any { it.key == p.key }) "Haber verilecek" else "Çıkınca haber ver"
            } else {
                if (prefs.watchlist.any { it.key == p.key }) "✓   Listemde" else "+   Listeme ekle"
            })
        }
        label()
        b.setOnClickListener {
            if (upcoming(p)) {
                val l = prefs.reminders
                if (l.any { it.key == p.key }) prefs.reminders = l.filter { it.key != p.key }
                else {
                    prefs.reminders = l + p
                    Glass.toast(this, "Vizyona girdiği gün ana ekranda haber vereceğim")
                }
            } else {
                val l = prefs.watchlist
                prefs.watchlist = if (l.any { it.key == p.key }) l.filter { it.key != p.key } else listOf(p) + l
            }
            label()
        }
        return b
    }

    private fun pausePlayer() {
        if (nativeMode) exo?.playWhenReady = false
        else web?.evaluateJavascript("if(player&&player.getPlayerState()===1)player.pauseVideo();", null)
    }

    /** The film's page; the trailer stops while it is open. */
    private fun details(p: Pick) {
        pausePlayer()
        DetailActivity.open(this, p)
    }

    // ---------- End ----------

    /** The trailer is over: the film's picture dimmed, what to do next and, in a marathon, the next one in 5 s. */
    private fun showEnd() {
        guide.hide(quick = true)
        if (done || endCard != null) return
        prefs.trailerAt = prefs.trailerAt - id
        hideCast()
        handler.removeCallbacks(hideInfo)
        info.animate().alpha(0f).setDuration(D.MED).start()
        center.alpha = 0f
        shieldTo(1f)
        val box = FrameLayout(this)
        val p = film()
        // Keep the film artwork as the emotional anchor of the end card.
        if (p != null && p.backdrop.isNotEmpty()) {
            val img = ImageView(this)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.alpha = 0f
            box.addView(img, ui.fl(ui.match, ui.match))
            val wpx = resources.displayMetrics.widthPixels
            Thread {
                val b = Net.bitmap("https://image.tmdb.org/t/p/w1280" + p.backdrop, wpx / 2, true)
                runOnUiThread {
                    if (b != null && !isFinishing) {
                        img.setImageBitmap(b)
                        img.animate().alpha(0.42f).setDuration(D.SLOW).start()
                    }
                }
            }.start()
        }
        val shade = View(this)
        shade.background = ui.gradient(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            Color.argb(210, 0, 0, 0), Color.argb(55, 0, 0, 0)
        )
        box.addView(shade, ui.fl(ui.match, ui.match))

        val card = ui.vbox()
        card.clipChildren = false
        card.clipToPadding = false
        card.addView(ui.label(if (list.size > 1 && at + 1 < list.size) "BU FRAGMAN SONA ERDİ" else "FRAGMAN SONA ERDİ"))
        val t = ui.text(p?.title ?: currentTitle, 84, C.TEXT, 800)
        t.letterSpacing = -0.02f
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        card.addView(t, ui.ll(ui.u(1050), ui.wrap, top = 10))
        if (infoMeta.childCount > 0) {
            (infoMeta.parent as? LinearLayout)?.removeView(infoMeta)
            card.addView(infoMeta, ui.ll(ui.wrap, ui.wrap, top = 18))
        }

        if (p != null) {
            val phase = releasePhase(p)
            val releaseStatus = when (phase) {
                TrailerReleasePhase.UPCOMING -> "YAKINDA SİNEMALARDA"
                TrailerReleasePhase.RELEASES_TODAY -> "BUGÜN VİZYONA GİRİYOR"
                TrailerReleasePhase.PAST_OR_UNKNOWN -> ""
            }
            if (releaseStatus.isNotEmpty()) {
                val statusText = ui.text(releaseStatus, 19, Color.WHITE, 800)
                statusText.letterSpacing = 0.12f
                statusText.setPadding(ui.u(16), ui.u(9), ui.u(16), ui.u(9))
                statusText.background = ui.round(Color.argb(115, 255, 255, 255), 12, 1, Color.argb(65, 255, 255, 255))
                card.addView(statusText, ui.ll(ui.wrap, ui.wrap, top = 22))
            }
            val line = when {
                phase == TrailerReleasePhase.UPCOMING -> "Vizyona girdiğinde LeXia'da haber vereyim mi?"
                phase == TrailerReleasePhase.RELEASES_TODAY -> "Film bugün vizyona giriyor."
                p.id > 0 -> "Hazırsan filmi LeXia kaynaklarında arayabilirsin."
                else -> "Filmi listene ekleyip daha sonra kolayca bulabilirsin."
            }
            card.addView(ui.text(line, 27, C.TEXT2, 400), ui.ll(ui.wrap, ui.wrap, top = 18))
        }

        val row = ui.hbox()
        row.clipChildren = false
        row.clipToPadding = false
        if (p != null) {
            // Streaming/source discovery stays inside LeXia; no external platform is shown.
            if (!upcoming(p) && !inCinemas(p) && p.id > 0) {
                // The end card's main action: tinted, white only when selected (as on the controls).
                val watch = ui.pill("▶   Filmi izle", 68, 24)
                styleAccentAction(watch)
                watch.setOnClickListener { toFilm(p) }
                row.addView(watch)
            } else {
                row.addView(listButton(p, primary = false).also { styleAccentAction(it) })
            }
            if (!upcoming(p) && p.release.isNotEmpty()) {
                val save = ui.pill(if (prefs.watchlist.any { it.key == p.key }) "✓   Listemde" else "+   Listeme ekle", 64, 23)
                save.setOnClickListener {
                    val saved = prefs.watchlist.any { it.key == p.key }
                    prefs.watchlist = if (saved) prefs.watchlist.filterNot { it.key == p.key } else listOf(p) + prefs.watchlist
                    save.text = if (saved) "+   Listeme ekle" else "✓   Listemde"
                }
                row.addView(save)
            }
        }
        val again = ui.pill("Fragmanı tekrar izle", 64, 23)
        again.setOnClickListener { replay() }
        row.addView(again)
        val detailsButton = if (p != null) ui.pill("Detaylar", 64, 23).also { b ->
            b.setOnClickListener { details(p) }
        } else null
        if (detailsButton != null) row.addView(detailsButton)
        val shut = ui.pill("Kapat", 64, 23)
        shut.setOnClickListener { close() }
        row.addView(shut)
        card.addView(row, ui.ll(ui.wrap, ui.wrap, top = 40))
        (row.getChildAt(0) as? TextView)?.let {
            accentAction = it
            styleAccentAction(it)
        }
        val lp = ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.START)
        lp.leftMargin = ui.u(110)
        lp.bottomMargin = ui.u(120)
        box.addView(card, lp)

        // Marathon's five-second up-next countdown remains independent of the end-card actions.
        val next = at + 1
        if (list.size > 1 && next < list.size) {
            val marathonProgress = ui.text("MARATON  ·  ${at + 1} / ${list.size}", 18, C.TEXT2, 700)
            marathonProgress.letterSpacing = 0.14f
            marathonProgress.setPadding(ui.u(110), 0, 0, ui.u(16))
            box.addView(marathonProgress, ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = ui.u(110)
                bottomMargin = ui.u(500)
            })
            box.addView(upNext(next), run {
            val l = ui.fl(ui.u(480), ui.wrap, Gravity.BOTTOM or Gravity.END)
            l.rightMargin = ui.u(110)
            l.bottomMargin = ui.u(120)
            l
            })
        }
        frame.addView(box, ui.fl(ui.match, ui.match))
        endCard = box
        card.alpha = 0f
        card.translationY = ui.uf(30)
        box.alpha = 0f
        box.animate().alpha(1f).setDuration(D.MED).start()
        card.animate().alpha(1f).translationY(0f).setStartDelay(D.FAST.toLong())
            .setDuration(D.SLOW).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        // The primary action is always the first focus target for the TV remote.
        row.getChildAt(0)?.requestFocus()
    }

    /** "SIRADAKİ FRAGMAN" card with a five-second ring. */
    private fun upNext(next: Int): View {
        val box = ui.vbox()
        box.isFocusable = true
        box.isClickable = true
        box.background = ui.glass(Color.argb(160, 20, 20, 26), 24, 2)
        box.clipToOutline = true
        val pic = FrameLayout(this)
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.setBackgroundColor(Color.parseColor("#1C2A3A"))
        pic.addView(img, ui.fl(ui.match, ui.match))
        val ring = Ring(this, barColor)
        val rlp = ui.fl(ui.u(64), ui.u(64), Gravity.BOTTOM or Gravity.END)
        rlp.setMargins(0, 0, ui.u(18), ui.u(18))
        pic.addView(ring, rlp)
        box.addView(pic, ui.ll(ui.match, ui.u(270)))
        val txt = ui.vbox()
        txt.setPadding(ui.u(24), ui.u(20), ui.u(24), ui.u(24))
        txt.addView(ui.label("SIRADAKİ FRAGMAN"))
        val nt = ui.text(titles.getOrElse(next) { "" }, 28, C.TEXT, 700)
        nt.maxLines = 1
        nt.ellipsize = TextUtils.TruncateAt.END
        txt.addView(nt, ui.ll(ui.match, ui.wrap, top = 6))
        val left = ui.text("5 saniye içinde başlıyor", 18, C.TEXT2, 500)
        txt.addView(left, ui.ll(ui.wrap, ui.wrap, top = 6))
        val action = ui.text("OK  ·  HEMEN GEÇ", 16, C.TEXT, 700)
        action.letterSpacing = 0.08f
        txt.addView(action, ui.ll(ui.wrap, ui.wrap, top = 12))
        box.addView(txt)
        films.firstOrNull { it.trailer == list[next] || it.trailers.any { v -> v.key == list[next] } }?.backdrop?.takeIf { it.isNotEmpty() }?.let { path ->
            Thread {
                val b = Net.bitmap("https://image.tmdb.org/t/p/w780$path", ui.u(480), true)
                runOnUiThread { if (b != null && !isFinishing) img.setImageBitmap(b) }
            }.start()
        }
        box.setOnFocusChangeListener { v, f ->
            ui.lift(v, f, 1.04f, 20)
            box.background = ui.round(if (f) Color.argb(230, 255, 255, 255) else Color.argb(160, 20, 20, 26), 24, 1, Color.argb(40, 255, 255, 255))
            for (tv in listOf(nt, left, action)) tv.setTextColor(if (f) C.INK else if (tv === nt) C.TEXT else C.TEXT2)
        }
        box.setOnClickListener { stopCountdown(); nextTrailer() }
        var s = 5
        val r = object : Runnable {
            override fun run() {
                ring.fraction = s / 5f
                left.text = if (s > 0) "$s saniye içinde başlıyor" else "Başlıyor…"
                if (s <= 0) { nextTrailer(); return }
                s--
                handler.postDelayed(this, 1000)
            }
        }
        countdown = r
        handler.post(r)
        return box
    }

    private fun stopCountdown() {
        countdown?.let { handler.removeCallbacks(it) }
        countdown = null
    }

    private fun removeEnd() {
        stopCountdown()
        endCard?.let { frame.removeView(it) }
        endCard = null
        accentAction = null
        // The badges were lent to the card: back under the title.
        if (infoMeta.parent !== infoTitle.parent) {
            (infoMeta.parent as? android.view.ViewGroup)?.removeView(infoMeta)
            (infoTitle.parent as LinearLayout).addView(infoMeta, ui.ll(ui.wrap, ui.wrap, top = 14))
        }
    }

    private fun replay() {
        removeEnd()
        playing = false
        shieldTo(1f)
        startTrailer(0)
        showInfo()
    }

    /** Marathon: the next trailer in the same player. */
    private fun nextTrailer() {
        guide.hide(quick = true)
        if (done) return
        removeEnd()
        if (at >= list.size - 1) {
            close()
            return
        }
        if (onButtons) leaveButtons()
        at++
        id = list[at]
        shownSource = null
        playing = false
        paused = false
        shieldTo(1f)
        currentTitle = titles.getOrElse(at) { "" }
        status.text = if (currentTitle.isEmpty()) "Sıradaki fragman…" else "$currentTitle  ·  ${at + 1} / ${list.size}"
        status.animate().alpha(1f).setDuration(D.FAST).start()
        progress = 0f
        placeKnob()
        bindFilm()
        showInfo()
        startTrailer(0)
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, 15000)
    }

    /** The five-second ring on the next trailer's card: a white arc that empties. */
    private class Ring(ctx: Context, accent: Int) : View(ctx) {
        var fraction = 1f
            set(v) { field = v; invalidate() }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(70, 255, 255, 255) }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = accent; strokeCap = Paint.Cap.ROUND }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

        override fun onDraw(c: Canvas) {
            val s = width.toFloat()
            val st = s * 0.08f
            track.strokeWidth = st
            arc.strokeWidth = st
            val r = RectF(st, st, s - st, s - st)
            c.drawCircle(s / 2, s / 2, s / 2 - st / 2, bg)
            c.drawArc(r, 0f, 360f, false, track)
            c.drawArc(r, -90f, 360f * fraction, false, arc)
            num.textSize = s * 0.36f
            c.drawText(Math.round(fraction * 5).toString(), s / 2, s / 2 + num.textSize * 0.36f, num)
        }
    }

    /** Could not be played here: the YouTube app, once, then this screen closes. */
    private fun fallBack() {
        if (done) return
        done = true
        youtube(this, id)
        finish()
    }

    /** Sound falls, the curtain closes, then the screen goes; the place is kept for next time. */
    private fun close() {
        guide.hide(quick = true)
        if (done) return
        done = true
        savePlace()
        // The sound fades, then the player is let go before the page leaves, so the home screen
        // comes back smoothly instead of waiting on a black screen while the video shuts down.
        // Geri closes it as a cinema would: the curtains meet over the picture while the sound fades,
        // and only then the page goes (a sudden black screen looked like a fault, TV 24 Kasım).
        val slow = !Fx.lite
        if (nativeMode) fadeVolume(0f, if (slow) 380 else 250) else web?.evaluateJavascript("fadeTo(0,${if (slow) 380 else 250})", null)
        if (slow) {
            info.animate().cancel()
            info.animate().alpha(0f).setDuration(200).start()
            pauseBox.animate().cancel()
            pauseBox.animate().alpha(0f).setDuration(200).start()
            for ((v, dir) in listOf(curtainTop to -1f, curtainLow to 1f)) {
                v.animate().cancel()
                v.visibility = View.VISIBLE
                if (curtainOpen || v.translationY != 0f) v.translationY = dir * v.height
                v.animate().translationY(0f).setStartDelay(0).setDuration(380)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).start()
            }
        }
        handler.postDelayed({
            releasePlayers()
            finish()
        }, if (slow) 420L else 120L)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        close()
    }

    /** Where the trailer was left, if it was left half way (not in the first or last seconds). */
    private fun savePlace() {
        val key = id
        fun keep(now: Double, all: Double) {
            if (all <= 0) return
            prefs.trailerAt = if (now >= 8 && now < all - 10) prefs.trailerAt + (key to now.toInt()) else prefs.trailerAt - key
        }
        if (nativeMode) {
            val p = exo ?: return
            if (nativeStarted) keep(p.currentPosition / 1000.0, p.duration / 1000.0)
            return
        }
        val w = web ?: return
        w.evaluateJavascript("(player&&player.getDuration)?(player.getCurrentTime()+'|'+player.getDuration()):''") { r ->
            val parts = r?.trim('"')?.split('|') ?: return@evaluateJavascript
            val now = parts.getOrNull(0)?.toDoubleOrNull() ?: return@evaluateJavascript
            val all = parts.getOrNull(1)?.toDoubleOrNull() ?: return@evaluateJavascript
            keep(now, all)
        }
    }

    private val showGuide = Runnable { tryShowGuide() }

    /** The guide, once per trailer, when its facts are in and nothing else is on screen. */
    private fun tryShowGuide() {
        if (guideShownFor != id || System.currentTimeMillis() > guideDueUntil) return
        if (!playing || paused || onButtons || endCard != null || castPanel != null || done) return
        val facts = guideFacts?.takeIf { guideFactsFor == id } ?: return
        val language = when (trailerLanguage()) {
            "dub" -> "Türkçe dublaj"
            "sub" -> "Türkçe altyazılı"
            "orig" -> "Orijinal ses"
            else -> ""
        }
        val rows = ArrayList(facts)
        if (language.isNotEmpty()) rows.add(rows.indexOfFirst { it.first == "Tür" }.let { if (it < 0) minOf(1, rows.size) else it + 1 }, "Dil" to language)
        if (rows.isEmpty()) return
        guideDueUntil = 0L
        guide.show(rows, barColor)
    }

    /** Age, genre and IMDb's content warnings of [p], looked up once per trailer, off the main thread. */
    private fun loadGuideFacts(p: Pick) {
        val key = id
        if (guideFactsFor == key) return
        guideFacts = null
        guideFactsFor = key
        val app = applicationContext
        finder.execute {
            val cert = p.cert.ifBlank { try { TmdbDiscover.cert(p.kind, p.id) } catch (e: Exception) { "" } }
            val imdb = p.imdbId.ifBlank { try { MediaAddonRepository(app).imdbId(p.kind, p.id) } catch (e: Exception) { "" } }
            val warnings = try { com.nuvio.tv.LeXiaNuvio.contentWarnings(app, imdb) } catch (e: Exception) { emptyList() }
            val facts = ArrayList<Pair<String, String>>()
            if (cert.isNotEmpty()) facts.add("Yaş sınırı" to if (cert == "Genel") "Genel izleyici" else cert)
            if (p.genres.isNotEmpty()) facts.add("Tür" to p.genres.take(3).joinToString(", "))
            facts.addAll(warnings)
            runOnUiThread {
                if (isFinishing || guideFactsFor != key) return@runOnUiThread
                guideFacts = facts
                tryShowGuide()
            }
        }
    }

    /**
     * "Filmi izle": the sound falls and the curtain closes over the trailer, then the film's
     * sources open, whose player parts its own curtain: one screen flowing into the next.
     */
    private fun toFilm(p: Pick) {
        guide.hide(quick = true)
        if (Fx.lite) {
            pausePlayer()
            MediaSourcePickerActivity.open(this, p)
            return
        }
        if (nativeMode) fadeVolume(0f, 420) else web?.evaluateJavascript("fadeTo(0,420)", null)
        for ((v, dir) in listOf(curtainTop to -1f, curtainLow to 1f)) {
            v.animate().cancel()
            v.visibility = View.VISIBLE
            v.translationY = dir * v.height
            v.animate().translationY(0f).setStartDelay(0).setDuration(420)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).start()
        }
        curtainClosedForFilm = true
        handler.postDelayed({
            if (isFinishing) return@postDelayed
            pausePlayer()
            MediaSourcePickerActivity.open(this, p)
        }, 460)
    }

    /** The first time a trailer plays, the curtain parts from the middle. */
    private fun openCurtain() {
        if (curtainOpen) return
        curtainOpen = true
        if (Fx.lite) {
            curtainTop.visibility = View.GONE
            curtainLow.visibility = View.GONE
            return
        }
        for ((v, dir) in listOf(curtainTop to -1f, curtainLow to 1f)) {
            v.animate().translationY(dir * v.height).setStartDelay(450).setDuration(1100)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
    }

    /** Another trailer of the same film (dubbed, subtitled, original, teaser), in the same player. */
    private fun chooseTrailer(p: Pick) {
        guide.hide(quick = true)
        val cur = p.trailers.indexOfFirst { it.key == id }
        Dialogs.list(this, "Fragmanlar", p.trailers.map { it.label to "" }, subtitle = p.title,
            icon = R.drawable.ic_film, current = cur) { i ->
            val v = p.trailers[i]
            if (v.key == id) return@list
            removeEnd()
            leaveButtons()
            // Chosen by hand: this film's trailer is not changed for a Turkish one any more.
            switchedFor.add(p.key)
            id = v.key
            shownSource = null
            playing = false
            paused = false
            shieldTo(1f)
            progress = 0f
            placeKnob()
            status.text = "${p.title}  ·  ${v.label.lowercase(java.util.Locale("tr", "TR"))}"
            status.animate().alpha(1f).setDuration(D.FAST).start()
            bindFilm()
            showInfo()
            startTrailer(0)
            handler.removeCallbacks(giveUp)
            handler.postDelayed(giveUp, 15000)
        }
    }

    /** The cast down the right side, with their photos; the trailer goes on behind. */
    private fun toggleCast(p: Pick) {
        guide.hide(quick = true)
        if (castPanel != null) {
            hideCast()
            return
        }
        val panel = ui.vbox()
        panel.setPadding(ui.u(40), ui.u(56), ui.u(40), ui.u(40))
        panel.background = ui.glass(Color.argb(ui.panelAlpha(), 0x16, 0x16, 0x1B), D.PANEL, 2)
        panel.addView(ui.label("OYUNCULAR"), ui.ll(ui.wrap, ui.wrap, bottom = 20))
        for (c in p.cast.take(7)) {
            val row = ui.hbox()
            val ph = FrameLayout(this)
            val bg = android.graphics.drawable.GradientDrawable()
            bg.shape = android.graphics.drawable.GradientDrawable.OVAL
            bg.setColor(Color.parseColor("#2A2B30"))
            ph.background = bg
            ph.clipToOutline = true
            val img = ImageView(this)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            ph.addView(img, ui.fl(ui.match, ui.match))
            if (c.photo.isNotEmpty()) Img.load(this, "w185", c.photo, ui.u(84), img)
            row.addView(ph, ui.ll(ui.u(76), ui.u(76)))
            val t = ui.vbox()
            val n = ui.text(c.name, 22, C.TEXT, 700)
            n.maxLines = 1
            n.ellipsize = TextUtils.TruncateAt.END
            t.addView(n)
            if (c.role.isNotEmpty()) {
                val r = ui.text(c.role, 17, C.TEXT3, 500)
                r.maxLines = 1
                r.ellipsize = TextUtils.TruncateAt.END
                t.addView(r, ui.ll(ui.wrap, ui.wrap, top = 2))
            }
            row.addView(t, ui.ll(ui.match, ui.wrap, left = 18))
            panel.addView(row, ui.ll(ui.match, ui.wrap, bottom = 16))
        }
        val lp = ui.fl(ui.u(460), ui.wrap, Gravity.END or Gravity.TOP)
        lp.setMargins(0, ui.u(60), ui.u(60), 0)
        frame.addView(panel, lp)
        panel.alpha = 0f
        panel.translationX = ui.uf(60)
        panel.animate().alpha(1f).translationX(0f).setDuration(D.MED).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        castPanel = panel
    }

    private fun hideCast() {
        val p = castPanel ?: return
        castPanel = null
        p.animate().alpha(0f).translationX(ui.uf(60)).setDuration(D.FAST).withEndAction { frame.removeView(p) }.start()
    }

    /** The bar and its knob take the film's own colour, softened toward white. */
    private fun filmColor(p: Pick) {
        barColor = Color.WHITE
        applyAccent()
        if (p.backdrop.isEmpty()) return
        val key = id
        Thread {
            val b = Net.bitmap("https://image.tmdb.org/t/p/w300" + p.backdrop, 300, true)
            val c = b?.let { Fx.accent(it) } ?: 0
            val soft = if (Fx.lite) null else b?.let { TrailerAmbience.glow(it) }
            runOnUiThread {
                if (isFinishing || key != id) return@runOnUiThread
                barColor = if (c == 0) Color.WHITE else Fx.blend(c, Color.WHITE, 0.3f)
                applyAccent()
                soft?.let { glow.setImageBitmap(it) }
            }
        }.start()
    }

    private fun applyAccent() {
        barFill.background = ui.round(barColor, 4)
        knob.background = ui.round(barColor, 13, 6, Color.argb(50, 255, 255, 255))
        infoLabel.setTextColor(barColor)
        center.setTextColor(accentInk())
        center.background = ui.round(accentWash(54), 75, 2, accentWash(210))
        bubble.setTextColor(accentInk())
        bubble.background = ui.round(barColor, 30, 1, accentWash(210))
        accentAction?.let(::styleAccentAction)
    }

    private fun accentInk(): Int = if (Color.luminance(barColor) > 0.55f) C.INK else Color.WHITE

    private fun accentWash(alpha: Int): Int =
        Color.argb(alpha, Color.red(barColor), Color.green(barColor), Color.blue(barColor))

    /** The main end-card action follows the film colour and becomes a clear white focus plate. */
    private fun styleAccentAction(button: TextView) {
        fun style(focused: Boolean) {
            // Unfocused it is only tinted with the film's colour (a solid plate looked like the
            // selection, TV photo 24 Kasım); the white plate is the selection alone.
            button.background = ui.round(
                if (focused) Color.WHITE else accentWash(60),
                D.PANEL,
                if (focused) 0 else 2,
                if (focused) Color.WHITE else accentWash(190)
            )
            button.setTextColor(if (focused) C.INK else Color.WHITE)
        }
        style(button.isFocused)
        button.setOnFocusChangeListener { view, focused ->
            style(focused)
            ui.lift(view, focused, 1.08f, 18)
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onPause() {
        super.onPause()
        // Leaving (Home, another app): stop the sound, keep the place.
        if (!done) savePlace()
        exo?.let {
            resumeOnReturn = nativeMode && it.playWhenReady
            it.playWhenReady = false
        }
        web?.onPause()
        if (isFinishing) web?.loadUrl("about:blank")
    }

    override fun onResume() {
        super.onResume()
        if (curtainClosedForFilm) {
            curtainClosedForFilm = false
            exo?.volume = 1f
            web?.evaluateJavascript("try{if(player)player.setVolume(100);}catch(x){}", null)
            curtainOpen = false
            openCurtain()
        }
        if (nativeMode && resumeOnReturn && !done && endCard == null) exo?.playWhenReady = true
        resumeOnReturn = false
        web?.onResume()
    }

    /** The video and its decoder go now (on the way out), not after the page has left. */
    private fun releasePlayers() {
        handler.removeCallbacksAndMessages(null)
        volumeFade?.cancel()
        playerView?.player = null
        exo?.let {
            it.removeListener(nativeListener)
            it.release()
        }
        exo = null
        web?.loadUrl("about:blank")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        finder.shutdownNow()
        volumeFade?.cancel()
        playerView?.player = null
        exo?.let {
            it.removeListener(nativeListener)
            it.release()
        }
        exo = null
        web?.let {
            (it.parent as? FrameLayout)?.removeView(it)
            it.destroy()
        }
        web = null
        super.onDestroy()
    }
}
