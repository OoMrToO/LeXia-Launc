package tv.lexia.launcher

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Plays a direct HTTPS media URL supplied by the user or an authorized provider. */
class AddonPlayerActivity : Activity() {
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var seriesId = ""
    private var season = 0
    private var episode = 0
    private var durationMs = 0L
    private var resumeApplied = false

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
        current.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    durationMs = current.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
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
                Toast.makeText(
                    this@AddonPlayerActivity,
                    "Video oynatılamadı veya biçim desteklenmiyor.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        })
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        current.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        current.prepare()
        current.playWhenReady = true
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
        playerView.player = null
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
        const val EXTRA_URL = "media_url"
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SEASON = "season"
        const val EXTRA_EPISODE = "episode"
    }
}
