package tv.lexia.launcher

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Full-screen Android TV playback surface.
 *
 * The launcher remains the app's home experience; media playback lives in its own
 * Activity so playback lifecycle and remote controls do not complicate MainActivity.
 * Open with an Intent data URI or [EXTRA_MEDIA_URI].
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_MEDIA_URI = "tv.lexia.launcher.extra.MEDIA_URI"
        const val EXTRA_MEDIA_TITLE = "tv.lexia.launcher.extra.MEDIA_TITLE"
        const val EXTRA_MIME_TYPE = "tv.lexia.launcher.extra.MIME_TYPE"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var mediaUri: Uri? = null
    private var mediaTitle: String? = null
    private var mimeType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawUri = intent.data?.toString()
            ?: intent.getStringExtra(EXTRA_MEDIA_URI)
        mediaUri = rawUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        mediaTitle = intent.getStringExtra(EXTRA_MEDIA_TITLE)
            ?: intent.getStringExtra(android.content.Intent.EXTRA_TITLE)
        mimeType = intent.getStringExtra(EXTRA_MIME_TYPE)

        if (mediaUri == null) {
            Toast.makeText(this, "Oynatılacak medya adresi bulunamadı", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = true
            controllerAutoShow = true
            controllerHideOnTouch = true
            controllerShowTimeoutMs = 5_000
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setKeepContentOnPlayerReset(true)
        }
        setContentView(playerView)
    }

    override fun onStart() {
        super.onStart()
        val uri = mediaUri ?: return
        if (player != null) return

        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val reason = error.localizedMessage ?: "Bilinmeyen oynatma hatası"
                    Toast.makeText(this@PlayerActivity, "Oynatma başarısız: $reason", Toast.LENGTH_LONG).show()
                }
            })

            val item = MediaItem.Builder()
                .setUri(uri)
                .apply { mimeType?.let(::setMimeType) }
                .build()
            exo.setMediaItem(item)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    override fun onStop() {
        playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
