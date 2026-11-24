package tv.lexia.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        /** How long the greeting stays when the home screen opens. */
        private const val GREETING_MS = 6_000L
        /** "Short" for a weeknight: ends within an hour and 45 minutes. */
        private const val SHORT_MIN = 105
        /** How long a film is on screen before its trailer starts (without sound). */
        private const val HERO_TRAILER_DELAY_MS = 5_000L
        /** The side panel of apps: its width and its tiles' (two 16:9 tiles to a row). */
        private const val SIDE_PANEL_W = 700
        private const val SIDE_TILE_W = 284
        /** How long the screensaver's trailer marathon lasts before the pictures come back. */
        private const val SAVER_TRAILERS_MS = 30 * 60_000L
        private const val SHELF_H = 330
        private const val TOP_H = 420
        private const val POSTER_H = 470
        private const val PLAT_H = 300
        /** After this long without a key, the quiet top bar is fully back. */
        private const val BAR_WAKE_MS = 8_000L
        /** Tag of the "Tüm uygulamalar" card at the end of the app row. */
        private const val ALL_APPS = "__all_apps"
        private const val REQ_VOICE = 41
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tr = Locale("tr", "TR")

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private var screenH = 0

    // Layers
    private lateinit var frame: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var stage: FrameLayout
    /** The focused poster's soft colours under the rows (HomePeek), over the shades, under the content. */
    private lateinit var ambience: FrameLayout
    /** The trailer taking the whole screen while the remote rests (HeroCinema). */
    private lateinit var heroStage: HeroCinema
    private lateinit var backA: ImageView
    private lateinit var backB: ImageView
    private var front: ImageView? = null
    private var kenBurns: Animator? = null
    private val tintDrawable = GradientDrawable(GradientDrawable.Orientation.BL_TR, intArrayOf(0, 0, 0))
    private var tintColor = 0
    /** The light of the film on screen (Fx.accent), used by the hero's buttons and progress bar. */
    private var filmTint = 0
    private var filmFill = Color.WHITE
    private var tintAnim: ValueAnimator? = null
    private val scrims = ArrayList<View>()
    private lateinit var boost: View
    private var boostLevel = 0f
    private lateinit var daylight: View
    private var daylightColor = -1
    private lateinit var ambient: FrameLayout
    private lateinit var ambientTitle: TextView

    // Top bar
    private lateinit var greeting: TextView
    private lateinit var dateText: TextView
    private lateinit var weatherBox: LinearLayout
    private lateinit var weatherIcon: ImageView
    private lateinit var tempView: TextView
    private lateinit var netImg: ImageView
    private lateinit var gear: View
    private lateinit var updatePill: TextView

    // Hero
    private lateinit var hero: LinearLayout
    private lateinit var heroText: LinearLayout
    private lateinit var tagView: TextView
    private lateinit var logoView: ImageView
    private lateinit var titleView: TextView
    private lateinit var badge: LinearLayout
    private lateinit var imdbScore: TextView
    private lateinit var metaView: TextView
    private lateinit var whyView: TextView
    private lateinit var descView: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var trailerBtn: TextView
    private lateinit var resumeBtn: TextView
    /** The title left half way, shown first in the big area ("KALDIĞIN YERDEN"); empty when none. */
    private var resumeKey = ""
    private lateinit var listBtn: TextView
    private lateinit var progress: LinearLayout
    private var progressAnim: Animator? = null
    private var progressFill: View? = null

    // Rows
    private lateinit var scroller: HorizontalScrollView
    private lateinit var row: LinearLayout
    private lateinit var shelf: LinearLayout
    private lateinit var shelfScroller: HorizontalScrollView
    private lateinit var shelfRow: LinearLayout
    private var level = 0
    private lateinit var topShelf: LinearLayout
    private lateinit var topRow: LinearLayout
    private var top10: List<Pick> = emptyList()
    private lateinit var tvShelf: LinearLayout
    private lateinit var cinemaShelf: LinearLayout
    private lateinit var cinemaRow: LinearLayout
    private lateinit var soonShelf: LinearLayout
    private lateinit var soonRow: LinearLayout
    /** "Yerli yapımlar": Turkish films and series from today's list. */
    private lateinit var localShelf: LinearLayout
    private lateinit var localRow: LinearLayout
    private var localList: List<Pick> = emptyList()
    /** "Beğendiklerine benzer": films like the ones marked "Beğendim". */
    private lateinit var likeShelf: LinearLayout
    private lateinit var likeRow: LinearLayout
    /** Platforms whose app is on this TV (by the names in today's lists), for "SENDE VAR". */
    private var mine: Set<String> = emptySet()
    /** Placeholders of the first start; gone once the film list is here. */
    private var skeletonView: View? = null

    private fun skeleton(): View {
        val box = ui.vbox()
        fun bar(w: Int, h: Int, top: Int, r: Int = 9) {
            val v = View(this)
            v.background = Fx.Shimmer(Color.parseColor("#1A1B23"), ui.uf(r))
            box.addView(v, ui.ll(ui.u(w), ui.u(h), top = top))
        }
        bar(180, 18, 0)
        bar(760, 96, 22, 14)
        bar(520, 18, 24)
        bar(640, 18, 14)
        val pills = ui.hbox()
        for (w in listOf(240, 170, 200)) {
            val v = View(this)
            v.background = Fx.Shimmer(Color.parseColor("#1A1B23"), ui.uf(32))
            pills.addView(v, ui.ll(ui.u(w), ui.u(64), right = 18))
        }
        box.addView(pills, ui.ll(ui.wrap, ui.wrap, top = 34))
        return box
    }

    private fun hideSkeleton() {
        val v = skeletonView ?: return
        skeletonView = null
        v.animate().alpha(0f).setDuration(D.MED).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
    }

    /** "Son baktıkların": film pages opened lately and not yet in Listem. */
    private lateinit var contShelf: LinearLayout
    /** "Platformlar": one tile per streaming platform; a tile shows what is new there. */
    private lateinit var platShelf: LinearLayout
    private lateinit var platRow: LinearLayout
    private var platSig = "-"
    /** The top half while the remote is in the rows: the poster under it, as on Apple TV. */
    private lateinit var peek: HomePeek
    /** The bar at the top (greeting, weather, clock…): it goes quiet while the remote is elsewhere. */
    private lateinit var topBarView: View
    private val barWake = Runnable { quietBar(false) }
    private lateinit var contRow: LinearLayout
    private var contSig = "-"
    private lateinit var recentShelf: LinearLayout
    private lateinit var recentRow: LinearLayout
    private var recentSig = "-"
    /** Today's new titles for apps on this TV (app key to platform name and films), for the badge on its card. */
    private var appNews: Map<String, Pair<String, List<Pick>>> = emptyMap()
    /** The morning card while it is on screen. */
    private var morning: View? = null
    private var likeList: List<Pick> = emptyList()
    private var cinema: List<Pick> = emptyList()
    private var soon: List<Pick> = emptyList()
    private lateinit var remindPill: TextView
    private lateinit var homePill: TextView
    private lateinit var weatherLayer: WeatherLayer
    private lateinit var blackout: View
    private lateinit var ambientMeta: TextView
    private lateinit var ambientTag: TextView
    /** Home screen films set aside while the screensaver shows cinema films. */
    private var savedPicks: List<Pick>? = null
    /** Films that came out on this day in earlier years, for the screensaver. */
    private var history: List<Pick> = emptyList()
    /** New on streaming platforms in Turkey ("Platformlarda yeni"), with the platform's name. */
    private var platformList: List<Pick> = emptyList()
    /** Bell in the top bar and its unread dot. */
    private lateinit var bell: FrameLayout
    private lateinit var bellDot: TextView
    private var savedIndex = 0
    private lateinit var ambientLine: TextView
    /** Screensaver: "Film gecesine 2 sa 10 dk · Dune". */
    private lateinit var nightLine: TextView
    private lateinit var ambientClock: RollingClock
    /** The clock in the top bar, where the screensaver's clock flies back to. */
    private var barClock: RollingClock? = null
    private lateinit var ambientWeather: LinearLayout
    private lateinit var ambientWeatherIcon: ImageView
    private lateinit var ambientTemp: TextView
    private lateinit var certView: TextView
    /** "▶ NETFLIX" in red, "SİNEMADA": where the film on screen can be watched. */
    private lateinit var whereView: TextView
    /** "Sana göre" rows: their shelves and card rows, and the genre films from pick.json. */
    private val genreShelves = ArrayList<LinearLayout>()
    private val genreCards = ArrayList<LinearLayout>()
    private var genrePools: List<Pair<String, List<Pick>>> = emptyList()
    private var genreShown: List<Pair<String, List<Pick>>> = emptyList()
    /** Films marked watched (cached per refresh) and a signature of watched/liked marks. */
    private var watchedNow: Set<String> = emptySet()
    /** Watched or turned down: left out of every suggestion. */
    private var skipNow: Set<String> = emptySet()
    /** Text size the screen was built with; a change in the settings rebuilds it. */
    private var builtBig = false
    private var markSig = ""
    /** Weather for the weather-matched film: Open-Meteo code (-1 unknown) and °C. */
    private var weatherCode = -1
    private var weatherTempC = 15
    private var moodKey = ""
    private val rolling = ArrayList<RollingClock>()
    private lateinit var tvRow: LinearLayout
    private var top10tv: List<Pick> = emptyList()
    private lateinit var hint: TextView

    // Overlays and modes
    private var menu: FrameLayout? = null
    private var menuOwner: View? = null
    /** The side panel, while it is the open overlay. */
    private var sidePanel: FrameLayout? = null
    private var moving: View? = null
    /** Row being moved up or down, and the card that keeps focus meanwhile. */
    private var movingRow: String? = null
    private var rowOwner: View? = null
    private var rowSig = ""
    private var wiggle: Animator? = null
    private var idle = false
    private var swallowUp = false

    // Data
    private var picks: List<Pick> = emptyList()
    private var pickIndex = 0
    private var imageToken = 0
    // Picture caches with a size limit, so low-memory TVs don't run out.
    private val backdrops = BitmapCache(minOf(96, (Runtime.getRuntime().maxMemory() / 1024 / 1024 / 5).toInt()))
    private val logos = BitmapCache(16)
    private val thumbs = BitmapCache(24)
    private val appColors = HashMap<String, Int>()
    private var dockSig = ""
    /** The film the hero starts with on this start; each start moves one film on. */
    private var heroStart = 0
    /** Small handle at the left edge while the apps live in the side panel. */
    private lateinit var edgeTab: FrameLayout
    private var setupShown = false
    private var shelfSig = "-"
    private var lastPicks = 0L
    private var lastWeather = 0L
    private var updatePulse: Animator? = null
    /** Looks for a new version every 30 minutes while the home screen is open. */
    private val updateCheck = object : Runnable {
        override fun run() {
            Housekeeping.refreshRepos(this@MainActivity)
            Updater.check(this@MainActivity) {
                remoteChanged()
                showUpdatePill()
                updateBell()
                // Never in the middle of a film: the update waits for the next check.
                if (Updater.available && prefs.autoUpdate && Updater.canInstall(this@MainActivity) && !com.nuvio.tv.LeXiaCinema.playing) startUpdate()
            }
            handler.postDelayed(this, 30 * 60 * 1000L)
        }
    }
    /**
     * A scheduled message or featured film starts (or ends) at its minute even between refreshes:
     * every five minutes the home screen looks again.
     */
    private var remoteState = ""
    private val remoteTick = object : Runnable {
        override fun run() {
            remoteChanged()
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    /** Applies the owner's settings again if anything in them changed (or a scheduled one began). */
    private fun remoteChanged() {
        val state = Remote.sig()
        if (state != remoteState) {
            remoteState = state
            applyRemote()
            applyGenres()
        }
    }
    private var loadingPicks = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** Time between films: the screensaver has its own pace. */
    private val rotateMs: Long get() = (if (idle) prefs.saverSec else prefs.rotateSec) * 1000L
    private val clocks = ArrayList<TextClock>()
    private var allPicks: List<Pick> = emptyList()
    private var genreSig = "-"
    private var weatherSig = ""

    private val rotate = Runnable { autoNext() }
    private lateinit var heroTrailer: HeroTrailer
    private val trailerDelay = Runnable { startHeroTrailer() }
    private val idleTimer = Runnable { enterIdle() }
    /** The side panel's glass, whose light glides as the remote moves over the apps. */
    private var sideGlass: LiquidGlass? = null
    /** The screensaver's trailer marathon: trailers play until this time, then the pictures come back. */
    private var saverTrailersUntil = 0L
    /** The screensaver's trailer on screen has shown (it played, not failed). */
    private var saverShown = false
    private val saverTrailer = Runnable { startSaverTrailer() }
    /** Hands the screen back to the TV's own screensaver and sleep setting. */
    private val holdRelease = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }


    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean =
        Pointer.handle(this, ev) || super.dispatchGenericMotionEvent(ev)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // LeXia failed to open three times in a row: a plain page that can still update (Guard.kt).
        if (Guard.safe(this)) {
            startActivity(Intent(this, SafeActivity::class.java))
            finish()
            return
        }
        ui = Ui(this)
        prefs = Prefs(this)
        prefs.homeV2()
        val m = ui.match
        val w = ui.wrap
        screenH = resources.displayMetrics.heightPixels
        val screenW = resources.displayMetrics.widthPixels

        frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)

        val base = View(this)
        base.background = ui.gradient(
            GradientDrawable.Orientation.TL_BR,
            Color.parseColor("#1A1F29"), Color.parseColor("#0A0B0E"), Color.BLACK
        )
        frame.addView(base, ui.fl(m, m))

        stage = FrameLayout(this)
        backA = ImageView(this)
        backB = ImageView(this)
        for (b in listOf(backA, backB)) {
            b.scaleType = ImageView.ScaleType.CENTER_CROP
            b.alpha = 0f
            stage.addView(b, ui.fl(m, m))
        }
        frame.addView(stage, ui.fl(m, m))
        heroTrailer = HeroTrailer(this, stage).also { t ->
            // In the screensaver's trailer marathon the next film comes as soon as one ends; a
            // trailer that could not play leaves its picture for the usual while.
            t.onFinished = { if (idle && saverShown) next() else schedule() }
            // The full-screen trailer (sinema anı) in 1080p; a TV that stutters goes back to 720p for good.
            t.height = if (prefs.heroTrailerHd && !Fx.lite) 1080 else 720
            t.onStutter = {
                prefs.heroTrailerHd = false
                t.height = 720
            }
            t.onShowing = { on ->
                if (::heroStage.isInitialized) heroStage.trailerShowing(on)
                if (idle) saverTrailerShowing(on) else trailerLight(on)
            }
            t.onCues = { if (::heroStage.isInitialized) heroStage.cues(it) }
        }

        // Dip to black between films, like a cut in the cinema.
        blackout = View(this)
        blackout.setBackgroundColor(Color.BLACK)
        blackout.alpha = 0f
        frame.addView(blackout, ui.fl(m, m))

        weatherLayer = WeatherLayer(this)
        weatherLayer.visibility = View.GONE
        frame.addView(weatherLayer, ui.fl(m, m))

        // Cinematic vignette and scrims keep text readable on any picture.
        val vignette = View(this)
        val vg = GradientDrawable()
        vg.gradientType = GradientDrawable.RADIAL_GRADIENT
        vg.colors = intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(150, 0, 0, 0))
        vg.gradientRadius = screenW * 0.78f
        vg.setGradientCenter(0.62f, 0.32f)
        vignette.background = vg
        frame.addView(vignette, ui.fl(m, m))
        val left = View(this)
        left.background = ui.gradient(
            GradientDrawable.Orientation.LEFT_RIGHT,
            Color.argb(215, 0, 0, 0), Color.argb(110, 0, 0, 0), Color.argb(0, 0, 0, 0)
        )
        frame.addView(left, ui.fl((screenW * 0.72f).toInt(), m))
        val bottom = View(this)
        bottom.background = ui.gradient(
            GradientDrawable.Orientation.TOP_BOTTOM,
            Color.argb(0, 0, 0, 0), Color.argb(150, 0, 0, 0), Color.argb(245, 0, 0, 0)
        )
        frame.addView(bottom, ui.fl(m, (screenH * 0.6f).toInt(), Gravity.BOTTOM))
        val top = View(this)
        top.background = ui.gradient(
            GradientDrawable.Orientation.TOP_BOTTOM,
            Color.argb(130, 0, 0, 0), Color.argb(0, 0, 0, 0)
        )
        frame.addView(top, ui.fl(m, ui.u(240), Gravity.TOP))
        scrims.addAll(listOf(vignette, left, bottom, top))
        // Bright pictures (snow, sky, a white room) get a deeper shade behind the text.
        boost = View(this)
        boost.background = ui.gradient(
            GradientDrawable.Orientation.BL_TR,
            Color.argb(170, 0, 0, 0), Color.argb(90, 0, 0, 0), Color.argb(0, 0, 0, 0)
        )
        boost.alpha = 0f
        frame.addView(boost, ui.fl(m, m))
        // The light of the hour used to lie over the picture and made films look washed out (the
        // viewer asked it gone): it stays hidden. The focused app's colour stays, softer than before.
        daylight = View(this)
        daylight.visibility = View.GONE
        frame.addView(daylight, ui.fl(m, m))
        val tint = View(this)
        tint.background = tintDrawable
        frame.addView(tint, ui.fl(m, m))
        ambience = FrameLayout(this)
        frame.addView(ambience, ui.fl(m, m))

        content = FrameLayout(this)
        frame.addView(content, ui.fl(m, m))
        heroStage = HeroCinema(
            this, ui, frame, content, stage, scrims,
            // Rain or snow, the hour's light and the app's colour wash: none of them over the film.
            veils = listOf(weatherLayer, daylight, tint, boost),
            film = { picks.getOrNull(pickIndex) },
            logo = { logoView.drawable?.takeIf { logoView.visibility == View.VISIBLE } },
            // A trailer not known to be dubbed or original may carry Turkish subtitles in its picture.
            wordsInPicture = {
                val key = if (::heroTrailer.isInitialized) heroTrailer.where()?.first else null
                val label = picks.getOrNull(pickIndex)?.trailers?.firstOrNull { it.key == key }?.label?.lowercase(tr).orEmpty()
                !("dubla" in label || "orijinal" in label || "original" in label) || "altyaz" in label
            },
            captionsAllowed = { heroCaptionsAllowed(heroTrailer.where()?.first, picks.getOrNull(pickIndex)) }
        )

        root = ui.vbox()
        root.clipChildren = false
        root.clipToPadding = false
        root.setPadding(ui.u(110), ui.u(52), ui.u(110), ui.u(50))
        content.addView(root, ui.fl(m, screenH))

        // At least 76 high, taller when the greeting needs it (big text, small TVs): the date never gets cut off.
        val bar = topBar()
        topBarView = bar
        bar.post { sayHello() }
        bar.minimumHeight = ui.u(76)
        root.addView(bar, ui.ll(m, w))
        root.addView(View(this), ui.ll(m, 0, weight = 1f))
        root.addView(heroBlock(), ui.ll(m, w))

        row = ui.hbox()
        scroller = rowScroller(row)
        val slp = ui.ll(m, w, top = 26)
        slp.leftMargin = -ui.u(30)
        slp.rightMargin = -ui.u(30)
        root.addView(scroller, slp)

        val films = topBlock("BU HAFTANIN TOP 10 FİLMİ")
        topShelf = films.first
        topRow = films.second
        root.addView(topShelf, ui.ll(m, ui.u(TOP_H)))
        val series = topBlock("BU HAFTANIN TOP 10 DİZİSİ")
        tvShelf = series.first
        tvRow = series.second
        root.addView(tvShelf, ui.ll(m, ui.u(TOP_H)))
        val now = topBlock("ŞU AN SİNEMALARDA")
        cinemaShelf = now.first
        cinemaRow = now.second
        root.addView(cinemaShelf, ui.ll(m, ui.u(POSTER_H)))
        val next = topBlock("YAKINDA VİZYONDA")
        soonShelf = next.first
        soonRow = next.second
        root.addView(soonShelf, ui.ll(m, ui.u(POSTER_H)))
        for (i in 0 until 3) {
            val g = topBlock("")
            genreShelves.add(g.first)
            genreCards.add(g.second)
            root.addView(g.first, ui.ll(m, ui.u(POSTER_H)))
        }
        val local = topBlock("YERLİ YAPIMLAR")
        localShelf = local.first
        localRow = local.second
        root.addView(localShelf, ui.ll(m, ui.u(POSTER_H)))
        val like = topBlock("BEĞENDİKLERİNE BENZER")
        likeShelf = like.first
        likeRow = like.second
        root.addView(likeShelf, ui.ll(m, ui.u(POSTER_H)))
        val cont = topBlock("YARIM KALANLAR")
        contShelf = cont.first
        contRow = cont.second
        root.addView(contShelf, ui.ll(m, ui.u(POSTER_H)))
        val plat = topBlock("PLATFORMLAR")
        platShelf = plat.first
        platRow = plat.second
        root.addView(platShelf, ui.ll(m, ui.u(PLAT_H)))
        val seen = topBlock("SON BAKTIKLARIN")
        recentShelf = seen.first
        recentRow = seen.second
        root.addView(recentShelf, ui.ll(m, ui.u(POSTER_H)))
        root.addView(shelfBlock(), ui.ll(m, ui.u(SHELF_H)))

        peek = HomePeek(this, ui, stage, ambience, content)

        edgeTab = edgeHandle()
        content.addView(edgeTab, ui.fl(ui.u(44), ui.u(150), Gravity.START or Gravity.CENTER_VERTICAL))

        ambient = ambientBlock()
        frame.addView(ambient, ui.fl(m, m))

        hint = ui.text("", 21, C.TEXT, 500)
        hint.background = ui.round(Color.parseColor("#D91C1C1F"), 28, 1)
        hint.setPadding(ui.u(30), ui.u(14), ui.u(30), ui.u(14))
        hint.visibility = View.GONE
        val hlp = ui.fl(w, w, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        hlp.topMargin = ui.u(56)
        frame.addView(hint, hlp)

        setContentView(frame)
        builtBig = prefs.bigText
        heroStart = prefs.heroTurn
        prefs.heroTurn = heroStart + 1
        Fx.lite = prefs.lite
        Sound.attach(this)
        watchNetwork()
        openStartApp()

        content.alpha = 0f
        content.animate().alpha(1f).setStartDelay(100).setDuration(800)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    /** Shows the first-run guide on a TV that isn't set up yet; a set-up TV is marked done quietly. */
    private fun maybeStartSetup() {
        if (prefs.setupDone || setupShown) return
        if (!BuildConfig.SETUP && Updater.canInstall(this) && HomeButtonService.isOn(this)) {
            prefs.setupDone = true
            return
        }
        setupShown = true
        startActivity(android.content.Intent(this, SetupActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** First start after the TV boots: open the chosen app, if any. */
    private fun openStartApp() {
        val boot = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        val fresh = Math.abs(boot - prefs.lastBoot) > 60000L
        prefs.lastBoot = boot
        val key = prefs.startApp
        if (!fresh || key.isEmpty()) return
        val app = Apps.all(this).firstOrNull { it.key == key } ?: return
        HomeButtonService.opened(app.pkg)
        handler.postDelayed({ open(Apps.launchIntent(app)) }, 800)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::heroTrailer.isInitialized) heroTrailer.release()
        if (::peek.isInitialized) peek.release()
        if (::heroStage.isInitialized) heroStage.release()
        try {
            netCallback?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
        }
    }

    // ---------- Layout pieces ----------

    private fun newClock(): TextClock {
        val c = TextClock(this)
        applyClock(c)
        clocks.add(c)
        return c
    }

    private fun rollingClock(size: Int, weight: Int): RollingClock {
        val c = RollingClock(this, ui, size, weight, C.TEXT)
        c.use24 = prefs.clock24
        rolling.add(c)
        return c
    }

    private fun applyClock(c: TextClock) {
        val f = if (prefs.clock24) "HH:mm" else "h:mm a"
        c.format24Hour = f
        c.format12Hour = f
    }

    private fun rowScroller(r: LinearLayout): HorizontalScrollView {
        r.clipChildren = false
        r.clipToPadding = false
        val s = HorizontalScrollView(this)
        s.isHorizontalScrollBarEnabled = false
        s.overScrollMode = View.OVER_SCROLL_NEVER
        s.clipChildren = false
        s.clipToPadding = false
        s.setPadding(ui.u(30), ui.u(44), ui.u(30), ui.u(4))
        Fx.softEdges(s, ui)
        s.addView(r)
        return s
    }

    private fun topBar(): View {
        val bar = ui.hbox()
        val col = ui.vbox()
        greetingBox = col
        greeting = ui.text("", 34, C.TEXT, 600)
        greeting.letterSpacing = -0.01f
        greeting.maxLines = 1
        greeting.ellipsize = TextUtils.TruncateAt.END
        dateText = ui.text("", 17, C.TEXT3, 600)
        dateText.letterSpacing = 0.18f
        col.addView(greeting, ui.ll(ui.match, ui.wrap))
        col.addView(dateText, ui.ll(ui.wrap, ui.wrap, top = 6))
        // The greeting takes all the free space on the left, so it is never cut short.
        bar.addView(col, ui.ll(0, ui.wrap, weight = 1f))
        // Apple TV's top bar: at rest only the weather and the clock (and what needs a look: an
        // update, no internet, a new notice); the buttons open beside the clock when the remote
        // comes up to the bar, one after another, and close again when it leaves.
        val cluster = ui.hbox()
        cluster.gravity = Gravity.CENTER_VERTICAL
        barCluster = cluster
        val tools = ui.hbox()
        tools.gravity = Gravity.CENTER_VERTICAL
        barTools = tools

        remindPill = ui.pill("", 56, 20, primary = true)
        (remindPill.layoutParams as LinearLayout.LayoutParams).rightMargin = ui.u(18)
        remindPill.visibility = View.GONE
        cluster.addView(remindPill)

        // Until the Home button opens LeXia: a quiet reminder for the first five days.
        homePill = ui.iconPill("Ana Sayfa tuşunu ayarla", R.drawable.ic_tv, 56, 20)
        (homePill.layoutParams as LinearLayout.LayoutParams).rightMargin = ui.u(18)
        homePill.visibility = View.GONE
        homePill.isLongClickable = true
        homePill.setOnClickListener { HomeGuide.open(this) }
        homePill.setOnLongClickListener {
            Dialogs.confirm(this, "Hatırlatma kapansın mı?", "İstediğin zaman Ayarlar › Ana Sayfa tuşunu yönlendir'den kurabilirsin.") {
                prefs.homeNudgeOff = true
                refreshHomeNudge()
            }
            true
        }
        cluster.addView(homePill)

        updatePill = ui.pill("Güncelleme geldi", 56, 20, primary = true)
        (updatePill.layoutParams as LinearLayout.LayoutParams).apply { rightMargin = ui.u(18); leftMargin = 0 }
        updatePill.visibility = View.GONE
        updatePill.setOnClickListener { startUpdate() }
        val pillStyle = updatePill.onFocusChangeListener
        updatePill.setOnFocusChangeListener { v, f ->
            pillStyle?.onFocusChange(v, f)
            // Hold still while selected; pulse again when focus leaves.
            if (f) {
                updatePulse?.pause()
                updatePill.alpha = 1f
            } else {
                updatePulse?.resume()
            }
        }

        // An update waiting: in sight even at rest.
        cluster.addView(updatePill)

        // No internet: the network button shows (it opens the TV's network settings); online it is not needed.
        val net = ui.iconButton(R.drawable.ic_wifi, 60)
        (net.layoutParams as LinearLayout.LayoutParams).rightMargin = ui.u(24)
        netImg = net.getChildAt(0) as ImageView
        net.setOnClickListener { SystemSettings.open(this, SystemSettings.Screen.NETWORK) }
        net.visibility = View.GONE
        netButton = net
        cluster.addView(net)

        weatherBox = ui.hbox()
        weatherBox.visibility = View.GONE
        weatherIcon = ImageView(this)
        weatherIcon.setColorFilter(C.TEXT, PorterDuff.Mode.SRC_IN)
        weatherBox.addView(weatherIcon, ui.ll(ui.u(34), ui.u(34)))
        tempView = ui.text("", 30, C.TEXT, 500)
        weatherBox.addView(tempView, ui.ll(ui.wrap, ui.wrap, left = 10))
        val sep = View(this)
        sep.setBackgroundColor(C.STROKE)
        weatherBox.addView(sep, ui.ll(maxOf(1, ui.u(2)), ui.u(30), left = 26, right = 26))
        cluster.addView(weatherBox)

        barClock = rollingClock(34, 500)
        cluster.addView(barClock)
        // A new notice: a small pink dot beside the clock; the bell itself opens with the buttons.
        newDot = View(this)
        newDot.background = ui.round(Color.parseColor("#FF4D8D"), 6)
        newDot.visibility = View.GONE
        cluster.addView(newDot, ui.ll(ui.u(12), ui.u(12), left = 14))
        bar.addView(cluster)

        // "Ne izlesem?": a die among the buttons.
        val ask = ui.iconButton(R.drawable.ic_dice, 60)
        (ask.layoutParams as LinearLayout.LayoutParams).leftMargin = ui.u(30)
        ask.setOnClickListener { askWhatToWatch() }
        ask.contentDescription = "Ne izlesem?"
        tools.addView(ask)
        askPill = ask

        bell = ui.iconButton(R.drawable.ic_bell, 60)
        (bell.layoutParams as LinearLayout.LayoutParams).leftMargin = ui.u(14)
        bell.setOnClickListener { openNotices() }
        // How many new notices: a small pink count on the bell.
        bellDot = ui.text("", 13, Color.WHITE, 800)
        bellDot.gravity = Gravity.CENTER
        bellDot.includeFontPadding = false
        bellDot.minWidth = ui.u(24)
        bellDot.setPadding(ui.u(6), 0, ui.u(6), 0)
        bellDot.background = ui.round(Color.parseColor("#FF4D8D"), 12, 2, Color.argb(220, 12, 12, 16))
        bellDot.visibility = View.GONE
        val dlp = ui.fl(ui.wrap, ui.u(24), Gravity.TOP or Gravity.END)
        dlp.setMargins(0, ui.u(4), ui.u(4), 0)
        bell.addView(bellDot, dlp)
        tools.addView(bell)

        // Search lives in Keşfet (its search button opens the search page); the bar keeps fewer buttons.

        // Keşfet: every film of the day, with filters.
        val explore = ui.iconButton(R.drawable.ic_film, 60)
        (explore.layoutParams as LinearLayout.LayoutParams).leftMargin = ui.u(14)
        explore.setOnClickListener { openExplore() }
        tools.addView(explore)

        // İzlediklerim: what was left half way, the series and where they are, what was finished.
        val watched = ui.iconButton(R.drawable.ic_history, 60)
        (watched.layoutParams as LinearLayout.LayoutParams).leftMargin = ui.u(14)
        watched.setOnClickListener { openHistory() }
        tools.addView(watched)

        val g = ui.iconButton(R.drawable.ic_gear, 60)
        (g.layoutParams as LinearLayout.LayoutParams).leftMargin = ui.u(14)
        g.setOnClickListener { openPanel() }
        tools.addView(g)
        gear = g
        bar.addView(tools)
        // At rest the buttons are closed: once measured, the clock moves over to the right edge.
        tools.post { setBarOpen(false, animate = false) }
        bar.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> setBarOpen(now != null && isInBar(now)) }
        return bar
    }

    private lateinit var greetingBox: View
    private val greetingAway = Runnable { hideGreeting() }

    /**
     * "İyi akşamlar, Mert" and the date say hello when the home screen opens (and when it wakes
     * from the screensaver), then slide up and fade away; they do not stay taking room.
     */
    private fun sayHello() {
        if (!::greetingBox.isInitialized) return
        handler.removeCallbacks(greetingAway)
        greetingBox.animate().cancel()
        greetingBox.visibility = View.VISIBLE
        greetingBox.alpha = 0f
        greetingBox.translationY = ui.uf(14)
        greetingBox.animate().alpha(1f).translationY(0f).setDuration(600).setInterpolator(DecelerateInterpolator(2f)).start()
        handler.postDelayed(greetingAway, GREETING_MS)
    }

    private fun hideGreeting() {
        if (!::greetingBox.isInitialized) return
        greetingBox.animate().cancel()
        greetingBox.animate().alpha(0f).translationY(-ui.uf(18)).setDuration(700)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
            .withEndAction { greetingBox.visibility = View.INVISIBLE }.start()
    }

    private lateinit var barCluster: LinearLayout
    private lateinit var barTools: LinearLayout
    private lateinit var netButton: View
    private lateinit var newDot: View
    private var barOpen = true

    /** Opens the top bar's buttons beside the clock (one after another), or closes them. */
    private fun setBarOpen(open: Boolean, animate: Boolean = true) {
        if (!::barTools.isInitialized || (open == barOpen && animate)) return
        barOpen = open
        val w = barTools.width.toFloat()
        val ease = DecelerateInterpolator(2f)
        barCluster.animate().cancel()
        if (!animate || Fx.lite) barCluster.translationX = if (open) 0f else w
        else barCluster.animate().translationX(if (open) 0f else w).setDuration(if (open) 380L else 450L).setInterpolator(ease).start()
        for (i in 0 until barTools.childCount) {
            val c = barTools.getChildAt(i)
            c.animate().cancel()
            if (!animate || Fx.lite) {
                c.alpha = if (open) 1f else 0f
                c.translationX = 0f
            } else if (open) {
                c.alpha = 0f
                c.translationX = -ui.uf(18)
                c.animate().alpha(1f).translationX(0f).setStartDelay(60L + i * 45L).setDuration(300).setInterpolator(ease).start()
            } else {
                c.animate().alpha(0f).setStartDelay(0).setDuration(220).start()
            }
        }
    }

    private fun heroBlock(): View {
        hero = ui.vbox()
        hero.clipChildren = false
        heroText = ui.vbox()
        heroText.alpha = 0f

        tagView = ui.label("")
        logoView = ImageView(this)
        logoView.adjustViewBounds = true
        logoView.maxHeight = ui.u(190)
        logoView.maxWidth = ui.u(700)
        logoView.scaleType = ImageView.ScaleType.FIT_START
        logoView.visibility = View.GONE
        titleView = ui.text("", 84, C.TEXT, 800)
        titleView.letterSpacing = -0.025f
        titleView.maxLines = 2
        titleView.ellipsize = TextUtils.TruncateAt.END
        titleView.maxWidth = ui.u(1000)
        titleView.setLineSpacing(0f, 0.95f)
        titleView.setShadowLayer(ui.uf(30), 0f, ui.uf(4), Color.argb(130, 0, 0, 0))

        val metaRow = ui.hbox()
        badge = ui.hbox()
        val imdb = ui.text("IMDb", 15, Color.BLACK, 800)
        imdb.setPadding(ui.u(9), ui.u(3), ui.u(9), ui.u(3))
        imdb.background = ui.round(Color.parseColor("#F5C518"), D.BADGE)
        badge.addView(imdb)
        imdbScore = ui.text("", 22, C.TEXT, 600)
        badge.addView(imdbScore, ui.ll(ui.wrap, ui.wrap, left = 12, right = 26))
        whereView = ui.whereBadge()
        whereView.visibility = View.GONE
        metaRow.addView(whereView, ui.ll(ui.wrap, ui.wrap, right = 22))
        metaRow.addView(badge)
        certView = ui.certBadge()
        metaRow.addView(certView, ui.ll(ui.wrap, ui.wrap, right = 24))
        metaView = ui.text("", 21, C.TEXT2, 500)
        metaRow.addView(metaView)

        whyView = ui.text("", 25, C.TEXT, 500)
        whyView.maxWidth = ui.u(900)
        descView = ui.text("", 20, C.TEXT3, 400)
        descView.maxWidth = ui.u(860)
        descView.maxLines = 2
        descView.ellipsize = TextUtils.TruncateAt.END
        descView.setLineSpacing(0f, 1.25f)

        heroText.addView(tagView)
        heroText.addView(logoView, ui.ll(ui.wrap, ui.wrap, top = 20))
        heroText.addView(titleView, ui.ll(ui.wrap, ui.wrap, top = 12))
        heroText.addView(metaRow, ui.ll(ui.wrap, ui.wrap, top = 20))
        heroText.addView(whyView, ui.ll(ui.wrap, ui.wrap, top = 20))
        heroText.addView(descView, ui.ll(ui.wrap, ui.wrap, top = 8))
        // First start, before any film list: soft placeholders where the film will be, not an empty screen.
        if (!java.io.File(filesDir, "pick.json").exists()) {
            val sk = skeleton()
            hero.addView(sk)
            skeletonView = sk
        }
        hero.addView(heroText)

        buttons = ui.hbox()
        buttons.clipChildren = false
        buttons.visibility = View.INVISIBLE
        // The film (or series) left half way, when it is the one on show: on from where it stopped.
        resumeBtn = ui.pill("", 64, 23, primary = true)
        resumeBtn.visibility = View.GONE
        resumeBtn.setOnClickListener { picks.getOrNull(pickIndex)?.let { resume(it) } }
        trailerBtn = ui.pill("▶   Fragmanı izle", 64, 23, primary = true)
        trailerBtn.setOnClickListener { picks.getOrNull(pickIndex)?.let { trailer(it) } }
        listBtn = ui.pill("+   Listeme ekle", 64, 23)
        listBtn.setOnClickListener { toggleList() }
        val infoBtn = ui.pill("Detaylar", 64, 23)
        infoBtn.setOnClickListener { picks.getOrNull(pickIndex)?.let { openDetail(it, null, null) } }
        val nextBtn = ui.pill("Sonraki", 64, 23)
        nextBtn.setOnClickListener { next() }
        for (b in listOf(resumeBtn, trailerBtn, infoBtn, listBtn, nextBtn)) {
            val old = b.onFocusChangeListener
            b.setOnFocusChangeListener { v, f ->
                old?.onFocusChange(v, f)
                if (f) {
                    shiftTo(0)
                    tintTo(filmTint)
                }
            }
        }
        progress = ui.hbox()
        buttons.addView(resumeBtn)
        buttons.addView(trailerBtn)
        buttons.addView(infoBtn)
        buttons.addView(listBtn)
        buttons.addView(nextBtn)
        buttons.addView(progress, ui.ll(ui.wrap, ui.wrap, left = 22))
        hero.addView(buttons, ui.ll(ui.wrap, ui.wrap, top = 30))
        return hero
    }

    /** A Top 10 row: its title and a scrolling row of ranked posters. */
    private fun topBlock(title: String): Pair<LinearLayout, LinearLayout> {
        val shelf = ui.vbox()
        shelf.clipChildren = false
        shelf.visibility = View.GONE
        val label = ui.label(title)
        shelf.addView(label, ui.ll(ui.wrap, ui.wrap, top = 40))
        val row = ui.hbox()
        val sc = rowScroller(row)
        sc.setPadding(ui.u(30), ui.u(24), ui.u(30), ui.u(4))
        val lp = ui.ll(ui.match, ui.wrap)
        lp.leftMargin = -ui.u(30)
        lp.rightMargin = -ui.u(30)
        shelf.addView(sc, lp)
        return shelf to row
    }

    /** Big outlined rank number with the poster leaning on it, as on streaming apps. */
    private fun topCard(rank: Int, p: Pick, rowId: String): View {
        val box = FrameLayout(this)
        box.clipChildren = false
        val num = ui.text("$rank", 210, Color.argb(200, 255, 255, 255), 800)
        num.paint.style = android.graphics.Paint.Style.STROKE
        num.paint.strokeWidth = ui.uf(4)
        num.letterSpacing = -0.06f
        num.includeFontPadding = false
        val nlp = ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.START)
        nlp.bottomMargin = -ui.u(18)
        box.addView(num, nlp)
        val poster = FrameLayout(this)
        poster.isFocusable = true
        poster.isClickable = true
        poster.background = ui.round(C.CARD, D.CARD)
        poster.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        poster.addView(img, ui.fl(ui.match, ui.match))
        val strip = episodeBadge(p)
        strip?.let { addBadge(poster, it, episode = true) }
        WatchMarks.add(ui, poster, WatchMarks.of(playback, p), aboveStrip = strip != null)
        if (p.key in watchedNow) addWatchedMark(poster)
        val fx = Fx.decorate(ui, poster, D.CARD)
        Img.load(this, "w500", p.poster, ui.u(180), img)
        poster.setOnFocusChangeListener { v, f ->
            ui.lift(v, f, 1.08f, 30)
            fx(f)
            if (f) {
                shiftTo(levelOf(rowId))
                tintTo(0)
                peek.film(p, rowTitle(rowId).orEmpty())
            }
        }
        poster.setTag(R.id.lexia_pick, p)
        poster.setOnClickListener { openDetail(p, poster, img) }
        poster.isLongClickable = true
        poster.setOnLongClickListener {
            rowMenu(rowId, poster)
            true
        }
        val plp = ui.fl(ui.u(180), ui.u(270), Gravity.END or Gravity.BOTTOM)
        box.addView(poster, plp)
        box.layoutParams = ui.ll(ui.u(if (rank == 10) 330 else 290), ui.u(270), right = 18)
        return box
    }

    private fun refreshTop() {
        topRow.removeAllViews()
        top10.forEachIndexed { i, p -> topRow.addView(topCard(i + 1, p, "top")) }
        tvRow.removeAllViews()
        top10tv.forEachIndexed { i, p -> tvRow.addView(topCard(i + 1, p, "tv")) }
        cinemaRow.removeAllViews()
        for (p in cinema) cinemaRow.addView(posterCard(p, "cinema", "", p.genres.take(2).joinToString(", ")))
        soonRow.removeAllViews()
        for (p in soon) soonRow.addView(posterCard(p, "soon", releaseLabel(p.release), countdown(p.release)))
        localRow.removeAllViews()
        for (p in localList.filter { it.key !in skipNow }) localRow.addView(posterCard(p, "local", "", scoreLine(p)))
        refreshLike()
        showTopRows()
    }

    private fun showTopRows() {
        val on = prefs.showPicks
        val hidden = prefs.hiddenRows
        // Rows the owner switched off for everyone (admin page) stay hidden too.
        fun vis(id: String, has: Boolean) = if (has && id !in hidden && !Remote.rowOff(id)) View.VISIBLE else View.GONE
        topShelf.visibility = vis("top", top10.isNotEmpty() && on)
        tvShelf.visibility = vis("tv", top10tv.isNotEmpty() && on)
        cinemaShelf.visibility = vis("cinema", cinema.isNotEmpty() && on && prefs.cinema)
        soonShelf.visibility = vis("soon", soon.isNotEmpty() && on && prefs.cinema)
        for (i in 0 until 3) genreShelves[i].visibility = vis("g${i + 1}", genreShown.size > i && on)
        // Empty Listem shows a card that says how to fill it, while there are films to add.
        shelf.visibility = vis("list", prefs.watchlist.isNotEmpty() || (on && top10.isNotEmpty()))
        localShelf.visibility = vis("local", localList.isNotEmpty() && on)
        likeShelf.visibility = vis("like", likeList.size >= 4 && on)
        contShelf.visibility = vis("cont", contRow.childCount > 0)
        platShelf.visibility = vis("plat", platRow.childCount > 0 && on)
        recentShelf.visibility = vis("recent", recentRow.childCount > 0)
        updateRootHeight()
        fadeRows()
    }

    private var barQuiet = false

    private fun isInBar(v: View): Boolean {
        var x: View? = v
        while (x != null) {
            if (x === topBarView) return true
            x = x.parent as? View
        }
        return false
    }

    /** Apple TV's quiet top bar: dimmed while the remote is on the films, full on it. */
    private fun quietBar(quiet: Boolean) {
        if (quiet == barQuiet) return
        barQuiet = quiet
        topBarView.animate().alpha(if (quiet) 0.32f else 1f).setDuration(if (quiet) 500L else 350L).start()
    }

    /** One tile per platform with new titles today, the user's own platforms first. */
    private fun refreshPlatforms() {
        val pool = (platformList + allPicks + localList).distinctBy { it.key }
        val by = LinkedHashMap<String, MutableList<Pick>>()
        for (p in platformList) if (p.provider.isNotEmpty()) by.getOrPut(p.provider) { ArrayList() }.add(p)
        for (p in pool) for (name in p.where) {
            val list = by.getOrPut(name) { ArrayList() }
            if (list.none { it.key == p.key }) list.add(p)
        }
        val mineFirst = prefs.platforms
        val order = by.entries.filter { it.value.size >= 2 }
            .sortedWith(compareByDescending<Map.Entry<String, MutableList<Pick>>> { it.key in mineFirst }.thenByDescending { it.value.size })
        val sig = order.joinToString("|") { it.key + ":" + it.value.size }
        if (sig == platSig) return
        platSig = sig
        platRow.removeAllViews()
        for ((name, films) in order.take(12)) {
            val fresh = films.sortedByDescending { it.release }
            platRow.addView(platformTile(name, fresh))
        }
        showTopRows()
    }

    /** A platform's tile: its colours over its newest film's picture, its name large. */
    private fun platformTile(name: String, films: List<Pick>): View {
        val box = FrameLayout(this)
        box.isFocusable = true
        box.isClickable = true
        val (deep, light) = HomePeek.colors(name)
        box.background = ui.round(deep, D.CARD)
        box.clipToOutline = true
        films.firstOrNull { it.backdrop.isNotEmpty() }?.let { f ->
            val img = ImageView(this)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            box.addView(img, ui.fl(ui.match, ui.match))
            Img.load(this, "w500", f.backdrop, ui.u(330), img)
        }
        val wash = View(this)
        wash.background = ui.gradient(GradientDrawable.Orientation.BL_TR,
            Color.argb(245, Color.red(deep), Color.green(deep), Color.blue(deep)),
            Color.argb(175, Color.red(light), Color.green(light), Color.blue(light)))
        box.addView(wash, ui.fl(ui.match, ui.match))
        val label = ui.vbox()
        val nameView = ui.text(name, 32, Color.WHITE, 800)
        nameView.maxLines = 1
        nameView.ellipsize = TextUtils.TruncateAt.END
        label.addView(nameView)
        label.addView(ui.text("${films.size} yapım", 17, Color.argb(210, 255, 255, 255), 600), ui.ll(ui.wrap, ui.wrap, top = 2))
        val lp = ui.fl(ui.match, ui.wrap, Gravity.BOTTOM or Gravity.START)
        lp.setMargins(ui.u(22), 0, ui.u(16), ui.u(18))
        box.addView(label, lp)
        val ring = View(this)
        ring.background = ui.round(Color.TRANSPARENT, D.CARD, 4, Color.WHITE)
        ring.alpha = 0f
        box.addView(ring, ui.fl(ui.match, ui.match))
        box.setOnFocusChangeListener { v, f ->
            ui.lift(v, f, 1.07f, 30)
            ring.animate().alpha(if (f) 1f else 0f).setDuration(D.FAST).start()
            if (f) {
                shiftTo(levelOf("plat"))
                tintTo(0)
                peek.platform(name, films)
            }
        }
        box.setOnClickListener { ExploreActivity.openPlatform(this, name, films.count { it.isSeries } > films.size / 2) }
        box.isLongClickable = true
        box.setOnLongClickListener {
            rowMenu("plat", box)
            true
        }
        box.layoutParams = ui.ll(ui.u(330), ui.u(186), right = 24)
        return box
    }

    /** A scene card (16:9) for "Yarım kalanlar": the film's picture, its name, the bar where it was left. */
    private fun wideCard(p: Pick, rowId: String): View {
        val col = ui.vbox()
        col.clipChildren = false
        val box = FrameLayout(this)
        box.isFocusable = true
        box.isClickable = true
        box.background = ui.round(C.CARD, D.CARD)
        box.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        box.addView(img, ui.fl(ui.match, ui.match))
        Img.load(this, "w780", p.backdrop, ui.u(420), img)
        val shade = View(this)
        shade.background = ui.gradient(GradientDrawable.Orientation.BOTTOM_TOP, Color.argb(210, 0, 0, 0), Color.TRANSPARENT)
        box.addView(shade, ui.fl(ui.match, ui.u(140), Gravity.BOTTOM))
        val name = ui.text(p.title, 24, C.TEXT, 800)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        val nlp = ui.fl(ui.match, ui.wrap, Gravity.BOTTOM or Gravity.START)
        nlp.setMargins(ui.u(18), 0, ui.u(18), ui.u(30))
        box.addView(name, nlp)
        WatchMarks.add(ui, box, WatchMarks.of(playback, p))
        val fx = Fx.decorate(ui, box, D.CARD)
        box.setOnFocusChangeListener { v, f ->
            ui.lift(v, f, 1.06f, 30)
            fx(f)
            if (f) {
                shiftTo(levelOf(rowId))
                tintTo(0)
                peek.film(p, rowTitle(rowId).orEmpty())
            }
        }
        box.setTag(R.id.lexia_pick, p)
        box.setOnClickListener { openDetail(p, box, img) }
        box.isLongClickable = true
        box.setOnLongClickListener {
            rowMenu(rowId, box)
            true
        }
        col.addView(box, ui.ll(ui.u(420), ui.u(236)))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 26)
        return col
    }

    private fun shelfOf(id: String): Pair<View, Int> = when (id) {
        "top" -> topShelf to ui.u(TOP_H)
        "tv" -> tvShelf to ui.u(TOP_H)
        "cinema" -> cinemaShelf to ui.u(POSTER_H)
        "soon" -> soonShelf to ui.u(POSTER_H)
        "g1" -> genreShelves[0] to ui.u(POSTER_H)
        "g2" -> genreShelves[1] to ui.u(POSTER_H)
        "g3" -> genreShelves[2] to ui.u(POSTER_H)
        "local" -> localShelf to ui.u(POSTER_H)
        "like" -> likeShelf to ui.u(POSTER_H)
        "cont" -> contShelf to ui.u(POSTER_H)
        "plat" -> platShelf to ui.u(PLAT_H)
        "recent" -> recentShelf to ui.u(POSTER_H)
        else -> shelf to ui.u(SHELF_H)
    }

    /** The rows below the apps, in the user's order, with their heights. */
    private fun rows(): List<Pair<View, Int>> = prefs.rowOrder.map { shelfOf(it) }

    /** Position of a row for [shiftTo]: 1 = first row under the apps. */
    private fun levelOf(id: String) = prefs.rowOrder.indexOf(id) + 1

    /** Puts the rows under the apps in the saved order. */
    private fun arrangeRows() {
        val order = prefs.rowOrder
        val sig = order.joinToString(",")
        if (sig == rowSig) return
        rowSig = sig
        val views = order.map { shelfOf(it).first }
        for (v in views) root.removeView(v)
        for (v in views) root.addView(v)
        if (level > 0) {
            level = -1
            shiftTo(0)
        }
    }

    private fun updateRootHeight() {
        val lp = root.layoutParams as FrameLayout.LayoutParams
        lp.height = screenH + extraBelow(Prefs.ROWS.size)
        root.layoutParams = lp
        val r = rows()
        if (level > 0 && r[level - 1].first.visibility != View.VISIBLE) shiftTo(0)
    }

    /** Height of the visible rows below the apps, down to and including row [upTo]. */
    private fun extraBelow(upTo: Int): Int {
        var h = 0
        rows().forEachIndexed { i, (v, height) -> if (i < upTo && v.visibility == View.VISIBLE) h += height }
        return h
    }

    /** Poster with an optional date badge and a line under it, for the cinema rows. */
    private fun posterCard(p: Pick, rowId: String, badge: String, line: String): View {
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
        Img.load(this, "w500", p.poster, ui.u(180), img)
        if (badge.isNotEmpty()) addBadge(poster, badge)
        else addMoneyTag(poster, p)
        val strip = if (badge.isEmpty()) episodeBadge(p) else null
        strip?.let { addBadge(poster, it, episode = true) }
        WatchMarks.add(ui, poster, WatchMarks.of(playback, p), aboveStrip = strip != null)
        if (p.key in watchedNow) addWatchedMark(poster)
        else if (playback.isAvailable(p.key)) addPlayableMark(poster)
        val fx = Fx.decorate(ui, poster, D.CARD)
        val title = ui.text(p.title, 18, C.TEXT, 600)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        // Apple TV's quiet rows: the name and the line under a poster show only while it has the remote.
        title.alpha = 0f
        val sub = ui.text(line, 15, C.TEXT3, 500)
        sub.maxLines = 1
        sub.ellipsize = TextUtils.TruncateAt.END
        sub.alpha = 0f
        poster.setOnFocusChangeListener { v, f ->
            ui.lift(v, f, 1.08f, 30)
            fx(f)
            title.animate().alpha(if (f) 1f else 0f).setDuration(D.FAST).start()
            sub.animate().alpha(if (f) 1f else 0f).setDuration(D.FAST).start()
            if (f) {
                shiftTo(levelOf(rowId))
                tintTo(0)
                peek.film(p, rowTitle(rowId).orEmpty())
            }
        }
        poster.setTag(R.id.lexia_pick, p)
        poster.setOnClickListener { openDetail(p, poster, img) }
        poster.isLongClickable = true
        poster.setOnLongClickListener {
            rowMenu(rowId, poster)
            true
        }
        col.addView(poster, ui.ll(ui.u(180), ui.u(270)))
        col.addView(title, ui.ll(ui.u(180), ui.wrap, top = 14))
        if (line.isNotEmpty()) col.addView(sub, ui.ll(ui.u(180), ui.wrap, top = 3))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 26)
        return col
    }

    /**
     * A series' episode strip: a coming episode ("Bugün yeni bölüm", "Yeni bölüm · Perşembe"),
     * else one that came out this week and is not watched to the end ("Yeni bölüm çıktı").
     */
    private fun episodeBadge(p: Pick): String? {
        Episodes.of(p)?.let { return Episodes.badge(it) }
        val recent = Episodes.recent(p) ?: return null
        val id = p.imdbId.ifBlank { if (p.id > 0) "tmdb:${p.id}" else "" }
        if (id.isNotEmpty() && playback.isFinished("$id:${recent.season}:${recent.episode}")) return null
        return "Yeni bölüm çıktı"
    }

    private val playback by lazy { MediaPlaybackStore(this) }

    /**
     * A label on a card: dates sit in the top-left corner; a new episode is a light strip
     * across the bottom, wide enough for "Yeni bölüm · Perşembe" on a narrow poster.
     */
    private fun addBadge(card: FrameLayout, text: String, episode: Boolean = false) {
        if (text.isEmpty()) return
        val b = ui.text(text, if (episode) 14 else 15, if (episode) C.INK else C.TEXT, 700)
        b.maxLines = 1
        b.ellipsize = TextUtils.TruncateAt.END
        if (episode) {
            b.gravity = Gravity.CENTER
            b.setBackgroundColor(Color.argb(238, 255, 255, 255))
            b.setPadding(ui.u(6), ui.u(7), ui.u(6), ui.u(7))
            card.addView(b, ui.fl(ui.match, ui.wrap, Gravity.BOTTOM))
            return
        }
        b.background = ui.round(Color.argb(200, 12, 12, 14), D.BADGE)
        b.setPadding(ui.u(10), ui.u(5), ui.u(10), ui.u(5))
        val blp = ui.fl(ui.wrap, ui.wrap, Gravity.TOP or Gravity.START)
        blp.setMargins(ui.u(10), ui.u(10), ui.u(10), 0)
        card.addView(b, blp)
    }

    /**
     * "SENDE VAR" on films on a platform whose app is on this TV: what can be watched without a new
     * subscription, at a glance.
     */
    private fun addMoneyTag(card: FrameLayout, p: Pick) {
        val have = (p.where + listOfNotNull(p.provider.takeIf { it.isNotEmpty() })).any { it in mine }
        if (!have) return
        val b = ui.text("SENDE VAR", 13, C.INK, 800)
        b.letterSpacing = 0.04f
        b.background = ui.round(Color.argb(240, 255, 255, 255), D.BADGE)
        b.setPadding(ui.u(9), ui.u(4), ui.u(9), ui.u(4))
        val lp = ui.fl(ui.wrap, ui.wrap, Gravity.TOP or Gravity.START)
        lp.setMargins(ui.u(10), ui.u(10), 0, 0)
        card.addView(b, lp)
    }

    /** A small play mark in a poster's top-right corner: sources were found for it this week. */
    private fun addPlayableMark(card: FrameLayout) {
        val box = FrameLayout(this)
        box.background = ui.round(Color.argb(225, 92, 104, 246), 16)
        val i = ImageView(this)
        i.setImageResource(R.drawable.ic_play)
        i.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        val pad = ui.u(6)
        i.setPadding(pad, pad, pad, pad)
        box.addView(i, ui.fl(ui.match, ui.match))
        val lp = ui.fl(ui.u(32), ui.u(32), Gravity.TOP or Gravity.END)
        lp.setMargins(0, ui.u(10), ui.u(10), 0)
        card.addView(box, lp)
    }

    /** Small white check in a poster's top-right corner for films marked "İzledim". */
    private fun addWatchedMark(card: FrameLayout) {
        val box = FrameLayout(this)
        box.background = ui.round(Color.argb(235, 255, 255, 255), 16)
        val i = ImageView(this)
        i.setImageResource(R.drawable.ic_check)
        i.setColorFilter(C.INK, PorterDuff.Mode.SRC_IN)
        val pad = ui.u(5)
        i.setPadding(pad, pad, pad, pad)
        box.addView(i, ui.fl(ui.match, ui.match))
        val lp = ui.fl(ui.u(32), ui.u(32), Gravity.TOP or Gravity.END)
        lp.setMargins(0, ui.u(10), ui.u(10), 0)
        card.addView(box, lp)
    }

    /** Opens a film page, growing the card's picture into it. */
    private fun openDetail(p: Pick, from: View?, img: ImageView?) {
        idleOff()
        // The row it was opened from: ◀ ▶ on the film page go along it.
        val row = DetailActivity.rowAround(from)
        Fx.zoomOpen(frame, from, img?.drawable) {
            try {
                DetailActivity.open(this, p, row = row)
                overridePendingTransition(0, 0)
            } catch (e: Exception) {
                Fx.zoomReset(frame)
            }
        }
    }

    // ---------- Bell: notices ----------

    /** [days]: something you follow that is coming (a film's release, a series' episode), days left; it goes on top. */
    /**
     * [pick]: the film or series it is about (its poster in the row, its picture on a countdown card);
     * [films]: a platform's new titles, opened in place under the row; [tag]: a countdown card's kicker.
     */
    private class Notice(
        val id: String, val icon: Int, val title: String, val sub: String, val days: Int? = null,
        val pick: Pick? = null, val films: List<Pick> = emptyList(), val tag: String = "", val open: () -> Unit
    )

    /** Kinds the bell can turn off from a notice's menu, with the names Ayarlar › Bildirimler uses. */
    private val noticeKinds = mapOf(
        "new" to "Yeni bölüm çıktı", "ep" to "Yaklaşan bölümler", "rel" to "Hatırlattığın filmler",
        "soon" to "Yakında vizyonda", "plat" to "Platformlara gelenler", "now" to "Artık platformda",
        "ready" to "Artık izlenebilir", "night" to "Akşam planı", "listold" to "Listende bekleyenler"
    )

    /** A countdown's id changes at a week and at each of the last three days, so it comes back new then. */
    private fun stage(d: Int) = if (d <= 3) "$d" else "w"

    private fun daysAhead(d: Int, what: String) = when (d) {
        0 -> "Bugün $what: "
        1 -> "Yarın $what: "
        else -> "$d gün sonra $what: "
    }

    /** {id: when} kept in settings; entries older than a month drop out. */
    private fun noticeTimes(json: String): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        val from = System.currentTimeMillis() - 30 * 86400_000L
        try {
            val o = org.json.JSONObject(json.ifBlank { "{}" })
            for (k in o.keys()) o.optLong(k).takeIf { it > from }?.let { out[k] = it }
        } catch (_: Exception) {
        }
        return out
    }

    private fun timesJson(m: Map<String, Long>) =
        org.json.JSONObject().apply { m.entries.sortedByDescending { it.value }.take(400).forEach { put(it.key, it.value) } }.toString()

    private fun hideNotices(ids: Collection<String>) {
        val m = noticeTimes(prefs.noticeHidden)
        val now = System.currentTimeMillis()
        for (id in ids) m[id] = now
        prefs.noticeHidden = timesJson(m)
    }

    /**
     * What's worth knowing now: new episodes of Listem series (today, tomorrow) and of series you
     * watch (out this week, not seen yet), reminded films
     * on release, films coming to cinemas in the next three days, and a new version.
     */
    private fun notices(): List<Notice> {
        val out = ArrayList<Notice>()
        Remote.activeMessage()?.let { m ->
            out.add(Notice("msg:${m.id}", R.drawable.ic_info, m.title.ifEmpty { "Duyuru" }, m.text) { showMessage(m) })
        }
        MovieNight.get(prefs)?.takeIf { it.at > System.currentTimeMillis() }?.let { plan ->
            out.add(Notice("night:${plan.at}", R.drawable.ic_moon, "Akşam planı: ${MovieNight.day(plan.at)}",
                plan.film.title + " · " + MovieNight.left(plan.at) + " kaldı", pick = plan.film) { openDetail(plan.film, null, null) })
        }
        if (!prefs.homeNudgeOff && HomeGuide.needed(this)) {
            out.add(Notice("home", R.drawable.ic_tv, "Ana Sayfa tuşu LeXia'yı açmıyor",
                "Bir kez ayarlaman yeter; adım adım göstereyim.") { HomeGuide.open(this) })
        }
        if (Updater.available) {
            out.add(Notice("upd:${Updater.latest}", R.drawable.ic_update, "Yeni sürüm hazır: #${Updater.latest}",
                "Kurmak için OK'e bas.") { startUpdate() })
        }
        for (p in prefs.watchlist) {
            val n = Episodes.of(p) ?: continue
            val d = Episodes.daysUntil(n.date) ?: continue
            if (d > 7) continue
            val which = if (n.season > 0 && n.episode > 0) "${n.season}. sezon ${n.episode}. bölüm" else Episodes.what(n)
            val w = Episodes.what(n).lowercase(tr)
            out.add(Notice("ep:${p.key}:${n.date}:${stage(d)}", R.drawable.ic_tv,
                daysAhead(d, w) + p.title, which, days = d, pick = p, tag = Episodes.what(n).uppercase(tr)) { openDetail(p, null, null) })
        }
        // A series you watch in LeXia has a new episode out (this week) that you have not finished:
        // OK opens it straight on its sources.
        val today = todayIndex()
        val listed = prefs.watchlist.map { it.key }.toSet()
        for (e in WatchHistory(this).entries().filter { it.pick.isSeries }) {
            val p = today[e.pick.key] ?: e.pick
            val r = Episodes.recent(p) ?: continue
            if (p.key in listed && Episodes.of(p) != null) continue
            val id = p.imdbId.ifBlank { e.pick.imdbId }.ifBlank { if (p.id > 0) "tmdb:${p.id}" else "" }
            if (id.isEmpty() || r.season <= 0 || r.episode <= 0) continue
            val episode = "$id:${r.season}:${r.episode}"
            if (playback.isFinished(episode)) continue
            out.add(Notice("new:${p.key}:${r.season}:${r.episode}", R.drawable.ic_tv, "Yeni bölüm çıktı: ${p.title}",
                "${r.season}. sezon ${r.episode}. bölüm  ·  İzlemek için OK'e bas", pick = p) {
                MediaSourcePickerActivity.open(this, p, resumeEpisode = episode)
            })
        }
        for (p in prefs.reminders) {
            val d = daysUntil(p.release) ?: continue
            if (d !in -3..7) continue
            val t = if (d < 0) "Vizyonda: " else daysAhead(d, "vizyonda")
            out.add(Notice("rel:${p.key}:${p.release}" + (if (d >= 0) ":${stage(d)}" else ""), R.drawable.ic_film, t + p.title,
                "Hatırlatmıştın.", days = d.takeIf { it >= 0 }, pick = p, tag = "SİNEMADA") { openDetail(p, null, null) })
        }
        // A Listem film not out yet: the week before, it comes to the top with its countdown.
        val remindedKeys = prefs.reminders.map { it.key }.toSet()
        for (p in prefs.watchlist) {
            if (p.isSeries || p.key in remindedKeys) continue
            val d = daysUntil(p.release) ?: continue
            if (d !in 0..7) continue
            out.add(Notice("rel:${p.key}:${p.release}:${stage(d)}", R.drawable.ic_film, daysAhead(d, "vizyonda") + p.title,
                "Listende bekliyordu.", days = d, pick = p, tag = "SİNEMADA") { openDetail(p, null, null) })
        }
        // New on the platforms you chose (or whose app is on this TV): one notice per platform a day.
        for ((name, films) in streamingFirst().groupBy { it.provider }) {
            if (!myPlatform(name) || name in prefs.noticePlatOff) continue
            out.add(Notice("plat:$name:${prefs.pickDate}", R.drawable.ic_tv, "$name: ${films.size} yeni yapım",
                films.take(3).joinToString(", ") { it.title }, films = films) { showPlatformNews(name, films) })
        }
        // A film you saved (Listem, reminders) that came to a platform since: "Artık Netflix'te".
        for ((p, name, day) in nowStreaming()) {
            out.add(Notice("now:${p.key}:$name", R.drawable.ic_tv, "Artık ${TrailerActivity.locative(name)}: ${p.title}",
                (if (day == todayStr()) "Bugün geldi" else "Bu hafta geldi") + ". Listende bekliyordu.", pick = p) { openNowStreaming(p, name) })
        }
        // A Listem film whose sources were found for the first time while the TV rested: three days.
        for (p in prefs.watchlist) {
            if (p.isSeries) continue
            val at = playback.readyAt(p.key)
            if (at <= 0L || System.currentTimeMillis() - at > 3 * 86400_000L || playback.isFinished(p.key)) continue
            out.add(Notice("ready:${p.key}", R.drawable.ic_film, "Artık izlenebilir: ${p.title}",
                "Kaynağı bulundu. Listende bekliyordu.", pick = p) { openDetail(p, null, null) })
        }
        listNudge()?.let { out.add(it) }
        val reminded = prefs.reminders.map { it.key }.toSet()
        for (p in soon.filter { it.key !in reminded }.take(12)) {
            val d = daysUntil(p.release) ?: continue
            if (d !in 0..3) continue
            val t = when (d) { 0 -> "Bugün vizyonda: "; 1 -> "Yarın vizyonda: "; else -> "$d gün sonra vizyonda: " }
            out.add(Notice("soon:${p.key}:${p.release}", R.drawable.ic_film, t + p.title,
                p.genres.take(2).joinToString(", "), pick = p) { openDetail(p, null, null) })
        }
        // Kinds turned off in Ayarlar › Bildirimler; films you did not ask about when only yours are
        // wanted; what was put away in the bell; and what was read three days ago (a countdown, an
        // update or a plan stays while it is true).
        val off = prefs.noticeOff
        val onlyMine = prefs.noticeOnlyMine
        val hidden = noticeTimes(prefs.noticeHidden)
        val seen = noticeTimes(prefs.noticeSeenAt)
        val stale = System.currentTimeMillis() - 3 * 86400_000L
        return out.filter {
            val kind = it.id.substringBefore(':')
            kind !in off && !(onlyMine && kind == "soon") && it.id !in hidden &&
                (it.days != null || kind in setOf("msg", "home", "upd", "night") || (seen[it.id] ?: Long.MAX_VALUE) > stale)
        }
    }

    private fun todayStr() = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())

    /** Every film in today's lists, by key, with today's facts (where it streams now). */
    private fun todayIndex(): Map<String, Pick> {
        val m = HashMap<String, Pick>()
        for (l in listOf(allPicks, platformList, top10, top10tv, cinema, soon, localList, likeList) + genrePools.map { it.second }) {
            for (p in l) if (!m.containsKey(p.key) || p.provider.isNotEmpty()) m[p.key] = p
        }
        return m
    }

    /**
     * Saved films (Listem, reminders) that had no platform when saved and have one in today's list:
     * found once, then told for three days. Film, platform and the day it was found.
     */
    private fun nowStreaming(): List<Triple<Pick, String, String>> {
        val today = todayStr()
        val known = prefs.nowStreaming
        val index = todayIndex()
        val added = ArrayList<String>()
        val inCinema = cinema.map { it.key }.toSet()
        for (saved in (prefs.watchlist + prefs.reminders).distinctBy { it.key }) {
            if (saved.provider.isNotEmpty() || saved.where.isNotEmpty() || saved.isSeries) continue
            // Films that were in cinemas lately: the ones that come to the platforms after.
            val since = daysUntil(saved.release)?.let { -it } ?: continue
            if (since !in 0..240) continue
            val now = index[saved.key] ?: continue
            if (now.key in inCinema) continue
            val name = now.provider.ifEmpty { now.where.firstOrNull() ?: "" }
            if (name.isEmpty() || known.any { it.startsWith("${saved.key}|$name|") }) continue
            added.add("${saved.key}|$name|$today")
        }
        if (added.isNotEmpty()) prefs.nowStreaming = (known + added).toList().takeLast(60).toSet()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val limit = System.currentTimeMillis() - 3 * 86400_000L
        val saved = (prefs.watchlist + prefs.reminders).associateBy { it.key }
        return prefs.nowStreaming.mapNotNull { e ->
            val (key, name, day) = e.split('|').takeIf { it.size == 3 } ?: return@mapNotNull null
            val t = try { fmt.parse(day)?.time ?: 0L } catch (x: Exception) { 0L }
            val p = saved[key] ?: return@mapNotNull null
            if (t < limit) null else Triple(index[key] ?: p, name, day)
        }
    }

    /** Opens the platform's app when it is on this TV, else the film's page. */
    private fun openNowStreaming(p: Pick, name: String) {
        val app = Apps.forPlatform(this, name)
        if (app != null) launch(app) else openDetail(p, null, null)
    }

    /** From Friday evening to Sunday: a film waiting in Listem for two weeks or more, once a week. */
    private fun listNudge(): Notice? {
        val c = Calendar.getInstance()
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val weekend = (dow == Calendar.FRIDAY && c.get(Calendar.HOUR_OF_DAY) >= 17) || dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
        if (!weekend) return null
        val added = prefs.listAdded
        val now = System.currentTimeMillis()
        val old = prefs.watchlist.filter { it.key !in watchedNow }
            .mapNotNull { p -> added[p.key]?.let { p to (now - it) / (7 * 86400_000L) } }
            .filter { it.second >= 2 }.maxByOrNull { it.second } ?: return null
        val (p, weeks) = old
        c.firstDayOfWeek = Calendar.MONDAY
        val week = "${c.get(Calendar.YEAR)}-${c.get(Calendar.WEEK_OF_YEAR)}"
        return Notice("listold:$week", R.drawable.ic_list, "Listende $weeks haftadır bekliyor: ${p.title}",
            "Hafta sonu için iyi bir seçim olabilir.", pick = p) { openDetail(p, null, null) }
    }

    // ---------- Günaydın ----------

    private val morningCheck = Runnable { maybeMorning() }

    /** The morning card: once a day, on the first start between 5 and 12, when nothing else is on screen. */
    private fun maybeMorning() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val today = todayStr()
        if (h !in 5..11 || !prefs.morningCard || prefs.morningSeen == today || !prefs.setupDone) return
        if (idle || menu != null || updateWall != null || morning != null || !hasWindowFocus()) return
        if (savedPicks == null && allPicks.isEmpty()) {
            // The film list is not here yet; try again in a moment.
            handler.postDelayed(morningCheck, 4000L)
            return
        }
        prefs.morningSeen = today
        showMorning()
    }

    private fun showMorning() {
        val lines = ArrayList<Pair<Int, String>>()
        if (prefs.weather && tempView.text.isNotEmpty()) {
            lines.add((if (Fx.weatherIcon != 0) Fx.weatherIcon else R.drawable.ic_sun) to "Dışarıda ${tempView.text}" +
                (if (weatherCode in 51..99) ", şemsiyeni unutma" else ""))
        }
        for (p in prefs.watchlist) {
            val n = Episodes.of(p) ?: continue
            if (Episodes.daysUntil(n.date) == 0) lines.add(R.drawable.ic_tv to "Bugün yeni bölüm: ${p.title}")
        }
        val out = soon.filter { daysUntil(it.release) == 0 }.map { it.title } +
            prefs.reminders.filter { daysUntil(it.release) == 0 }.map { it.title }
        if (out.isNotEmpty()) lines.add(R.drawable.ic_film to "Bugün vizyonda: " + out.distinct().take(3).joinToString(", "))
        MovieNight.get(prefs)?.takeIf { it.at > System.currentTimeMillis() && MovieNight.day(it.at).startsWith("Bu akşam") }?.let {
            lines.add(R.drawable.ic_moon to "Akşam planı ${MovieNight.time(it.at)}: ${it.film.title}")
        }
        val plat = appNews.values.firstOrNull()
        if (plat != null) lines.add(R.drawable.ic_tv to "${TrailerActivity.locative(plat.first)} ${plat.second.size} yeni yapım")
        if (lines.isEmpty()) lines.add(R.drawable.ic_film to "Günün filmi hazır: ${picks.firstOrNull()?.title ?: "aşağıda"}")

        val card = ui.vbox()
        card.setPadding(ui.u(48), ui.u(40), ui.u(56), ui.u(40))
        card.background = ui.glass(Color.argb(ui.panelAlpha(), 0x18, 0x18, 0x1E), D.PANEL, 2)
        card.elevation = ui.uf(40)
        val name = prefs.name.trim()
        card.addView(ui.text(if (name.isEmpty()) "Günaydın" else "Günaydın, $name", 46, C.TEXT, 700))
        val date = java.text.SimpleDateFormat("d MMMM EEEE", tr).format(java.util.Date())
        card.addView(ui.text(date, 21, C.TEXT2, 500), ui.ll(ui.wrap, ui.wrap, top = 6))
        for ((icon, text) in lines.take(5)) {
            val row = ui.hbox()
            val i = ImageView(this)
            i.setImageResource(icon)
            i.setColorFilter(C.TEXT, PorterDuff.Mode.SRC_IN)
            row.addView(i, ui.ll(ui.u(30), ui.u(30)))
            val t = ui.text(text, 23, C.TEXT, 500)
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.END
            row.addView(t, ui.ll(ui.wrap, ui.wrap, left = 18))
            card.addView(row, ui.ll(ui.wrap, ui.wrap, top = 22))
        }
        val tip = ui.text("Bir tuşa bas, kapansın", 17, C.TEXT3, 500)
        card.addView(tip, ui.ll(ui.wrap, ui.wrap, top = 26))
        val lp = ui.fl(ui.u(900), ui.wrap, Gravity.CENTER)
        frame.addView(card, lp)
        morning = card
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.translationY = ui.uf(20)
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(D.SLOW)
            .setInterpolator(DecelerateInterpolator(2f)).start()
        handler.postDelayed({ hideMorning() }, 9000L)
    }

    private fun hideMorning() {
        val c = morning ?: return
        morning = null
        c.animate().alpha(0f).translationY(-ui.uf(16)).setDuration(D.MED).withEndAction { frame.removeView(c) }.start()
    }

    // ---------- The owner's remote settings ----------

    private var updateWall: FrameLayout? = null
    private var askPill: View? = null

    /** Applies remote.json: bell on/off, genre rows, the message (once), and the update wall. */
    private fun applyRemote() {
        if (isFinishing) return
        bell.visibility = if (Remote.isOff("bell")) View.GONE else View.VISIBLE
        askPill?.visibility = if (Remote.isOff("surprise")) View.GONE else View.VISIBLE
        refreshGenreRows()
        showTopRows()
        updateBell()
        if (Remote.mustUpdate()) {
            showUpdateWall()
            return
        }
        hideUpdateWall()
        val m = Remote.activeMessage() ?: return
        if (prefs.remoteSeen == m.id) return
        // Shown once, when the home screen is really in front.
        handler.postDelayed({
            if (!isFinishing && !idle && hasWindowFocus() && prefs.remoteSeen != m.id) {
                prefs.remoteSeen = m.id
                showMessage(m)
            }
        }, 2500)
    }

    private fun showMessage(m: Remote.Message) {
        val lines = m.text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        WhatsNew.card(this, ui, "DUYURU", m.title.ifEmpty { "LeXia" }, lines)
    }

    /**
     * This version is older than the owner allows: a full screen asks to update; the rest of the
     * home screen stays out of reach until it is done.
     */
    private fun showUpdateWall() {
        if (updateWall != null) return
        idleOff()
        val wall = FrameLayout(this)
        wall.setBackgroundColor(Color.argb(242, 6, 7, 10))
        wall.isClickable = true
        val col = ui.vbox()
        col.gravity = Gravity.CENTER_HORIZONTAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_launcher)
        col.addView(logo, ui.ll(ui.u(150), ui.u(150)))
        col.addView(ui.text("Güncelleme gerekli", 52, C.TEXT, 700), ui.ll(ui.wrap, ui.wrap, top = 40))
        val body = ui.text(
            "Bu sürüm artık desteklenmiyor. Devam etmek için LeXia'nın son sürümünü kur; ayarların ve Listem aynen kalır.",
            25, C.TEXT2, 400
        )
        body.gravity = Gravity.CENTER
        body.maxWidth = ui.u(900)
        body.setLineSpacing(0f, 1.25f)
        col.addView(body, ui.ll(ui.wrap, ui.wrap, top = 18))
        val btn = ui.pill("Şimdi güncelle", 64, 23, primary = true)
        (btn.layoutParams as LinearLayout.LayoutParams).rightMargin = 0
        btn.setOnClickListener {
            val go = {
                if (Updater.available) Updater.install(this) { btn.text = it } else btn.text = "Yeni sürüm bulunamadı · Tekrar dene"
            }
            if (Updater.available) go() else {
                btn.text = "Denetleniyor…"
                Updater.check(this) { go() }
            }
        }
        col.addView(btn, ui.ll(ui.wrap, ui.u(64), top = 44))
        wall.addView(col, ui.fl(ui.wrap, ui.wrap, Gravity.CENTER))
        frame.addView(wall, ui.fl(ui.match, ui.match))
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        updateWall = wall
        btn.requestFocus()
    }

    private fun hideUpdateWall() {
        val w = updateWall ?: return
        frame.removeView(w)
        updateWall = null
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        focusFirst()
    }

    /** Pink dot on the bell while there is something not seen yet. */
    private fun updateBell() {
        if (!::bellDot.isInitialized) return
        val read = prefs.noticesRead
        val count = notices().count { it.id !in read }
        bellDot.text = if (count > 9) "9+" else count.toString()
        bellDot.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (::newDot.isInitialized) newDot.visibility = bellDot.visibility
    }

    private fun openNotices() {
        if (menu != null) return
        var list = notices()
        val read = prefs.noticesRead
        val shortcutsOff = prefs.noticeShortcutsOff
        val layer = FrameLayout(this)
        layer.setBackgroundColor(Color.argb(ui.dimAlpha(), 0, 0, 0))
        layer.isClickable = true
        val panel = ui.vbox()
        val base = Color.argb(ui.panelAlpha(), 0x1B, 0x1B, 0x20)
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(base, base))
        val r = ui.uf(36)
        bg.cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        panel.background = bg
        // Liquid Glass over it: the sheen and the rim of light, which glides as the remote moves.
        val glass = LiquidGlass(0, 0f, maxOf(1f, ui.uf(2)), floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r))
        panel.foreground = glass
        panel.elevation = ui.uf(40)
        panel.clipChildren = false
        panel.setPadding(ui.u(44), ui.u(70), ui.u(40), ui.u(40))
        // The panel takes on the light of the film in focus: a soft wash from the top, back to
        // plain on a shortcut.
        var tintNow = base
        var tintAnim: ValueAnimator? = null
        fun tint(img: ImageView?) {
            glass.sweep(Fx.lastDir)
            if (Fx.lite) return
            val bmp = (img?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            val a = bmp?.let { Fx.accent(it) } ?: 0
            val target = if (a == 0) base else Fx.blend(base, Color.argb(Color.alpha(base), Color.red(a), Color.green(a), Color.blue(a)), 0.3f)
            if (target == tintNow) return
            tintAnim?.cancel()
            tintAnim = ValueAnimator.ofObject(ArgbEvaluator(), tintNow, target).apply {
                duration = 700
                addUpdateListener { v ->
                    tintNow = v.animatedValue as Int
                    bg.colors = intArrayOf(tintNow, base)
                }
                start()
            }
        }
        val head = ui.hbox()
        head.gravity = Gravity.CENTER_VERTICAL
        head.clipChildren = false
        head.clipToPadding = false
        head.addView(ui.text("Bildirimler", 44, C.TEXT, 700), ui.ll(0, ui.wrap, weight = 1f))
        val clear = ui.pill("Temizle", 56, 21)
        clear.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        head.addView(clear, ui.ll(ui.wrap, ui.u(56), right = 12))
        panel.addView(head, ui.ll(ui.match, ui.wrap, left = 26))
        val tip = ui.text("Gizlemek için OK'e basılı tut", 17, C.TEXT3, 500)
        panel.addView(tip, ui.ll(ui.wrap, ui.wrap, left = 26, top = 6, bottom = 26))

        var first: View? = null
        var divider: View? = null
        val shortcuts = ArrayList<View>()
        // A shortcut put away from its menu: gone from the bell until Ayarlar › Bildirimler brings it back.
        fun shortcutMenu(row: View, id: String, name: String) {
            row.setOnLongClickListener {
                Dialogs.list(this, name, listOf("Bunu gizle" to ""), subtitle = "Ayarlar › Bildirimler'den geri getirebilirsin.") {
                    prefs.noticeShortcutsOff = prefs.noticeShortcutsOff + id
                    val at = shortcuts.indexOf(row)
                    shortcuts.remove(row)
                    (row.parent as? ViewGroup)?.removeView(row)
                    if (shortcuts.isEmpty()) divider?.let { d -> (d.parent as? ViewGroup)?.removeView(d) }
                    (shortcuts.getOrNull(at) ?: shortcuts.lastOrNull())?.requestFocus()
                }
                true
            }
        }
        val cal = noticeRow(R.drawable.ic_calendar, "Takvim", "Önümüzdeki iki hafta: vizyonlar ve yeni bölümler", false, focused = { tint(null) })
        cal.setOnClickListener {
            closeMenu(false)
            idleOff()
            startActivity(Intent(this, CalendarActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        val weekend = noticeRow(R.drawable.ic_film, "Hafta sonu rehberi", "Bu hafta sinemada, platformlarda ve dizilerde neler var", false, focused = { tint(null) })
        weekend.setOnClickListener {
            closeMenu(false)
            idleOff()
            WeekendActivity.open(this)
        }
        shortcutMenu(cal, "calendar", "Takvim")
        shortcutMenu(weekend, "weekend", "Hafta sonu rehberi")
        if (!Remote.isOff("calendar") && "calendar" !in shortcutsOff) {
            panel.addView(cal, ui.ll(ui.match, ui.wrap, bottom = 4))
            first = cal
            shortcuts.add(cal)
        }
        if (!Remote.isOff("weekend") && "weekend" !in shortcutsOff) {
            // Right under the calendar (above its divider when there is one).
            val at = if (first === cal) panel.indexOfChild(cal) + 1 else panel.childCount
            panel.addView(weekend, at, ui.ll(ui.match, ui.wrap, bottom = 4))
            if (first == null) first = weekend
            shortcuts.add(weekend)
        }
        // Which one subscription covers most of Listem this month.
        val best = PlatformMonthActivity.tally(this).first.firstOrNull()
        if (best != null && "month" !in shortcutsOff) {
            val total = prefs.watchlist.count { it.key !in prefs.watched }
            val month = noticeRow(R.drawable.ic_tv, "Bu ay hangi platform?", "Listendeki $total filmin ${best.second.size} tanesi ${TrailerActivity.locative(best.first)}", false, focused = { tint(null) })
            month.setOnClickListener {
                closeMenu(false)
                idleOff()
                PlatformMonthActivity.open(this)
            }
            panel.addView(month, ui.ll(ui.match, ui.wrap, bottom = 4))
            if (first == null) first = month
            shortcuts.add(month)
            shortcutMenu(month, "month", "Bu ay hangi platform?")
        }
        // The line under the shortcuts, when there are any.
        if (shortcuts.isNotEmpty()) {
            val div = View(this)
            div.setBackgroundColor(C.STROKE)
            panel.addView(div, ui.ll(ui.match, maxOf(1, ui.u(1)), left = 26, right = 26, top = 12, bottom = 12))
            divider = div
        }

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        val box = ui.vbox()
        box.clipChildren = false
        box.clipToPadding = false
        box.setPadding(ui.u(12), ui.u(8), ui.u(12), ui.u(8))
        scroll.addView(box)
        // Coming soon from what you follow first, nearest on top; then related notices together
        // under TV-friendly headings.
        fun groupOf(n: Notice): String = if (n.days != null) "YAKLAŞANLAR" else when (n.id.substringBefore(':')) {
            "ep", "new" -> "DİZİLER"
            "rel", "soon", "night" -> "FİLMLER"
            "plat", "now" -> "PLATFORMLAR"
            else -> "DİĞER"
        }
        val groupOrder = listOf("YAKLAŞANLAR", "DİZİLER", "FİLMLER", "PLATFORMLAR", "DİĞER")
        lateinit var fill: (Boolean) -> Unit
        // Put away, then the list is drawn again with the focus where it was.
        fun after(index: Int) {
            list = notices()
            fill(false)
            val rows = box.getFocusables(View.FOCUS_FORWARD)
            (rows.getOrNull(index) ?: rows.lastOrNull() ?: shortcuts.firstOrNull())?.requestFocus()
                ?: closeMenu()
        }
        fun noticeMenu(n: Notice, index: Int) {
            val kind = n.id.substringBefore(':')
            val acts = ArrayList<Pair<String, () -> Unit>>()
            acts.add("Bunu gizle" to { hideNotices(listOf(n.id)) })
            if (kind == "plat") {
                val name = n.id.removePrefix("plat:").substringBeforeLast(':')
                acts.add("$name bildirimlerini kapat" to { prefs.noticePlatOff = prefs.noticePlatOff + name })
            }
            noticeKinds[kind]?.let { k ->
                acts.add("Tümünü kapat: $k" to { prefs.noticeOff = prefs.noticeOff + kind })
            }
            Dialogs.list(this, n.title, acts.map { it.first to "" }, subtitle = "Ayarlar › Bildirimler'den geri açabilirsin.") { i ->
                acts[i].second()
                after(index)
                updateBell()
            }
        }
        /** A picture that can take the focus: white frame, lifted, the panel takes its light. */
        fun focusFrame(v: FrameLayout, img: ImageView, radius: Int, lift: Float) {
            v.isFocusable = true
            v.isClickable = true
            v.setOnFocusChangeListener { _, f ->
                v.foreground = if (f) ui.round(Color.TRANSPARENT, radius, 3, Color.WHITE) else null
                ui.lift(v, f, lift, 14)
                if (f) tint(img)
            }
        }
        // A platform's new titles open in place under its row: its app first when it is on the TV.
        var expanded: Pair<View, View>? = null
        fun expand(row: View, n: Notice) {
            val was = expanded
            was?.let { box.removeView(it.second) }
            expanded = null
            if (was?.first === row) {
                row.requestFocus()
                return
            }
            val name = n.id.removePrefix("plat:").substringBeforeLast(':')
            val hs = HorizontalScrollView(this)
            hs.isHorizontalScrollBarEnabled = false
            hs.overScrollMode = View.OVER_SCROLL_NEVER
            hs.clipChildren = false
            hs.clipToPadding = false
            val strip = ui.hbox()
            strip.clipChildren = false
            strip.clipToPadding = false
            strip.setPadding(ui.u(14), ui.u(12), ui.u(14), ui.u(12))
            hs.addView(strip)
            val w = ui.u(118)
            val h = ui.u(177)
            platformApps[name]?.let { app ->
                val col = ui.vbox()
                val tile = FrameLayout(this)
                tile.background = ui.round(Color.argb(40, 255, 255, 255), 14)
                tile.clipToOutline = true
                val ic = ImageView(this)
                ic.scaleType = ImageView.ScaleType.FIT_CENTER
                ic.setImageDrawable(try { app.info.loadIcon(packageManager) } catch (_: Exception) { null })
                tile.addView(ic, ui.fl(ui.u(64), ui.u(64), Gravity.CENTER))
                focusFrame(tile, ic, 14, 1.06f)
                tile.setOnClickListener {
                    closeMenu(false)
                    launch(app)
                }
                col.addView(tile, ui.ll(w, h))
                val t = ui.text(TrailerActivity.accusative(name) + " aç", 16, C.TEXT2, 600)
                t.maxLines = 1
                t.ellipsize = TextUtils.TruncateAt.END
                col.addView(t, ui.ll(w, ui.wrap, top = 8))
                strip.addView(col, ui.ll(ui.wrap, ui.wrap, right = 16))
            }
            for (f in n.films) {
                val col = ui.vbox()
                val tile = FrameLayout(this)
                tile.background = ui.round(C.CARD, 14)
                tile.clipToOutline = true
                val img = ImageView(this)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                tile.addView(img, ui.fl(ui.match, ui.match))
                Img.load(this, "w342", f.poster.ifEmpty { f.backdrop }, w, img)
                focusFrame(tile, img, 14, 1.06f)
                tile.setOnClickListener {
                    closeMenu(false)
                    openDetail(f, null, null)
                }
                col.addView(tile, ui.ll(w, h))
                val t = ui.text(f.title, 16, C.TEXT2, 600)
                t.maxLines = 1
                t.ellipsize = TextUtils.TruncateAt.END
                col.addView(t, ui.ll(w, ui.wrap, top = 8))
                strip.addView(col, ui.ll(ui.wrap, ui.wrap, right = 16))
            }
            box.addView(hs, box.indexOfChild(row) + 1, ui.ll(ui.match, ui.wrap, bottom = 8))
            expanded = row to hs
            if (!Fx.lite) {
                hs.alpha = 0f
                hs.translationY = -ui.uf(12)
                hs.animate().alpha(1f).translationY(0f).setDuration(D.MED).setInterpolator(DecelerateInterpolator(2f)).start()
            }
            strip.getChildAt(0)?.let { (it as? ViewGroup)?.getChildAt(0)?.requestFocus() }
        }
        /**
         * What you follow, coming soon: its picture, its name (logo when there is one) and a large
         * countdown; pink on its last two days.
         */
        fun countdownCard(n: Notice, unread: Boolean, w: Int): FrameLayout {
            val p = n.pick
            val card = FrameLayout(this)
            card.background = ui.round(C.CARD, 20)
            card.clipToOutline = true
            val pic = ImageView(this)
            pic.scaleType = ImageView.ScaleType.CENTER_CROP
            card.addView(pic, ui.fl(ui.match, ui.match))
            if (p != null) Img.load(this, "w780", p.backdrop.ifEmpty { p.poster }, w, pic)
            val shade = View(this)
            shade.background = ui.gradient(GradientDrawable.Orientation.BOTTOM_TOP,
                Color.argb(235, 0, 0, 0), Color.argb(110, 0, 0, 0), Color.TRANSPARENT)
            card.addView(shade, ui.fl(ui.match, ui.match))
            val d = n.days ?: 0
            val cd = ui.vbox()
            cd.gravity = Gravity.CENTER_HORIZONTAL
            cd.setPadding(ui.u(18), ui.u(10), ui.u(18), ui.u(12))
            cd.background = ui.round(if (d <= 1) Color.parseColor("#FF4D8D") else Color.argb(150, 0, 0, 0), 16)
            cd.addView(ui.text(when (d) { 0 -> "BUGÜN"; 1 -> "YARIN"; else -> "$d" }, if (d > 1) 44 else 24, C.TEXT, 800))
            if (d > 1) {
                val lab = ui.text("GÜN", 14, C.TEXT, 700)
                lab.letterSpacing = 0.18f
                cd.addView(lab, ui.ll(ui.wrap, ui.wrap, top = -4))
            }
            val cdl = ui.fl(ui.wrap, ui.wrap, Gravity.TOP or Gravity.END)
            cdl.setMargins(0, ui.u(16), ui.u(16), 0)
            card.addView(cd, cdl)
            val info = ui.vbox()
            val kicker = ui.text(listOf(n.tag, n.sub).filter { it.isNotBlank() }.joinToString("  ·  "), 15,
                if (unread) Color.parseColor("#FF7AA8") else C.TEXT2, 700)
            kicker.letterSpacing = 0.05f
            kicker.maxLines = 1
            kicker.ellipsize = TextUtils.TruncateAt.END
            info.addView(kicker)
            val logo = ImageView(this)
            logo.adjustViewBounds = true
            logo.maxHeight = ui.u(68)
            logo.maxWidth = ui.u(300)
            logo.scaleType = ImageView.ScaleType.FIT_START
            logo.visibility = View.GONE
            info.addView(logo, ui.ll(ui.wrap, ui.wrap, top = 10))
            val name = ui.text(p?.title ?: n.title, 28, C.TEXT, 800)
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            name.setShadowLayer(ui.uf(12), 0f, ui.uf(2), Color.argb(150, 0, 0, 0))
            info.addView(name, ui.ll(ui.match, ui.wrap, top = 6))
            if (p != null && p.logo.isNotEmpty()) Img.load(this, "w500", p.logo, ui.u(300), logo, opaque = false) { ok ->
                if (ok) {
                    logo.visibility = View.VISIBLE
                    name.visibility = View.GONE
                }
            }
            val il = ui.fl(ui.match, ui.wrap, Gravity.BOTTOM)
            il.setMargins(ui.u(22), 0, ui.u(22), ui.u(20))
            card.addView(info, il)
            focusFrame(card, pic, 20, 1.03f)
            return card
        }
        fill = { animate ->
            box.removeAllViews()
            expanded = null
            if (list.isEmpty()) {
                val e = ui.emptyState(R.drawable.ic_bell, "Her şey sakin",
                    "Takip ettiğin dizilerin yeni bölümleri, hatırlattığın filmler ve platformlarına gelenler burada görünür.", size = 0)
                box.addView(e, ui.ll(ui.match, ui.wrap, top = 40))
            }
            clear.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            var index = 0
            for (group in groupOrder) {
                val items = list.filter { groupOf(it) == group }.let { g -> if (group == "YAKLAŞANLAR") g.sortedBy { it.days } else g }
                if (items.isEmpty()) continue
                val heading = ui.text(group, 16, C.TEXT2, 700)
                heading.letterSpacing = 0.12f
                heading.setPadding(ui.u(18), ui.u(18), ui.u(18), ui.u(8))
                box.addView(heading, ui.ll(ui.match, ui.wrap))
                if (group == "YAKLAŞANLAR") {
                    // One card fills the width; more of them slide sideways.
                    val one = items.size == 1
                    val w = if (one) ui.u(530) else ui.u(440)
                    val strip = ui.hbox()
                    strip.clipChildren = false
                    strip.clipToPadding = false
                    strip.setPadding(ui.u(6), ui.u(8), ui.u(6), ui.u(12))
                    for (n in items) {
                        val card = countdownCard(n, n.id !in read, w)
                        card.setOnClickListener {
                            closeMenu(false)
                            n.open()
                        }
                        val at = index++
                        card.setOnLongClickListener {
                            noticeMenu(n, at)
                            true
                        }
                        strip.addView(card, ui.ll(w, ui.u(250), right = if (one) 0 else 16))
                        if (first == null) first = card
                    }
                    if (one) box.addView(strip, ui.ll(ui.match, ui.wrap))
                    else {
                        val hs = HorizontalScrollView(this)
                        hs.isHorizontalScrollBarEnabled = false
                        hs.overScrollMode = View.OVER_SCROLL_NEVER
                        hs.clipChildren = false
                        hs.clipToPadding = false
                        hs.addView(strip)
                        box.addView(hs, ui.ll(ui.match, ui.wrap))
                    }
                    continue
                }
                for (n in items) {
                    val badge = n.days?.let { d -> when (d) { 0 -> "Bugün"; 1 -> "Yarın"; else -> "$d gün" } }
                    val plat = if (n.id.startsWith("plat:")) n.id.removePrefix("plat:").substringBeforeLast(':') else ""
                    lateinit var row: View
                    row = noticeRow(n.icon, n.title, n.sub, n.id !in read, badge, n.pick, plat,
                        n.films.firstOrNull()?.poster.orEmpty(), focused = { tint(it) })
                    row.setOnClickListener {
                        if (n.films.isNotEmpty()) expand(row, n)
                        else {
                            closeMenu(false)
                            n.open()
                        }
                    }
                    val at = index++
                    row.setOnLongClickListener {
                        noticeMenu(n, at)
                        true
                    }
                    box.addView(row, ui.ll(ui.match, ui.wrap, bottom = 4))
                    if (first == null) first = row
                }
            }
            // The rows come in one after another, softly from below.
            if (animate && !Fx.lite) {
                for (k in 0 until box.childCount) {
                    val v = box.getChildAt(k)
                    v.alpha = 0f
                    v.translationY = ui.uf(22)
                    val delay = 140L + minOf(k, 12) * 45L
                    ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).apply { startDelay = delay; duration = 420; start() }
                    ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, ui.uf(22), 0f).apply {
                        startDelay = delay
                        duration = 520
                        interpolator = DecelerateInterpolator(2.2f)
                        start()
                    }
                }
            }
        }
        fill(true)
        clear.setOnClickListener {
            hideNotices(list.map { it.id })
            list = notices()
            fill(false)
            updateBell()
            (shortcuts.firstOrNull() ?: box.getFocusables(View.FOCUS_FORWARD).firstOrNull())
                ?.requestFocus() ?: closeMenu()
        }
        // Rows that scroll up are cut at the list's top, so they never draw over the calendar rows
        // and the title (TV photo, 24 Kasım): the panel lets its children draw outside, this box does
        // not. The side room in the list keeps the focused row's lift whole.
        val clip = FrameLayout(this)
        clip.addView(scroll, ui.fl(ui.match, ui.match))
        panel.addView(clip, ui.ll(ui.match, 0, weight = 1f))

        layer.addView(panel, ui.fl(ui.u(640), ui.match, Gravity.END))
        showOverlay(layer, first, bell)
        panel.translationX = ui.uf(640)
        panel.animate().translationX(0f).setDuration(D.MED).setInterpolator(DecelerateInterpolator(2.2f)).start()
        // Seen now: the dot goes; old ids drop out so the list stays small. When each was first
        // seen is kept, so it leaves the bell three days later.
        prefs.noticesRead = list.map { it.id }.toSet()
        val seenAt = noticeTimes(prefs.noticeSeenAt)
        val now = System.currentTimeMillis()
        for (n in list) if (n.id !in seenAt) seenAt[n.id] = now
        prefs.noticeSeenAt = timesJson(seenAt)
        bellDot.visibility = View.GONE
        if (::newDot.isInitialized) newDot.visibility = View.GONE
    }

    /** Platform apps on this TV, by platform name, found for the bell (null: not installed). */
    private val platformApps = HashMap<String, AppEntry?>()

    /**
     * A notice: its film's poster (a platform's app icon; a plain icon for the rest), title and a
     * small line under it; a countdown badge; a dot when it's new. [focused] gets the picture.
     */
    private fun noticeRow(
        icon: Int, title: String, sub: String, unread: Boolean, badge: String? = null,
        pick: Pick? = null, platform: String = "", fallbackPoster: String = "",
        focused: (ImageView?) -> Unit = {}
    ): View {
        val row = ui.hbox()
        row.isFocusable = true
        row.isClickable = true
        row.setPadding(ui.u(18), ui.u(12), ui.u(22), ui.u(12))
        row.gravity = Gravity.CENTER_VERTICAL
        var i: ImageView? = null
        var thumb: ImageView? = null
        if (pick != null && (pick.poster.isNotEmpty() || pick.backdrop.isNotEmpty())) {
            val v = ImageView(this)
            v.scaleType = ImageView.ScaleType.CENTER_CROP
            v.background = ui.round(C.CARD, 10)
            v.clipToOutline = true
            row.addView(v, ui.ll(ui.u(54), ui.u(80), right = 20))
            Img.load(this, "w185", pick.poster.ifEmpty { pick.backdrop }, ui.u(54), v)
            thumb = v
        } else if (platform.isNotEmpty()) {
            val v = ImageView(this)
            v.background = ui.round(Color.argb(40, 255, 255, 255), 12)
            v.clipToOutline = true
            v.scaleType = ImageView.ScaleType.FIT_CENTER
            v.setPadding(ui.u(8), ui.u(8), ui.u(8), ui.u(8))
            row.addView(v, ui.ll(ui.u(54), ui.u(54), right = 20, top = 13, bottom = 13))
            thumb = v
            // The platform's own icon when its app is here; else its first new title's poster.
            Thread {
                val app = synchronized(platformApps) {
                    if (platformApps.containsKey(platform)) platformApps[platform]
                    else Apps.forPlatform(this, platform).also { platformApps[platform] = it }
                }
                val d = try { app?.info?.loadIcon(packageManager) } catch (_: Exception) { null }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (d != null) v.setImageDrawable(d)
                    else if (fallbackPoster.isNotEmpty()) {
                        v.setPadding(0, 0, 0, 0)
                        v.scaleType = ImageView.ScaleType.CENTER_CROP
                        (v.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                            lp.height = ui.u(80)
                            lp.topMargin = 0
                            lp.bottomMargin = 0
                            v.layoutParams = lp
                        }
                        Img.load(this, "w185", fallbackPoster, ui.u(54), v)
                    }
                }
            }.start()
        } else {
            val v = ImageView(this)
            v.setImageResource(icon)
            v.setColorFilter(C.TEXT, PorterDuff.Mode.SRC_IN)
            row.addView(v, ui.ll(ui.u(34), ui.u(34), left = 4, right = 24))
            i = v
        }
        val col = ui.vbox()
        val t = ui.text(title, 23, C.TEXT, 600)
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        val s = ui.text(sub, 18, C.TEXT3, 500)
        s.maxLines = 1
        s.ellipsize = TextUtils.TruncateAt.END
        col.addView(t)
        if (sub.isNotEmpty()) col.addView(s, ui.ll(ui.wrap, ui.wrap, top = 4))
        row.addView(col, ui.ll(0, ui.wrap, weight = 1f))
        // A countdown for what is coming: pink on its last two days.
        val soon = badge == "Bugün" || badge == "Yarın"
        val pill = badge?.let { b ->
            val v = ui.text(b, 17, C.TEXT, 700)
            v.setPadding(ui.u(14), ui.u(5), ui.u(14), ui.u(5))
            v.background = ui.round(if (soon) Color.parseColor("#FF4D8D") else Color.argb(46, 255, 255, 255), 14)
            row.addView(v, ui.ll(ui.wrap, ui.wrap, left = 14))
            v
        }
        val dot = View(this)
        dot.background = ui.round(Color.parseColor("#FF4D8D"), 6)
        dot.visibility = if (unread) View.VISIBLE else View.INVISIBLE
        row.addView(dot, ui.ll(ui.u(12), ui.u(12), left = 14))
        row.setOnFocusChangeListener { _, f ->
            row.background = if (f) ui.round(Color.WHITE, D.PLATE) else null
            t.setTextColor(if (f) C.INK else C.TEXT)
            s.setTextColor(if (f) Color.argb(153, 11, 11, 13) else C.TEXT3)
            i?.setColorFilter(if (f) C.INK else C.TEXT, PorterDuff.Mode.SRC_IN)
            if (f) focused(thumb)
            if (pill != null && !soon) {
                pill.background = ui.round(if (f) Color.argb(26, 11, 11, 13) else Color.argb(46, 255, 255, 255), 14)
                pill.setTextColor(if (f) C.INK else C.TEXT)
            }
            ui.lift(row, f, 1.03f, 10)
            // Looked at: its new-dot goes out and stays out.
            if (f && dot.visibility == View.VISIBLE) {
                dot.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(D.MED)
                    .withEndAction { dot.visibility = View.INVISIBLE }.start()
            }
        }
        return row
    }

    // ---------- "Sana göre" genre rows ----------

    private val genreTitles = mapOf(
        "Komedi" to "GÜLDÜREN FİLMLER", "Aksiyon" to "AKSİYON DOLU", "Dram" to "ETKİLEYİCİ DRAMLAR",
        "Gerilim" to "GERİLİM SEVENLERE", "Bilim-Kurgu" to "BİLİM KURGU", "Macera" to "MACERAYA ÇIK",
        "Suç" to "SUÇ VE ADALET", "Romantik" to "ROMANTİK FİLMLER", "Animasyon" to "ANİMASYON",
        "Korku" to "KORKU GECESİ", "Fantastik" to "FANTASTİK DÜNYALAR", "Gizem" to "GİZEMLİ HİKÂYELER",
        "Aile" to "AİLECEK İZLEMELİK"
    )

    /** Name of a row for menus: genre rows by their genre once chosen. */
    private fun rowTitle(id: String): String? {
        val i = when (id) { "g1" -> 0; "g2" -> 1; "g3" -> 2; else -> -1 }
        val g = if (i >= 0) genreShown.getOrNull(i)?.first else null
        return if (g != null) "Sana göre: $g" else Prefs.ROWS[id]
    }

    /**
     * Up to three genre rows: the genres you chose in the settings, then the ones you liked most,
     * then a different pair each day. Watched films are left out; a genre needs 4 films to show.
     */
    private fun refreshGenreRows() {
        val pools = genrePools.map { (g, films) -> g to films.filter { it.key !in skipNow } }
            .filter { it.second.size >= 4 }
        val byName = pools.toMap()
        val order = ArrayList<String>()
        val liked = prefs.likedGenres()
        for (g in prefs.genres.sortedBy { liked.indexOf(it).let { i -> if (i < 0) 99 else i } }) if (g in byName) order.add(g)
        for (g in liked) if (g in byName && g !in order) order.add(g)
        // Genres turned down ("İlgimi çekmiyor") come last.
        val down = prefs.dislikedGenres()
        val rest = pools.map { it.first }.filter { it !in order && it !in down } + pools.map { it.first }.filter { it !in order && it in down }
        if (rest.isNotEmpty()) {
            val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            for (k in rest.indices) order.add(rest[(day * 2 + k) % rest.size])
        }
        genreShown = if (Remote.isOff("genres")) emptyList() else order.distinct().take(3).map { it to byName.getValue(it) }
        GenreRows.names = genreShown.map { it.first }
        for (i in 0 until 3) {
            val row = genreCards[i]
            row.removeAllViews()
            val shown = genreShown.getOrNull(i) ?: continue
            (genreShelves[i].getChildAt(0) as? TextView)?.text = genreTitles[shown.first] ?: shown.first.uppercase(tr)
            for (p in shown.second) {
                val bits = ArrayList<String>()
                if (p.imdb > 0) bits.add(String.format(tr, "IMDb %.1f", p.imdb))
                if (p.year.isNotEmpty()) bits.add(p.year)
                row.addView(posterCard(p, "g${i + 1}", "", bits.joinToString("  ·  ")))
            }
        }
        showTopRows()
    }

    /** "Yarım kalanlar": films and series left half way (İzlediklerim's first tab), last played first. */
    private fun refreshContinue() {
        val list = WatchHistory(this).entries()
            .filter { e -> WatchMarks.of(playback, e.pick)?.let { it.fraction != null || it.episode != null } == true }
            .map { it.pick }
            .filter { !Remote.isHidden(it) && it.poster.isNotEmpty() }
            .take(12)
        val sig = list.joinToString("|") { it.key } + "#" + MediaPlaybackStore.changes
        if (sig == contSig) return
        contSig = sig
        contRow.removeAllViews()
        for (p in list) contRow.addView(if (p.backdrop.isNotEmpty()) wideCard(p, "cont") else posterCard(p, "cont", "", scoreLine(p)))
        showTopRows()
    }

    /** After "İzledim"/"Beğendim" changed on a film page, redraw what depends on them. */
    /** "Son baktıkların": pages you opened that are not in Listem and not watched, newest first. */
    private fun refreshRecent() {
        val inList = prefs.watchlist.map { it.key }.toSet()
        val list = prefs.recent.filter { it.key !in inList && it.key !in skipNow && !Remote.isHidden(it) && it.poster.isNotEmpty() }.take(10)
        val sig = list.joinToString("|") { it.key }
        if (sig == recentSig) return
        recentSig = sig
        recentRow.removeAllViews()
        for (p in list) recentRow.addView(posterCard(p, "recent", "", scoreLine(p)))
        showTopRows()
    }

    private fun refreshMarks() {
        watchedNow = prefs.watched
        // Films watched to the end (also those finished only in the player) stay off the home screen.
        val finished = (allPicks + top10 + top10tv + cinema + soon + localList + platformList + genrePools.flatMap { it.second })
            .asSequence().distinctBy { it.key }
            .filter { playback.isFinished(playback.last(it.key)) }
            .map { it.key }.toSet()
        watchedNow = watchedNow + finished
        skipNow = watchedNow + prefs.disliked.keys
        val sig = skipNow.sorted().joinToString("|") + "#" + prefs.liked.keys.sorted().joinToString("|") +
            "#" + MediaPlaybackStore.changes
        if (sig == markSig) return
        val first = markSig.isEmpty()
        markSig = sig
        if (first) return
        refreshTop()
        refreshGenreRows()
        genreSig = "-"
        if (currentFocus == null) focusFirst()
    }

    private fun parseDay(d: String): Calendar? = try {
        val c = Calendar.getInstance()
        c.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)!!
        c
    } catch (e: Exception) {
        null
    }

    /** "14 Kasım" */
    private fun releaseLabel(d: String): String {
        val c = parseDay(d) ?: return ""
        return SimpleDateFormat("d MMMM", tr).format(c.time)
    }

    /** Whole days from today to [d]; negative if past. */
    private fun daysUntil(d: String): Int? {
        val c = parseDay(d) ?: return null
        val today = Calendar.getInstance()
        for (x in listOf(c, today)) {
            x.set(Calendar.HOUR_OF_DAY, 12); x.set(Calendar.MINUTE, 0); x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0)
        }
        return ((c.timeInMillis - today.timeInMillis) / 86400000L).toInt()
    }

    /** "Bugün vizyonda", "Yarın", "3 gün kaldı". */
    private fun countdown(d: String): String = when (val n = daysUntil(d)) {
        null -> ""
        0 -> "Bugün vizyonda"
        1 -> "Yarın vizyonda"
        else -> if (n < 0) "Vizyonda" else "$n gün kaldı"
    }

    /** The "Ana Sayfa tuşunu ayarla" button: while Home goes elsewhere, on at most five days, unless turned off. */
    private fun refreshHomeNudge() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val days = prefs.homeNudgeDays.split(',').filter { it.isNotEmpty() }
        val show = prefs.setupDone && !prefs.homeNudgeOff && HomeGuide.needed(this) && (today in days || days.size < 5)
        if (show && today !in days) prefs.homeNudgeDays = (days.takeLast(9) + today).joinToString(",")
        homePill.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Film gecesi: the countdown on the screensaver and, at the time, the full-screen card. */
    private val nightTick = object : Runnable {
        override fun run() {
            checkNight()
            setDaylight()
            // At nine on a weeknight the hero puts short films first (see shortEvening).
            if (!idle) applyGenres()
            handler.postDelayed(this, 30_000L)
        }
    }

    /** Once a week, from Friday evening: the weekend guide, when nothing else is on screen. */
    private val weekendCheck = Runnable {
        if (prefs.setupDone && !idle && menu == null && updateWall == null && hasWindowFocus() &&
            java.io.File(filesDir, "pick.json").exists() && WeekendActivity.due(this)
        ) {
            WeekendActivity.open(this)
        }
    }

    /** A wash too faint to notice on its own, that makes the screen feel like the time of day. */
    private fun setDaylight() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val c = when (h) {
            in 6..10 -> Color.argb(22, 120, 170, 255)
            in 11..16 -> Color.TRANSPARENT
            in 17..20 -> Color.argb(30, 255, 150, 60)
            21 -> Color.argb(30, 200, 110, 70)
            else -> Color.argb(46, 6, 10, 30)
        }
        if (c == daylightColor) return
        val from = if (daylightColor == -1) c else daylightColor
        daylightColor = c
        val a = ValueAnimator.ofArgb(from, c)
        a.duration = 4000
        a.addUpdateListener { daylight.setBackgroundColor(it.animatedValue as Int) }
        a.start()
    }

    private fun checkNight() {
        val plan = MovieNight.get(prefs)?.takeIf { !Remote.isOff("night") }
        val now = System.currentTimeMillis()
        val ahead = plan != null && plan.at > now
        nightLine.visibility = if (ahead) View.VISIBLE else View.GONE
        if (ahead) nightLine.text = "Akşam planına ${MovieNight.left(plan!!.at)}  ·  ${plan.film.title}"
        if (plan != null && !ahead && prefs.movieNightShown != plan.at && now < plan.at + 2 * 3600_000L) {
            if (idle) exitIdle()
            closeMenu(false)
            MovieNight.card(this, ui, plan) { openDetail(it, null, null) }
            refreshReminder()
        } else if (!ahead && remindPill.tag == "night") {
            refreshReminder()
        }
    }

    /** Shows "Bugün vizyonda: …" for reminded films from release day for three days. */
    private fun refreshReminder() {
        remindPill.tag = null
        MovieNight.get(prefs)?.takeIf { it.at > System.currentTimeMillis() }?.let { plan ->
            remindPill.tag = "night"
            remindPill.text = "Akşam planı ${MovieNight.time(plan.at)}: ${plan.film.title}"
            remindPill.visibility = View.VISIBLE
            remindPill.setOnClickListener { openDetail(plan.film, null, null) }
            return
        }
        val due = prefs.reminders.firstOrNull { val n = daysUntil(it.release); n != null && n in -3..0 }
        if (due == null) {
            showEpisodeNotice()
            return
        }
        remindPill.text = if (daysUntil(due.release) == 0) "Bugün vizyonda: ${due.title}" else "Vizyonda: ${due.title}"
        remindPill.visibility = View.VISIBLE
        remindPill.setOnClickListener {
            prefs.reminders = prefs.reminders.filter { it.key != due.key }
            openDetail(due, null, null)
            refreshReminder()
        }
        // Drop reminders that are long past.
        prefs.reminders = prefs.reminders.filter { (daysUntil(it.release) ?: 0) >= -3 }
    }

    /** "Bugün yeni bölüm: …" for a series in Listem whose new episode comes out today. */
    private fun showEpisodeNotice() {
        val seen = prefs.episodesSeen
        var mark = ""
        val show = prefs.watchlist.firstOrNull { p ->
            val n = Episodes.of(p) ?: return@firstOrNull false
            mark = "${p.key}:${n.date}"
            Episodes.daysUntil(n.date) == 0 && mark !in seen
        }
        if (show == null) {
            remindPill.visibility = View.GONE
            return
        }
        val key = mark
        remindPill.text = "Bugün yeni bölüm: ${show.title}"
        remindPill.visibility = View.VISIBLE
        remindPill.setOnClickListener {
            // Keep the list small: only today's notices matter.
            prefs.episodesSeen = prefs.episodesSeen.filter { it.endsWith(key.substringAfterLast(':')) }.toSet() + key
            openDetail(show, null, null)
            refreshReminder()
        }
    }

    private fun shelfBlock(): View {
        shelf = ui.vbox()
        shelf.clipChildren = false
        shelf.visibility = View.GONE
        val label = ui.label("LİSTEM")
        shelf.addView(label, ui.ll(ui.wrap, ui.wrap, top = 40))
        shelfRow = ui.hbox()
        shelfScroller = rowScroller(shelfRow)
        shelfScroller.setPadding(ui.u(30), ui.u(26), ui.u(30), ui.u(4))
        val lp = ui.ll(ui.match, ui.wrap)
        lp.leftMargin = -ui.u(30)
        lp.rightMargin = -ui.u(30)
        shelf.addView(shelfScroller, lp)
        return shelf
    }

    /** Screensaver layer: just the film, its name and the time. */
    private fun ambientBlock(): FrameLayout {
        val box = FrameLayout(this)
        box.alpha = 0f
        box.visibility = View.GONE
        val col = ui.vbox()
        val tag = ui.label("GÜNÜN FİLMLERİ")
        ambientTag = tag
        ambientTitle = ui.text("", 44, C.TEXT, 600)
        ambientTitle.setShadowLayer(ui.uf(24), 0f, ui.uf(3), Color.argb(150, 0, 0, 0))
        ambientMeta = ui.text("", 20, C.TEXT2, 600)
        ambientMeta.setShadowLayer(ui.uf(18), 0f, ui.uf(2), Color.argb(150, 0, 0, 0))
        ambientLine = ui.text("", 22, C.TEXT2, 400)
        ambientLine.maxWidth = ui.u(900)
        ambientLine.maxLines = 2
        ambientLine.ellipsize = TextUtils.TruncateAt.END
        ambientLine.setShadowLayer(ui.uf(18), 0f, ui.uf(2), Color.argb(150, 0, 0, 0))
        nightLine = ui.text("", 22, Color.parseColor("#FFD9A8"), 600)
        nightLine.setShadowLayer(ui.uf(18), 0f, ui.uf(2), Color.argb(150, 0, 0, 0))
        nightLine.visibility = View.GONE
        col.addView(nightLine, ui.ll(ui.wrap, ui.wrap, bottom = 28))
        col.addView(tag)
        col.addView(ambientTitle, ui.ll(ui.wrap, ui.wrap, top = 10))
        col.addView(ambientMeta, ui.ll(ui.wrap, ui.wrap, top = 10))
        col.addView(ambientLine, ui.ll(ui.wrap, ui.wrap, top = 8))
        val clp = ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.START)
        clp.leftMargin = ui.u(110)
        clp.bottomMargin = ui.u(90)
        box.addView(col, clp)
        val right = ui.hbox()
        ambientWeather = ui.hbox()
        ambientWeatherIcon = ImageView(this)
        ambientWeatherIcon.setColorFilter(C.TEXT, PorterDuff.Mode.SRC_IN)
        ambientWeather.addView(ambientWeatherIcon, ui.ll(ui.u(44), ui.u(44)))
        ambientTemp = ui.text("", 40, C.TEXT, 400)
        ambientTemp.setShadowLayer(ui.uf(20), 0f, ui.uf(2), Color.argb(150, 0, 0, 0))
        ambientWeather.addView(ambientTemp, ui.ll(ui.wrap, ui.wrap, left = 12))
        ambientWeather.visibility = View.GONE
        right.addView(ambientWeather, ui.ll(ui.wrap, ui.wrap, right = 40))
        ambientClock = rollingClock(72, 400)
        ambientClock.setShadow(ui.uf(24), ui.uf(3), Color.argb(150, 0, 0, 0))
        // The large clock grows from its bottom-right corner, so it stays inside the screen.
        ambientClock.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.pivotX = v.width.toFloat()
            v.pivotY = v.height.toFloat()
        }
        right.addView(ambientClock)
        val tlp = ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.END)
        tlp.rightMargin = ui.u(110)
        tlp.bottomMargin = ui.u(80)
        box.addView(right, tlp)
        return box
    }

    /** Screensaver text: name, IMDb and genres, and one short line about the film. */
    private fun setAmbient(p: Pick) {
        ambientTitle.text = p.title
        val bits = ArrayList<String>()
        val ahead = daysUntil(p.release)?.let { it > 0 } == true
        if (p.years > 0) bits.add("${p.years} yıl önce bugün gösterime girdi")
        val days = daysUntil(p.release)
        val countdownFilm = ahead && savedPicks != null && prefs.reminders.any { it.key == p.key }
        ambientTag.text = when {
            countdownFilm -> "HATIRLATTIĞIN FİLM  ·  " + releaseLabel(p.release).uppercase(tr)
            p.years > 0 -> "SİNEMA TARİHİNDE BUGÜN"
            savedPicks == null -> "GÜNÜN FİLMLERİ"
            p.provider.isNotEmpty() -> "YENİ  ·  " + p.provider.uppercase(tr)
            ahead -> "YAKINDA  ·  " + releaseLabel(p.release).uppercase(tr)
            else -> "ŞU AN SİNEMALARDA"
        }
        if (savedPicks != null && ahead) bits.add(countdown(p.release))
        if (p.imdb > 0) bits.add(String.format(tr, "IMDb %.1f", p.imdb))
        if (p.year.isNotEmpty()) bits.add(p.year)
        if (p.genres.isNotEmpty()) bits.add(p.genres.take(2).joinToString(", "))
        ambientMeta.text = bits.joinToString("   ·   ")
        // A film you asked to be reminded of: a large countdown instead of its slogan.
        if (countdownFilm && days != null) {
            ambientLine.text = if (days == 1) "Yarın vizyonda" else "Vizyona $days gün"
            ambientLine.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.uf(64))
            ambientLine.typeface = ui.font(700)
            ambientLine.setTextColor(C.TEXT)
            ambientLine.letterSpacing = -0.02f
            return
        }
        // The film's slogan, large and light; else its own story in two lines.
        val slogan = p.tagline.trim()
        if (slogan.isNotEmpty()) {
            ambientLine.text = slogan
            ambientLine.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.uf(34))
            ambientLine.typeface = ui.font(400)
            ambientLine.setTextColor(C.TEXT)
            ambientLine.letterSpacing = -0.01f
        } else {
            ambientLine.text = p.overview.trim().ifEmpty { p.reason }
            ambientLine.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.uf(22))
            ambientLine.setTextColor(C.TEXT2)
            ambientLine.letterSpacing = 0f
        }
    }

    // ---------- Apps dock ----------

    /** Apps in the side panel instead of the row; only while the film is there to take focus. */
    private fun appsSide() = prefs.appsSide && prefs.showPicks

    private fun refreshDock(force: Boolean = false) {
        val apps = Apps.shown(this, prefs)
        val side = appsSide()
        val sig = apps.joinToString("|") { it.key } + "#" + prefs.cardSize + "#" + side + "#" + prefs.appShape +
            "#" + appNews.entries.joinToString("|") { it.key + ":" + it.value.second.size }
        if (!force && sig == dockSig) return
        val first = dockSig.isEmpty()
        val focusedKey = currentFocus?.tag as? String
        val hadFocus = row.hasFocus()
        dockSig = sig
        row.removeAllViews()
        for (a in apps) row.addView(tile(a))
        row.addView(allAppsTile())
        scroller.visibility = if (side) View.GONE else View.VISIBLE
        edgeTab.visibility = if (side) View.VISIBLE else View.GONE
        if (first || (side && hadFocus)) {
            focusFirst()
        } else if (focusedKey != null) {
            (row.findViewWithTag<View>(focusedKey) as? ViewGroup)?.getChildAt(0)?.requestFocus()
        }
    }

    private fun tile(app: AppEntry): View {
        val pm = packageManager
        val col = ui.vbox()
        col.tag = app.key
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.clipChildren = false
        col.clipToPadding = false

        val card = FrameLayout(this)
        card.tag = app.key
        card.isFocusable = true
        card.isClickable = true
        card.isLongClickable = true
        val img = ImageView(this)
        val shape = prefs.appShape
        val icons = Apps.iconShape(shape)
        val cw = when (prefs.cardSize) { 0 -> 232; 2 -> 344; else -> 288 }
        // Icon shapes: a square as tall as the banner would be, with the app's own icon.
        val cardW = if (icons) cw * 9 / 16 else cw
        val radius = Apps.radius(shape, cardW)
        val banner = if (icons) null else try { app.info.loadBanner(pm) } catch (e: Exception) { null }
        val art: Drawable
        if (banner != null) {
            card.background = ui.round(Color.BLACK, radius)
            img.setImageDrawable(banner)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            art = banner
        } else {
            art = app.info.loadIcon(pm)
            Apps.iconCard(ui, card, app.key, art, if (icons) null else app.label, radius, cw * 9 / 16)
        }
        card.clipToOutline = true
        if (banner != null) card.addView(img, ui.fl(ui.match, ui.match))
        // New today on this app's platform: a small "3 yeni" in the corner until opened once.
        val news = appNews[app.key]
        if (news != null) {
            val b = ui.text("${news.second.size} yeni", 15, Color.WHITE, 800)
            b.includeFontPadding = false
            b.setPadding(ui.u(10), ui.u(5), ui.u(10), ui.u(5))
            b.background = ui.round(Color.parseColor("#FF4D8D"), 12)
            b.elevation = ui.uf(4)
            val blp = ui.fl(ui.wrap, ui.wrap, Gravity.TOP or Gravity.END)
            blp.setMargins(0, ui.u(8), ui.u(8), 0)
            card.addView(b, blp)
        }
        val fx = Fx.decorate(ui, card, radius)

        val label = ui.text(app.label, 20, C.TEXT, 500)
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.alpha = 0f
        val sub = ui.text("", 16, C.TEXT3, 500)
        sub.gravity = Gravity.CENTER
        sub.maxLines = 1
        sub.alpha = 0f

        card.setOnFocusChangeListener { _, f ->
            ui.lift(card, f, 1.12f, 36)
            fx(f)
            if (f) sub.text = lastOpened(app.key)
            for (t in listOf(label, sub)) {
                t.animate().alpha(if (f) 1f else 0f).translationY(if (f) ui.uf(8) else 0f)
                    .setDuration(D.FAST).start()
            }
            if (f) {
                shiftTo(0)
                tintTo(appColors.getOrPut(app.key) { colorOf(art) })
            }
        }
        card.setOnClickListener { if (appNews[app.key] != null) showAppNews(app) else launch(app) }
        card.setOnLongClickListener {
            appMenu(app, col)
            true
        }

        col.addView(card, ui.ll(ui.u(cardW), ui.u(cw * 9 / 16)))
        // Names under small icons get the width of a banner so they are not cut short.
        col.addView(label, ui.ll(ui.u(maxOf(cardW, 200)), ui.wrap, top = 18))
        col.addView(sub, ui.ll(ui.u(maxOf(cardW, 200)), ui.wrap, top = 4))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 34)
        return col
    }

    /** Last card of the row: opens the page with every app on the box. */
    private fun allAppsTile(): View {
        val col = ui.vbox()
        col.tag = ALL_APPS
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.clipChildren = false
        col.clipToPadding = false
        val card = FrameLayout(this)
        card.isFocusable = true
        card.isClickable = true
        val tileSize = (when (prefs.cardSize) { 0 -> 232; 2 -> 344; else -> 288 }) * 9 / 16
        val tileRadius = Apps.radius(prefs.appShape, tileSize)
        card.background = ui.round(Color.parseColor("#8C1C1C22"), tileRadius, 1)
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_apps)
        icon.setColorFilter(C.TEXT, PorterDuff.Mode.SRC_IN)
        card.addView(icon, ui.fl(ui.u(56), ui.u(56), Gravity.CENTER))
        val label = ui.text("Tüm uygulamalar", 20, C.TEXT, 500)
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        label.alpha = 0f
        card.setOnFocusChangeListener { _, f ->
            ui.lift(card, f, 1.12f, 36)
            card.background = if (f) ui.round(Color.WHITE, tileRadius) else ui.round(Color.parseColor("#8C1C1C22"), tileRadius, 1)
            icon.setColorFilter(if (f) C.INK else C.TEXT, PorterDuff.Mode.SRC_IN)
            label.animate().alpha(if (f) 1f else 0f).translationY(if (f) ui.uf(8) else 0f).setDuration(D.FAST).start()
            if (f) {
                shiftTo(0)
                tintTo(0)
            }
        }
        card.setOnClickListener { openAllApps() }
        val cw = when (prefs.cardSize) { 0 -> 232; 2 -> 344; else -> 288 }
        col.addView(card, ui.ll(ui.u(cw * 9 / 16), ui.u(cw * 9 / 16)))
        col.addView(label, ui.ll(ui.u(cw), ui.wrap, top = 18))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 34)
        return col
    }

    private fun colorAction(code: Int): String? {
        val color = when (code) {
            KeyEvent.KEYCODE_PROG_RED -> "red"
            KeyEvent.KEYCODE_PROG_GREEN -> "green"
            KeyEvent.KEYCODE_PROG_YELLOW -> "yellow"
            KeyEvent.KEYCODE_PROG_BLUE -> "blue"
            else -> return null
        }
        return prefs.colorKey(color).takeIf { it != "none" }
    }

    private fun runColorAction(act: String) {
        when (act) {
            "night" -> askWhatToWatch()
            "bell" -> openNotices()
            "history" -> openHistory()
            "explore" -> openExplore()
            "search" -> openSearch()
            "apps" -> if (appsSide()) openSideApps(currentFocus) else openAllApps()
            "calendar" -> {
                idleOff()
                startActivity(Intent(this, CalendarActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }
    }

    private fun openHistory() {
        closeMenu(false)
        idleOff()
        Fx.backdrop = Fx.snapshotBlur(frame, scale = 28, passes = 4)
            ?: ((if (backA.alpha >= backB.alpha) backA else backB).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        WatchHistoryActivity.open(this)
    }

    private fun openExplore() {
        closeMenu(false)
        idleOff()
        Fx.backdrop = Fx.snapshotBlur(frame, scale = 28, passes = 4)
            ?: ((if (backA.alpha >= backB.alpha) backA else backB).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        startActivity(Intent(this, ExploreActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun openAllApps() {
        closeMenu(false)
        idleOff()
        // Behind the grid: the home screen, blurred; in light mode (no blur) the film picture itself.
        Fx.backdrop = Fx.snapshotBlur(frame, scale = 28, passes = 4)
            ?: ((if (backA.alpha >= backB.alpha) backA else backB).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        startActivity(Intent(this, AppsActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** The handle at the left edge: shows the apps are there; a mouse opens them by pointing at it. */
    private fun edgeHandle(): FrameLayout {
        val tab = FrameLayout(this)
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#8C1C1C22"))
        val r = ui.uf(22)
        bg.cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        bg.setStroke(maxOf(1, ui.u(1)), C.STROKE)
        tab.background = bg
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_apps)
        icon.setColorFilter(C.TEXT2, PorterDuff.Mode.SRC_IN)
        tab.addView(icon, ui.fl(ui.u(26), ui.u(26), Gravity.CENTER))
        tab.alpha = 0.75f
        tab.isClickable = true
        tab.setOnClickListener { openSideApps(currentFocus) }
        tab.setOnHoverListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_HOVER_ENTER) openSideApps(currentFocus)
            false
        }
        tab.visibility = View.GONE
        return tab
    }

    /** The apps, sliding in from the left edge. */
    private fun openSideApps(owner: View?) {
        if (menu != null || moving != null || updateWall != null) return
        val layer = FrameLayout(this)
        layer.setBackgroundColor(Color.argb(ui.dimAlpha(), 0, 0, 0))
        layer.isClickable = true

        val panel = ui.vbox()
        val bg = GradientDrawable()
        bg.setColor(Color.argb(ui.panelAlpha(), 0x1B, 0x1B, 0x20))
        val r = ui.uf(36)
        bg.cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        panel.background = bg
        val glass = LiquidGlass(0, 0f, maxOf(1f, ui.uf(2)), floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f))
        glass.light = 0.12f
        panel.foreground = glass
        sideGlass = glass
        panel.elevation = ui.uf(40)
        panel.clipChildren = false
        panel.setPadding(ui.u(40), ui.u(64), ui.u(40), ui.u(36))

        val head = ui.label("UYGULAMALAR")
        panel.addView(head, ui.ll(ui.wrap, ui.wrap, left = 26, bottom = 22))

        // Apple TV style: the apps as picture tiles, two to a row; the selected one rises with its
        // name under it.
        val box = ui.vbox()
        box.clipChildren = false
        box.clipToPadding = false
        box.setPadding(ui.u(14), ui.u(14), ui.u(14), ui.u(14))
        var first: View? = null
        val pm = packageManager
        var line: LinearLayout? = null
        for ((n, app) in Apps.shown(this, prefs).withIndex()) {
            val item = appTile(app, pm)
            if (n % 2 == 0) {
                line = ui.hbox()
                line.clipChildren = false
                line.clipToPadding = false
                box.addView(line, ui.ll(ui.match, ui.wrap, bottom = 10))
            }
            line?.addView(item, ui.ll(ui.u(SIDE_TILE_W), ui.wrap, right = if (n % 2 == 0) 24 else 0))
            item.setOnClickListener {
                closeMenu(false)
                launch(app)
            }
            item.setOnLongClickListener {
                closeMenu(false)
                showMenu(app.label, listOf(
                    "Ana ekrandan çıkar" to {
                        closeMenu(false)
                        prefs.toggleFavorite(app.key)
                        refreshDock(force = true)
                        openSideApps(owner)
                    },
                    "Uygulama bilgisi" to {
                        closeMenu()
                        HomeButtonService.pause()
                        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                    }
                ), owner ?: hero)
                true
            }
            if (first == null) first = item
        }
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.addView(box)
        panel.addView(ui.clipBox(scroll), ui.ll(ui.match, 0, weight = 1f))

        val div = View(this)
        div.setBackgroundColor(C.STROKE)
        panel.addView(div, ui.ll(ui.match, maxOf(1, ui.u(1)), left = 26, right = 26, top = 12, bottom = 12))
        val all = sideRow("Tüm uygulamalar", null, R.drawable.ic_apps)
        all.setOnClickListener { openAllApps() }
        panel.addView(all, ui.ll(ui.match, ui.u(78)))
        if (first == null) first = all

        layer.addView(panel, ui.fl(ui.u(SIDE_PANEL_W), ui.match, Gravity.START))
        // Right (from the right column) or Back closes it (see dispatchKeyEvent and onBackPressed).
        showOverlay(layer, first, owner ?: hero)
        sidePanel = layer
        panel.translationX = -ui.uf(SIDE_PANEL_W)
        panel.animate().translationX(0f).setDuration(D.MED).setInterpolator(DecelerateInterpolator(2.2f)).start()
    }

    /**
     * An app in the side panel, as on Apple TV: its TV picture (or its icon on its own colour) in a
     * rounded 16:9 tile; selected, the tile rises with a soft light and a white rim, and the name
     * shows under it.
     */
    private fun appTile(app: AppEntry, pm: android.content.pm.PackageManager): LinearLayout {
        val w = SIDE_TILE_W
        val h = w * 9 / 16
        val col = ui.vbox()
        col.isFocusable = true
        col.isClickable = true
        col.isLongClickable = true
        col.clipChildren = false
        col.clipToPadding = false
        col.gravity = Gravity.CENTER_HORIZONTAL
        val tile = FrameLayout(this)
        tile.clipToOutline = true
        val banner = try { app.info.loadBanner(pm) } catch (e: Exception) { null }
        if (banner != null) {
            tile.background = ui.round(Color.parseColor("#1C1C22"), 16)
            val img = ImageView(this)
            img.setImageDrawable(banner)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            tile.addView(img, ui.fl(ui.match, ui.match))
        } else {
            val icon = try { app.info.loadIcon(pm) } catch (e: Exception) { null }
            Apps.iconCard(ui, tile, app.key, icon, null, 16, h)
        }
        // The light that crosses the glass when it is selected.
        val gloss = View(this)
        gloss.background = ui.gradient(GradientDrawable.Orientation.TL_BR,
            Color.argb(70, 255, 255, 255), Color.argb(10, 255, 255, 255), Color.TRANSPARENT, radius = 16)
        gloss.alpha = 0f
        tile.addView(gloss, ui.fl(ui.match, ui.match))
        col.addView(tile, ui.ll(ui.u(w), ui.u(h)))
        val name = ui.text(app.label, 19, C.TEXT, 600)
        name.gravity = Gravity.CENTER
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        name.alpha = 0f
        col.addView(name, ui.ll(ui.u(w), ui.wrap, top = 12))
        col.setOnFocusChangeListener { _, f ->
            tile.foreground = if (f) ui.round(Color.TRANSPARENT, 16, 3, Color.WHITE) else null
            tile.animate().cancel()
            tile.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f)
                .translationZ(if (f) ui.uf(24) else 0f).translationY(if (f) -ui.uf(4) else 0f)
                .setDuration(if (Fx.lite) 0L else D.FAST).setInterpolator(DecelerateInterpolator(2f)).start()
            gloss.animate().alpha(if (f) 1f else 0f).setDuration(D.FAST).start()
            if (f) sideGlass?.sweep(Fx.lastDir)
            name.animate().alpha(if (f) 1f else 0f).translationY(if (f) ui.uf(4) else 0f).setDuration(D.FAST).start()
        }
        return col
    }

    /** One line of the side panel: the app's icon (or a symbol) and its name. */
    private fun sideRow(label: String, icon: Drawable?, res: Int): LinearLayout {
        val item = ui.hbox()
        item.gravity = Gravity.CENTER_VERTICAL
        item.isFocusable = true
        item.isClickable = true
        item.isLongClickable = true
        item.setPadding(ui.u(22), 0, ui.u(22), 0)
        val img = ImageView(this)
        if (icon != null) img.setImageDrawable(icon) else img.setImageResource(res)
        if (icon == null) img.setColorFilter(C.TEXT, PorterDuff.Mode.SRC_IN)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        item.addView(img, ui.ll(ui.u(46), ui.u(46)))
        val t = ui.text(label, 24, C.TEXT, 500)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        item.addView(t, ui.ll(0, ui.wrap, left = 22, weight = 1f))
        item.setOnFocusChangeListener { _, f ->
            item.background = if (f) ui.round(Color.WHITE, D.PLATE) else null
            t.setTextColor(if (f) C.INK else C.TEXT)
            if (icon == null) img.setColorFilter(if (f) C.INK else C.TEXT, PorterDuff.Mode.SRC_IN)
            ui.lift(item, f, 1.03f, 10)
        }
        return item
    }

    /**
     * Left on the leftmost card or button opens the side panel. "Leftmost" means nothing to its
     * left on the same line: moving left would leave the row (or go nowhere).
     */
    private fun atLeftEdge(f: View): Boolean {
        val next = android.view.FocusFinder.getInstance().findNextFocus(content, f, View.FOCUS_LEFT) ?: return true
        val a = IntArray(2)
        val b = IntArray(2)
        f.getLocationOnScreen(a)
        next.getLocationOnScreen(b)
        val ca = a[1] + f.height / 2
        val cb = b[1] + next.height / 2
        return Math.abs(ca - cb) > f.height / 2
    }

    /** "Bugün açıldı", "Dün açıldı", "5 gün önce açıldı" or "Henüz açılmadı". */
    private fun lastOpened(key: String): String {
        val t = prefs.usedAt()[key] ?: return "Henüz açılmadı"
        val now = Calendar.getInstance()
        val then = Calendar.getInstance()
        then.timeInMillis = t
        val mins = (System.currentTimeMillis() - t) / 60000L
        val days = ((now.timeInMillis - t) / 86400000L).toInt()
        return when {
            mins < 1 -> "Az önce açıldı"
            mins < 60 -> "$mins dakika önce açıldı"
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) && days < 1 -> "Bugün açıldı"
            days <= 1 -> "Dün açıldı"
            days < 30 -> "$days gün önce açıldı"
            else -> "Uzun süredir açılmadı"
        }
    }

    /** A rich colour from the app's artwork, or 0 if it is mostly grey. */
    private fun colorOf(art: Drawable): Int {
        // Draw a copy: changing the bounds of the shown drawable shrinks it on the card.
        val d = art.constantState?.newDrawable(resources)?.mutate()
        val saved = art.copyBounds()
        return try {
            val b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
            val src = d ?: art
            src.setBounds(0, 0, 16, 9)
            src.draw(Canvas(b))
            val hsv = FloatArray(3)
            var x = 0.0
            var y = 0.0
            var weight = 0.0
            var sat = 0.0
            for (i in 0 until 16) for (j in 0 until 9) {
                val p = b.getPixel(i, j)
                if (Color.alpha(p) < 128) continue
                Color.colorToHSV(p, hsv)
                val wgt = (hsv[1] * hsv[2]).toDouble()
                if (wgt < 0.12) continue
                val rad = Math.toRadians(hsv[0].toDouble())
                x += Math.cos(rad) * wgt
                y += Math.sin(rad) * wgt
                sat += hsv[1] * wgt
                weight += wgt
            }
            b.recycle()
            if (weight < 4) return 0
            var hue = Math.toDegrees(Math.atan2(y, x)).toFloat()
            if (hue < 0) hue += 360f
            Color.HSVToColor(160, floatArrayOf(hue, (sat / weight).toFloat().coerceIn(0.45f, 0.85f), 0.75f))
        } catch (e: Exception) {
            0
        } finally {
            if (d == null) art.bounds = saved
        }
    }

    private fun tintTo(target: Int) {
        if (target == tintColor && tintAnim == null) return
        tintAnim?.cancel()
        val a = ValueAnimator.ofObject(ArgbEvaluator(), tintColor, target)
        a.duration = 700
        a.addUpdateListener {
            tintColor = it.animatedValue as Int
            val al = Color.alpha(tintColor)
            val rgb = tintColor and 0xFFFFFF
            // A low wash from the corner: a hint of the app's colour, not a filter over the film.
            tintDrawable.colors = intArrayOf(
                ((al * 0.35f).toInt() shl 24) or rgb,
                ((al * 0.1f).toInt() shl 24) or rgb,
                0
            )
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (tintAnim === animation) tintAnim = null
            }
        })
        tintAnim = a
        a.start()
    }

    private fun focusFirst() {
        // While the update screen is up, only its button takes focus.
        updateWall?.let { w ->
            w.findFocus() ?: (((w.getChildAt(0) as? ViewGroup)?.let { it.getChildAt(it.childCount - 1) })?.requestFocus())
            return
        }
        shiftTo(0)
        if (appsSide()) {
            (0 until buttons.childCount).map { buttons.getChildAt(it) }
                .firstOrNull { it.visibility == View.VISIBLE && it.isFocusable }?.requestFocus()
            return
        }
        (row.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus()
        scroller.smoothScrollTo(0, 0)
    }

    /** Which apps on this TV have new titles today (their platform's list), not yet opened today. */
    /** Off the main thread (it asks the TV for its apps): app key to platform and today's titles. */
    private fun newsFor(list: List<Pick>, today: String): Map<String, Pair<String, List<Pick>>> {
        val seen = prefs.platformSeen
        val out = HashMap<String, Pair<String, List<Pick>>>()
        for ((name, films) in list.filter { !Remote.isHidden(it) }.groupBy { it.provider }) {
            if (name.isEmpty() || "$today|$name" in seen) continue
            val app = Apps.forPlatform(this, name) ?: continue
            if (out.containsKey(app.key)) continue
            out[app.key] = name to films
        }
        return out
    }

    /** First press on a card with a badge: what is new there today; the app opens from the top line. */
    private fun showAppNews(app: AppEntry) {
        val (name, films) = appNews[app.key] ?: return launch(app)
        val today = prefs.pickDate
        prefs.platformSeen = prefs.platformSeen.filter { it.startsWith("$today|") }.toSet() + "$today|$name"
        val rows = listOf(TrailerActivity.accusative(name) + " aç" to "") +
            films.map { it.title to (if (it.isSeries) "Dizi" else "Film") + (if (it.imdb > 0) String.format(tr, "  ·  IMDb %.1f", it.imdb) else "") }
        val done = {
            handler.post {
                appNews = appNews.filterValues { it.first != name }
                refreshDock()
            }
        }
        Dialogs.list(this, "${TrailerActivity.locative(name)} bugün yeni", rows,
            subtitle = "Bir yapıma bas, sayfası açılsın; ya da doğrudan uygulamaya geç.", icon = R.drawable.ic_tv,
            cancel = { done() }) { i ->
            done()
            if (i == 0) launch(app) else openDetail(films[i - 1], null, null)
        }
    }

    /** From the bell's "Netflix: 4 yeni yapım": all of them in a list; one film opens its page straight away. */
    private fun showPlatformNews(name: String, films: List<Pick>) {
        if (films.size == 1) {
            openDetail(films.first(), null, null)
            return
        }
        Thread {
            val app = Apps.forPlatform(this, name)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val head = if (app != null) listOf(TrailerActivity.accusative(name) + " aç" to "") else emptyList()
                val rows = head + films.map {
                    it.title to (if (it.isSeries) "Dizi" else "Film") + (if (it.imdb > 0) String.format(tr, "  ·  IMDb %.1f", it.imdb) else "")
                }
                Dialogs.list(this, "${TrailerActivity.locative(name)} yeni", rows,
                    subtitle = "Bir yapıma bas, sayfası açılsın.", icon = R.drawable.ic_tv) { i ->
                    if (app != null && i == 0) launch(app) else openDetail(films[i - head.size], null, null)
                }
            }
        }.start()
    }

    private fun launch(app: AppEntry) {
        prefs.markUsed(app.key)
        HomeButtonService.opened(app.pkg)
        open(Apps.launchIntent(app))
    }

    private fun open(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Glass.toast(this, "Açılamadı")
        }
    }

    private fun askWhatToWatch() {
        val films = (allPicks + top10 + genrePools.flatMap { it.second }).filter { it.key !in skipNow && watchableTonight(it) }
        if (films.isEmpty()) {
            Glass.toast(this, "Film listesi henüz yüklenmedi")
            return
        }
        // Sinema Gecesi: one film picked and made ready, instead of questions first.
        CinemaNightActivity.open(this, films)
    }

    /**
     * A film that can be watched at home tonight: out already (not "coming soon"), and if it is
     * still only in cinemas, out on a platform or digitally too. Series are not suggested here.
     */
    private fun watchableTonight(p: Pick): Boolean {
        if (p.isSeries) return false
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        if ((p.year.take(4).toIntOrNull() ?: 0) > thisYear) return false
        if ((daysUntil(p.release) ?: 0) > 0) return false
        val inCinemaOnly = cinema.any { it.key == p.key } || soon.any { it.key == p.key }
        if (!inCinemaOnly) return true
        val digitalOut = daysUntil(p.digital)?.let { it <= 0 } == true
        return digitalOut || p.where.isNotEmpty() || p.provider.isNotEmpty()
    }

    /** Google TV's own search (voice or typed). */
    /**
     * Search LeXia's own film list by voice (the remote's microphone through Android's speech screen),
     * or by typing where the TV has no speech input.
     */
    private fun findFilm() {
        val voice = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Film ya da dizi adını söyle")
            .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        if (voice.resolveActivity(packageManager) != null) {
            try {
                startActivityForResult(voice, REQ_VOICE)
                return
            } catch (e: Exception) {
            }
        }
        Dialogs.input(this, "Film ya da dizi ara", "") { showFound(listOf(it)) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE || resultCode != RESULT_OK) return
        val heard = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS) ?: return
        showFound(heard)
    }

    /** Films whose title holds what was said (any of the speech guesses); one opens, several are listed. */
    private fun showFound(queries: List<String>) {
        fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(tr), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace('ı', 'i').replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()
        val pool = (allPicks + top10 + top10tv + cinema + soon + genrePools.flatMap { it.second } + history + platformList)
            .filter { it.title.isNotEmpty() }.distinctBy { it.key }
        val wanted = queries.map { norm(it) }.filter { it.length >= 2 }
        // "YouTube", "Netflix'i aç": an app's name opens the app instead of searching films.
        val apps = Apps.all(this)
        for (q in wanted) {
            val bare = q.replace(Regex(" ?(i|yi|u|yu|ı|yı|ü|yü)? ?(ac|aç)$"), "").trim()
            val app = apps.firstOrNull { norm(it.label) == bare || norm(it.label) == q }
                ?: apps.firstOrNull { val l = norm(it.label); bare.length >= 4 && (l.startsWith(bare) || bare.startsWith(l)) && l.length >= 4 }
            if (app != null) {
                launch(app)
                return
            }
        }
        val found = pool.filter { p ->
            val t = norm(p.title)
            wanted.any { q -> t.contains(q) || q.split(' ').filter { it.length > 1 }.let { w -> w.isNotEmpty() && w.all { t.contains(it) } } }
        }.take(12)
        val said = queries.firstOrNull().orEmpty()
        when {
            found.size == 1 -> openDetail(found[0], null, null)
            // Everything else: every film, series and person TMDB knows (Turkish series and platforms' too).
            BuildConfig.TMDB_API_KEY.isNotBlank() -> SearchAll.run(this, said, pool)
            found.isNotEmpty() -> Dialogs.list(
                this, "“$said”", found.map { it.title to listOf(if (it.isSeries) "Dizi" else "Film", it.year).filter { s -> s.isNotEmpty() }.joinToString(" · ") },
                "LeXia'nın bugünkü listesinden", R.drawable.ic_search
            ) { openDetail(found[it], null, null) }
            else -> Dialogs.list(
                this, "“$said” bulunamadı", listOf("TV'nin kendi aramasında ara" to "", "Tekrar söyle" to ""),
                "LeXia yalnızca bugünkü film listesinde arar.", R.drawable.ic_search
            ) { if (it == 0) openSearch() else findFilm() }
        }
    }

    private fun openSearch() {
        val tries = listOf(
            Intent("android.search.action.GLOBAL_SEARCH"),
            Intent(Intent.ACTION_ASSIST),
            Intent(Intent.ACTION_WEB_SEARCH)
        )
        for (i in tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(packageManager) == null) continue
            try {
                startActivity(i)
                return
            } catch (e: Exception) {
            }
        }
        Glass.toast(this, "Arama bu TV'de açılamadı. Kumandadaki mikrofon tuşunu deneyebilirsin.", long = true)
    }

    private fun trailer(p: Pick) {
        // The home screen's trailer is playing this film: the trailer page carries on from there.
        val at = if (::heroTrailer.isInitialized && heroTrailer.active && picks.getOrNull(pickIndex)?.key == p.key) heroTrailer.where() else null
        if (p.trailer.isNotEmpty()) TrailerActivity.open(this, p, at)
        else Glass.toast(this, "Fragman bulunamadı")
    }

    // ---------- Watchlist ----------

    private fun inList(p: Pick) = prefs.watchlist.any { it.key == p.key }

    private fun toggleList() {
        val p = picks.getOrNull(pickIndex) ?: return
        val list = prefs.watchlist
        val adding = list.none { it.key == p.key }
        prefs.watchlist = if (!adding) list.filter { it.key != p.key } else listOf(p) + list
        if (adding) Sound.event(Sound.ADD)
        updateListBtn()
        refreshShelf()
    }

    private fun updateListBtn() {
        val p = picks.getOrNull(pickIndex) ?: return
        listBtn.text = if (inList(p)) "✓   Listemde" else "+   Listeme ekle"
    }

    private fun refreshShelf() {
        val list = prefs.watchlist
        val sig = list.joinToString("|") { it.key } + "#" + Episodes.version
        if (sig == shelfSig) return
        shelfSig = sig
        shelfRow.removeAllViews()
        for (p in list) shelfRow.addView(shelfCard(p))
        if (list.isEmpty()) shelfRow.addView(emptyListCard())
        showTopRows()
    }

    private fun shelfCard(p: Pick): View {
        return shelfCardInner(p).also { col ->
            val card = (col as ViewGroup).getChildAt(0)
            card.isLongClickable = true
            card.setOnLongClickListener {
                rowMenu("list", card)
                true
            }
        }
    }

    /** Listem with nothing in it: how to fill it, and OK goes to this week's Top 10. */
    private fun emptyListCard(): View {
        val card = ui.hbox()
        card.isFocusable = true
        card.isClickable = true
        card.setPadding(ui.u(34), ui.u(20), ui.u(44), ui.u(20))
        val e = ui.emptyState(R.drawable.ic_list, "", "", size = 0)
        val icon = e.getChildAt(0)
        e.removeView(icon)
        card.addView(icon, ui.ll(ui.u(120), ui.u(120)))
        val col = ui.vbox()
        col.addView(ui.text("Listen henüz boş", 26, C.TEXT, 700))
        val sub = ui.text("Beğendiğin filmde “Listeme ekle”ye bas, burada dursun. OK: bu haftanın Top 10'una git.", 19, C.TEXT3, 400)
        sub.setLineSpacing(0f, 1.2f)
        sub.maxWidth = ui.u(560)
        col.addView(sub, ui.ll(ui.wrap, ui.wrap, top = 8))
        card.addView(col, ui.ll(ui.wrap, ui.wrap, left = 18))
        fun style(f: Boolean) {
            card.background = if (f) ui.round(Color.argb(60, 255, 255, 255), D.CARD, 2, Color.WHITE) else ui.round(C.GLASS, D.CARD, 1)
        }
        style(false)
        card.setOnFocusChangeListener { _, f ->
            style(f)
            ui.lift(card, f, 1.04f, 20)
            if (f) {
                shiftTo(levelOf("list"))
                tintTo(0)
            }
        }
        card.setOnClickListener {
            val target = if (topShelf.visibility == View.VISIBLE) topRow else tvRow
            (target.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.requestFocus()
        }
        card.isLongClickable = true
        card.setOnLongClickListener {
            rowMenu("list", card)
            true
        }
        card.layoutParams = ui.ll(ui.wrap, ui.u(169))
        return card
    }

    private fun shelfCardInner(p: Pick): View {
        val col = ui.vbox()
        col.clipChildren = false
        col.clipToPadding = false
        val card = FrameLayout(this)
        card.isFocusable = true
        card.isClickable = true
        card.background = ui.round(C.CARD, D.CARD)
        card.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        card.addView(img, ui.fl(ui.match, ui.match))
        episodeBadge(p)?.let { addBadge(card, it, episode = true) }
        val fx = Fx.decorate(ui, card, D.CARD)
        loadThumb(p.backdrop, img)

        val label = ui.text(p.title, 20, C.TEXT, 500)
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.alpha = 0f

        card.setOnFocusChangeListener { _, f ->
            ui.lift(card, f, 1.1f, 30)
            fx(f)
            label.animate().alpha(if (f) 1f else 0f).translationY(if (f) ui.uf(6) else 0f).setDuration(D.FAST).start()
            if (f) {
                shiftTo(levelOf("list"))
                tintTo(0)
            }
        }
        card.setOnClickListener {
            showMenu(
                p.title,
                listOf(
                    "Öne çıkar" to { closeMenu(false); feature(p) },
                    "Fragmanı izle" to { closeMenu(); trailer(p) },
                    "Listeden çıkar" to {
                        closeMenu(false)
                        prefs.watchlist = prefs.watchlist.filter { it.key != p.key }
                        refreshShelf()
                        updateListBtn()
                        if (shelf.visibility == View.VISIBLE) {
                            val c = shelfRow.getChildAt(0)
                            if (prefs.watchlist.isEmpty()) c?.requestFocus() else (c as? ViewGroup)?.getChildAt(0)?.requestFocus()
                        } else {
                            focusFirst()
                        }
                    }
                ),
                card
            )
        }
        col.addView(card, ui.ll(ui.u(300), ui.u(169)))
        col.addView(label, ui.ll(ui.u(300), ui.wrap, top = 16))
        col.layoutParams = ui.ll(ui.wrap, ui.wrap, right = 30)
        return col
    }

    private fun loadThumb(path: String, into: ImageView) {
        if (path.isEmpty()) return
        thumbs[path]?.let {
            into.setImageBitmap(it)
            return
        }
        val width = ui.u(300)
        into.setImageDrawable(Fx.Shimmer(C.CARD, 0f))
        Img.run(this) {
            val bmp = Net.bitmap("https://image.tmdb.org/t/p/w780$path", width, true)
            runOnUiThread {
                if (bmp != null) {
                    thumbs[path] = bmp
                    Fx.fadeIn(into, bmp)
                }
            }
        }
    }

    /** Show a watchlist film in the hero. */
    private fun feature(p: Pick) {
        var i = picks.indexOfFirst { it.key == p.key }
        if (i < 0) {
            picks = picks + p
            i = picks.size - 1
        }
        pickIndex = i
        showPick()
        shiftTo(0)
        trailerBtn.requestFocus()
    }

    /** Slide the screen up to reveal a row below the apps, like tvOS. 0 = top, n = n-th row under the apps. */
    private fun shiftTo(target: Int) {
        if (target == level) return
        level = target
        // Down in the rows the trailer stops; back on the film it starts again after a while.
        if (target > 0) stopHeroTrailer() else queueHeroTrailer()
        root.animate().translationY(-extraBelow(target).toFloat()).setDuration(450)
            .setInterpolator(DecelerateInterpolator(2f)).start()
        // Down in the rows the top half is the poster's (HomePeek); back up, the film of the day returns.
        hero.animate().alpha(if (target > 0) 0f else 1f).setDuration(D.MED).start()
        if (target <= 0) peek.hide()
        fadeRows()
    }

    /**
     * Only rows that fit on screen are shown: the focused one and the one above it. Rows that
     * would be cut at the top or peek in at the bottom fade out, so nothing looks half drawn.
     */
    private fun fadeRows() {
        val order = prefs.rowOrder
        val visible = order.filter { shelfOf(it).first.visibility == View.VISIBLE }
        // Position of the focused row among the visible ones (0 = apps row).
        val t = visible.count { levelOf(it) <= level }
        for (id in order) {
            val v = shelfOf(id).first
            val o = visible.indexOf(id) + 1
            // Only the row with the remote: the top half above it tells about the poster (HomePeek).
            val on = t > 0 && o == t
            v.animate().alpha(if (on) 1f else 0f).setDuration(D.MED).start()
        }
        scroller.animate().alpha(if (t == 0) 1f else 0f).setDuration(D.MED).start()
    }

    // ---------- Menus and panel ----------

    private fun rowMenu(id: String, owner: View) {
        val name = rowTitle(id) ?: return
        val tidy = ArrayList<Pair<String, () -> Unit>>()
        if (id == "list") {
            // Tidy Listem in one press: what you watched, and what has waited over a month.
            val watched = prefs.watchlist.filter { it.key in prefs.watched }
            val added = prefs.listAdded
            val month = System.currentTimeMillis() - 30 * 86400_000L
            val old = prefs.watchlist.filter { (added[it.key] ?: Long.MAX_VALUE) < month }
            if (prefs.watchlist.isNotEmpty()) tidy.add("Bu ay hangi platform?" to { closeMenu(false); PlatformMonthActivity.open(this) })
            if (watched.isNotEmpty()) tidy.add("İzlediklerimi çıkar (${watched.size})" to { closeMenu(false); dropFromList(watched) })
            if (old.isNotEmpty()) tidy.add("Bir aydan eskileri çıkar (${old.size})" to { closeMenu(false); dropFromList(old) })
        }
        showMenu(
            name,
            tidy + listOf(
                "Satırı taşı" to { closeMenu(false); startRowMove(id, owner) },
                "Satırı gizle" to {
                    closeMenu(false)
                    prefs.toggleRow(id)
                    showTopRows()
                    focusFirst()
                    Glass.toast(this, "“$name” gizlendi. Ayarlar › Ana ekran satırları'ndan geri açabilirsin.", long = true)
                }
            ),
            owner
        )
    }

    private fun dropFromList(films: List<Pick>) {
        val keys = films.map { it.key }.toSet()
        prefs.watchlist = prefs.watchlist.filter { it.key !in keys }
        refreshShelf()
        updateListBtn()
        Glass.toast(this, "${films.size} yapım Listem'den çıkarıldı")
        val c = shelfRow.getChildAt(0)
        if (shelf.visibility == View.VISIBLE && c != null) {
            if (prefs.watchlist.isEmpty()) c.requestFocus() else (c as? ViewGroup)?.getChildAt(0)?.requestFocus()
        } else focusFirst()
    }

    private fun startRowMove(id: String, owner: View) {
        movingRow = id
        rowOwner = owner
        owner.requestFocus()
        val v = shelfOf(id).first
        v.background = ui.round(Color.argb(28, 255, 255, 255), 28, 2, Color.argb(150, 255, 255, 255))
        hint.text = "▲ ▼  ile satırı taşı     ·     OK ile bitir"
        hint.visibility = View.VISIBLE
        hint.alpha = 0f
        hint.animate().alpha(1f).setDuration(D.FAST).start()
    }

    /** Moves the row past the next visible row above or below it. */
    private fun moveRow(d: Int) {
        val id = movingRow ?: return
        val order = prefs.rowOrder
        var j = order.indexOf(id) + d
        while (j in order.indices && shelfOf(order[j]).first.visibility != View.VISIBLE) j += d
        if (j !in order.indices) return
        prefs.moveRow(id, j - order.indexOf(id))
        arrangeRows()
        level = -1
        shiftTo(levelOf(id))
        rowOwner?.requestFocus()
    }

    private fun endRowMove() {
        val id = movingRow ?: return
        movingRow = null
        shelfOf(id).first.background = null
        rowOwner?.requestFocus()
        rowOwner = null
        hint.animate().alpha(0f).setDuration(D.FAST).withEndAction { hint.visibility = View.GONE }.start()
    }

    private fun appMenu(app: AppEntry, col: View) {
        showMenu(
            app.label,
            listOf(
                "Taşı" to { closeMenu(false); startMove(col) },
                "Ana ekrandan çıkar" to {
                    closeMenu(false)
                    prefs.toggleFavorite(app.key)
                    refreshDock(force = true)
                    Glass.toast(this, "Çıkarıldı  ·  “Tüm uygulamalar”dan geri eklenir", long = true)
                },
                "Uygulama bilgisi" to {
                    closeMenu()
                    HomeButtonService.pause()
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                },
                "Kaldır" to {
                    closeMenu()
                    Dialogs.pin(this, {
                        HomeButtonService.pause()
                        open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                    })
                }
            ),
            (col as ViewGroup).getChildAt(0)
        )
    }

    private fun showMenu(title: String, items: List<Pair<String, () -> Unit>>, owner: View) {
        if (menu != null || moving != null) return
        val layer = FrameLayout(this)
        layer.setBackgroundColor(Color.argb(170, 0, 0, 0))
        layer.isClickable = true

        val card = ui.vbox()
        card.background = ui.glass(Color.argb(ui.panelAlpha() - 23, 0x1E, 0x1E, 0x22), 36, 2)
        card.setPadding(ui.u(28), ui.u(32), ui.u(28), ui.u(24))
        card.elevation = ui.uf(40)
        card.clipChildren = false
        val name = ui.text(title, 26, C.TEXT2, 600)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        card.addView(name, ui.ll(ui.match, ui.wrap, left = 28, bottom = 18))
        var first: View? = null
        for ((label, action) in items) {
            val r = menuRow(label)
            r.setOnClickListener { action() }
            card.addView(r, ui.ll(ui.match, ui.u(74), bottom = 4))
            if (first == null) first = r
        }
        layer.addView(card, ui.fl(ui.u(560), ui.wrap, Gravity.CENTER))
        showOverlay(layer, first, owner)
        card.scaleX = 0.94f
        card.scaleY = 0.94f
        card.animate().scaleX(1f).scaleY(1f).setDuration(D.MED).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    /** Quick settings, sliding in from the right like Control Center. */
    private fun openPanel() {
        if (menu != null) return
        val layer = FrameLayout(this)
        layer.setBackgroundColor(Color.argb(ui.dimAlpha(), 0, 0, 0))
        layer.isClickable = true

        val panel = ui.vbox()
        val bg = GradientDrawable()
        bg.setColor(Color.argb(ui.panelAlpha(), 0x1B, 0x1B, 0x20))
        val r = ui.uf(36)
        bg.cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        panel.background = bg
        panel.foreground = LiquidGlass(0, 0f, maxOf(1f, ui.uf(2)), floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r))
        panel.elevation = ui.uf(40)
        panel.clipChildren = false
        panel.setPadding(ui.u(44), ui.u(70), ui.u(40), ui.u(40))

        val clock = newClock()
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.uf(66))
        clock.setTextColor(C.TEXT)
        clock.typeface = ui.font(500)
        panel.addView(clock, ui.ll(ui.wrap, ui.wrap, left = 26))
        val date = ui.text(dateText.text, 17, C.TEXT3, 600)
        date.letterSpacing = 0.18f
        panel.addView(date, ui.ll(ui.wrap, ui.wrap, left = 28, top = 4, bottom = 40))

        val items = listOf(
            "Wi-Fi ve internet" to SystemSettings.Screen.NETWORK,
            "Bluetooth ve kumandalar" to SystemSettings.Screen.BLUETOOTH,
            "Ekran ve ses" to SystemSettings.Screen.DISPLAY_SOUND,
            "Tüm Android ayarları" to SystemSettings.Screen.ALL
        )
        var first: View? = null
        for ((label, screen) in items) {
            val item = menuRow(label)
            item.setOnClickListener {
                closeMenu()
                SystemSettings.open(this, screen)
            }
            panel.addView(item, ui.ll(ui.match, ui.u(74), bottom = 4))
            if (first == null) first = item
        }
        val div = View(this)
        div.setBackgroundColor(C.STROKE)
        panel.addView(div, ui.ll(ui.match, maxOf(1, ui.u(1)), left = 26, right = 26, top = 16, bottom = 16))
        val own = menuRow("Launcher ayarları")
        own.setOnClickListener {
            closeMenu()
            idleOff()
            Fx.backdrop = Fx.snapshotBlur(frame, scale = 28, passes = 4)
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        panel.addView(own, ui.ll(ui.match, ui.u(74)))

        layer.addView(panel, ui.fl(ui.u(560), ui.match, Gravity.END))
        showOverlay(layer, first, gear)
        panel.translationX = ui.uf(560)
        panel.animate().translationX(0f).setDuration(D.MED).setInterpolator(DecelerateInterpolator(2.2f)).start()
    }

    private fun showOverlay(layer: FrameLayout, first: View?, owner: View) {
        // Frosted glass: a blurred copy of the screen behind the panel or menu.
        Fx.snapshotBlur(frame)?.let {
            layer.background = android.graphics.drawable.LayerDrawable(
                arrayOf(
                    android.graphics.drawable.BitmapDrawable(resources, it),
                    android.graphics.drawable.ColorDrawable(Color.argb(ui.dimAlpha(), 0, 0, 0))
                )
            )
        }
        frame.addView(layer, ui.fl(ui.match, ui.match))
        menu = layer
        menuOwner = owner
        first?.requestFocus()
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        layer.alpha = 0f
        layer.animate().alpha(1f).setDuration(D.FAST).start()
    }

    private fun menuRow(label: String): TextView {
        val t = ui.text(label, 25, C.TEXT, 500)
        t.gravity = Gravity.CENTER_VERTICAL
        t.setPadding(ui.u(26), 0, ui.u(26), 0)
        t.isFocusable = true
        t.isClickable = true
        t.setOnFocusChangeListener { _, f ->
            t.background = if (f) ui.round(Color.WHITE, D.PLATE) else null
            t.setTextColor(if (f) C.INK else C.TEXT)
            ui.lift(t, f, 1.03f, 10)
        }
        return t
    }

    private fun closeMenu(refocus: Boolean = true) {
        val layer = menu ?: return
        menu = null
        sidePanel = null
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        frame.removeView(layer)
        if (refocus) menuOwner?.requestFocus()
        menuOwner = null
    }

    // ---------- Move mode ----------

    private fun currentKeys(): List<String> =
        (0 until row.childCount).mapNotNull { (row.getChildAt(it).tag as? String)?.takeIf { k -> k != ALL_APPS } }

    private fun startMove(col: View) {
        val card = (col as ViewGroup).getChildAt(0)
        moving = col
        card.requestFocus()
        val a = ObjectAnimator.ofFloat(card, View.ROTATION, -1.2f, 1.2f)
        a.duration = 140
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        a.start()
        wiggle = a
        hint.text = "◀ ▶  ile taşı     ·     OK ile bitir"
        hint.visibility = View.VISIBLE
        hint.alpha = 0f
        hint.animate().alpha(1f).setDuration(D.FAST).start()
    }

    private fun moveBy(col: View, d: Int) {
        val i = row.indexOfChild(col)
        val n = i + d
        // The "Tüm uygulamalar" card stays last.
        val last = if (row.getChildAt(row.childCount - 1)?.tag == ALL_APPS) row.childCount - 2 else row.childCount - 1
        if (i < 0 || n < 0 || n > last) return
        row.removeViewAt(i)
        row.addView(col, n)
        (col as ViewGroup).getChildAt(0).requestFocus()
    }

    private fun endMove() {
        val col = moving ?: return
        moving = null
        wiggle?.cancel()
        wiggle = null
        (col as ViewGroup).getChildAt(0).rotation = 0f
        val keys = currentKeys()
        prefs.order = keys + prefs.order.filter { it !in keys }
        dockSig = keys.joinToString("|") + "#" + prefs.cardSize
        hint.animate().alpha(0f).setDuration(D.FAST).withEndAction { hint.visibility = View.GONE }.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            Fx.lastDir = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -2
                KeyEvent.KEYCODE_DPAD_DOWN -> 2
                else -> Fx.lastDir
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) Sleep.onKey()
        // The remote's coloured keys open what Ayarlar › Renkli tuşlar says.
        colorAction(event.keyCode)?.let { act ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && updateWall == null) {
                if (idle) exitIdle()
                if (::heroStage.isInitialized) heroStage.wake()
                closeMenu(false)
                runColorAction(act)
            }
            return true
        }
        // The trailer had the whole screen: the first key brings the home screen back. OK opens
        // the film, Back only wakes; the arrows go on and move as usual.
        if (::heroStage.isInitialized && event.action == KeyEvent.ACTION_DOWN) {
            val wasOn = heroStage.on
            // The screensaver's trailer: OK opens that film (the screensaver ends under it).
            val film = picks.getOrNull(pickIndex)
            heroStage.wake()
            if (wasOn) when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (idle) exitIdle()
                    film?.let { openDetail(it, null, null) }
                    swallowUp = true
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    swallowUp = true
                    return true
                }
            }
        }
        // The remote rests again: with the trailer still playing, it takes the stage once more.
        if (::heroStage.isInitialized && event.action == KeyEvent.ACTION_UP && ::heroTrailer.isInitialized) {
            heroStage.rest(heroTrailer.active && level == 0 && menu == null)
        }
        if (event.action == KeyEvent.ACTION_UP && ::topBarView.isInitialized) {
            // The bar goes quiet while the remote is elsewhere; it comes back on it, or after a pause.
            handler.post { quietBar(currentFocus?.let { isInBar(it) } != true) }
            handler.removeCallbacks(barWake)
            handler.postDelayed(barWake, BAR_WAKE_MS)
        }
        if (swallowUp && event.action == KeyEvent.ACTION_UP) {
            swallowUp = false
            return true
        }
        if (morning != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                hideMorning()
                swallowUp = true
            }
            return true
        }
        if (idle) {
            // The first key only wakes the screen.
            if (event.action == KeyEvent.ACTION_DOWN) {
                exitIdle()
                swallowUp = true
            }
            return true
        }
        resetIdle()
        if (event.action == KeyEvent.ACTION_DOWN && movingRow == null && moving == null) {
            // Right closes the side panel only from its right column (in the grid it moves across).
            if (sidePanel != null && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                val here = currentFocus
                val panelRoot = sidePanel as? ViewGroup
                val across = if (here != null && panelRoot != null)
                    android.view.FocusFinder.getInstance().findNextFocus(panelRoot, here, View.FOCUS_RIGHT) else null
                if (across == null) {
                    closeMenu()
                    return true
                }
            }
            val f = currentFocus
            if (menu == null && updateWall == null && appsSide() && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                f != null && atLeftEdge(f)
            ) {
                openSideApps(f)
                return true
            }
        }
        if (movingRow != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> moveRow(-1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(1)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> endRowMove()
                }
            }
            return true
        }
        val col = moving ?: run {
            Fx.edgeNudge(this, event, ui)
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(col, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(col, 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> endMove()
            }
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (updateWall != null) return
        when {
            menu != null -> closeMenu()
            moving != null -> endMove()
            else -> focusFirst()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (idle) exitIdle()
        closeMenu(false)
        endMove()
        focusFirst()
    }

    // ---------- Screensaver ----------

    /** While our film screensaver may run, stop the TV's own screensaver from replacing it. */
    private fun holdScreen() {
        handler.removeCallbacks(holdRelease)
        if (prefs.saverMin > 0 && prefs.saverHoldMin != 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun resetIdle() {
        handler.removeCallbacks(idleTimer)
        if (prefs.saverMin > 0) handler.postDelayed(idleTimer, prefs.saverMin * 60000L)
    }

    private fun idleOff() = handler.removeCallbacks(idleTimer)

    private fun enterIdle(force: Boolean = false) {
        if (idle || updateWall != null) return
        if (menu != null || moving != null || (!force && !hasWindowFocus()) || picks.isEmpty() || !prefs.showPicks) {
            resetIdle()
            return
        }
        stopHeroTrailer()
        if (::peek.isInitialized) peek.pause()
        idle = true
        styleAmbient()
        // Screensaver plays films in cinemas and coming soon, one of each in turn; it starts on the
        // film that was on the screen, so the picture does not jump to another one.
        val onScreen = picks.getOrNull(pickIndex)?.takeIf { it.backdrop.isNotEmpty() }
        val list = (listOfNotNull(onScreen) + saverFilms()).distinctBy { it.key }
        if (list.size > 1) {
            savedPicks = picks
            savedIndex = pickIndex
            picks = list
            pickIndex = 0
            showPick()
        }
        content.animate().alpha(0f).setDuration(1400).start()
        for (s in scrims) s.animate().alpha(0.35f).setDuration(1400).start()
        tintTo(0)
        picks.getOrNull(pickIndex)?.let { setAmbient(it) }
        ambient.visibility = View.VISIBLE
        ambient.animate().alpha(1f).setStartDelay(600).setDuration(1200).start()
        if (prefs.saverHoldMin > 0) handler.postDelayed(holdRelease, prefs.saverHoldMin * 60000L)
        schedule()
        Housekeeping.prefetch(this)
        // "Sessiz fragman maratonu": the films' trailers, silent, one after another, for half an hour.
        saverTrailersUntil = if (prefs.saverStyle == "trailers" && !Fx.lite) System.currentTimeMillis() + SAVER_TRAILERS_MS else 0L
        handler.removeCallbacks(saverTrailer)
        if (saverTrailersOn()) handler.postDelayed(saverTrailer, 2_500)
    }

    private fun saverTrailersOn() = idle && System.currentTimeMillis() < saverTrailersUntil

    /** The film on the screensaver plays its trailer: silent, 720p (light for the TV), full screen. */
    private fun startSaverTrailer() {
        if (!saverTrailersOn() || !::heroTrailer.isInitialized) return
        val p = picks.getOrNull(pickIndex) ?: return
        if (p.homeTrailer.isBlank()) return
        handler.removeCallbacks(rotate)
        progressAnim?.cancel()
        saverShown = false
        heroTrailer.sound = false
        heroTrailer.height = 720
        heroTrailer.start(p.homeTrailer)
    }

    /** The screensaver's words and clock make way for the trailer, and come back after it. */
    private fun saverTrailerShowing(on: Boolean) {
        if (on) saverShown = true
        ambient.animate().cancel()
        ambient.animate().alpha(if (on) 0f else 1f).setStartDelay(0).setDuration(if (on) 900L else 600L).start()
        // The full-screen trailer's way out brings the home screen back: not while the screensaver runs.
        if (!on) {
            content.animate().cancel()
            content.animate().alpha(0f).setDuration(300).start()
            for (s in scrims) s.animate().alpha(0.35f).setDuration(600).start()
        }
    }

    /** Screensaver clock size and weather, as chosen in the settings. */
    private fun styleAmbient() {
        ambientClock.visibility = if (prefs.saverClock == 0) View.GONE else View.VISIBLE
        val big = prefs.saverClock == 2
        ambientClock.scaleX = if (big) 1.6f else 1f
        ambientClock.scaleY = ambientClock.scaleX
        val w = prefs.saverWeather && prefs.weather && tempView.text.isNotEmpty()
        ambientWeather.visibility = if (w) View.VISIBLE else View.GONE
        if (w) {
            ambientTemp.text = tempView.text
            if (Fx.weatherIcon != 0) ambientWeatherIcon.setImageResource(Fx.weatherIcon)
        }
        // A big clock needs room so the weather moves left of it.
        (ambientWeather.layoutParams as LinearLayout.LayoutParams).rightMargin = ui.u(if (big) 110 else 40)
    }

    private fun exitIdle() {
        idle = false
        handler.removeCallbacks(saverTrailer)
        saverTrailersUntil = 0L
        if (::heroTrailer.isInitialized) {
            heroTrailer.stop()
            heroTrailer.height = if (prefs.heroTrailerHd && !Fx.lite) 1080 else 720
        }
        Housekeeping.stopPrefetch()
        holdScreen()
        savedPicks?.let {
            picks = it
            pickIndex = savedIndex.coerceIn(0, maxOf(0, it.size - 1))
            savedPicks = null
            showPick()
        }
        for (s in scrims) s.animate().alpha(1f).setDuration(500).start()
        boost.animate().alpha(boostLevel).setDuration(500).start()
        if (!wakeUp()) {
            content.animate().alpha(1f).setDuration(500).start()
            ambient.animate().alpha(0f).setStartDelay(0).setDuration(D.MED)
                .withEndAction { if (!idle) ambient.visibility = View.GONE }.start()
        }
        updateGreeting()
        sayHello()
        resetIdle()
    }

    /**
     * Waking from the screensaver: the large clock shrinks and flies into its place in the top bar,
     * the film's words fade, and the home screen rises in under it. False when there is no clock
     * to fly (hidden, light mode), so the plain fade is used.
     */
    private fun wakeUp(): Boolean {
        val to = barClock ?: return false
        val from = ambientClock
        if (Fx.lite || from.visibility != View.VISIBLE || from.width == 0 || to.width == 0 || ambient.alpha < 0.5f) return false
        fun center(v: View): FloatArray {
            val at = IntArray(2)
            (v.parent as View).getLocationInWindow(at)
            return floatArrayOf(at[0] + v.left + v.width / 2f, at[1] + v.top + v.height / 2f)
        }
        val a = center(from)
        val b = center(to)
        from.animate().cancel()
        from.pivotX = from.width / 2f
        from.pivotY = from.height / 2f
        val endScale = to.width.toFloat() / from.width
        // Everything but the clock goes first.
        for (i in 0 until ambient.childCount) {
            val c = ambient.getChildAt(i)
            if (c === from.parent) {
                for (j in 0 until (c as ViewGroup).childCount) c.getChildAt(j).let { if (it !== from) it.animate().alpha(0f).setDuration(200).start() }
            } else c.animate().alpha(0f).setDuration(220).start()
        }
        to.alpha = 0f
        content.alpha = 0f
        content.translationY = ui.uf(26)
        content.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(560)
            .setInterpolator(DecelerateInterpolator(2f)).start()
        from.animate().translationX(b[0] - a[0]).translationY(b[1] - a[1]).scaleX(endScale).scaleY(endScale)
            .setStartDelay(0).setDuration(560).setInterpolator(DecelerateInterpolator(2.2f))
            .withEndAction {
                to.alpha = 1f
                ambient.alpha = 0f
                if (!idle) ambient.visibility = View.GONE
                // Back to where the screensaver wants it next time.
                from.translationX = 0f
                from.translationY = 0f
                from.pivotX = from.width.toFloat()
                from.pivotY = from.height.toFloat()
                for (i in 0 until ambient.childCount) {
                    val c = ambient.getChildAt(i)
                    c.alpha = 1f
                    if (c is ViewGroup) for (j in 0 until c.childCount) c.getChildAt(j).alpha = 1f
                }
                styleAmbient()
            }.start()
        return true
    }

    // ---------- Picks ----------

    private fun startPicks() {
        if (loadingPicks) return
        loadingPicks = true
        lastPicks = System.currentTimeMillis()
        val logoWidth = ui.u(700)
        Thread {
            // Show the last saved list at once, then refresh it from the internet.
            val saved = java.io.File(filesDir, "pick.json")
            val old = try { if (saved.exists()) saved.readText() else null } catch (e: Exception) { null }
            // The owner's hidden films and featured film apply to the saved list too.
            Remote.loadSaved(this)
            val sigBefore = Remote.filmSig()
            if (old != null && allPicks.isEmpty()) usePicks(old, logoWidth, last = false)
            // The owner's settings for every TV (message, lowest version, features off, films).
            Remote.refresh(this)
            runOnUiThread { applyRemote() }
            val t = "?t=" + System.currentTimeMillis()
            val fresh = Net.fromHosts("/pick.json" + t)
            if (fresh != null && fresh != old) {
                try {
                    saved.writeText(fresh)
                } catch (e: Exception) {
                }
                usePicks(fresh, logoWidth, last = true)
            } else if (old != null && Remote.filmSig() != sigBefore) {
                usePicks(old, logoWidth, last = true)
            } else {
                runOnUiThread { loadingPicks = false }
            }
        }.start()
    }

    /** Reads a pick.json body (on a background thread) and shows it. */
    private fun usePicks(body: String?, logoWidth: Int, last: Boolean) {
        run {
            val list = ArrayList<Pick>()
            var date = ""
            try {
                if (body != null) {
                    val o = JSONObject(body)
                    date = o.optString("date")
                    val arr = o.getJSONArray("picks")
                    for (i in 0 until arr.length()) {
                        val x = arr.getJSONObject(i)
                        if (x.optString("type", "movie") != "tv") Pick.from(x).takeIf { !Remote.isHidden(it) }?.let { list.add(it) }
                    }
                }
            } catch (e: Exception) {
            }
            val top = ArrayList<Pick>()
            val topTv = ArrayList<Pick>()
            val nowList = ArrayList<Pick>()
            val soonList = ArrayList<Pick>()
            val genres = ArrayList<Pair<String, List<Pick>>>()
            val plat = ArrayList<Pick>()
            val localTitles = ArrayList<Pick>()
            val past = ArrayList<Pick>()
            try {
                val o = JSONObject(body ?: "{}")
                for ((k, dst) in listOf("cinema" to nowList, "soon" to soonList)) {
                    val a = o.optJSONArray(k)
                    for (i in 0 until (a?.length() ?: 0)) Pick.from(a!!.getJSONObject(i)).takeIf { !Remote.isHidden(it) }?.let { dst.add(it) }
                }
                Episodes.load(o)
                val ha = o.optJSONArray("history")
                for (i in 0 until (ha?.length() ?: 0)) Pick.from(ha!!.getJSONObject(i)).takeIf { !Remote.isHidden(it) }?.let { past.add(it) }
                val ga = o.optJSONArray("genres")
                for (i in 0 until (ga?.length() ?: 0)) {
                    val g = ga!!.getJSONObject(i)
                    val fa = g.optJSONArray("films") ?: continue
                    val films = (0 until fa.length()).map { Pick.from(fa.getJSONObject(it)) }.filter { it.poster.isNotEmpty() && !Remote.isHidden(it) }
                    if (films.isNotEmpty()) genres.add(g.optString("name") to films)
                }
                val tv = o.optJSONArray("top10tv")
                for (i in 0 until (tv?.length() ?: 0)) Pick.from(tv!!.getJSONObject(i)).takeIf { !Remote.isHidden(it) }?.let { topTv.add(it) }
                val arr = o.optJSONArray("top10")
                for (i in 0 until (arr?.length() ?: 0)) Pick.from(arr!!.getJSONObject(i)).takeIf { !Remote.isHidden(it) }?.let { top.add(it) }
            } catch (e: Exception) {
            }
            // Title logos are small; fetch them first so the hero doesn't jump.
            try {
                val pa = JSONObject(body ?: "{}").optJSONArray("platforms")
                for (i in 0 until (pa?.length() ?: 0)) Pick.from(pa!!.getJSONObject(i)).takeIf { !Remote.isHidden(it) && it.backdrop.isNotEmpty() }?.let { plat.add(it) }
            } catch (e: Exception) {
            }
            try {
                val la = JSONObject(body ?: "{}").optJSONArray("local")
                for (i in 0 until (la?.length() ?: 0)) Pick.from(la!!.getJSONObject(i)).takeIf { !Remote.isHidden(it) && it.poster.isNotEmpty() }?.let { localTitles.add(it) }
            } catch (e: Exception) {
            }
            val got = HashMap<String, Bitmap>()
            // Title logos for the films the hero will show (today's films, and the others when chosen).
            val heroFilms = (list + (if ("platform" in prefs.heroKinds) plat.take(24) else emptyList()) + (if ("cinema" in prefs.heroKinds) nowList else emptyList())) + topTv
            for (p in heroFilms) {
                if (p.logo.isEmpty() || logos.containsKey(p.logo)) continue
                Net.bitmap("https://image.tmdb.org/t/p/w500" + p.logo, logoWidth, false)
                    ?.takeIf { !Img.isDark(it) }
                    ?.let { got[p.logo] = it }
            }
            val news = newsFor(plat, date)
            // Which platforms (by name) have their app on this TV: "SENDE VAR" on posters.
            val names = (list + plat + nowList + localTitles).flatMap { it.where + listOfNotNull(it.provider.takeIf { n -> n.isNotEmpty() }) }.toSet()
            val have = names.filter { Apps.forPlatform(this, it) != null }.toSet()
            runOnUiThread {
                if (last) loadingPicks = false
                logos.putAll(got)
                // Before the rows are drawn: they use these lists too.
                if (plat.isNotEmpty()) {
                    platformList = plat
                    appNews = news
                    refreshDock()
                }
                if (localTitles.isNotEmpty()) localList = localTitles
                refreshPlatforms()
                mine = have
                if (top.isNotEmpty() || topTv.isNotEmpty() || nowList.isNotEmpty() || soonList.isNotEmpty()) {
                    top10 = top
                    top10tv = topTv
                    cinema = nowList
                    soon = soonList
                    refreshTop()
                }
                if (past.isNotEmpty()) history = past

                updateBell()
                if (genres.isNotEmpty()) {
                    genrePools = genres
                    refreshGenreRows()
                }
                refreshShelf()
                refreshReminder()
                if (list.isNotEmpty()) {
                    hideSkeleton()
                    if (date.isNotEmpty()) prefs.pickDate = date
                    allPicks = list
                    genreSig = "-"
                    applyGenres()
                    refreshPlatforms()
                }
            }
        }
    }

    /**
     * Today's films for the hero: the chosen genres (all if too few match), without watched
     * films, liked genres moved forward, and the weather-matched film second.
     */
    private fun applyGenres() {
        val g = prefs.genres
        val sig = g.sorted().joinToString(",") + "#" + markSig + "#" + Remote.filmSig() + "#" + moodKey + "#" +
            prefs.heroSource + platformList.size + "/" + cinema.size + "/" + prefs.platforms.sorted().joinToString(",") + "#" + shortEvening()
        if (sig == genreSig || allPicks.isEmpty()) return
        genreSig = sig
        val base = heroPool()
        val unseen = base.filter { it.key !in skipNow }.ifEmpty { base }
        val match = if (g.isEmpty()) unseen else unseen.filter { p -> p.genres.any { it in g } }
        val liked = prefs.likedGenres().take(3)
        val down = prefs.dislikedGenres()
        var next = (if (match.size >= 3) match else unseen)
            .sortedBy { p -> if (p.genres.any { it in liked }) 0 else if (p.genres.any { it in down }) 2 else 1 }
        // The weather film belongs to today's films; cinema-only and streaming-only stay as chosen.
        if ("picks" in prefs.heroKinds) {
            moodPick()?.let { m -> next = (next.take(1) + m + next.drop(1)).distinctBy { it.key } }
        }
        // Weeknights after nine: films that end early come first (no setting needed).
        if (shortEvening()) next = next.sortedBy { if (!it.isSeries && it.runtime in 1..SHORT_MIN) 0 else 1 }
        pinPicks().takeIf { it.isNotEmpty() }?.let { fs ->
            val keys = fs.map { it.key }.toSet()
            next = fs + next.filter { it.key !in keys }
        }
        // What was left half way comes first, with "Devam et".
        val left = leftHalfWay()
        resumeKey = left?.key.orEmpty()
        if (left != null) next = listOf(left) + next.filter { it.key != left.key }
        if (savedPicks != null) {
            // The screensaver is showing cinema films; swap in after it ends.
            savedPicks = next
            savedIndex = if (pinPick() != null || next.isEmpty()) 0 else heroStart % next.size
            return
        }
        picks = next
        // Each start of the TV begins on another film; a featured film (admin page) or the one left
        // half way always comes first.
        pickIndex = if (pinPick() != null || left != null || next.isEmpty()) 0 else heroStart % next.size
        showPick()
    }

    /** The film or series last left half way (İzlediklerim), if it was played in the last two weeks. */
    private fun leftHalfWay(): Pick? = WatchHistory(this).entries().firstOrNull { e ->
        System.currentTimeMillis() - e.playedAt < 14L * 24 * 60 * 60 * 1000 &&
            WatchMarks.of(playback, e.pick)?.let { it.fraction != null || (e.pick.isSeries && it.episode != null) } == true &&
            !Remote.isHidden(e.pick) && e.pick.backdrop.isNotEmpty()
    }?.pick

    /** "Devam et · 42 dk kaldı", "Devam et · S2 · B5", "Sıradaki · S2 · B6". */
    private fun resumeLabel(p: Pick): String {
        val state = WatchMarks.of(playback, p) ?: return "▶   Oynat"
        val last = playback.last(p.key)
        val pos = playback.resumePositionMs(last)
        val dur = playback.durationMs(last)
        val left = if (pos > 0 && dur > pos) ((dur - pos) / 60_000L).coerceAtLeast(1) else 0L
        val time = when {
            left >= 60 -> "${left / 60} sa ${left % 60} dk kaldı"
            left > 0 -> "$left dk kaldı"
            else -> ""
        }
        return when {
            state.episode != null && state.fraction == null -> "▶   Sıradaki  ·  ${state.episode}"
            state.episode != null -> "▶   Devam et  ·  ${state.episode}" + if (time.isNotEmpty()) "  ·  $time" else ""
            time.isNotEmpty() -> "▶   Devam et  ·  $time"
            else -> "▶   Devam et"
        }
    }

    /** The source page, on the film (it opens where it stopped) or the series' episode to go on with. */
    private fun resume(p: Pick) {
        val last = playback.last(p.key)
        val m = Regex(":(\\d+):(\\d+)$").find(last)
        val episode = if (p.isSeries && m != null) {
            val season = m.groupValues[1].toInt()
            val number = m.groupValues[2].toInt()
            if (playback.isFinished(last) && playback.resumePositionMs(last) <= 0) last.substring(0, m.range.first) + ":$season:${number + 1}"
            else last
        } else ""
        MediaSourcePickerActivity.open(this, p, resumeEpisode = episode)
    }

    /**
     * Sunday to Thursday from 21:00 until morning: there is work or school tomorrow, so films that
     * end within about an hour and three quarters come first. Friday and Saturday nights are free.
     */
    private fun shortEvening(): Boolean {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val d = c.get(Calendar.DAY_OF_WEEK)
        return when {
            h >= 21 -> d != Calendar.FRIDAY && d != Calendar.SATURDAY
            h < 3 -> d != Calendar.SATURDAY && d != Calendar.SUNDAY
            else -> false
        }
    }

    /** "bir buçuk saatte", "1 saat 40 dakikada". */
    private fun shortTime(min: Int): String = when {
        min in 85..95 -> "bir buçuk saatte"
        min < 60 -> "$min dakikada"
        min % 60 == 0 -> "${min / 60} saatte"
        else -> "${min / 60} saat ${min % 60} dakikada"
    }

    /**
     * The films and series behind the home screen, as chosen in Ayarlar › Görünüm › Ana ekrandaki
     * filmler. Series of the week take turns with the films, and well-liked films of the genre rows
     * fill in, in another order each day, so the same few do not keep coming back.
     */
    private fun heroPool(): List<Pick> {
        val now = cinema.filter { it.backdrop.isNotEmpty() }
        val stream = streamingFirst()
        // Today's films that are out (an old list may still hold ones coming later).
        val films = allPicks.filter { (daysUntil(it.release) ?: 0) <= 0 }.ifEmpty { allPicks }
        val series = top10tv.filter { it.backdrop.isNotEmpty() && !Remote.isHidden(it) }
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        fun daily(l: List<Pick>): List<Pick> = if (l.size < 2) l else (day % l.size).let { k -> l.drop(k) + l.take(k) }
        val more = genrePools.flatMap { it.second }
            .filter { it.backdrop.isNotEmpty() && !it.isSeries && it.imdb >= 7.0 && (daysUntil(it.release) ?: 0) <= 0 }
            .distinctBy { it.key }
            .shuffled(java.util.Random(day.toLong()))
        fun turns(vararg lists: List<Pick>, max: Int): List<Pick> {
            val out = ArrayList<Pick>()
            for (i in 0 until lists.maxOf { it.size }) for (l in lists) l.getOrNull(i)?.let { out.add(it) }
            return out.distinctBy { it.key }.take(max)
        }
        // One of each chosen kind in turn: today's film, a series, one in cinemas, one new on streaming, one more.
        val kinds = prefs.heroKinds
        val lists = ArrayList<List<Pick>>()
        if ("picks" in kinds) lists.add(daily(films))
        if ("picks" in kinds || "platform" in kinds) lists.add(daily(series))
        if ("cinema" in kinds) lists.add(now)
        if ("platform" in kinds) lists.add(stream)
        if ("picks" in kinds) lists.add(more)
        return turns(*lists.toTypedArray(), max = 48).ifEmpty { films }
    }

    /** "IMDb 7.2  ·  2024" under a poster. */
    private fun scoreLine(p: Pick): String {
        val bits = ArrayList<String>()
        if (p.imdb > 0) bits.add(String.format(tr, "IMDb %.1f", p.imdb))
        if (p.year.isNotEmpty()) bits.add(p.year)
        return bits.joinToString("  ·  ")
    }

    /** Films like the liked ones (their own "similar" lists), not watched, turned down or liked already. */
    private fun refreshLike() {
        val liked = prefs.liked.keys
        val pool = (allPicks + top10 + top10tv + cinema + genrePools.flatMap { it.second } + platformList + localList + history)
        val seen = HashSet<String>()
        val out = ArrayList<Pick>()
        for (p in pool) {
            if (p.key !in liked || !seen.add(p.key)) continue
            for (s in p.similar) if (s.poster.isNotEmpty() && s.key !in skipNow && s.key !in liked) out.add(s)
        }
        likeList = out.distinctBy { it.key }.take(14)
        likeRow.removeAllViews()
        for (p in likeList) likeRow.addView(posterCard(p, "like", "", scoreLine(p)))
    }

    /** A platform the user picked in Platformlarım, or (none picked) one whose app is on this TV. */
    private fun myPlatform(name: String): Boolean {
        val mine = prefs.platforms
        return if (mine.isNotEmpty()) name in mine else Apps.forPlatform(this, name) != null
    }

    /** New-on-streaming titles, those of platforms whose app is on this TV first. */
    private fun streamingFirst(): List<Pick> {
        if (platformList.isEmpty()) return emptyList()
        // Only the platforms chosen in Ayarlar › Platformlar, if any are chosen (and still in the list).
        val mine = prefs.platforms
        val chosen = if (mine.isEmpty()) platformList else platformList.filter { it.provider in mine }.ifEmpty { platformList }
        fun words(s: String) = s.lowercase(tr).split(' ', '+', '-', '.').filter { it.length >= 3 && it !in setOf("video", "plus", "the", "app") }
        val labels = Apps.all(this).map { it.label.lowercase(tr) }
        val have = chosen.map { it.provider }.distinct().filter { name -> words(name).any { w -> labels.any { it.contains(w) } } }.toSet()
        return chosen.sortedBy { if (it.provider in have) 0 else 1 }
    }

    /**
     * When the weather (or the time of day) calls for a different film, slip it in right after
     * the current one, so the screen doesn't jump to it.
     */
    private fun useMood() {
        val m = moodPick() ?: return
        val key = m.key + "|" + m.reason
        if (key == moodKey) return
        moodKey = key
        // Title artwork for the hero, ready before the film comes up.
        if (m.logo.isNotEmpty() && !logos.containsKey(m.logo)) {
            val w = ui.u(700)
            Thread {
                val bmp = Net.bitmap("https://image.tmdb.org/t/p/w500" + m.logo, w, false)?.takeIf { !Img.isDark(it) }
                if (bmp != null) runOnUiThread { logos[m.logo] = bmp }
            }.start()
        }
        genreSig = genreSig.substringBeforeLast("#") + "#" + moodKey
        if (picks.isEmpty() || savedPicks != null) return
        val rest = picks.filter { it.key != m.key }
        val at = (rest.indexOfFirst { it.key == picks.getOrNull(pickIndex)?.key } + 1).coerceIn(0, rest.size)
        picks = rest.take(at) + m + rest.drop(at)
        pickIndex = (at - 1).coerceAtLeast(0)
        // Only the counter changes; the bar keeps filling toward the next film.
        (progress.getChildAt(1) as? TextView)?.text = "${pickIndex + 1} / ${picks.size}"
    }

    /** The weather-matched film, with its reason line; null until the weather is known. */
    private fun moodPick(): Pick? {
        if (weatherCode < 0 || !prefs.weather || Remote.isOff("mood")) return null
        val pool = allPicks + genrePools.flatMap { it.second }
        val (p, line) = Mood.pick(weatherCode, weatherTempC, pool, skipNow, prefs.likedGenres()) ?: return null
        return Mood.withReason(p, line).also { moodFilmKey = it.key }
    }

    private var moodFilmKey = ""

    /** The films the owner features on every TV (admin page › Seçki), first in the hero, in order. */
    private fun pinPicks(): List<Pick> {
        val pin = Remote.activePin() ?: return emptyList<Pick>().also { pinFilmKeys = emptySet() }
        val pool = allPicks + top10 + cinema + soon + genrePools.flatMap { it.second } + platformList
        val out = pin.all.mapNotNull { (k, note) ->
            val p = pool.firstOrNull { it.id > 0 && !it.isSeries && Remote.keyOf(it) == k && it.backdrop.isNotEmpty() } ?: return@mapNotNull null
            if (note.isNotEmpty()) Mood.withReason(p, note) else p
        }.distinctBy { it.key }
        pinFilmKeys = out.map { it.key }.toSet()
        return out
    }

    /** First featured film, if any. */
    private fun pinPick(): Pick? = pinPicks().firstOrNull()

    private var pinFilmKeys: Set<String> = emptySet()

    /**
     * What the screensaver plays: films in cinemas and coming soon, films that came out on this
     * day in earlier years, both in turn ("mix"), or empty to keep today's films.
     */
    private fun saverFilms(): List<Pick> = (countdownFilms() + saverRest()).distinctBy { it.key }

    /** Films you asked to be reminded of, coming within 30 days: first in the screensaver, with a countdown. */
    private fun countdownFilms(): List<Pick> = if (prefs.saverContent == "picks") emptyList() else
        prefs.reminders.filter { p -> p.backdrop.isNotEmpty() && daysUntil(p.release)?.let { it in 1..30 } == true }
            .sortedBy { it.release }

    private fun saverRest(): List<Pick> {
        val now = cinemaMix()
        val past = if (Remote.isOff("history")) emptyList() else history.filter { it.backdrop.isNotEmpty() }
        val stream = streamingFirst().filter { it.backdrop.isNotEmpty() }
        return when (prefs.saverContent) {
            "cinema" -> now
            "history" -> past.ifEmpty { now }
            "platform" -> stream.ifEmpty { now }
            "mix" -> {
                // One from history after every two cinema films, and something new on the platforms after every two.
                val base = ArrayList<Pick>()
                var h = 0
                for ((i, p) in now.withIndex()) {
                    base.add(p)
                    if (i % 2 == 1 && h < past.size) base.add(past[h++])
                }
                while (h < past.size) base.add(past[h++])
                val out = ArrayList<Pick>()
                var s = 0
                for ((i, p) in base.withIndex()) {
                    out.add(p)
                    if (i % 2 == 1 && s < stream.size) out.add(stream[s++])
                }
                while (s < stream.size && s < 6) out.add(stream[s++])
                // This week's series take turns with the films too.
                val series = top10tv.filter { it.backdrop.isNotEmpty() && !Remote.isHidden(it) }
                if (series.isEmpty()) return out
                val mixed = ArrayList<Pick>()
                var t = 0
                for ((i, p) in out.withIndex()) {
                    mixed.add(p)
                    if (i % 3 == 2 && t < series.size) mixed.add(series[t++])
                }
                while (t < series.size && t < 4) mixed.add(series[t++])
                mixed
            }
            else -> emptyList()
        }
    }

    /** Films in cinemas and coming soon, alternating, only those with a picture. */
    private fun cinemaMix(): List<Pick> {
        val a = cinema.filter { it.backdrop.isNotEmpty() }
        val b = soon.filter { it.backdrop.isNotEmpty() }
        val out = ArrayList<Pick>()
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let { out.add(it) }
            b.getOrNull(i)?.let { out.add(it) }
        }
        return out
    }

    private fun autoNext() {
        // Down in the rows the top half belongs to the poster under the remote (HomePeek): the
        // film of the day does not turn behind it while browsing.
        val busy = menu != null || moving != null || trailerBtn.hasFocus() || listBtn.hasFocus() || !hasWindowFocus() || level > 0
        val on = prefs.showPicks && (prefs.autoRotate || idle)
        if (on && picks.size > 1 && !busy) next() else schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(rotate)
        handler.postDelayed(rotate, rotateMs)
        startProgress()
    }

    private fun next() {
        if (picks.isEmpty()) return
        pickIndex = (pickIndex + 1) % picks.size
        showPick()
    }

    /**
     * After a few seconds on a film, its picture turns into its trailer without sound; the film
     * stays until the trailer ends (Ayarlar › Görünüm). Not on weak TVs, nor in the screensaver.
     */
    /**
     * While the trailer plays the shades over the picture lighten, so it shows in full colour:
     * only the side and the bottom behind the text stay (lighter), the vignette and the top go.
     */
    /** Our subtitles on the full-screen trailer: not on a dubbed one, nor one with Turkish words in its picture. */
    private fun heroCaptionsAllowed(key: String?, p: Pick?): Boolean {
        val label = p?.trailers?.firstOrNull { it.key == key }?.label?.lowercase(tr).orEmpty()
        return "dubla" !in label && "altyaz" !in label && "türkçe" !in label
    }

    private fun trailerLight(on: Boolean) {
        if (scrims.size < 4) return
        val (vignette, left, bottom, top) = scrims
        val time = if (on) 1400L else 600L
        vignette.animate().alpha(if (on) 0f else 1f).setDuration(time).start()
        // Rain or snow only over the still picture: a trailer plays clean.
        if (::weatherLayer.isInitialized) {
            weatherLayer.animate().cancel()
            if (on) weatherLayer.animate().alpha(0f).setDuration(600).withEndAction { weatherLayer.stop() }.start()
            else {
                weatherLayer.start()
                weatherLayer.animate().alpha(1f).setDuration(600).start()
            }
        }
        top.animate().alpha(if (on) 0.3f else 1f).setDuration(time).start()
        left.animate().alpha(if (on) 0.7f else 1f).setDuration(time).start()
        bottom.animate().alpha(if (on) 0.8f else 1f).setDuration(time).start()
        if (::boost.isInitialized) boost.animate().alpha(if (on) 0f else boostLevel).setDuration(time).start()
    }

    private fun startHeroTrailer() {
        val p = picks.getOrNull(pickIndex) ?: return
        if (!prefs.heroTrailer || Fx.lite || idle || level != 0 || !hasWindowFocus() || menu != null) return
        if (p.homeTrailer.isBlank()) return
        handler.removeCallbacks(rotate)
        progressAnim?.cancel()
        heroTrailer.sound = prefs.heroTrailerSound
        heroTrailer.start(p.homeTrailer)
    }

    private fun queueHeroTrailer() {
        handler.removeCallbacks(trailerDelay)
        if (prefs.heroTrailer && !Fx.lite) handler.postDelayed(trailerDelay, HERO_TRAILER_DELAY_MS)
    }

    private fun stopHeroTrailer() {
        handler.removeCallbacks(trailerDelay)
        if (::heroTrailer.isInitialized && heroTrailer.active) {
            heroTrailer.stop()
            schedule()
        }
    }

    private fun showPick() {
        val p = picks.getOrNull(pickIndex) ?: return
        if (::heroTrailer.isInitialized) heroTrailer.stop()
        queueHeroTrailer()
        handler.removeCallbacks(saverTrailer)
        if (saverTrailersOn()) handler.postDelayed(saverTrailer, 1_500)
        buildProgress()
        schedule()
        loadBackdrop(pickIndex)
        buttons.visibility = View.VISIBLE
        if (idle) {
            for (v in listOf(ambientTitle, ambientMeta, ambientLine)) v.animate().alpha(0f).setDuration(400).start()
            ambientTitle.postDelayed({
                setAmbient(p)
                for (v in listOf(ambientTitle, ambientMeta, ambientLine)) v.animate().alpha(1f).setDuration(800).start()
            }, 420)
        }
        val bind = {
            tagView.text = when {
                p.key == resumeKey -> "KALDIĞIN YERDEN"
                p.key in pinFilmKeys -> "LEXIA SEÇKİSİ"
                shortEvening() && !p.isSeries && p.runtime in 1..SHORT_MIN -> "BU AKŞAM İÇİN KISA"
                p.key == moodFilmKey && pickIndex != 0 -> "HAVAYA GÖRE"
                // The platform's name is on the coloured badge under the title.
                p.provider.isNotEmpty() -> "PLATFORMLARDA YENİ"
                p.isSeries -> "HAFTANIN DİZİLERİ"
                "cinema" in prefs.heroKinds && cinema.any { it.key == p.key } -> "ŞU AN SİNEMADA"
                pickIndex == 0 -> "GÜNÜN FİLMİ"
                else -> "BU HAFTA ÖNE ÇIKANLAR"
            }
            val logo = logos[p.logo]
            if (logo != null) {
                logoView.setImageBitmap(logo)
                logoView.visibility = View.VISIBLE
                titleView.visibility = View.GONE
                // A thin light crosses the film's logo as it comes.
                logoView.postDelayed({ Fx.shine(logoView) }, 500)
            } else {
                logoView.visibility = View.GONE
                titleView.visibility = View.VISIBLE
                titleView.text = p.title
            }
            if (p.imdb > 0) {
                badge.visibility = View.VISIBLE
                imdbScore.text = String.format(tr, "%.1f", p.imdb)
            } else {
                badge.visibility = View.GONE
            }
            certView.text = p.cert
            certView.visibility = if (p.cert.isEmpty()) View.GONE else View.VISIBLE
            // Where to watch it: the platform it is new on, else one it streams on, else cinemas.
            val inCinema = cinema.any { it.key == p.key }
            ui.setWhere(whereView, when {
                p.provider.isNotEmpty() -> p.provider
                inCinema -> ""
                p.where.isNotEmpty() -> p.where.first()
                else -> null
            })
            val parts = ArrayList<String>()
            if (p.year.isNotEmpty()) parts.add(p.year)
            if (p.runtime > 0) parts.add(if (p.runtime >= 60) "${p.runtime / 60} sa ${p.runtime % 60} dk" else "${p.runtime} dk")
            if (p.genres.isNotEmpty()) parts.add(p.genres.joinToString(", "))
            metaView.text = if (parts.isNotEmpty() && p.imdb > 0) parts.joinToString("   ·   ")
            else p.meta.replace("  ·  ", "   ·   ")
            whyView.text = if (shortEvening() && !p.isSeries && p.runtime in 1..SHORT_MIN && p.key !in pinFilmKeys)
                "Yarın iş var: bu akşam ${shortTime(p.runtime)} biten bir film." else p.reason
            whyView.visibility = if (p.reason.isEmpty()) View.GONE else View.VISIBLE
            descView.text = p.overview
            val resuming = p.key == resumeKey
            resumeBtn.visibility = if (resuming) View.VISIBLE else View.GONE
            if (resuming) resumeBtn.text = resumeLabel(p)
            trailerBtn.visibility = if (p.trailer.isEmpty()) View.GONE else View.VISIBLE
            updateListBtn()
        }
        heroText.animate().cancel()
        heroText.animate().alpha(0f).translationY(ui.uf(10)).setDuration(240).withEndAction {
            bind()
            if (Fx.lite) {
                for (i in 0 until heroText.childCount) heroText.getChildAt(i).apply { animate().cancel(); alpha = 1f; translationY = 0f }
                heroText.translationY = ui.uf(16)
                heroText.animate().alpha(1f).translationY(0f).setDuration(D.SLOW)
                    .setInterpolator(DecelerateInterpolator(2.2f)).start()
                return@withEndAction
            }
            // A thin light passes once over the film's logo (or title) as it settles.
            (if (logoView.visibility == View.VISIBLE) logoView else titleView).let { t -> t.post { Fx.shine(t) } }
            // Like opening titles: label, title, badges, then the lines, each a beat after the other.
            heroText.alpha = 1f
            heroText.translationY = 0f
            var beat = 0
            for (i in 0 until heroText.childCount) {
                val c = heroText.getChildAt(i)
                c.animate().cancel()
                if (c.visibility != View.VISIBLE) continue
                c.alpha = 0f
                c.translationY = ui.uf(22)
                c.animate().alpha(1f).translationY(0f).setStartDelay(beat * 85L).setDuration(D.SLOW)
                    .setInterpolator(DecelerateInterpolator(2.2f)).start()
                beat++
            }
        }.start()
    }

    /** Segmented bar: one per film, the current one fills until the next film. */
    /** One thin bar that fills until the next film, and a "3 / 20" counter. */
    private fun buildProgress() {
        progress.removeAllViews()
        progressFill = null
        if (picks.size < 2) return
        val track = FrameLayout(this)
        track.background = ui.round(Color.argb(70, 255, 255, 255), 3)
        track.clipToOutline = true
        val fill = View(this)
        fill.background = ui.round(filmFill, 3)
        fill.pivotX = 0f
        fill.scaleX = 0f
        track.addView(fill, ui.fl(ui.match, ui.match))
        progress.addView(track, ui.ll(ui.u(120), ui.u(4)))
        val count = ui.text("${pickIndex + 1} / ${picks.size}", 17, C.TEXT2, 600)
        count.fontFeatureSettings = "tnum"
        progress.addView(count, ui.ll(ui.wrap, ui.wrap, left = 14))
        progressFill = fill
    }

    private fun startProgress() {
        progressAnim?.cancel()
        val fill = progressFill ?: return
        if (!prefs.autoRotate && !idle) {
            fill.scaleX = 1f
            return
        }
        val a = ObjectAnimator.ofFloat(fill, View.SCALE_X, 0f, 1f)
        a.duration = rotateMs
        a.interpolator = LinearInterpolator()
        a.start()
        progressAnim = a
    }

    private fun loadBackdrop(index: Int) {
        val p = picks.getOrNull(index) ?: return
        imageToken += 1
        val token = imageToken
        val cached = backdrops[p.backdrop]
        if (cached != null) {
            showBackdrop(cached)
            prefetch(index + 1)
            return
        }
        // A small copy comes first and shows as soft colour; the sharp picture then settles over it.
        var sharp = false
        if (p.backdrop.isNotEmpty()) Thread {
            val small = Net.bitmap("https://image.tmdb.org/t/p/w300${p.backdrop}", 300, true)?.let { Fx.soften(it) }
            runOnUiThread {
                if (small != null && !sharp && token == imageToken) {
                    showBackdrop(small)
                    // The sharp one, when it comes, fades over this without another cut.
                    softToken = token
                }
            }
        }.start()
        fetchBackdrop(p.backdrop) { bmp ->
            sharp = true
            if (token == imageToken && bmp != null) showBackdrop(bmp)
            prefetch(index + 1)
        }
    }

    /** The film whose soft placeholder is on screen, so its sharp picture fades in without a cut. */
    private var softToken = -1

    private fun prefetch(index: Int) {
        if (picks.isEmpty()) return
        val p = picks[index % picks.size]
        if (!backdrops.containsKey(p.backdrop)) fetchBackdrop(p.backdrop) { }
    }

    private fun fetchBackdrop(path: String, done: (Bitmap?) -> Unit) {
        if (path.isEmpty()) {
            done(null)
            return
        }
        val width = resources.displayMetrics.widthPixels
        val size = if (width > 1280) "original" else "w1280"
        Thread {
            val bmp = Net.bitmap("https://image.tmdb.org/t/p/$size$path", width, true)
            runOnUiThread {
                if (bmp != null) backdrops[path] = bmp
                done(bmp)
            }
        }.start()
    }

    private fun showBackdrop(bmp: Bitmap) {
        if (softToken == imageToken && front != null) {
            softToken = -1
            reveal(bmp, keepMotion = true)
            return
        }
        if (front == null || idle) {
            reveal(bmp)
            return
        }
        // Dip to black, then open on the next film, like a cut in the cinema.
        blackout.animate().cancel()
        blackout.animate().alpha(0.92f).setDuration(380).withEndAction {
            reveal(bmp, instant = true)
            blackout.animate().alpha(0f).setDuration(1100).setInterpolator(DecelerateInterpolator(1.5f)).start()
        }.start()
    }

    /** The film's colour reaches the progress bar and, while a hero button is focused, the room light. */
    private fun useFilmColor(bmp: Bitmap) {
        // How bright the picture is where the words sit (left and bottom).
        val light = Fx.brightness(bmp, 0f, 0.3f, 0.6f, 1f)
        boostLevel = ((light - 0.36f) / 0.26f).coerceIn(0f, 1f)
        if (!idle) boost.animate().alpha(boostLevel).setDuration(700).start()
        val c = Fx.accent(bmp)
        filmTint = if (c == 0) 0 else Color.argb(120, Color.red(c), Color.green(c), Color.blue(c))
        // A pale version on the bar so it still reads as white-ish light, not a coloured stripe.
        filmFill = if (c == 0) Color.WHITE else Fx.blend(c, Color.WHITE, 0.35f)
        progressFill?.background = ui.round(filmFill, 3)
        if (currentFocus?.let { it.parent === buttons } == true) tintTo(filmTint)
    }

    private fun reveal(bmp: Bitmap, instant: Boolean = false, keepMotion: Boolean = false) {
        useFilmColor(bmp)
        val target = if (front === backA) backB else backA
        val old = front
        target.animate().cancel()
        target.setImageBitmap(bmp)
        target.alpha = 0f
        stage.bringChildToFront(target)
        // Sharp over soft: the same picture, so it carries on from the soft one's size.
        val carry = if (keepMotion && old != null) old.scaleX else 0f
        kenBurns?.cancel()
        target.pivotX = stage.width * (if (pickIndex % 2 == 0) 0.7f else 0.3f)
        target.pivotY = stage.height * 0.35f
        val a = ObjectAnimator.ofPropertyValuesHolder(
            target,
            PropertyValuesHolder.ofFloat(View.SCALE_X, if (carry > 1f) carry else 1.02f, 1.1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, if (carry > 1f) carry else 1.02f, 1.1f)
        )
        a.duration = if (carry > 1f) (24000 * (1.1f - carry) / 0.08f).toLong().coerceAtLeast(1000) else 24000
        a.interpolator = LinearInterpolator()
        if (Fx.lite) {
            target.scaleX = 1.02f
            target.scaleY = 1.02f
        } else {
            a.start()
            kenBurns = a
        }
        front = target
        if (instant) {
            target.alpha = 1f
            old?.alpha = 0f
            return
        }
        target.animate().alpha(1f).setDuration(if (keepMotion) 700 else 1200).withEndAction {
            if (old != null && front === target) old.alpha = 0f
        }.start()
    }

    // ---------- Weather, network, greeting ----------

    private fun weatherIconFor(code: Int, day: Boolean): Int = when (code) {
        0, 1 -> if (day) R.drawable.ic_sun else R.drawable.ic_moon
        45, 48 -> R.drawable.ic_fog
        in 51..67, in 80..82 -> R.drawable.ic_rain
        in 71..77, 85, 86 -> R.drawable.ic_snow
        in 95..99 -> R.drawable.ic_storm
        else -> R.drawable.ic_cloud
    }

    private fun loadWeather() {
        lastWeather = System.currentTimeMillis()
        Thread {
            var lat: Double? = if (prefs.city.isNotEmpty()) prefs.cityLat.toDouble() else null
            var lon: Double? = if (prefs.city.isNotEmpty()) prefs.cityLon.toDouble() else null
            if (lat == null) for (address in listOf("https://ipapi.co/json/", "https://get.geojs.io/v1/ip/geo.json")) {
                val b = Net.get(address) ?: continue
                try {
                    val o = JSONObject(b)
                    lat = o.optString("latitude").toDoubleOrNull()
                    lon = o.optString("longitude").toDoubleOrNull()
                } catch (e: Exception) {
                }
                if (lat != null && lon != null) break
            }
            if (lat == null || lon == null) return@Thread
            val body = Net.get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day&timezone=auto"
            ) ?: return@Thread
            try {
                val cur = JSONObject(body).getJSONObject("current")
                val temp = cur.getDouble("temperature_2m").roundToInt()
                val icon = weatherIconFor(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1)
                val code = cur.getInt("weather_code")
                runOnUiThread {
                    weatherLayer.kind = if (!prefs.ambience || Fx.lite) WeatherLayer.Kind.NONE else when (code) {
                        in 51..67, in 80..82, in 95..99 -> WeatherLayer.Kind.RAIN
                        in 71..77, 85, 86 -> WeatherLayer.Kind.SNOW
                        else -> WeatherLayer.Kind.NONE
                    }
                    tempView.text = "$temp°"
                    weatherIcon.setImageResource(icon)
                    Fx.weatherTemp = "$temp°"
                    Fx.weatherIcon = icon
                    weatherCode = code
                    weatherTempC = temp
                    useMood()
                    if (prefs.weather) weatherBox.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun updateNet() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = try { cm?.getNetworkCapabilities(cm.activeNetwork) } catch (e: Exception) { null }
        val online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val wired = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        netImg.setImageResource(if (wired) R.drawable.ic_ethernet else R.drawable.ic_wifi)
        netImg.alpha = if (online) 1f else 0.35f
        // Online the network button is not needed; without internet it shows, to open the settings.
        if (::netButton.isInitialized) netButton.visibility = if (online) View.GONE else View.VISIBLE
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    updateNet()
                    // Came back online: fill anything that failed to load.
                    if (prefs.showPicks && picks.isEmpty()) startPicks()
                    if (prefs.weather && weatherBox.visibility != View.VISIBLE) loadWeather()
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread { updateNet() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
        }
    }

    private fun updateGreeting() {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val hello = when {
            h < 5 -> "İyi geceler"
            h < 12 -> "Günaydın"
            h < 18 -> "İyi günler"
            else -> "İyi akşamlar"
        }
        greeting.text = if (prefs.name.isEmpty()) hello else "$hello, ${prefs.name}"
        greeting.visibility = if (prefs.greet) View.VISIBLE else View.GONE
        dateText.text = SimpleDateFormat("d MMMM EEEE", tr).format(c.time).uppercase(tr)
    }

    // ---------- Updates ----------

    private fun showUpdatePill() {
        if (Updater.available && !Updater.busy) {
            updatePill.text = "Güncelleme geldi"
            updatePill.visibility = View.VISIBLE
            if (updatePulse == null) {
                // A gentle pulse so the new version gets noticed.
                val a = ObjectAnimator.ofFloat(updatePill, View.ALPHA, 1f, 0.55f)
                a.duration = 900
                a.repeatCount = ValueAnimator.INFINITE
                a.repeatMode = ValueAnimator.REVERSE
                a.start()
                updatePulse = a
            }
        } else if (!Updater.busy) {
            updatePulse?.cancel()
            updatePulse = null
            updatePill.alpha = 1f
            updatePill.visibility = View.GONE
        }
    }

    private fun startUpdate() {
        if (!Updater.available) return
        updatePulse?.cancel()
        updatePulse = null
        updatePill.alpha = 1f
        updatePill.visibility = View.VISIBLE
        Updater.install(this) { updatePill.text = it }
    }

    // ---------- Lifecycle ----------

    override fun onResume() {
        super.onResume()
        if (level == 0 && !idle) queueHeroTrailer()
        // Ayarlar › Görünüm › Büyük yazı changed: build the screen again with the new text size.
        if (prefs.bigText != builtBig) {
            recreate()
            return
        }
        HomeButtonService.resume()
        handler.removeCallbacks(healthy)
        handler.postDelayed(healthy, 20_000L)
        handler.removeCallbacks(liteCheck)
        if (!prefs.lite && !prefs.liteAsked) handler.postDelayed(liteCheck, 25_000L)
        // Keşfet's list read ahead, quietly, so it opens at once.
        handler.postDelayed({ ExploreActivity.warm(this) }, 4_000L)
        Fx.lite = prefs.lite
        NightSleep.schedule(this)
        Fx.zoomReset(frame)
        // Rows drawn again (the film just opened moves to "Son baktıkların"): the remote stays on its film.
        val spot = FocusKeep.save(this)
        refreshMarks()
        refreshContinue()
        refreshRecent()
        refreshShelf()
        if (spot != null && FocusKeep.save(this)?.key != spot.key) FocusKeep.restore(this, spot)
        // Films put in Listem by older versions get a date now, for "Listende 3 haftadır bekliyor".
        if (prefs.listAdded.isEmpty() && prefs.watchlist.isNotEmpty()) prefs.watchlist = prefs.watchlist
        handler.removeCallbacks(morningCheck)
        handler.postDelayed(morningCheck, 3000L)
        // Listem backup: keeps the copy fresh; on a fresh install it can bring the lists back.
        Backup.sync(this) {
            runOnUiThread {
                refreshMarks()
                refreshRecent()
                refreshShelf()
                Glass.toast(this, "Listelerin yedekten geri geldi", long = true)
            }
        }
        maybeStartSetup()
        refreshHomeNudge()
        handler.removeCallbacks(nightTick)
        handler.postDelayed(nightTick, 900L)
        handler.removeCallbacks(weekendCheck)
        handler.postDelayed(weekendCheck, 2500L)
        // The weekend plan: today's film, said once a day.
        handler.postDelayed({
            if (!isFinishing && hasWindowFocus()) WeekendActivity.todayOnce(this)?.let {
                Glass.toast(this, "Bugünkü programın: $it", long = true)
            }
        }, 4_000L)
        updateGreeting()
        updateNet()
        if (Cache.clearRequested) {
            Cache.clearRequested = false
            backdrops.clear()
            logos.clear()
            thumbs.clear()
            appColors.clear()
            Img.clear()
            allPicks = emptyList()
            picks = emptyList()
            lastPicks = 0L
        }
        for (c in clocks) applyClock(c)
        for (c in rolling) c.use24 = prefs.clock24
        hero.visibility = if (prefs.showPicks) View.VISIBLE else View.GONE
        stage.visibility = if (prefs.showPicks && prefs.background == "film") View.VISIBLE else View.GONE
        ambience.visibility = stage.visibility
        refreshDock()
        arrangeRows()
        refreshShelf()
        showTopRows()
        applyGenres()
        val now = System.currentTimeMillis()
        if (prefs.showPicks && (allPicks.isEmpty() || now - lastPicks > 3 * 60 * 60 * 1000L)) startPicks()
        val wsig = "${prefs.weather}|${prefs.city}|${prefs.ambience}|${prefs.lite}"
        if (!prefs.weather) weatherBox.visibility = View.GONE
        if (prefs.weather && (wsig != weatherSig || now - lastWeather > 30 * 60 * 1000L)) {
            weatherSig = wsig
            loadWeather()
        }
        schedule()
        resetIdle()
        holdScreen()
        refreshReminder()
        updateBell()
        if (!prefs.ambience || Fx.lite) weatherLayer.kind = WeatherLayer.Kind.NONE
        weatherLayer.start()
        handler.removeCallbacks(updateCheck)
        if (Updater.waitingForPermission && Updater.canInstall(this)) {
            startUpdate()
            handler.postDelayed(updateCheck, 30 * 60 * 1000L)
        } else {
            showUpdatePill()
            handler.post(updateCheck)
        }
        handler.removeCallbacks(remoteTick)
        remoteState = Remote.sig()
        handler.postDelayed(remoteTick, 5 * 60 * 1000L)
        if (Remote.mustUpdate()) showUpdateWall()
        if (Saver.previewRequested) {
            // "Şimdi önizle" in the settings: start the screensaver as soon as we are back.
            Saver.previewRequested = false
            handler.postDelayed({ enterIdle(force = true) }, 700)
        } else if (prefs.setupDone && !setupShown) {
            // Only while the home screen is really in front, so the card isn't missed.
            handler.postDelayed({ if (!isFinishing && !idle && hasWindowFocus()) WhatsNew.maybeShow(this, ui, prefs) }, 1500)
        }
    }

    /** Up for 20 seconds on screen: LeXia opens fine, the crash shield's count goes back to zero. */
    private val healthy = Runnable { Guard.ok(this) }

    /**
     * TVs with 2 GB of memory or less: offer Hafif mod once (never turned on by itself). Waits until
     * nothing else is on screen (the Yenilikler card, a menu), the screen saver is not running.
     */
    private val liteCheck = Runnable { suggestLite() }

    private fun weakTv(): Boolean = try {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        am.isLowRamDevice || mi.totalMem < 2_600L * 1024 * 1024
    } catch (e: Exception) {
        false
    }

    private fun suggestLite() {
        if (prefs.lite || prefs.liteAsked || isFinishing) return
        if (!weakTv()) {
            prefs.liteAsked = true
            return
        }
        if (!hasWindowFocus() || idle) {
            handler.postDelayed(liteCheck, 60_000L)
            return
        }
        prefs.liteAsked = true
        Dialogs.confirm(
            this, "Hafif mod açılsın mı?",
            "Bu TV'nin belleği az. Hafif mod eğilme, ışık ve bulanık cam efektlerini kapatır; " +
                "LeXia daha akıcı gezer. İstediğin zaman Ayarlar › Görünüm › Hafif mod'dan değiştirebilirsin."
        ) {
            prefs.lite = true
            Fx.lite = true
            recreate()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::heroStage.isInitialized) heroStage.wake()
        stopHeroTrailer()
        if (::peek.isInitialized) peek.pause()
        handler.removeCallbacks(healthy)
        handler.removeCallbacks(liteCheck)
        handler.removeCallbacks(rotate)
        handler.removeCallbacks(updateCheck)
        handler.removeCallbacks(remoteTick)
        handler.removeCallbacks(nightTick)
        handler.removeCallbacks(weekendCheck)
        handler.removeCallbacks(morningCheck)
        progressAnim?.cancel()
        weatherLayer.stop()
        endMove()
        endRowMove()
        if (idle) exitIdle()
        handler.removeCallbacks(idleTimer)
        // Last, since leaving the screensaver above turns the hold back on.
        handler.removeCallbacks(holdRelease)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

}
