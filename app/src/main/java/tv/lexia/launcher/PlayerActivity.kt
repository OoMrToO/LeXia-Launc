package tv.lexia.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder

/** External subtitle track passed by a source/plugin resolver. */
data class SubtitleTrack(
    val uri: String,
    val mimeType: String = "",
    val language: String = "",
    val label: String = ""
)

/**
 * Full-screen Android TV player. It is View-based so it can be launched by LeXia's existing
 * Activities without introducing a second UI framework into the launcher.
 *
 * The source resolver/plugin layer will call [open] once it has selected a playable stream.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    companion object {
        private const val EXTRA_URI = "media.uri"
        private const val EXTRA_TITLE = "media.title"
        private const val EXTRA_KEY = "media.key"
        private const val EXTRA_HEADERS = "media.headers"
        private const val EXTRA_SUBTITLE_URIS = "media.subtitle.uris"
        private const val EXTRA_SUBTITLE_MIMES = "media.subtitle.mimes"
        private const val EXTRA_SUBTITLE_LANGUAGES = "media.subtitle.languages"
        private const val EXTRA_SUBTITLE_LABELS = "media.subtitle.labels"
        private const val SAVE_INTERVAL_MS = 10_000L

        /**
         * Start playback for an HTTP(S), content:// or file:// stream.
         *
         * [mediaKey] must be a stable movie/episode ID, not the stream URL. Request headers and
         * sidecar subtitles are supplied by the future Nuvio-compatible source resolver.
         */
        fun open(
            context: Context,
            mediaUri: String,
            title: String,
            mediaKey: String = "",
            headers: Map<String, String> = emptyMap(),
            subtitles: List<SubtitleTrack> = emptyList()
        ) {
            require(isSupportedUri(Uri.parse(mediaUri))) {
                "Only HTTP(S), content:// and file:// media URLs are supported."
            }

            val intent = Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URI, mediaUri)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_KEY, mediaKey)
                .putExtra(EXTRA_HEADERS, Bundle().apply {
                    headers.forEach { (name, value) -> putString(name, value) }
                })
                .putStringArrayListExtra(EXTRA_SUBTITLE_URIS, ArrayList(subtitles.map { it.uri }))
                .putStringArrayListExtra(EXTRA_SUBTITLE_MIMES, ArrayList(subtitles.map { it.mimeType }))
                .putStringArrayListExtra(EXTRA_SUBTITLE_LANGUAGES, ArrayList(subtitles.map { it.language }))
                .putStringArrayListExtra(EXTRA_SUBTITLE_LABELS, ArrayList(subtitles.map { it.label }))

            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        private fun isSupportedUri(uri: Uri): Boolean =
            uri.scheme.equals("https", true) ||
                uri.scheme.equals("http", true) ||
                uri.scheme.equals("content", true) ||
                uri.scheme.equals("file", true)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var audioButton: TextView
    private lateinit var subtitleButton: TextView
    private lateinit var playbackStore: MediaPlaybackStore
    private var player: ExoPlayer? = null
    private var mediaKey = ""
    private var mediaTitle = ""

    private val saveProgressRunnable = object : Runnable {
        override fun run() {
            saveProgress()
            if (player != null) mainHandler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            updateTrackButtons(tracks)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                playbackStore.clear(mediaKey)
                if (mediaKey.isNotBlank()) {
                    val prefs = Prefs(this@PlayerActivity)
                    prefs.watched = prefs.watched + mediaKey
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(
                this@PlayerActivity,
                "Video oynatılamadı: ${error.errorCodeName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        mediaTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "LeXia" }
        playbackStore = MediaPlaybackStore(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildPlayerUi()
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(saveProgressRunnable)
        saveProgress()
        releasePlayer()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            showAudioTracks()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildPlayerUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
        }

        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 5_000
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            playerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xD9000000.toInt(), 0x00000000)
            )
        }

        titleView = TextView(this).apply {
            text = mediaTitle
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        topBar.addView(
            titleView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        audioButton = actionButton("Ses")
        audioButton.isEnabled = false
        audioButton.setOnClickListener { showAudioTracks() }
        topBar.addView(audioButton, layoutButtonParams())

        subtitleButton = actionButton("Altyazı")
        subtitleButton.isEnabled = false
        subtitleButton.setOnClickListener { showSubtitleTracks() }
        topBar.addView(subtitleButton, layoutButtonParams())

        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        setContentView(root)
    }

    private fun initializePlayer() {
        val rawUri = intent.getStringExtra(EXTRA_URI).orEmpty()
        val uri = Uri.parse(rawUri)
        if (!isSupportedPlaybackUri(uri)) {
            Toast.makeText(this, "Geçersiz video adresi", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val requestHeaders = BundleCompat.stringMap(intent.getBundleExtra(EXTRA_HEADERS))
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(requestHeaders)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val selector = DefaultTrackSelector(this).apply {
            val language = resources.configuration.locales.get(0)?.language
            if (!language.isNullOrBlank()) {
                setParameters(
                    buildUponParameters()
                        .setPreferredAudioLanguage(language)
                        .setPreferredTextLanguage(language)
                )
            }
        }
        player = ExoPlayer.Builder(this)
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { exo ->
                playerView.player = exo
                exo.addListener(playerListener)
                val resumePosition = playbackStore.resumePositionMs(mediaKey)
                exo.setMediaItem(buildMediaItem(uri), resumePosition)
                exo.prepare()
                exo.playWhenReady = true
            }

        mainHandler.removeCallbacks(saveProgressRunnable)
        mainHandler.postDelayed(saveProgressRunnable, SAVE_INTERVAL_MS)
    }

    private fun buildMediaItem(uri: Uri): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(mediaTitle)
                    .build()
            )

        val uris = intent.getStringArrayListExtra(EXTRA_SUBTITLE_URIS).orEmpty()
        val mimes = intent.getStringArrayListExtra(EXTRA_SUBTITLE_MIMES).orEmpty()
        val languages = intent.getStringArrayListExtra(EXTRA_SUBTITLE_LANGUAGES).orEmpty()
        val labels = intent.getStringArrayListExtra(EXTRA_SUBTITLE_LABELS).orEmpty()
        val configurations = uris.mapIndexedNotNull { index, raw ->
            val subtitleUri = Uri.parse(raw)
            if (!isSupportedPlaybackUri(subtitleUri)) return@mapIndexedNotNull null
            val mime = mimes.getOrNull(index)?.takeIf { it.isNotBlank() }
                ?: guessSubtitleMimeType(raw)
            val config = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                .setMimeType(mime)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            languages.getOrNull(index)?.takeIf { it.isNotBlank() }?.let(config::setLanguage)
            labels.getOrNull(index)?.takeIf { it.isNotBlank() }?.let(config::setLabel)
            config.build()
        }
        if (configurations.isNotEmpty()) builder.setSubtitleConfigurations(configurations)
        return builder.build()
    }

    private fun showAudioTracks() = showTrackDialog(C.TRACK_TYPE_AUDIO, "Ses parçaları")

    private fun showSubtitleTracks() = showTrackDialog(C.TRACK_TYPE_TEXT, "Altyazılar")

    private fun showTrackDialog(trackType: Int, title: String) {
        val currentPlayer = player ?: return
        val available = currentPlayer.currentTracks.groups.any {
            it.type == trackType && it.isSupported
        }
        if (!available) {
            Toast.makeText(this, "Bu kaynakta uygun parça bulunamadı", Toast.LENGTH_SHORT).show()
            return
        }
        TrackSelectionDialogBuilder(this, title, currentPlayer, trackType)
            .setShowDisableOption(trackType == C.TRACK_TYPE_TEXT)
            .build()
            .show()
    }

    private fun updateTrackButtons(tracks: Tracks) {
        val audioAvailable = tracks.groups.any {
            it.type == C.TRACK_TYPE_AUDIO && it.isSupported
        }
        val textAvailable = tracks.groups.any {
            it.type == C.TRACK_TYPE_TEXT && it.isSupported
        }
        audioButton.isEnabled = audioAvailable
        subtitleButton.isEnabled = textAvailable
        audioButton.alpha = if (audioAvailable) 1f else 0.45f
        subtitleButton.alpha = if (textAvailable) 1f else 0.45f
    }

    private fun saveProgress() {
        val currentPlayer = player ?: return
        if (mediaKey.isBlank()) return
        val duration = currentPlayer.duration.takeIf { it > 0L } ?: 0L
        playbackStore.save(mediaKey, currentPlayer.currentPosition, duration)
    }

    private fun releasePlayer() {
        player?.removeListener(playerListener)
        playerView.player = null
        player?.release()
        player = null
    }

    private fun actionButton(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setPadding(dp(18), dp(11), dp(18), dp(11))
        background = GradientDrawable().apply {
            setColor(0xB3222630.toInt())
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), 0x66FFFFFF)
        }
        setOnFocusChangeListener { view, focused ->
            view.scaleX = if (focused) 1.06f else 1f
            view.scaleY = if (focused) 1.06f else 1f
            view.alpha = if (focused) 1f else 0.88f
        }
    }

    private fun layoutButtonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        marginStart = dp(10)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun isSupportedPlaybackUri(uri: Uri): Boolean =
        uri.scheme.equals("https", true) ||
            uri.scheme.equals("http", true) ||
            uri.scheme.equals("content", true) ||
            uri.scheme.equals("file", true)

    private fun guessSubtitleMimeType(rawUri: String): String {
        val extension = rawUri.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (extension) {
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            "ass", "ssa" -> "text/x-ssa"
            "ttml", "xml" -> "application/ttml+xml"
            else -> "text/vtt"
        }
    }

    private object BundleCompat {
        fun stringMap(bundle: Bundle?): Map<String, String> {
            if (bundle == null) return emptyMap()
            return bundle.keySet().mapNotNull { key ->
                bundle.getString(key)?.let { key to it }
            }.toMap()
        }
    }
}
