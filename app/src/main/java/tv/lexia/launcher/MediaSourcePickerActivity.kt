package tv.lexia.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nuvio.tv.LeXiaNuvio
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * "Oynat" on a film page: asks the installed add-ons for sources and plays one.
 *
 * With Ayarlar › Oynatma › "En iyi kaynağı kendiliğinden aç" on, the most fitting source starts
 * by itself and this list is what Back from the player returns to. A series first shows its
 * episodes; the player asks for the next episode when one ends.
 */
class MediaSourcePickerActivity : Activity() {

    companion object {
        private const val EXTRA_FILM = "media.pick"
        private const val REQUEST_PLAY = 41
        /** Once the first sources are in, how long the rest get before the best so far is played. */
        private const val AUTO_WAIT_MS = 6_000L
        private const val MEMORY_SIZE = 60

        fun open(activity: Activity, film: Pick) {
            activity.startActivity(
                Intent(activity, MediaSourcePickerActivity::class.java)
                    .putExtra(EXTRA_FILM, film.toJson().toString())
            )
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    /** Why a source would start without the viewer picking it. */
    private enum class Auto { NO, BEST, NEXT_EPISODE }

    private val repository by lazy { MediaAddonRepository(this) }
    private val nuvioRepository by lazy { NuvioJsRepository(this) }
    private val prefs by lazy { Prefs(this) }
    private val sourcePrefs by lazy { getSharedPreferences("media_source_picker", MODE_PRIVATE) }
    private val worker = Executors.newCachedThreadPool()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var film: Pick
    /** The title's IMDb id ("tt…"): what Stremio add-ons look it up by. Looked up when the film list has none. */
    private var imdb = ""
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var sourceControls: LinearLayout
    private lateinit var sortButton: TextView
    private lateinit var groupButton: TextView
    private var sortByQuality = true
    private var groupByProvider = true

    /** Every search gets a number; an answer to an older search is dropped. */
    private var generation = 0
    private val running = ArrayList<Future<*>>()
    private var searching = false

    private var episodes: List<MediaEpisode> = emptyList()
    private var season = 0
    /** The episode whose sources are on screen: its place in [episodes], or -1 (a film, or typed numbers). */
    private var episodeIndex = -1
    private var typedSeason = 0
    private var typedEpisode = 0

    private var streams: List<MediaStream> = emptyList()
    /** Subtitles from subtitle add-ons (OpenSubtitles and the like); they go with whichever source is played. */
    private var addonSubtitles: List<MediaSubtitle> = emptyList()
    private var playbackTitle = ""
    private var mediaKey = ""
    private val failed = HashSet<String>()
    private var playing: MediaStream? = null
    private var focusedUrl = ""
    private var listShown = false
    private var auto = Auto.NO
    private val autoDeadline = Runnable { playBestNow() }

    private val screenHeight by lazy {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.supportedModes.maxOfOrNull { it.physicalHeight }
            ?: resources.displayMetrics.heightPixels
    }
    private val screenHdr by lazy {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
    }
    /** Turkish sound wanted: a dubbed source is the better first choice. */
    private val wantDubbed by lazy { prefs.playAudioLang.ifBlank { Lang.device() } == "tr" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        film = try {
            Pick.from(JSONObject(intent.getStringExtra(EXTRA_FILM) ?: "{}"))
        } catch (_: Exception) {
            finish()
            return
        }
        imdb = film.imdbId
        sortByQuality = sourcePrefs.getBoolean("sort_quality", true)
        groupByProvider = sourcePrefs.getBoolean("group_provider", true)
        buildUi()
        updateSourceControls()
        loadContent()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The viewer is choosing: nothing starts by itself any more.
        if (event.action == KeyEvent.ACTION_DOWN && auto != Auto.NO && searching) cancelAuto()
        return super.dispatchKeyEvent(event)
    }

    // ---------- Screen ----------

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), dp(28), dp(44), dp(24))
            setBackgroundColor(Color.rgb(7, 9, 15))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(TextView(this).apply {
            text = if (film.isSeries) "DİZİYİ İZLE" else "FİLMİ İZLE"
            textSize = 12f
            letterSpacing = 0.14f
            setTextColor(Color.rgb(125, 151, 255))
            typeface = Typeface.DEFAULT_BOLD
        })
        heading.addView(TextView(this).apply {
            text = film.title
            textSize = 31f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        top.addView(heading, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val manage = action("Kaynakları yönet").apply {
            setOnClickListener {
                startActivity(Intent(this@MediaSourcePickerActivity, MediaAddonsActivity::class.java))
            }
        }
        top.addView(manage)
        root.addView(top)

        status = TextView(this).apply {
            text = "Kaynaklar hazırlanıyor…"
            textSize = 16f
            setTextColor(Color.rgb(170, 181, 199))
            setPadding(0, dp(14), 0, dp(10))
        }
        root.addView(status)

        sourceControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(10))
            visibility = View.GONE
        }
        sortButton = action("").apply {
            setOnClickListener {
                sortByQuality = !sortByQuality
                sourcePrefs.edit().putBoolean("sort_quality", sortByQuality).apply()
                updateSourceControls()
                if (streams.isNotEmpty()) renderStreams()
            }
        }
        groupButton = action("").apply {
            setOnClickListener {
                groupByProvider = !groupByProvider
                sourcePrefs.edit().putBoolean("group_provider", groupByProvider).apply()
                updateSourceControls()
                if (streams.isNotEmpty()) renderStreams()
            }
        }
        sourceControls.addView(sortButton)
        sourceControls.addView(groupButton, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        root.addView(sourceControls)

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        // The remote starts on a button; a focused container would swallow every D-pad press.
        manage.requestFocus()
    }

    private fun updateSourceControls() {
        sortButton.text = if (sortByQuality) "Sırala: Kalite" else "Sırala: Ad"
        groupButton.text = if (groupByProvider) "Grupla: Sağlayıcı" else "Grupla: Tümü"
    }

    private fun clearContent(showSourceControls: Boolean = false) {
        content.removeAllViews()
        sourceControls.visibility = if (showSourceControls) View.VISIBLE else View.GONE
    }

    // ---------- What to show ----------

    private fun loadContent() {
        if (film.id <= 0) {
            showMessage(
                "İçerik kimliği bulunamadı",
                "Kaynak araması için LeXia film kaydında TMDB kimliği bulunmalı."
            )
            return
        }
        if (repository.installedAddons().isEmpty() && nuvioRepository.installedRepositories().isEmpty()) {
            showNoAddons()
            return
        }
        // Most add-ons know a film only by its IMDb id; without it they answer nothing.
        if (imdb.isNotEmpty()) {
            if (film.isSeries) loadEpisodes() else loadMovieStreams()
            return
        }
        cancelSearch()
        val mine = generation
        status.text = "Yapım bilgisi alınıyor…"
        worker.execute {
            val found = repository.imdbId(if (film.isSeries) "tv" else "movie", film.id)
            runOnUiThread {
                if (mine != generation || isFinishing) return@runOnUiThread
                imdb = found
                if (film.isSeries) loadEpisodes() else loadMovieStreams()
            }
        }
    }

    /** Why the add-ons gave nothing, in the viewer's words. */
    private fun whyNothing(): String {
        val notes = NuvioSearch.notes
        val stremio = repository.installedAddons().count { it.enabled && "stream" in it.resources }
        val scrapers = nuvioRepository.installedScraperCount()
        val reasons = ArrayList<String>()
        if (notes.torrents.get() > 0) {
            reasons.add("${notes.torrents.get()} kaynak bulundu, ama hepsi torrent. LeXia torrent oynatmıyor.")
        }
        if (notes.unplayable.get() > 0) reasons.add("${notes.unplayable.get()} kaynak oynatılabilir bir video adresi vermedi (bir web sayfası ya da YouTube bağlantısıydı).")
        if (scrapers > 0 && notes.providers.get() == 0) {
            reasons.add("Kurulu $scrapers Nuvio sağlayıcısının hiçbiri bu yapım için sonuç vermedi; bu sitelerde olmayabilir.")
            val errors = nuvioRepository.nuvioErrorsNow()
            if (errors.isNotEmpty()) reasons.add("Nuvio'nun bildirdiği hata: " + errors.joinToString("  •  "))
        }
        if (notes.error.isNotBlank()) reasons.add(notes.error)
        if (imdb.isEmpty()) reasons.add("Bu yapımın IMDb kimliği bulunamadı; çoğu eklenti onsuz arama yapamaz.")
        if (scrapers > 0 && BuildConfig.TMDB_API_KEY.isBlank()) reasons.add("Bu sürümde TMDB anahtarı yok; Nuvio sağlayıcıları yapımı tanıyamıyor.")
        if (stremio == 0 && scrapers == 0) reasons.add("Kaynak veren bir eklenti kurulu değil.")
        return reasons.joinToString(" ").ifBlank { "Kurulu eklentiler bu yapım için oynatılabilir bir kaynak vermedi." }
    }

    private fun loadMovieStreams() {
        episodeIndex = -1
        // Stremio add-ons commonly expect IMDb IDs; Nuvio turns it into the TMDB id its scrapers take.
        val stremioId = imdb.ifBlank { "tmdb:${film.id}" }
        search(
            message = "Film kaynakları aranıyor…",
            title = film.title,
            key = stremioId,
            auto = if (prefs.playAutoSource) Auto.BEST else Auto.NO,
            sources = { update -> nuvioSearch("movie", stremioId, null, null, update) },
            subtitles = { repository.fetchSubtitles("movie", stremioId) }
        )
    }

    private fun loadEpisodes() {
        cancelSearch()
        val mine = ++generation
        status.text = "Dizi bölümleri aranıyor…"
        clearContent()
        if (episodes.isNotEmpty()) {
            showEpisodes()
            return
        }
        worker.execute {
            val found = try {
                repository.fetchEpisodes(film.id, imdb)
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (mine != generation || isFinishing) return@runOnUiThread
                episodes = found
                if (found.isEmpty()) showManualEpisodeSearch() else showEpisodes()
            }
        }
    }

    private fun seasonName(number: Int) = if (number > 0) "$number. Sezon" else "Özel bölümler"

    private fun episodeLine(e: MediaEpisode) =
        if (e.season > 0 && e.episode > 0) "${e.season}. Sezon  ·  ${e.episode}. Bölüm" else "Bölüm"

    /** The seasons as a row of buttons, the chosen season's episodes under it. */
    private fun showEpisodes(focusSeason: Boolean = false) {
        episodeIndex = -1
        val last = remembered("episodes")
        val seasons = episodes.map { it.season }.distinct()
        if (season !in seasons) {
            season = episodes.firstOrNull { it.id == last }?.season
                ?: seasons.firstOrNull { it > 0 } ?: seasons.first()
        }
        val shown = episodes.withIndex().filter { it.value.season == season }
        status.text = if (seasons.size > 1) "${seasons.size} sezon  ·  ${episodes.size} bölüm" else "${episodes.size} bölüm"
        clearContent()

        var focus: View? = null
        if (seasons.size > 1) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            seasons.forEach { number ->
                val chosen = number == season
                val button = action(seasonName(number), primary = chosen).apply {
                    setOnClickListener {
                        season = number
                        showEpisodes(focusSeason = true)
                    }
                }
                // Stepping through the seasons keeps the remote on the season row.
                if (chosen && focusSeason) focus = button
                row.addView(button, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            }
            val strip = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            strip.addView(row)
            content.addView(strip, fullWidthParams(bottom = 10))
        }
        addSectionTitle(seasonName(season).uppercase(Locale.forLanguageTag("tr-TR")))
        shown.forEach { (index, episode) ->
            val card = addCard(
                episode.title,
                episodeLine(episode) + if (isUnreleased(episode)) "  ·  Henüz yayınlanmadı" else "",
                "Kaynakları ara"
            ) { loadEpisodeStreams(index, if (prefs.playAutoSource) Auto.BEST else Auto.NO) }
            if (!focusSeason && (episode.id == last || focus == null)) focus = card
        }
        focusLater(focus)
    }

    private fun isUnreleased(e: MediaEpisode): Boolean {
        val day = e.released.take(10)
        if (day.length < 10) return false
        return day > SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
    }

    private fun showManualEpisodeSearch() {
        status.text = "Eklentiler bölüm listesi vermedi."
        clearContent()
        addMessageCard(
            "Sezon ve bölüm numarasıyla ara",
            "Bazı dizi eklentileri bölüm listesi vermez; kaynakları sezon ve bölüm numarasıyla bulur."
        )
        val button = action("Bölüm numarası gir", primary = true).apply {
            setOnClickListener { showEpisodeNumberDialog() }
        }
        content.addView(button, fullWidthParams(top = 14))
        focusLater(button)
    }

    private fun showEpisodeNumberDialog() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), 0)
        }
        val seasonField = EditText(this).apply {
            hint = "Sezon numarası"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((typedSeason.takeIf { it > 0 } ?: 1).toString())
            setTextColor(Color.WHITE)
        }
        val episodeField = EditText(this).apply {
            hint = "Bölüm numarası"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((typedEpisode.takeIf { it > 0 } ?: 1).toString())
            setTextColor(Color.WHITE)
        }
        fields.addView(seasonField)
        fields.addView(episodeField)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Bölüm seç")
            .setView(fields)
            .setNegativeButton("İptal", null)
            .setPositiveButton("Kaynakları ara") { _, _ ->
                val s = seasonField.text.toString().toIntOrNull()
                val e = episodeField.text.toString().toIntOrNull()
                if (s == null || e == null || s !in 1..100 || e !in 1..2000) {
                    status.text = "Geçerli bir sezon ve bölüm numarası gir."
                } else {
                    loadTypedEpisode(s, e, if (prefs.playAutoSource) Auto.BEST else Auto.NO)
                }
            }
            .create()
        dialog.show()
        dialog.window?.setLayout(dp(560), LinearLayout.LayoutParams.WRAP_CONTENT)
        seasonField.requestFocus()
    }

    private fun loadEpisodeStreams(index: Int, auto: Auto) {
        val episode = episodes.getOrNull(index) ?: return
        episodeIndex = index
        season = episode.season
        remember("episodes", episode.id)
        searchEpisode(episode.id, episode.title, episode.season, episode.episode, auto)
    }

    private fun loadTypedEpisode(s: Int, e: Int, auto: Auto) {
        episodeIndex = -1
        typedSeason = s
        typedEpisode = e
        searchEpisode("${imdb.ifBlank { "tmdb:${film.id}" }}:$s:$e", "$s. Sezon · $e. Bölüm", s, e, auto)
    }

    private fun searchEpisode(videoId: String, title: String, s: Int, e: Int, auto: Auto) {
        search(
            message = "Kaynaklar aranıyor  ·  $title",
            title = "${film.title} · $title",
            key = videoId,
            auto = auto,
            sources = { update ->
                nuvioSearch("series", videoId, s.takeIf { it > 0 && e > 0 }, e.takeIf { s > 0 && it > 0 }, update)
            },
            subtitles = { repository.fetchSubtitles("series", videoId) }
        )
    }

    /**
     * NuvioTV's own search (Stremio add-ons and plugin repositories together), shown in this list.
     * LeXia's Stremio add-ons are handed to Nuvio's store first; runs on a worker thread.
     */
    private fun nuvioSearch(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        update: (List<MediaStream>) -> Unit
    ): List<MediaStream> {
        val app = applicationContext
        if (imdb.isNotBlank()) {
            // LeXia already knows both ids: Nuvio need not ask TMDB for the pair.
            LeXiaNuvio.tmdbService.preCacheMapping(imdb, film.id, if (film.isSeries) "tv" else "movie")
        }
        try {
            runBlocking { LeXiaNuvio.setStremioAddons(app, repository.installedAddons().filter { it.enabled }.map { it.baseUrl }) }
        } catch (e: Exception) {
            Log.w("LeXiaSources", "Add-ons not handed to Nuvio: ${e.message}")
        }
        return NuvioSearch.streams(app, type, videoId, season, episode, update)
    }

    /** The episode after the one on screen, as (what to show, how to load it); null at the end. */
    private fun nextEpisode(): Pair<String, (Auto) -> Unit>? {
        if (!film.isSeries) return null
        if (episodeIndex >= 0) {
            val next = episodes.getOrNull(episodeIndex + 1) ?: return null
            if (next.season <= 0 || isUnreleased(next)) return null
            val index = episodeIndex + 1
            val name = episodeLine(next) + if (next.title.isNotBlank() && !next.title.contains("Bölüm")) "  ·  ${next.title}" else ""
            return name to { auto: Auto -> loadEpisodeStreams(index, auto) }
        }
        if (typedSeason > 0 && typedEpisode > 0) {
            val s = typedSeason
            val e = typedEpisode + 1
            return "$s. Sezon  ·  $e. Bölüm" to { auto: Auto -> loadTypedEpisode(s, e, auto) }
        }
        return null
    }

    // ---------- Searching ----------

    private fun cancelSearch() {
        // Answers still on their way belong to a search nobody is waiting for.
        generation++
        handler.removeCallbacks(autoDeadline)
        running.forEach { it.cancel(true) }
        running.clear()
        searching = false
        auto = Auto.NO
    }

    private fun cancelAuto() {
        handler.removeCallbacks(autoDeadline)
        auto = Auto.NO
        updateStatus()
    }

    /**
     * Asks both kinds of add-ons at the same time and fills the list as answers come in, so one
     * slow add-on (a scraper may take two minutes) holds nothing up.
     */
    private fun search(
        message: String,
        title: String,
        key: String,
        auto: Auto,
        sources: ((List<MediaStream>) -> Unit) -> List<MediaStream>,
        subtitles: () -> List<MediaSubtitle>
    ) {
        cancelSearch()
        val mine = ++generation
        streams = emptyList()
        addonSubtitles = emptyList()
        failed.clear()
        focusedUrl = ""
        listShown = false
        playbackTitle = title
        mediaKey = key
        this.auto = auto
        searching = true
        status.text = message
        clearContent()

        var open = 2
        fun partial(part: List<MediaStream>) = runOnUiThread {
            if (mine == generation && !isFinishing) addStreams(part)
        }
        fun finished() = runOnUiThread {
            if (mine != generation || isFinishing) return@runOnUiThread
            open--
            if (open == 0) searchDone()
        }
        // A broken subtitle add-on must not hide the sources.
        running.add(worker.submit {
            try {
                val found = subtitles()
                runOnUiThread { if (mine == generation && !isFinishing) addonSubtitles = found }
            } catch (e: Exception) {
                Log.w("LeXiaSources", "Subtitle search failed: ${e.message}")
            } finally {
                finished()
            }
        })
        running.add(worker.submit {
            try {
                partial(sources(::partial))
            } catch (e: Exception) {
                Log.w("LeXiaSources", "Source search failed: ${e.message}")
            } finally {
                finished()
            }
        })
    }

    private fun addStreams(part: List<MediaStream>) {
        val known = streams.mapTo(HashSet()) { "${it.addonName.lowercase()}|${it.url}" }
        val fresh = part.filter { known.add("${it.addonName.lowercase()}|${it.url}") }
        if (fresh.isEmpty()) return
        val first = streams.isEmpty()
        streams = streams + fresh
        renderStreams()
        if (first && auto != Auto.NO && searching) {
            handler.removeCallbacks(autoDeadline)
            handler.postDelayed(autoDeadline, AUTO_WAIT_MS)
        }
    }

    private fun searchDone() {
        searching = false
        running.clear()
        if (streams.isEmpty()) {
            auto = Auto.NO
            showMessage(
                if (film.isSeries) "Bu bölüm için kaynak bulunamadı" else "Oynatılabilir kaynak bulunamadı",
                whyNothing()
            )
            if (film.isSeries) addBackToEpisodes()
            return
        }
        updateStatus()
        if (auto != Auto.NO) playBestNow()
    }

    private fun updateStatus() {
        if (streams.isEmpty()) return
        val count = "${streams.size} kaynak"
        status.text = when {
            auto != Auto.NO -> "$count bulundu  ·  En uygun kaynak açılıyor…"
            searching -> "$count bulundu  ·  Diğer eklentiler aranıyor…"
            failed.isNotEmpty() -> "Kaynak oynatılamadı. Başka bir kaynak seç."
            else -> "$count  ·  ${streams.map { it.addonName.trim().lowercase() }.distinct().size} sağlayıcı"
        }
    }

    // ---------- Choosing ----------

    private fun signature(stream: MediaStream) =
        listOf(stream.addonName.trim().lowercase(), stream.tags.resolution.toString(), stream.tags.turkish).joinToString("|")

    /** 2: same add-on, resolution and dubbing as the remembered choice; 1: same add-on; 0: neither. */
    private fun likeness(stream: MediaStream, wanted: String): Int = when {
        wanted.isBlank() -> 0
        signature(stream) == wanted -> 2
        stream.addonName.trim().lowercase() == wanted.substringBefore('|') -> 1
        else -> 0
    }

    private fun score(stream: MediaStream) = stream.tags.score(screenHeight, screenHdr, wantDubbed)

    /**
     * The source to start without asking: what the viewer picked for this title last time (so a
     * series stays on one source), otherwise the best picture this TV can show.
     */
    private fun best(): MediaStream? {
        val wanted = remembered("choices")
        return streams.filter { it.url !in failed }
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<MediaStream>> { likeness(it.value, wanted) }
                    .thenByDescending { score(it.value) }
                    .thenBy { it.index }
            )
            .firstOrNull()?.value
    }

    private fun playBestNow() {
        handler.removeCallbacks(autoDeadline)
        if (auto == Auto.NO) return
        auto = Auto.NO
        val choice = best()
        updateStatus()
        if (choice != null && !isFinishing) play(choice)
    }

    /** The source's own subtitles, then the subtitle add-ons': the wanted language first, 24 at most. */
    private fun subtitlesFor(stream: MediaStream): List<SubtitleTrack> {
        val wanted = prefs.playSubLang.takeIf { it != "off" }.orEmpty().ifBlank { Lang.device() }
        fun rank(s: MediaSubtitle) = if (Lang.code(s.language).ifBlank { Lang.code(s.label) } == wanted) 0 else 1
        return (stream.subtitles + addonSubtitles.sortedBy { rank(it) })
            .distinctBy { it.url }
            .take(24)
            .map { SubtitleTrack(it.url, language = it.language, label = it.label) }
    }

    private fun play(stream: MediaStream) {
        handler.removeCallbacks(autoDeadline)
        auto = Auto.NO
        val intent = try {
            PlayerActivity.intent(
                this,
                stream.url,
                playbackTitle,
                mediaKey,
                stream.headers,
                subtitlesFor(stream),
                watchKey = film.key,
                markWatchedOnCompletion = !film.isSeries,
                nextTitle = nextEpisode()?.first.orEmpty(),
                fromSourceList = true
            )
        } catch (e: Exception) {
            status.text = e.message ?: "Oynatıcı açılamadı."
            return
        }
        playing = stream
        focusedUrl = stream.url
        startActivityForResult(intent, REQUEST_PLAY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PLAY) return
        val stream = playing
        playing = null
        when (resultCode) {
            PlayerActivity.RESULT_FAILED -> {
                if (stream != null) {
                    failed.add(stream.url)
                    if (remembered("choices") == signature(stream)) remember("choices", "")
                }
                if (streams.isNotEmpty()) renderStreams()
            }
            PlayerActivity.RESULT_NEXT -> {
                // The next episode starts on the source this one played from.
                if (stream != null) remember("choices", signature(stream))
                val next = nextEpisode()
                if (next != null) next.second(Auto.NEXT_EPISODE) else loadEpisodes()
            }
            RESULT_OK -> {
                if (!film.isSeries) {
                    finish()
                    return
                }
                // Watched to the end: the episode list opens on the one after it.
                if (episodeIndex >= 0) episodes.getOrNull(episodeIndex + 1)?.let { remember("episodes", it.id) }
                loadEpisodes()
            }
            else -> if (streams.isNotEmpty()) renderStreams()
        }
    }

    // ---------- The source list ----------

    private fun renderStreams() {
        if (streams.isEmpty()) return
        val groups: Map<String, List<MediaStream>> = if (groupByProvider) {
            streams.groupBy { it.addonName.trim().ifBlank { "Diğer kaynaklar" } }
        } else {
            linkedMapOf("" to streams)
        }
        updateStatus()
        val inList = content.findFocus() != null
        clearContent(showSourceControls = true)
        addSectionTitle("YAYIN KAYNAKLARI")

        val tr = Locale.forLanguageTag("tr-TR")
        var number = 0
        var focus: View? = null
        var firstCard: View? = null
        groups.forEach { (provider, list) ->
            val ordered = if (sortByQuality) list.sortedByDescending { score(it) } else list.sortedBy { it.name.lowercase(tr) }
            if (groupByProvider) addSectionTitle("${provider.uppercase(tr)}  ·  ${ordered.size} KAYNAK")
            ordered.forEach { stream ->
                number++
                val broken = stream.url in failed
                val details = listOf(
                    stream.server,
                    stream.description.takeIf { it != stream.name },
                    stream.fileName
                ).filterNotNull().map { it.replace('\n', ' ').trim() }.filter { it.isNotBlank() }.distinct().joinToString("  ·  ")
                val badges = ArrayList(stream.tags.badges)
                if (stream.subtitles.isNotEmpty()) badges.add("${stream.subtitles.size} altyazı")
                val card = addCard(
                    stream.name.replace('\n', ' ').trim().ifBlank { "Kaynak $number" },
                    details,
                    if (broken) "Oynatılamadı" else "Oynat",
                    badges = badges,
                    dim = broken
                ) {
                    remember("choices", signature(stream))
                    play(stream)
                }
                card.setTag(stream.url)
                if (firstCard == null) firstCard = card
                if (stream.url == focusedUrl) focus = card
            }
        }
        if (film.isSeries) addBackToEpisodes()
        // A list that is still filling keeps the remote where it was (on its source, or on a button
        // above the list); a new list starts on its first source.
        if (inList || !listShown) focusLater(focus ?: firstCard)
        listShown = true
    }

    private fun addBackToEpisodes() {
        val back = action("Bölüm listesine dön").apply {
            setOnClickListener {
                cancelSearch()
                if (episodes.isEmpty()) showManualEpisodeSearch() else loadEpisodes()
            }
        }
        content.addView(back, fullWidthParams(top = 12))
    }

    private fun showNoAddons() {
        status.text = "Henüz medya kaynağı eklenmedi."
        clearContent()
        addMessageCard(
            "LeXia'ya kaynak ekle",
            "Film ve dizi kaynaklarını bulmak için önce uyumlu bir eklenti kur. Eklenti adresi manifest üzerinden doğrulanır."
        )
        val button = action("Eklenti yöneticisini aç", primary = true).apply {
            setOnClickListener {
                startActivity(Intent(this@MediaSourcePickerActivity, MediaAddonsActivity::class.java))
            }
        }
        content.addView(button, fullWidthParams(top = 14))
        focusLater(button)
    }

    private fun showMessage(title: String, description: String) {
        status.text = ""
        clearContent()
        addMessageCard(title, description)
        val manager = action("Kaynakları yönet").apply {
            setOnClickListener {
                startActivity(Intent(this@MediaSourcePickerActivity, MediaAddonsActivity::class.java))
            }
        }
        content.addView(manager, fullWidthParams(top = 14))
        focusLater(manager)
    }

    // ---------- What is remembered per title ----------

    /** A small map kept as one setting: title → value, the oldest titles dropped. */
    private fun remembered(name: String): String = try {
        JSONObject(sourcePrefs.getString(name, "{}") ?: "{}").optString(film.key)
    } catch (_: Exception) {
        ""
    }

    private fun remember(name: String, value: String) {
        val map = try {
            JSONObject(sourcePrefs.getString(name, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        map.remove(film.key)
        if (value.isNotBlank()) map.put(film.key, value)
        // JSONObject keeps the order things were put in: the first names are the oldest.
        val names = map.keys().asSequence().toList()
        names.take((names.size - MEMORY_SIZE).coerceAtLeast(0)).forEach { map.remove(it) }
        sourcePrefs.edit().putString(name, map.toString()).apply()
    }

    // ---------- Pieces ----------

    private fun focusLater(view: View?) {
        view?.post { if (!isFinishing) view.requestFocus() }
    }

    private fun addSectionTitle(title: String) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 12f
            letterSpacing = 0.15f
            setTextColor(Color.rgb(127, 151, 255))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(12))
        })
    }

    private fun addMessageCard(title: String, description: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(21), dp(22), dp(21))
            background = panelBackground()
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = description
            textSize = 16f
            setTextColor(Color.rgb(174, 183, 198))
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(card, fullWidthParams())
    }

    private fun badge(label: String, strong: Boolean): TextView = TextView(this).apply {
        text = label
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.04f
        setTextColor(if (strong) Color.rgb(7, 9, 15) else Color.rgb(205, 213, 230))
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(if (strong) Color.rgb(205, 213, 230) else Color.rgb(44, 51, 68))
        }
    }

    private fun addCard(
        title: String,
        description: String,
        actionLabel: String,
        badges: List<String> = emptyList(),
        dim: Boolean = false,
        onClick: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(15), dp(20), dp(15))
            background = panelBackground()
            isFocusable = true
            isClickable = true
            alpha = if (dim) 0.55f else 1f
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (badges.isNotEmpty()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, 0)
            }
            badges.take(8).forEachIndexed { i, label ->
                row.addView(badge(label, strong = i == 0), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            }
            texts.addView(row)
        }
        if (description.isNotBlank()) {
            texts.addView(TextView(this).apply {
                text = description
                textSize = 14f
                setTextColor(Color.rgb(153, 165, 185))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            })
        }
        card.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(TextView(this).apply {
            text = actionLabel
            textSize = 13f
            setTextColor(Color.rgb(185, 193, 208))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, 0, 0)
        })
        card.setOnFocusChangeListener { view, focused ->
            view.background = if (focused) focusedBackground() else panelBackground()
            view.scaleX = if (focused) 1.012f else 1f
            view.scaleY = if (focused) 1.012f else 1f
            if (focused) (view.tag as? String)?.let { focusedUrl = it }
        }
        content.addView(card, fullWidthParams(bottom = 10))
        return card
    }

    private fun action(label: String, primary: Boolean = false): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setTextColor(Color.WHITE)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        val idle = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (primary) Color.rgb(64, 87, 172) else Color.rgb(34, 39, 51))
            setStroke(dp(1), if (primary) Color.rgb(126, 150, 255) else Color.rgb(62, 71, 91))
        }
        // Focused: white with dark text, so the selected button is unmistakable from the sofa.
        val focusedLook = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.WHITE)
        }
        background = idle
        setOnFocusChangeListener { view, focused ->
            view.background = if (focused) focusedLook else idle
            (view as TextView).setTextColor(if (focused) Color.rgb(7, 9, 15) else Color.WHITE)
            view.scaleX = if (focused) 1.04f else 1f
            view.scaleY = if (focused) 1.04f else 1f
        }
    }

    private fun panelBackground() = GradientDrawable().apply {
        cornerRadius = dp(15).toFloat()
        setColor(Color.rgb(20, 24, 34))
        setStroke(dp(1), Color.rgb(41, 48, 64))
    }

    private fun focusedBackground() = GradientDrawable().apply {
        cornerRadius = dp(15).toFloat()
        setColor(Color.rgb(35, 44, 69))
        setStroke(dp(2), Color.rgb(126, 150, 255))
    }

    private fun fullWidthParams(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            if (top > 0) topMargin = dp(top)
            if (bottom > 0) bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
