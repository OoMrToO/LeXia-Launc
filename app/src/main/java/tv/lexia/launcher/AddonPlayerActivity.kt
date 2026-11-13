package tv.lexia.launcher

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder

/** Plays a direct HTTPS media URL supplied by the user or an authorized provider. */
@UnstableApi
class AddonPlayerActivity : Activity() {
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var seriesId = ""
    private var season = 0
    private var episode = 0
    private var durationMs = 0L
    private var resumeApplied = false
    private var networkRetryCount = 0
    private var pendingRetry: Runnable? = null
    private var externalSubtitleUri: Uri? = null
    private val playbackPrefs by lazy {
        getSharedPreferences("playback_preferences", MODE_PRIVATE)
    }

    private val progressTask = object : Runnable {
        override fun run() {
            val current = player ?: return
            if (current.isPlaying) saveProgress()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rawUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        season = intent.getIntExtra(EXTRA_SEASON, -1)
        episode = intent.getIntExtra(EXTRA_EPISODE, -1)
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull()
        if (uri == null || !uri.scheme.equals("https", true) || uri.host.isNullOrBlank() ||
            !uri.userInfo.isNullOrBlank() || seriesId.isBlank() || season < 0 || episode < 1) {
            Toast.makeText(this, "Geçerli bir HTTPS medya adresi gerekli.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 5_000
            setBackgroundColor(android.graphics.Color.BLACK)
            keepScreenOn = true
            isFocusable = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        setContentView(playerView)
        playerView.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (!::playerView.isInitialized) return

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(false)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val sourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)
        val current = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sourceFactory)
            .build()
        player = current
        playerView.player = current
        resumeApplied = false
        networkRetryCount = 0
        current.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    durationMs = current.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                    applyPreferredLanguages(current)
                    if (!resumeApplied) {
                        val saved = WatchProgress.get(this@AddonPlayerActivity, seriesId, season, episode)
                        if (saved != null && !saved.isComplete && saved.positionMs > 5_000) {
                            val target = if (durationMs > 0) {
                                saved.positionMs.coerceAtMost((durationMs - 1_000).coerceAtLeast(0))
                            } else saved.positionMs
                            current.seekTo(target)
                        }
                        resumeApplied = true
                    }
                    handler.removeCallbacks(progressTask)
                    handler.post(progressTask)
                } else if (playbackState == Player.STATE_ENDED) {
                    durationMs = current.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: durationMs
                    if (durationMs > 0) {
                        WatchProgress.save(
                            this@AddonPlayerActivity, seriesId, season, episode, durationMs, durationMs
                        )
                    }
                    handler.removeCallbacks(progressTask)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val statusCode = httpStatusCode(error)
                val isTemporaryNetworkError =
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                val isRetryableHttpError =
                    statusCode == HTTP_REQUEST_TIMEOUT ||
                        statusCode == HTTP_TOO_MANY_REQUESTS ||
                        statusCode in HTTP_SERVER_ERROR_RANGE

                if ((isTemporaryNetworkError || isRetryableHttpError) &&
                    networkRetryCount < MAX_NETWORK_RETRIES
                ) {
                    networkRetryCount++
                    val retryNumber = networkRetryCount
                    val resumePosition = current.currentPosition.coerceAtLeast(0L)
                    val delayMs = RETRY_BASE_DELAY_MS * retryNumber
                    Toast.makeText(
                        this@AddonPlayerActivity,
                        "Bağlantı kesildi. Yeniden deneniyor ($retryNumber/$MAX_NETWORK_RETRIES)…",
                        Toast.LENGTH_SHORT
                    ).show()

                    pendingRetry?.let(handler::removeCallbacks)
                    val retry = Runnable {
                        pendingRetry = null
                        if (player !== current || isFinishing || isDestroyed) return@Runnable
                        current.seekTo(resumePosition)
                        current.prepare()
                        current.playWhenReady = true
                    }
                    pendingRetry = retry
                    handler.postDelayed(retry, delayMs)
                    return
                }

                val message = when (statusCode) {
                    HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
                        "Yayın sunucusu erişim izni vermedi (HTTP $statusCode)."
                    HTTP_NOT_FOUND ->
                        "Medya adresi bulunamadı (HTTP 404)."
                    in HTTP_SERVER_ERROR_RANGE, HTTP_REQUEST_TIMEOUT, HTTP_TOO_MANY_REQUESTS ->
                        "Yayın sunucusu geçici olarak yanıt vermiyor (HTTP $statusCode). Daha sonra tekrar dene."
                    else ->
                        "Video oynatılamadı. Bağlantıyı ve medya adresini kontrol et."
                }
                Toast.makeText(this@AddonPlayerActivity, message, Toast.LENGTH_LONG).show()
                finish()
            }
        })
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        current.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        current.prepare()
        current.playWhenReady = true
    }

    @Deprecated("Deprecated in Android; retained for compatibility with the document picker result.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXTERNAL_SUBTITLE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val mimeType = subtitleMimeType(uri) ?: run {
            Toast.makeText(this, "Yalnızca SRT veya WebVTT altyazı dosyaları desteklenir.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        externalSubtitleUri = uri
        playbackPrefs.edit().putString(KEY_EXTERNAL_SUBTITLE_URI, uri.toString()).apply()
        applyExternalSubtitle(mimeType)
    }

    private fun subtitleMimeType(uri: Uri): String? {
        val name = uri.lastPathSegment.orEmpty().lowercase()
        val detected = contentResolver.getType(uri)?.lowercase().orEmpty()
        return when {
            name.endsWith(".srt") || detected.contains("subrip") -> MimeTypes.APPLICATION_SUBRIP
            name.endsWith(".vtt") || detected.contains("vtt") -> MimeTypes.TEXT_VTT
            else -> null
        }
    }

    private fun applyExternalSubtitle(mimeType: String? = null) {
        val current = player ?: return
        val uri = externalSubtitleUri ?: return
        val type = mimeType ?: subtitleMimeType(uri) ?: return
        val position = current.currentPosition.coerceAtLeast(0L)
        val wasPlaying = current.playWhenReady
        val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(type)
            .setLanguage("und")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val item = MediaItem.Builder()
            .setUri(intent.getStringExtra(EXTRA_URL).orEmpty())
            .setSubtitleConfigurations(listOf(subtitle))
            .build()
        current.setMediaItem(item, position)
        current.prepare()
        current.playWhenReady = wasPlaying
        Toast.makeText(this, "Harici altyazı eklendi.", Toast.LENGTH_SHORT).show()
    }

    private fun applyPreferredLanguages(current: ExoPlayer) {
        val audio = playbackPrefs.getString(KEY_AUDIO_LANGUAGE, null)
        val text = playbackPrefs.getString(KEY_TEXT_LANGUAGE, null)
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(audio)
            .setPreferredTextLanguage(text)
            .build()
    }

    private fun showLanguagePreference(title: String, key: String, audio: Boolean) {
        val choices = arrayOf("Otomatik", "Türkçe", "İngilizce", "Almanca", "Fransızca", "İspanyolca", "Arapça")
        val tags = arrayOf<String?>(null, "tr", "en", "de", "fr", "es", "ar")
        val selectedTag = playbackPrefs.getString(key, null)
        val selected = tags.indexOf(selectedTag).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(choices, selected) { dialog, which ->
                playbackPrefs.edit().putString(key, tags[which]).apply()
                player?.let { applyPreferredLanguages(it) }
                dialog.dismiss()
                Toast.makeText(this, "$title kaydedildi.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun chooseExternalSubtitle() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_EXTERNAL_SUBTITLE)
    }

    /** Android TV remotes commonly expose MENU or INFO for playback options. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val current = player
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                current?.let { if (it.isPlaying) it.pause() else it.play() }
                if (current != null) return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                current?.play()
                if (current != null) return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                current?.pause()
                if (current != null) return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                current?.let { seekBy(it, SEEK_INTERVAL_MS) }
                if (current != null) return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                current?.let { seekBy(it, -SEEK_INTERVAL_MS) }
                if (current != null) return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                showPlaybackMenu()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun httpStatusCode(error: PlaybackException): Int? {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode
            }
            cause = cause.cause
        }
        return null
    }

    private fun seekBy(current: ExoPlayer, offsetMs: Long) {
        val target = (current.currentPosition + offsetMs).coerceAtLeast(0L)
        val duration = current.duration
        current.seekTo(
            if (offsetMs > 0 && duration > 0 && duration != C.TIME_UNSET) {
                target.coerceAtMost(duration)
            } else {
                target
            }
        )
    }

    private fun showPlaybackMenu() {
        val current = player ?: return
        AlertDialog.Builder(this)
            .setTitle("Oynatma seçenekleri")
            .setItems(arrayOf(
                "Ses parçası seç",
                "Altyazı seç",
                "Harici altyazı yükle",
                "Tercih edilen ses dili",
                "Tercih edilen altyazı dili",
                "Oynatma hızı"
            )) { _, which ->
                when (which) {
                    0 -> showTrackDialog(current, C.TRACK_TYPE_AUDIO, "Ses dili")
                    1 -> showTrackDialog(current, C.TRACK_TYPE_TEXT, "Altyazı")
                    2 -> chooseExternalSubtitle()
                    3 -> showLanguagePreference("Tercih edilen ses dili", KEY_AUDIO_LANGUAGE, true)
                    4 -> showLanguagePreference("Tercih edilen altyazı dili", KEY_TEXT_LANGUAGE, false)
                    5 -> showPlaybackSpeedDialog(current)
                }
            }
            .show()
    }

    private fun showTrackDialog(current: ExoPlayer, trackType: Int, title: String) {
        val hasTracks = current.currentTracks.groups.any { it.type == trackType }
        if (!hasTracks) {
            Toast.makeText(this, "Bu içerikte kullanılabilir " + title.lowercase() + " bulunamadı.", Toast.LENGTH_SHORT).show()
            return
        }
        TrackSelectionDialogBuilder(this, title, current, trackType)
            .setShowDisableOption(trackType == C.TRACK_TYPE_TEXT)
            .build()
            .show()
    }

    private fun showPlaybackSpeedDialog(current: ExoPlayer) {
        val speeds = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        val labels = speeds.map { it.toString() + "x" }.toTypedArray()
        val selected = speeds.indices.minByOrNull {
            kotlin.math.abs(speeds[it] - current.playbackParameters.speed)
        } ?: 1
        AlertDialog.Builder(this)
            .setTitle("Oynatma hızı")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                current.playbackParameters = PlaybackParameters(speeds[which])
                dialog.dismiss()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun saveProgress() {
        val current = player ?: return
        val position = current.currentPosition.coerceAtLeast(0)
        val duration = current.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: durationMs
        if (position > 0) {
            durationMs = duration
            WatchProgress.save(this, seriesId, season, episode, position, duration)
        }
    }

    override fun onPause() {
        saveProgress()
        player?.pause()
        handler.removeCallbacks(progressTask)
        super.onPause()
    }

    override fun onStop() {
        saveProgress()
        handler.removeCallbacks(progressTask)
        pendingRetry?.let(handler::removeCallbacks)
        pendingRetry = null
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTask)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    companion object {
        private const val MAX_NETWORK_RETRIES = 3
        private const val RETRY_BASE_DELAY_MS = 1_500L
        private const val SEEK_INTERVAL_MS = 10_000L
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val HTTP_SERVER_ERROR_RANGE = 500..599

        const val EXTRA_URL = "media_url"
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SEASON = "season"
        const val EXTRA_EPISODE = "episode"
    }
}
