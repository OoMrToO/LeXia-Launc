package tv.lexia.launcher

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView

/** Plays a direct HTTPS media URL supplied by the user or an authorized provider.\n * Playback position is persisted locally so the episode can resume after leaving the player.\n */
class AddonPlayerActivity : Activity() {
    private lateinit var video: VideoView
    private val handler = Handler(Looper.getMainLooper())
    private var seriesId = ""
    private var season = 0
    private var episode = 0
    private var lastPosition = 0L
    private var duration = 0L

    private val progressTask = object : Runnable {
        override fun run() {
            if (::video.isInitialized && video.isPlaying) saveProgress()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        season = intent.getIntExtra(EXTRA_SEASON, 0)
        episode = intent.getIntExtra(EXTRA_EPISODE, 0)
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() ||
            !uri.userInfo.isNullOrBlank() || seriesId.isBlank() || episode < 1) {
            finish()
            return
        }

        video = VideoView(this)
        val controls = MediaController(this)
        controls.setAnchorView(video)
        video.setMediaController(controls)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1))
        })
        video.setOnPreparedListener { media ->
            duration = media.duration.toLong().coerceAtLeast(0)
            val saved = WatchProgress.get(this, seriesId, season, episode)
            if (saved != null && !saved.isComplete && saved.positionMs > 5_000) {
                video.seekTo(saved.positionMs.coerceAtMost((duration - 1).coerceAtLeast(0)).toInt())
            }
            video.start()
            handler.post(progressTask)
        }
        video.setOnCompletionListener {
            duration = video.duration.toLong().coerceAtLeast(duration)
            WatchProgress.save(this, seriesId, season, episode, duration, duration)
            handler.removeCallbacks(progressTask)
        }
        video.setOnErrorListener { _, _, _ ->
            android.widget.Toast.makeText(this, "Video oynatılamadı veya biçim desteklenmiyor.", android.widget.Toast.LENGTH_LONG).show()
            finish()
            true
        }
        video.setVideoURI(uri)
    }

    private fun saveProgress() {
        if (!::video.isInitialized) return
        lastPosition = video.currentPosition.toLong().coerceAtLeast(0)
        duration = video.duration.toLong().coerceAtLeast(0)
        WatchProgress.save(this, seriesId, season, episode, lastPosition, duration)
    }

    override fun onPause() {
        if (::video.isInitialized) {
            saveProgress()
            video.pause()
        }
        handler.removeCallbacks(progressTask)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTask)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "media_url"
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SEASON = "season"
        const val EXTRA_EPISODE = "episode"
    }
}
