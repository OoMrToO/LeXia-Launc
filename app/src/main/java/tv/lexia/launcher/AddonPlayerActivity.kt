package tv.lexia.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import androidx.media3.ui.CaptionStyleCompat
import android.util.TypedValue
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
    private var contentTitle = ""
    private var mediaType = "tv"
    private data class NextEpisode(val season: Int, val episode: Int, val title: String)
    private var nextEpisodes: List<NextEpisode> = emptyList()
    private var nextPromptShown = false
    private var nextDialog: AlertDialog? = null
    private var nextCountdown = 10
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
        contentTitle = intent.getStringExtra(EXTRA_CONTENT_TITLE).orEmpty()
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE).orEmpty().ifBlank { "tv" }
        nextEpisodes = readNextEpisodes(intent.getStringExtra(EXTRA_NEXT_EPISODES).orEmpty())
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
            setControllerHideOnTouch(true)
            setShowRewindButton(true)
            setShowFastForwardButton(true)
        }
        setContentView(playerView)
        playerView.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (!::playerView.isInitialized) return

        val providerHeaders = runCatching {
            val json = org.json.JSONObject(intent.getStringExtra(EXTRA_HEADERS).orEmpty())
            buildMap {
                val names = json.names() ?: org.json.JSONArray()
                for (i in 0 until names.length()) {
                    val key = names.optString(i)
                    val value = json.optString(key)
                    if (key.length <= 80 && value.length <= 2048 &&
                        !value.contains("\\r") && !value.contains("\\n")) put(key, value)
                }
            }
        }.getOrDefault(emptyMap())
        val subtitleHeaders = runCatching {
            val json = org.json.JSONObject(intent.getStringExtra(EXTRA_SUBTITLE_HEADERS).orEmpty())
            buildMap {
                val names = json.names() ?: org.json.JSONArray()
                for (i in 0 until names.length()) {
                    val key = names.optString(i)
                    val value = json.optString(key)
                    if (key.length <= 80 && value.length <= 2048 &&
                        !value.contains("\\r") && !value.contains("\\n")) put(key, value)
                }
            }
        }.getOrDefault(emptyMap())
        val requestHeaders = providerHeaders + subtitleHeaders
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(requestHeaders)
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
                    applyPreferredVideoQuality(current)
                    applySubtitleAppearance()
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
                    showNextEpisodePrompt()
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
        externalSubtitleUri = playbackPrefs.getString(KEY_EXTERNAL_SUBTITLE_URI, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val remoteSubtitle = intent.getStringExtra(EXTRA_SUBTITLE_URL)
            ?.takeIf { raw -> runCatching {
                val parsed = Uri.parse(raw)
                parsed.scheme.equals("https", true) && !parsed.host.isNullOrBlank() && parsed.userInfo.isNullOrBlank()
            }.getOrDefault(false) }
            ?.let(Uri::parse)
        val selectedSubtitle = remoteSubtitle ?: externalSubtitleUri
        val selectedSubtitleMime = intent.getStringExtra(EXTRA_SUBTITLE_MIME)
        val selectedSubtitleLanguage = intent.getStringExtra(EXTRA_SUBTITLE_LANGUAGE) ?: "und"
        val initialItem = buildMediaItem(url, selectedSubtitle, selectedSubtitleMime, selectedSubtitleLanguage)
        current.setMediaItem(initialItem)
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
        val item = buildMediaItem(intent.getStringExtra(EXTRA_URL).orEmpty(), uri, type)
        current.setMediaItem(item, position)
        current.prepare()
        current.playWhenReady = wasPlaying
        Toast.makeText(this, "Harici altyazı eklendi.", Toast.LENGTH_SHORT).show()
    }

    private fun buildMediaItem(
        url: String,
        subtitleUri: Uri?,
        knownMimeType: String? = null,
        subtitleLanguage: String = "und"
    ): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        val type = subtitleUri?.let { knownMimeType ?: subtitleMimeType(it) }
        if (subtitleUri != null && type != null) {
            val subtitle = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                .setMimeType(type)
                .setLanguage(subtitleLanguage)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            builder.setSubtitleConfigurations(listOf(subtitle))
        }
        return builder.build()
    }

    private fun applyPreferredLanguages(current: ExoPlayer) {
        val audio = playbackPrefs.getString(KEY_AUDIO_LANGUAGE, null)
        val text = playbackPrefs.getString(KEY_TEXT_LANGUAGE, null)
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(audio)
            .setPreferredTextLanguage(text)
            .build()
    }

    private fun applySubtitleAppearance() {
        val subtitleView = if (::playerView.isInitialized) playerView.subtitleView else null
        subtitleView?.setFixedTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            playbackPrefs.getFloat(KEY_SUBTITLE_SIZE, 22f).coerceIn(16f, 36f)
        )
        subtitleView?.setBottomPaddingFraction(
            playbackPrefs.getFloat(KEY_SUBTITLE_POSITION, 0.08f).coerceIn(0.02f, 0.28f)
        )
        subtitleView?.setStyle(CaptionStyleCompat(
            android.graphics.Color.WHITE,
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            null
        ))
    }

    private fun showSubtitleAppearanceDialog() {
        val labels = arrayOf("Küçük", "Normal", "Büyük", "Çok büyük")
        val sizes = floatArrayOf(18f, 22f, 27f, 32f)
        val selected = sizes.indices.minByOrNull {
            kotlin.math.abs(sizes[it] - playbackPrefs.getFloat(KEY_SUBTITLE_SIZE, 22f))
        } ?: 1
        AlertDialog.Builder(this)
            .setTitle("Altyazı boyutu")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                playbackPrefs.edit().putFloat(KEY_SUBTITLE_SIZE, sizes[which]).apply()
                applySubtitleAppearance()
                dialog.dismiss()
                showSubtitlePositionDialog()
            }
            .setNegativeButton("Kapat", null)
            .create().also { stylePlaybackDialog(it) }.show()
    }

    private fun showSubtitlePositionDialog() {
        val labels = arrayOf("Ekran altı", "Biraz yukarı", "Orta-alt")
        val values = floatArrayOf(0.08f, 0.15f, 0.23f)
        val selected = values.indices.minByOrNull {
            kotlin.math.abs(values[it] - playbackPrefs.getFloat(KEY_SUBTITLE_POSITION, 0.08f))
        } ?: 0
        AlertDialog.Builder(this)
            .setTitle("Altyazı konumu")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                playbackPrefs.edit().putFloat(KEY_SUBTITLE_POSITION, values[which]).apply()
                applySubtitleAppearance()
                dialog.dismiss()
            }
            .setNegativeButton("Kapat", null)
            .create().also { stylePlaybackDialog(it) }.show()
    }

    private fun showLanguagePreference(title: String, key: String) {
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
                "Altyazı boyutu ve konumu",
                "Oynatma hızı",
                "Görüntü kalitesi",
                "Otomatik sonraki bölüm: " + if (playbackPrefs.getBoolean(KEY_AUTO_NEXT, true)) "Açık" else "Kapalı"
            )) { _, which ->
                when (which) {
                    0 -> showTrackDialog(current, C.TRACK_TYPE_AUDIO, "Ses dili")
                    1 -> showTrackDialog(current, C.TRACK_TYPE_TEXT, "Altyazı")
                    2 -> chooseExternalSubtitle()
                    3 -> showLanguagePreference("Tercih edilen ses dili", KEY_AUDIO_LANGUAGE)
                    4 -> showLanguagePreference("Tercih edilen altyazı dili", KEY_TEXT_LANGUAGE)
                    5 -> showSubtitleAppearanceDialog()
                    6 -> showPlaybackSpeedDialog(current)
                    7 -> showVideoQualityDialog(current)
                    8 -> {
                        val enabled = !playbackPrefs.getBoolean(KEY_AUTO_NEXT, true)
                        playbackPrefs.edit().putBoolean(KEY_AUTO_NEXT, enabled).apply()
                        Toast.makeText(this, "Otomatik sonraki bölüm: " + if (enabled) "Açık" else "Kapalı", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .create().also { stylePlaybackDialog(it) }.show()
    }

    private fun stylePlaybackDialog(dialog: AlertDialog) {
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF171B23.toInt())
                    cornerRadius = resources.displayMetrics.density * 18f
                    setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), 0xFF343C49.toInt())
                }
            )
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFFB9A7FF.toInt())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFB9A7FF.toInt())
            dialog.listView?.let { list ->
                list.setBackgroundColor(0xFF171B23.toInt())
                list.divider = android.graphics.drawable.ColorDrawable(0xFF303744.toInt())
                list.dividerHeight = (resources.displayMetrics.density).toInt().coerceAtLeast(1)
            }
        }
    }

    private fun showVideoQualityDialog(current: ExoPlayer) {
        val labels = arrayOf("Otomatik (en yüksek)", "En fazla 480p", "En fazla 720p", "En fazla 1080p", "En fazla 4K")
        val values = arrayOf("auto", "480", "720", "1080", "2160")
        val selected = values.indexOf(playbackPrefs.getString(KEY_VIDEO_QUALITY, "auto")).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Görüntü kalitesi")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                playbackPrefs.edit().putString(KEY_VIDEO_QUALITY, values[which]).apply()
                applyPreferredVideoQuality(current)
                dialog.dismiss()
                Toast.makeText(this, "Görüntü kalitesi: " + labels[which], Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Kapat", null)
            .create().also { stylePlaybackDialog(it) }.show()
    }

    /** Applies a maximum video size; adaptive streams may use any resolution up to this limit. */
    private fun applyPreferredVideoQuality(current: ExoPlayer) {
        val builder = current.trackSelectionParameters.buildUpon()
        when (playbackPrefs.getString(KEY_VIDEO_QUALITY, "auto")) {
            "480" -> builder.setMaxVideoSize(854, 480)
            "720" -> builder.setMaxVideoSize(1280, 720)
            "1080" -> builder.setMaxVideoSize(1920, 1080)
            "2160" -> builder.setMaxVideoSize(3840, 2160)
            else -> builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        current.trackSelectionParameters = builder.build()
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
            .create().also { stylePlaybackDialog(it) }.show()
    }

    private fun readNextEpisodes(raw: String): List<NextEpisode> = runCatching {
        val array = org.json.JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val s = item.optInt("season", -1)
            val e = item.optInt("episode", -1)
            if (s < 0 || e < 1) null else NextEpisode(s, e, item.optString("title"))
        }.distinctBy { it.season to it.episode }.sortedWith(compareBy<NextEpisode> { it.season }.thenBy { it.episode })
    }.getOrDefault(emptyList())

    private fun nextEpisodeAfterCurrent(): NextEpisode? {
        val index = nextEpisodes.indexOfFirst { it.season == season && it.episode == episode }
        return if (index >= 0) nextEpisodes.getOrNull(index + 1) else null
    }

    private fun showNextEpisodePrompt() {
        if (nextPromptShown || !playbackPrefs.getBoolean(KEY_AUTO_NEXT, true)) return
        val next = nextEpisodeAfterCurrent() ?: return
        nextPromptShown = true
        nextCountdown = 10
        val dialog = AlertDialog.Builder(this)
            .setTitle("Sonraki bölüm · 10 sn")
            .setMessage("S${next.season} B${next.episode} · ${next.title.ifBlank { contentTitle }}")
            .setPositiveButton("Şimdi izle") { _, _ ->
                handler.removeCallbacks(nextCountdownTask)
                startNextEpisode(next)
            }
            .setNegativeButton("İptal", null)
            .create()
        nextDialog = dialog
        dialog.setOnDismissListener {
            handler.removeCallbacks(nextCountdownTask)
            if (nextDialog === dialog) nextDialog = null
        }
        dialog.show()
        handler.postDelayed(nextCountdownTask, 1_000L)
    }

    private val nextCountdownTask = object : Runnable {
        override fun run() {
            val dialog = nextDialog ?: return
            if (!dialog.isShowing) return
            nextCountdown--
            if (nextCountdown <= 0) {
                val next = nextEpisodeAfterCurrent()
                dialog.dismiss()
                if (next != null) startNextEpisode(next)
            } else {
                dialog.setTitle("Sonraki bölüm · ${nextCountdown} sn")
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    private fun startNextEpisode(next: NextEpisode) {
        val repository = intent.getStringExtra(EXTRA_PROVIDER_REPOSITORY).orEmpty()
        val providerId = intent.getStringExtra(EXTRA_PROVIDER_ID).orEmpty()
        val tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        if (repository.isBlank() || providerId.isBlank() || tmdbId <= 0) {
            Toast.makeText(this, "Sonraki bölüm için sağlayıcı bilgisi bulunamadı.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "S${next.season} B${next.episode} yükleniyor…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val addon = Addons.fetchManifest(repository)
                val provider = addon.scrapers.firstOrNull { it.id == providerId && it.enabled }
                    ?: error("Kayıtlı sağlayıcı artık kullanılamıyor.")
                val source = Addons.fetchScraperSource(addon, provider)
                val args = org.json.JSONObject()
                    .put("tmdbId", tmdbId)
                    .put("mediaType", mediaType)
                    .put("season", next.season)
                    .put("episode", next.episode)
                    .toString()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    AddonSandbox.evaluate(
                        this, source, repository, provider.id, args,
                        Addons.providerSettings(this, repository, provider.id)
                    ) { result ->
                        if (!result.success) {
                            Toast.makeText(this, "Sonraki bölüm bulunamadı: ${result.value}", Toast.LENGTH_LONG).show()
                            return@evaluate
                        }
                        val providerResult = runCatching { Addons.parseProviderResult(result.value) }.getOrNull()
                        val stream = providerResult?.streams?.firstOrNull()
                        if (stream == null) {
                            Toast.makeText(this, "Sonraki bölüm için yayın kaynağı bulunamadı.", Toast.LENGTH_LONG).show()
                            return@evaluate
                        }
                        val preferredLanguage = playbackPrefs.getString(KEY_TEXT_LANGUAGE, null)
                        val subtitle = providerResult.subtitles.firstOrNull { item ->
                            !preferredLanguage.isNullOrBlank() && item.language.startsWith(preferredLanguage, true)
                        } ?: providerResult.subtitles.firstOrNull()
                        launchNextEpisode(stream, subtitle, next)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) Toast.makeText(this, e.message ?: "Sonraki bölüm yüklenemedi.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun launchNextEpisode(
        stream: Addons.ProviderStream,
        subtitle: Addons.ProviderSubtitle?,
        next: NextEpisode
    ) {
        val nextIntent = Intent(this, AddonPlayerActivity::class.java).apply {
            putExtra(EXTRA_URL, stream.url)
            putExtra(EXTRA_HEADERS, org.json.JSONObject(stream.headers).toString())
            putExtra(EXTRA_SERIES_ID, seriesId)
            putExtra(EXTRA_SEASON, next.season)
            putExtra(EXTRA_EPISODE, next.episode)
            putExtra(EXTRA_PROVIDER_REPOSITORY, intent.getStringExtra(EXTRA_PROVIDER_REPOSITORY))
            putExtra(EXTRA_PROVIDER_ID, intent.getStringExtra(EXTRA_PROVIDER_ID))
            putExtra(EXTRA_TMDB_ID, intent.getIntExtra(EXTRA_TMDB_ID, 0))
            putExtra(EXTRA_MEDIA_TYPE, mediaType)
            putExtra(EXTRA_CONTENT_TITLE, contentTitle)
            putExtra(EXTRA_NEXT_EPISODES, intent.getStringExtra(EXTRA_NEXT_EPISODES).orEmpty())
            if (subtitle != null) {
                putExtra(EXTRA_SUBTITLE_URL, subtitle.url)
                putExtra(EXTRA_SUBTITLE_MIME, if (subtitle.format == "srt") MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                putExtra(EXTRA_SUBTITLE_LANGUAGE, subtitle.language.ifBlank { "und" })
                putExtra(EXTRA_SUBTITLE_HEADERS, org.json.JSONObject(subtitle.headers).toString())
            }
        }
        startActivity(nextIntent)
        finish()
    }

    private fun saveProgress() {
        val current = player ?: return
        val position = current.currentPosition.coerceAtLeast(0)
        val duration = current.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: durationMs
        if (position > 0) {
            durationMs = duration
            WatchProgress.save(this, seriesId, season, episode, position, duration, contentTitle, mediaType)
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
        handler.removeCallbacks(nextCountdownTask)
        nextDialog?.dismiss()
        nextDialog = null
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTask)
        handler.removeCallbacks(nextCountdownTask)
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
        private const val REQUEST_EXTERNAL_SUBTITLE = 4102
        private const val KEY_AUDIO_LANGUAGE = "preferred_audio_language"
        private const val KEY_TEXT_LANGUAGE = "preferred_text_language"
        private const val KEY_SUBTITLE_SIZE = "preferred_subtitle_size"
        private const val KEY_SUBTITLE_POSITION = "preferred_subtitle_position"
        private const val KEY_VIDEO_QUALITY = "preferred_video_quality"
        private const val KEY_AUTO_NEXT = "automatic_next_episode"
        private const val KEY_EXTERNAL_SUBTITLE_URI = "external_subtitle_uri"
        private val HTTP_SERVER_ERROR_RANGE = 500..599

        const val EXTRA_URL = "media_url"
        const val EXTRA_HEADERS = "media_headers"
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SEASON = "season"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_SUBTITLE_URL = "subtitle_url"
        const val EXTRA_SUBTITLE_MIME = "subtitle_mime"
        const val EXTRA_SUBTITLE_LANGUAGE = "subtitle_language"
        const val EXTRA_SUBTITLE_HEADERS = "subtitle_headers"
        const val EXTRA_PROVIDER_REPOSITORY = "provider_repository"
        const val EXTRA_PROVIDER_ID = "provider_id"
        const val EXTRA_TMDB_ID = "provider_tmdb_id"
        const val EXTRA_MEDIA_TYPE = "provider_media_type"
        const val EXTRA_CONTENT_TITLE = "content_title"
        const val EXTRA_NEXT_EPISODES = "next_episodes"
    }
}
