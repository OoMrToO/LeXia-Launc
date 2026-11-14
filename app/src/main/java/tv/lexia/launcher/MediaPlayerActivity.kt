package tv.lexia.launcher

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Full-screen Media3 playback surface for user-provided media sources.
 *
 * This activity is intentionally separate from MainActivity so LeXia keeps its HOME/launcher
 * behavior while the media experience grows alongside it.
 */
class MediaPlayerActivity : Activity() {
    companion object {
        const val EXTRA_MEDIA_URI = "tv.lexia.launcher.extra.MEDIA_URI"
        const val EXTRA_TITLE = "tv.lexia.launcher.extra.TITLE"

        fun open(activity: Activity, mediaUri: String, title: String = "") {
            activity.startActivity(
                android.content.Intent(activity, MediaPlayerActivity::class.java)
                    .putExtra(EXTRA_MEDIA_URI, mediaUri)
                    .putExtra(EXTRA_TITLE, title)
            )
        }
    }

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        val rawUri = intent.getStringExtra(EXTRA_MEDIA_URI).orEmpty()
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https", "content", "file")) {
            Toast.makeText(this, "Geçerli bir medya adresi bulunamadı", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val playerView = PlayerView(this).apply {
            useController = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            keepScreenOn = true
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = intent.getStringExtra(EXTRA_TITLE)?.takeIf(String::isNotBlank)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(
                playerView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(root)

        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            playerView.player = exoPlayer
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@MediaPlayerActivity, "Oynatma başlatılamadı", Toast.LENGTH_LONG).show()
                }
            })
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
        playerView.requestFocus()
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
