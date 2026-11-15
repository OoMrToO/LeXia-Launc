package tv.lexia.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C as MediaC
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** A separate subtitle file that comes with a source. */
data class SubtitleTrack(
    val uri: String,
    val mimeType: String = "",
    val language: String = "",
    val label: String = ""
)

/**
 * The film player. The picture, sound and subtitles are Media3's; everything on top of the
 * picture is LeXia's own, so every button of the remote does one known thing:
 *
 *  - nothing on screen: OK pauses or plays, left/right jump 10 seconds, up/down show the controls;
 *  - controls on screen: the remote moves between the progress line and the buttons, Back hides them;
 *  - a list on screen (sound, subtitles, subtitle settings): Back closes it.
 *
 * Opened by the source list, or by another app with a video address.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    companion object {
        private const val EXTRA_URI = "media.uri"
        private const val EXTRA_TITLE = "media.title"
        private const val EXTRA_KEY = "media.key"
        private const val EXTRA_WATCH_KEY = "media.watch.key"
        private const val EXTRA_MARK_WATCHED = "media.mark.watched"
        private const val EXTRA_HEADERS = "media.headers"
        private const val EXTRA_SUBTITLE_URIS = "media.subtitle.uris"
        private const val EXTRA_SUBTITLE_MIMES = "media.subtitle.mimes"
        private const val EXTRA_SUBTITLE_LANGUAGES = "media.subtitle.languages"
        private const val EXTRA_SUBTITLE_LABELS = "media.subtitle.labels"
        private const val EXTRA_NEXT_TITLE = "media.next.title"
        private const val EXTRA_FROM_LIST = "media.from.list"

        /** The video could not be played and the viewer asked for another source. */
        const val RESULT_FAILED = RESULT_FIRST_USER
        /** The viewer (or the countdown at the end) asked for the next episode. */
        const val RESULT_NEXT = RESULT_FIRST_USER + 1

        private const val SAVE_EVERY_MS = 10_000L
        private const val TICK_MS = 500L
        private const val HIDE_AFTER_MS = 4_500L
        private const val SEEK_STEP_MS = 10_000L
        private const val SEEK_SETTLE_MS = 450L
        private const val NEXT_COUNTDOWN_S = 10
        private const val DELAY_STEP_MS = 100L
        private const val DELAY_LIMIT_MS = 60_000L

        private const val SUBTITLE_PADDING = 0.07f
        private const val SUBTITLE_PADDING_WITH_CONTROLS = 0.28f

        private val SUBTITLE_SIZES = floatArrayOf(0.040f, 0.0533f, 0.066f, 0.080f)
        private val SUBTITLE_SIZE_NAMES = listOf("Küçük", "Normal", "Büyük", "Çok büyük")

        /**
         * The intent that plays [mediaUri]. [mediaKey] is a lasting id of the film or episode (not
         * the video address): the place to continue from is kept under it. [nextTitle] is the
         * episode that follows, if any; the player then offers it and answers [RESULT_NEXT].
         */
        fun intent(
            context: Context,
            mediaUri: String,
            title: String,
            mediaKey: String = "",
            headers: Map<String, String> = emptyMap(),
            subtitles: List<SubtitleTrack> = emptyList(),
            watchKey: String = mediaKey,
            markWatchedOnCompletion: Boolean = true,
            nextTitle: String = "",
            fromSourceList: Boolean = false
        ): Intent {
            require(isSupportedUri(Uri.parse(mediaUri))) {
                "Yalnızca HTTP(S), content:// ve file:// video adresleri oynatılabilir."
            }
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URI, mediaUri)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_KEY, mediaKey)
                .putExtra(EXTRA_WATCH_KEY, watchKey)
                .putExtra(EXTRA_MARK_WATCHED, markWatchedOnCompletion)
                .putExtra(EXTRA_NEXT_TITLE, nextTitle)
                .putExtra(EXTRA_FROM_LIST, fromSourceList)
                .putExtra(EXTRA_HEADERS, Bundle().apply {
                    headers.forEach { (name, value) -> putString(name, value) }
                })
                .putStringArrayListExtra(EXTRA_SUBTITLE_URIS, ArrayList(subtitles.map { it.uri }))
                .putStringArrayListExtra(EXTRA_SUBTITLE_MIMES, ArrayList(subtitles.map { it.mimeType }))
                .putStringArrayListExtra(EXTRA_SUBTITLE_LANGUAGES, ArrayList(subtitles.map { it.language }))
                .putStringArrayListExtra(EXTRA_SUBTITLE_LABELS, ArrayList(subtitles.map { it.label }))
        }

        private fun isSupportedUri(uri: Uri): Boolean =
            uri.scheme.equals("https", true) ||
                uri.scheme.equals("http", true) ||
                uri.scheme.equals("content", true) ||
                uri.scheme.equals("file", true)
    }

    private class PanelRow(
        val label: String,
        val value: () -> String = { "" },
        val checked: Boolean = false,
        /** Left/right on the row: -1 or +1. */
        val step: ((Int) -> Unit)? = null,
        val click: (() -> Unit)? = null
    )

    private enum class Panel { NONE, AUDIO, SUBTITLES, SUBTITLE_SETTINGS }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var store: MediaPlaybackStore

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var chrome: FrameLayout
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var seekBar: PlayerSeekBar
    private lateinit var playButton: TextView
    private lateinit var audioButton: TextView
    private lateinit var subtitleButton: TextView
    private lateinit var subtitleSettingsButton: TextView
    private var nextButton: TextView? = null
    private lateinit var panelHost: FrameLayout
    private lateinit var cardHost: FrameLayout

    private var player: ExoPlayer? = null
    private var mediaKey = ""
    private var watchKey = ""
    private var markWatched = true
    private var mediaTitle = ""
    private var nextTitle = ""
    private var fromSourceList = false

    private var chromeVisible = false
    private var panel = Panel.NONE
    private var panelFocus = 0
    private var panelOpener: View? = null
    private var cardVisible = false
    /** A key whose press was used up here; its repeats and release are used up too. */
    private var swallowedKey = 0
    /** Where a run of left/right presses is heading, before the player is asked to go there. */
    private var pendingSeekMs = -1L
    /** Kept across leaving and returning to the player (Home, then back). */
    private var lastPositionMs = -1L
    private val subtitleDelayUs = AtomicLong(0L)
    /** Whether "no subtitles when the sound is already in that language" has been decided. */
    private var subtitleRuleApplied = false
    private var wantedSubtitleLanguage = ""
    private var trackWarningShown = false
    private var keptChoice: TrackSelectionParameters? = null
    private var ended = false
    private var countdown = 0

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            if (chromeVisible) handler.postDelayed(this, TICK_MS)
        }
    }
    private val saveLoop = object : Runnable {
        override fun run() {
            saveProgress()
            if (player != null) handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }
    private val hideChromeLater = Runnable {
        val p = player
        // Stays while paused, so the paused picture keeps its title and time.
        if (p != null && p.playWhenReady && pendingSeekMs < 0) hideChrome()
    }
    private val commitSeek = Runnable {
        val target = pendingSeekMs
        pendingSeekMs = -1L
        if (target >= 0) player?.seekTo(target)
        restartHideTimer()
    }
    private val countdownTick = object : Runnable {
        override fun run() {
            countdown--
            if (countdown <= 0) {
                finishWith(RESULT_NEXT)
            } else {
                countdownText?.text = countdownLine()
                handler.postDelayed(this, 1000L)
            }
        }
    }
    private var countdownText: TextView? = null

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            applySubtitleRule(tracks)
            warnAboutUnplayableTracks(tracks)
            updateTrackButtons(tracks)
            if (panel == Panel.AUDIO || panel == Panel.SUBTITLES) showPanel(panel, panelFocus)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayButton()
            if (isPlaying) restartHideTimer()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updatePlayButton()
            keepScreenOn()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            keepScreenOn()
            if (playbackState == Player.STATE_ENDED && !ended) {
                ended = true
                onEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player?.seekToDefaultPosition()
                player?.prepare()
                return
            }
            saveProgress()
            showError(error)
        }
    }

    // ---------- Life ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        store = MediaPlaybackStore(this)
        mediaKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        watchKey = intent.getStringExtra(EXTRA_WATCH_KEY).orEmpty()
        markWatched = intent.getBooleanExtra(EXTRA_MARK_WATCHED, true)
        mediaTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        nextTitle = intent.getStringExtra(EXTRA_NEXT_TITLE).orEmpty()
        fromSourceList = intent.getBooleanExtra(EXTRA_FROM_LIST, false)
        subtitleDelayUs.set(store.subtitleDelayMs(mediaKey) * 1000L)
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        if (!cardVisible) startPlayer()
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        pendingSeekMs = -1L
        saveProgress()
        releasePlayer()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The countdown to the next episode does not run on while the player is out of sight.
        if (ended && nextTitle.isNotBlank() && !isFinishing) finishWith(RESULT_OK)
        super.onStop()
    }

    private fun finishWith(result: Int) {
        setResult(result)
        finish()
    }

    // ---------- Player ----------

    private fun startPlayer() {
        if (player != null) return
        // Opened by LeXia with an extra, or by another app with a VIEW intent.
        val address = intent.getStringExtra(EXTRA_URI).orEmpty().ifBlank { intent.dataString.orEmpty() }
        val uri = Uri.parse(address)
        if (address.isBlank() || !isSupportedUri(uri)) {
            Glass.toast(this, "Geçersiz video adresi", long = true)
            finish()
            return
        }

        val headers = intent.getBundleExtra(EXTRA_HEADERS)?.let { bundle ->
            bundle.keySet().mapNotNull { key -> bundle.getString(key)?.let { key to it } }.toMap()
        }.orEmpty()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers)

        val subtitleHints = HashMap<String, SubtitleHint>()
        val item = buildMediaItem(uri, subtitleHints)
        val dataSources = SubtitleFixDataSource.Factory(DefaultDataSource.Factory(this, http), subtitleHints)

        val exo = ExoPlayer.Builder(this)
            .setRenderersFactory(DelayedSubtitleRenderers(this, subtitleDelayUs))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(MediaC.USAGE_MEDIA)
                    .setContentType(MediaC.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Back from Home: the sound and subtitles chosen during the film stay chosen.
        exo.trackSelectionParameters = keptChoice ?: languageChoice(exo)
        exo.addListener(listener)
        playerView.player = exo
        player = exo

        val resumeAt = if (lastPositionMs >= 0) lastPositionMs else store.resumePositionMs(mediaKey)
        if (resumeAt > 0) exo.setMediaItem(item, resumeAt) else exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = true
        ended = false

        handler.removeCallbacks(saveLoop)
        handler.postDelayed(saveLoop, SAVE_EVERY_MS)
        showChrome(playButton)
    }

    /** Ayarlar › Oynatma: which sound and which subtitles to start with. */
    private fun languageChoice(exo: ExoPlayer) = exo.trackSelectionParameters.buildUpon().apply {
        when (val audio = prefs.playAudioLang) {
            "orig" -> setPreferredAudioLanguage(null)
            "" -> setPreferredAudioLanguage(Lang.device())
            else -> setPreferredAudioLanguage(audio)
        }
        when (val text = prefs.playSubLang) {
            "off" -> {
                wantedSubtitleLanguage = ""
                setTrackTypeDisabled(MediaC.TRACK_TYPE_TEXT, true)
            }
            else -> {
                wantedSubtitleLanguage = text.ifBlank { Lang.device() }
                setPreferredTextLanguage(wantedSubtitleLanguage)
                // A film's only subtitle often carries no language at all.
                setSelectUndeterminedTextLanguage(true)
            }
        }
    }.build()

    private fun buildMediaItem(uri: Uri, hints: MutableMap<String, SubtitleHint>): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(mediaTitle).build())

        val uris = intent.getStringArrayListExtra(EXTRA_SUBTITLE_URIS).orEmpty()
        val mimes = intent.getStringArrayListExtra(EXTRA_SUBTITLE_MIMES).orEmpty()
        val languages = intent.getStringArrayListExtra(EXTRA_SUBTITLE_LANGUAGES).orEmpty()
        val labels = intent.getStringArrayListExtra(EXTRA_SUBTITLE_LABELS).orEmpty()
        val seen = HashSet<String>()
        val configurations = uris.mapIndexedNotNull { index, raw ->
            val subtitleUri = Uri.parse(raw)
            if (!isSupportedUri(subtitleUri) || !seen.add(subtitleUri.toString())) return@mapIndexedNotNull null
            val mime = mimes.getOrNull(index)?.takeIf { it.isNotBlank() } ?: SubtitleText.mimeFromAddress(raw)
            val rawLanguage = languages.getOrNull(index).orEmpty()
            val label = labels.getOrNull(index).orEmpty()
            // "tur", "Turkish", "TR" all become "tr", which is what the language choice looks for.
            val language = Lang.code(rawLanguage).ifBlank { Lang.code(label) }
            if (subtitleUri.scheme.equals("http", true) || subtitleUri.scheme.equals("https", true)) {
                hints[subtitleUri.toString()] = SubtitleHint(language, mime)
            }
            val config = MediaItem.SubtitleConfiguration.Builder(subtitleUri).setMimeType(mime)
            if (language.isNotBlank()) config.setLanguage(language)
            val shownLabel = label.ifBlank { rawLanguage }.trim()
            // A label that only repeats the language ("TR") is left out; the list names the language itself.
            if (shownLabel.isNotBlank() && (language.isBlank() || Lang.code(shownLabel) != language)) {
                config.setLabel(shownLabel.take(60))
            }
            config.build()
        }
        if (configurations.isNotEmpty()) builder.setSubtitleConfigurations(configurations)
        return builder.build()
    }

    private fun releasePlayer() {
        val p = player ?: return
        lastPositionMs = if (ended) -1L else p.currentPosition
        keptChoice = p.trackSelectionParameters
        p.removeListener(listener)
        playerView.player = null
        p.release()
        player = null
    }

    private fun saveProgress() {
        val p = player ?: return
        if (mediaKey.isBlank()) return
        val duration = p.duration.takeIf { it > 0L } ?: 0L
        val finished = ended || store.save(mediaKey, p.currentPosition, duration)
        if (finished) {
            store.clear(mediaKey)
            markAsWatched()
        }
    }

    private fun markAsWatched() {
        if (!markWatched || watchKey.isBlank()) return
        if (watchKey !in prefs.watched) prefs.watched = prefs.watched + watchKey
    }

    private fun keepScreenOn() {
        val p = player
        val on = p != null && p.playWhenReady &&
            (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun onEnded() {
        store.clear(mediaKey)
        markAsWatched()
        if (nextTitle.isBlank()) finishWith(RESULT_OK) else showNextCard()
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.playWhenReady = !p.playWhenReady
    }

    // ---------- Tracks ----------

    private class TrackEntry(val group: Tracks.Group, val index: Int, val format: Format)

    private fun tracksOf(type: Int): List<TrackEntry> {
        val p = player ?: return emptyList()
        val result = ArrayList<TrackEntry>()
        for (group in p.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (group.isTrackSupported(i)) result.add(TrackEntry(group, i, group.getTrackFormat(i)))
            }
        }
        return result
    }

    /**
     * Subtitles in the language the sound is already in are left off (Turkish sound, Turkish
     * subtitles). Decided once per film, as soon as the tracks are known; the viewer's own choice
     * in the subtitle list always wins afterwards.
     */
    private fun applySubtitleRule(tracks: Tracks) {
        if (subtitleRuleApplied || tracks.isEmpty) return
        subtitleRuleApplied = true
        if (!prefs.playSubSmart || wantedSubtitleLanguage.isBlank()) return
        val p = player ?: return
        val sound = tracks.groups.firstOrNull { it.type == MediaC.TRACK_TYPE_AUDIO && it.isSelected } ?: return
        val soundLanguage = (0 until sound.length).firstOrNull { sound.isTrackSelected(it) }
            ?.let { Lang.code(sound.getTrackFormat(it).language) }.orEmpty()
        if (soundLanguage.isNotBlank() && soundLanguage == wantedSubtitleLanguage) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(MediaC.TRACK_TYPE_TEXT, true)
                .build()
        }
    }

    private fun warnAboutUnplayableTracks(tracks: Tracks) {
        if (trackWarningShown || tracks.isEmpty) return
        fun unplayable(type: Int): Boolean {
            val groups = tracks.groups.filter { it.type == type }
            return groups.isNotEmpty() && groups.none { it.isSupported }
        }
        val message = when {
            unplayable(MediaC.TRACK_TYPE_VIDEO) -> "Bu TV bu kaynağın görüntü biçimini oynatamıyor. Başka bir kaynak dene."
            unplayable(MediaC.TRACK_TYPE_AUDIO) -> "Bu TV bu kaynağın ses biçimini çalamıyor. Başka bir kaynak dene."
            else -> return
        }
        trackWarningShown = true
        Glass.toast(this, message, long = true)
    }

    private fun updateTrackButtons(tracks: Tracks) {
        fun available(type: Int) = tracks.groups.any { it.type == type && it.isSupported }
        fun set(button: TextView, on: Boolean) {
            button.isEnabled = on
            button.isFocusable = on
            button.alpha = if (on) 1f else 0.4f
        }
        // One sound track leaves nothing to choose.
        set(audioButton, tracksOf(MediaC.TRACK_TYPE_AUDIO).size > 1)
        val subtitles = available(MediaC.TRACK_TYPE_TEXT)
        set(subtitleButton, subtitles)
        set(subtitleSettingsButton, subtitles)
    }

    private fun selectTrack(entry: TrackEntry) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(entry.group.mediaTrackGroup, entry.index))
            .setTrackTypeDisabled(entry.group.type, false)
            .build()
    }

    private fun turnSubtitlesOff() {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(MediaC.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(MediaC.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun applySubtitleLook() {
        playerView.subtitleView?.apply {
            setApplyEmbeddedFontSizes(false)
            setStyle(
                CaptionStyleCompat(
                    Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, ui.font(600)
                )
            )
            setFractionalTextSize(SUBTITLE_SIZES[prefs.playSubSize.coerceIn(0, SUBTITLE_SIZES.size - 1)])
            setBottomPaddingFraction(if (chromeVisible) SUBTITLE_PADDING_WITH_CONTROLS else SUBTITLE_PADDING)
        }
    }

    private fun delayText(): String {
        val ms = subtitleDelayUs.get() / 1000L
        if (ms == 0L) return "Yok"
        val seconds = String.format(Locale.forLanguageTag("tr-TR"), "%.1f", kotlin.math.abs(ms) / 1000.0)
        return if (ms > 0) "$seconds sn geç" else "$seconds sn erken"
    }

    private fun changeDelay(steps: Int) {
        val ms = (subtitleDelayUs.get() / 1000L + steps * DELAY_STEP_MS).coerceIn(-DELAY_LIMIT_MS, DELAY_LIMIT_MS)
        subtitleDelayUs.set(ms * 1000L)
        store.saveSubtitleDelay(mediaKey, ms)
    }

    // ---------- Screen ----------

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            descendantFocusability = FrameLayout.FOCUS_BLOCK_DESCENDANTS
        }
        root.addView(playerView, ui.fl(ui.match, ui.match))
        applySubtitleLook()

        chrome = FrameLayout(this)
        chrome.visibility = View.GONE
        chrome.alpha = 0f
        root.addView(chrome, ui.fl(ui.match, ui.match))

        val top = View(this)
        top.background = ui.gradient(GradientDrawable.Orientation.TOP_BOTTOM, 0xD9000000.toInt(), 0x00000000)
        chrome.addView(top, ui.fl(ui.match, ui.u(240), Gravity.TOP))
        val title = ui.text(mediaTitle, 36, C.TEXT, 600)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        chrome.addView(title, ui.fl(ui.match, ui.wrap, Gravity.TOP).apply {
            setMargins(ui.u(72), ui.u(52), ui.u(72), 0)
        })

        val bottom = ui.vbox()
        bottom.background = ui.gradient(GradientDrawable.Orientation.BOTTOM_TOP, 0xE6000000.toInt(), 0x00000000)
        bottom.setPadding(ui.u(72), ui.u(120), ui.u(72), ui.u(52))
        chrome.addView(bottom, ui.fl(ui.match, ui.wrap, Gravity.BOTTOM))

        val times = ui.hbox()
        positionText = ui.text("0:00", 24, C.TEXT, 500)
        durationText = ui.text("", 24, C.TEXT2, 500)
        seekBar = PlayerSeekBar(this, ui)
        seekBar.setOnKeyListener { _, code, event ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        nudge(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1, event.repeatCount)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) togglePlay()
                    true
                }
                else -> false
            }
        }
        times.addView(positionText, ui.ll(ui.wrap, ui.wrap))
        times.addView(seekBar, ui.ll(0, ui.wrap, left = 14, right = 14, weight = 1f))
        times.addView(durationText, ui.ll(ui.wrap, ui.wrap))
        bottom.addView(times, ui.ll(ui.match, ui.wrap))

        val buttons = ui.hbox()
        fun button(label: String, onClick: (TextView) -> Unit): TextView {
            val b = ui.pill(label, 60, 22)
            b.setOnClickListener { onClick(b) }
            buttons.addView(b)
            return b
        }
        playButton = button("Duraklat") { togglePlay() }
        audioButton = button("Ses") { openPanel(Panel.AUDIO, it) }
        subtitleButton = button("Altyazı") { openPanel(Panel.SUBTITLES, it) }
        subtitleSettingsButton = button("Altyazı ayarı") { openPanel(Panel.SUBTITLE_SETTINGS, it) }
        if (nextTitle.isNotBlank()) nextButton = button("Sonraki bölüm") { finishWith(RESULT_NEXT) }
        listOf(audioButton, subtitleButton, subtitleSettingsButton).forEach {
            it.isEnabled = false
            it.isFocusable = false
            it.alpha = 0.4f
        }
        bottom.addView(buttons, ui.ll(ui.match, ui.wrap, top = 14))

        panelHost = FrameLayout(this)
        panelHost.visibility = View.GONE
        root.addView(panelHost, ui.fl(ui.u(660), ui.wrap, Gravity.TOP or Gravity.END).apply {
            setMargins(0, ui.u(52), ui.u(72), ui.u(52))
        })

        cardHost = FrameLayout(this)
        cardHost.visibility = View.GONE
        root.addView(cardHost, ui.fl(ui.match, ui.match))

        setContentView(root)
    }

    private fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) + 500L) / 1000L
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    private fun updateProgress() {
        val p = player ?: return
        val duration = p.duration
        val seekable = duration > 0 && p.isCurrentMediaItemSeekable
        val position = if (pendingSeekMs >= 0) pendingSeekMs else p.currentPosition
        positionText.text = clock(position)
        if (duration > 0) {
            durationText.text = clock(duration)
            seekBar.progress = position.toFloat() / duration
            seekBar.buffered = p.bufferedPosition.toFloat() / duration
        } else {
            durationText.text = if (p.isCurrentMediaItemLive) "CANLI" else ""
            seekBar.progress = 0f
            seekBar.buffered = 0f
        }
        if (seekBar.isFocusable != seekable) {
            if (!seekable && seekBar.isFocused) playButton.requestFocus()
            seekBar.isFocusable = seekable
        }
    }

    private fun updatePlayButton() {
        val p = player ?: return
        playButton.text = if (p.playWhenReady) "Duraklat" else "Oynat"
    }

    /** Left/right: ten seconds a press; held down, the steps grow. */
    private fun nudge(direction: Int, repeat: Int) {
        val p = player ?: return
        val duration = p.duration
        if (duration <= 0 || !p.isCurrentMediaItemSeekable) return
        val step = when {
            repeat < 30 -> SEEK_STEP_MS
            repeat < 90 -> 30_000L
            else -> 60_000L
        }
        val from = if (pendingSeekMs >= 0) pendingSeekMs else p.currentPosition
        // Never onto the very end: that would finish the film by a slip of the thumb.
        pendingSeekMs = (from + direction * step).coerceIn(0L, (duration - 2_000L).coerceAtLeast(0L))
        updateProgress()
        handler.removeCallbacks(commitSeek)
        handler.postDelayed(commitSeek, SEEK_SETTLE_MS)
    }

    private fun showChrome(focus: View?) {
        if (cardVisible || panel != Panel.NONE) return
        if (!chromeVisible) {
            chromeVisible = true
            chrome.animate().cancel()
            chrome.visibility = View.VISIBLE
            chrome.animate().alpha(1f).setDuration(D.FAST).withEndAction(null).start()
            // Subtitles move up, clear of the controls.
            playerView.subtitleView?.setBottomPaddingFraction(SUBTITLE_PADDING_WITH_CONTROLS)
        }
        handler.removeCallbacks(tick)
        tick.run()
        updatePlayButton()
        if (focus != null && focus.isFocusable) focus.requestFocus() else if (chrome.findFocus() == null) playButton.requestFocus()
        restartHideTimer()
    }

    private fun hideChrome() {
        handler.removeCallbacks(hideChromeLater)
        if (!chromeVisible) return
        chromeVisible = false
        handler.removeCallbacks(tick)
        playerView.subtitleView?.setBottomPaddingFraction(SUBTITLE_PADDING)
        chrome.animate().cancel()
        chrome.animate().alpha(0f).setDuration(D.FAST).withEndAction {
            if (!chromeVisible) chrome.visibility = View.GONE
        }.start()
    }

    private fun restartHideTimer() {
        handler.removeCallbacks(hideChromeLater)
        if (chromeVisible) handler.postDelayed(hideChromeLater, HIDE_AFTER_MS)
    }

    // ---------- Lists (sound, subtitles, subtitle settings) ----------

    private fun openPanel(kind: Panel, opener: View) {
        panelOpener = opener
        val focus = when (kind) {
            Panel.AUDIO -> tracksOf(MediaC.TRACK_TYPE_AUDIO).indexOfFirst { it.group.isTrackSelected(it.index) }
            Panel.SUBTITLES -> tracksOf(MediaC.TRACK_TYPE_TEXT).indexOfFirst { it.group.isTrackSelected(it.index) } + 1
            else -> 0
        }
        hideChrome()
        showPanel(kind, focus.coerceAtLeast(0))
    }

    private fun showPanel(kind: Panel, focus: Int) {
        panel = kind
        panelFocus = focus
        val title: String
        var note = ""
        val rows = ArrayList<PanelRow>()
        when (kind) {
            Panel.AUDIO -> {
                title = "SES"
                tracksOf(MediaC.TRACK_TYPE_AUDIO).forEachIndexed { i, entry ->
                    rows.add(PanelRow(TrackNames.audio(entry.format, i + 1), checked = entry.group.isTrackSelected(entry.index)) {
                        panelFocus = i
                        selectTrack(entry)
                    })
                }
            }
            Panel.SUBTITLES -> {
                title = "ALTYAZI"
                val entries = tracksOf(MediaC.TRACK_TYPE_TEXT)
                rows.add(PanelRow("Kapalı", checked = entries.none { it.group.isTrackSelected(it.index) }) {
                    panelFocus = 0
                    turnSubtitlesOff()
                })
                entries.forEachIndexed { i, entry ->
                    rows.add(PanelRow(TrackNames.text(entry.format, i + 1), checked = entry.group.isTrackSelected(entry.index)) {
                        panelFocus = i + 1
                        selectTrack(entry)
                    })
                }
            }
            Panel.SUBTITLE_SETTINGS -> {
                title = "ALTYAZI AYARI"
                note = "Sağ ve sol tuşlarıyla değiştir. Yazı sesten önce çıkıyorsa geciktir, sonra çıkıyorsa öne al."
                rows.add(PanelRow("Boyut", value = { SUBTITLE_SIZE_NAMES[prefs.playSubSize.coerceIn(0, SUBTITLE_SIZES.size - 1)] }, step = { d ->
                    prefs.playSubSize = (prefs.playSubSize + d).coerceIn(0, SUBTITLE_SIZES.size - 1)
                    applySubtitleLook()
                }))
                rows.add(PanelRow("Zamanlama", value = { delayText() }, step = { d -> changeDelay(d) }))
                rows.add(PanelRow("Zamanlamayı sıfırla") {
                    subtitleDelayUs.set(0L)
                    store.saveSubtitleDelay(mediaKey, 0L)
                    showPanel(Panel.SUBTITLE_SETTINGS, 2)
                })
            }
            Panel.NONE -> return
        }
        if (rows.isEmpty()) {
            closePanel()
            return
        }

        val box = ui.vbox()
        box.background = ui.round(Color.argb(ui.panelAlpha(), 24, 24, 30), D.PANEL, 1)
        box.setPadding(ui.u(18), ui.u(30), ui.u(18), ui.u(22))
        box.addView(ui.label(title), ui.ll(ui.wrap, ui.wrap, left = 24, bottom = 14))
        val list = ui.vbox()
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(list)
        box.addView(scroll, ui.ll(ui.match, 0, weight = 1f).apply { height = ui.wrap })
        var toFocus: View? = null
        rows.forEachIndexed { i, row ->
            val view = panelRow(row)
            list.addView(view, ui.ll(ui.match, ui.u(76)))
            if (i == focus.coerceIn(0, rows.size - 1)) toFocus = view
        }
        if (note.isNotBlank()) {
            val text = ui.text(note, 20, C.TEXT3, 400)
            text.setLineSpacing(0f, 1.25f)
            box.addView(text, ui.ll(ui.match, ui.wrap, left = 24, top = 14, right = 24))
        }
        panelHost.removeAllViews()
        panelHost.addView(box, ui.fl(ui.match, ui.wrap))
        if (panelHost.visibility != View.VISIBLE) {
            panelHost.alpha = 0f
            panelHost.visibility = View.VISIBLE
            panelHost.animate().alpha(1f).setDuration(D.FAST).start()
        }
        toFocus?.requestFocus()
    }

    private fun panelRow(row: PanelRow): View {
        val line = ui.hbox()
        line.setPadding(ui.u(24), 0, ui.u(24), 0)
        line.isFocusable = true
        line.isClickable = row.click != null
        val label = ui.text(row.label, 25, C.TEXT, 500)
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        line.addView(label, ui.ll(0, ui.wrap, weight = 1f))
        val value = ui.text("", 23, C.TEXT2, 500)
        fun valueText() = if (row.step != null) "‹   ${row.value()}   ›" else row.value()
        value.text = valueText()
        line.addView(value, ui.ll(ui.wrap, ui.wrap, left = 16))
        val check = ImageView(this)
        if (row.checked) {
            check.setImageDrawable(getDrawable(R.drawable.ic_check)?.mutate())
            check.setColorFilter(C.TEXT)
            line.addView(check, ui.ll(ui.u(30), ui.u(30), left = 16))
        }
        line.setOnFocusChangeListener { _, focused ->
            line.background = if (focused) ui.round(Color.WHITE, D.PLATE) else null
            label.setTextColor(if (focused) C.INK else C.TEXT)
            value.setTextColor(if (focused) C.INK else C.TEXT2)
            check.setColorFilter(if (focused) C.INK else C.TEXT)
        }
        row.click?.let { click -> line.setOnClickListener { click() } }
        row.step?.let { step ->
            line.setOnKeyListener { _, code, event ->
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    step(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    value.text = valueText()
                }
                true
            }
        }
        return line
    }

    private fun closePanel() {
        if (panel == Panel.NONE) return
        panel = Panel.NONE
        panelHost.animate().cancel()
        panelHost.visibility = View.GONE
        panelHost.removeAllViews()
        val opener = panelOpener
        panelOpener = null
        showChrome(opener)
    }

    // ---------- Cards (next episode, error) ----------

    private fun card(label: String, title: String, detail: String, vararg actions: Pair<String, () -> Unit>): TextView {
        handler.removeCallbacks(hideChromeLater)
        if (panel != Panel.NONE) {
            panel = Panel.NONE
            panelHost.visibility = View.GONE
            panelHost.removeAllViews()
        }
        hideChrome()
        cardVisible = true
        cardHost.removeAllViews()
        cardHost.setBackgroundColor(Color.argb(150, 0, 0, 0))
        val box = ui.vbox()
        box.background = ui.round(Color.argb(240, 24, 24, 30), D.PANEL, 1)
        box.setPadding(ui.u(56), ui.u(48), ui.u(56), ui.u(48))
        box.addView(ui.label(label, C.ACCENT))
        val heading = ui.text(title, 40, C.TEXT, 600)
        heading.maxLines = 2
        heading.ellipsize = TextUtils.TruncateAt.END
        box.addView(heading, ui.ll(ui.match, ui.wrap, top = 14))
        val body = ui.text(detail, 25, C.TEXT2, 400)
        body.setLineSpacing(0f, 1.25f)
        box.addView(body, ui.ll(ui.match, ui.wrap, top = 14))
        val row = ui.hbox()
        var first: View? = null
        actions.forEachIndexed { i, (name, run) ->
            val b = ui.pill(name, 68, 24, primary = i == 0)
            b.setOnClickListener { run() }
            row.addView(b)
            if (i == 0) first = b
        }
        box.addView(row, ui.ll(ui.match, ui.wrap, top = 34))
        cardHost.addView(box, ui.fl(ui.u(900), ui.wrap, Gravity.CENTER))
        cardHost.alpha = 0f
        cardHost.visibility = View.VISIBLE
        cardHost.animate().alpha(1f).setDuration(D.MED).start()
        first?.requestFocus()
        return body
    }

    private fun hideCard() {
        cardVisible = false
        handler.removeCallbacks(countdownTick)
        countdownText = null
        cardHost.animate().cancel()
        cardHost.visibility = View.GONE
        cardHost.removeAllViews()
    }

    private fun countdownLine() = "$countdown saniye sonra başlar."

    private fun showNextCard() {
        countdown = NEXT_COUNTDOWN_S
        countdownText = card(
            "SIRADAKİ BÖLÜM", nextTitle, countdownLine(),
            "Şimdi oynat" to { finishWith(RESULT_NEXT) },
            "Kapat" to { finishWith(RESULT_OK) }
        )
        handler.removeCallbacks(countdownTick)
        handler.postDelayed(countdownTick, 1000L)
    }

    private fun showError(error: PlaybackException) {
        val code = error.errorCode
        val http = generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val reason = when {
            http != null -> "Kaynak videoyu vermedi (hata $http). Bağlantının süresi dolmuş olabilir."
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Kaynağa bağlanılamadı. İnternet bağlantını kontrol et ya da başka bir kaynak dene."
            code == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                "Bu kaynak güvenli olmayan (http) bir adres kullanıyor; LeXia yalnızca güvenli adresleri oynatır."
            code in 2000..2999 -> "Videoya ulaşılamadı."
            code in 3000..3999 -> "Dosya tanınmadı ya da bozuk."
            code in 4000..4999 -> "Bu TV bu videonun biçimini oynatamıyor."
            code in 5000..5999 -> "Ses başlatılamadı."
            else -> "Beklenmeyen bir sorun oldu (${error.errorCodeName})."
        }
        val retry = "Yeniden dene" to {
            hideCard()
            val p = player
            if (p == null) startPlayer() else {
                p.prepare()
                p.playWhenReady = true
                showChrome(playButton)
            }
        }
        if (fromSourceList) {
            card("OYNATILAMADI", mediaTitle.ifBlank { "Video" }, reason, "Başka kaynak seç" to { finishWith(RESULT_FAILED) }, retry)
        } else {
            card("OYNATILAMADI", mediaTitle.ifBlank { "Video" }, reason, retry, "Kapat" to { finish() })
        }
    }

    // ---------- Remote ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action != KeyEvent.ACTION_DOWN) {
            if (code == swallowedKey) {
                if (event.action == KeyEvent.ACTION_UP) swallowedKey = 0
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (code == swallowedKey && event.repeatCount > 0) {
            // A held left/right keeps jumping; other held keys do nothing more.
            if (!cardVisible && panel == Panel.NONE) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> nudge(-1, event.repeatCount)
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> nudge(1, event.repeatCount)
                }
            }
            return true
        }

        fun used(): Boolean {
            swallowedKey = code
            return true
        }

        if (cardVisible) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                finishWith(if (ended) RESULT_OK else RESULT_CANCELED)
                return used()
            }
            return super.dispatchKeyEvent(event)
        }

        // The remote's own play buttons work whatever is on screen.
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                togglePlay()
                if (panel == Panel.NONE) showChrome(null)
                return used()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.let { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.playWhenReady = true }
                return used()
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.playWhenReady = false
                if (panel == Panel.NONE) showChrome(null)
                return used()
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                finish()
                return used()
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (nextTitle.isNotBlank()) finishWith(RESULT_NEXT)
                return used()
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (panel == Panel.NONE) {
                    showChrome(seekBar)
                    nudge(if (code == KeyEvent.KEYCODE_MEDIA_REWIND) -1 else 1, 0)
                }
                return used()
            }
        }

        if (panel != Panel.NONE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                closePanel()
                return used()
            }
            return super.dispatchKeyEvent(event)
        }

        if (!chromeVisible) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> {
                    togglePlay()
                    showChrome(playButton)
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    showChrome(seekBar)
                    nudge(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1, 0)
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO ->
                    showChrome(playButton)
                else -> return super.dispatchKeyEvent(event)
            }
            return used()
        }

        restartHideTimer()
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            hideChrome()
            return used()
        }
        return super.dispatchKeyEvent(event)
    }
}
