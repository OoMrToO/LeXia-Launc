package tv.lexia.launcher

import android.app.Activity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.concurrent.Executors

/**
 * The home screen's film picture turning into its trailer, without sound, after a few seconds
 * (Ayarlar › Görünüm › "Ana ekranda sessiz fragman"). Uses the trailer page's own streams
 * (YouTubeStreams, YouTubeChunkedDataSource); nothing is shown until the first frame is drawn,
 * and any failure simply leaves the picture.
 */
@UnstableApi
internal class HeroTrailer(private val activity: Activity, private val stage: FrameLayout) {

    private val finder = Executors.newSingleThreadExecutor()
    private var player: ExoPlayer? = null
    private var view: TextureView? = null
    private var token = 0

    /** Playing (or about to): the hero keeps this film until it ends. */
    var active = false
        private set

    /** Called when the trailer ends or fails, so the hero can carry on. */
    var onFinished: (() -> Unit)? = null

    fun start(youtubeId: String) {
        stop()
        if (youtubeId.isBlank()) return
        val mine = ++token
        active = true
        finder.execute {
            val source = try {
                YouTubeStreams.find(youtubeId, 720, TrailerDecoders::canDecode)
            } catch (_: Exception) {
                null
            }
            activity.runOnUiThread {
                if (mine != token || activity.isFinishing) return@runOnUiThread
                if (source == null) finish(mine) else play(source, mine)
            }
        }
    }

    private fun play(source: TrailerSource, mine: Int) {
        val texture = TextureView(activity).apply { alpha = 0f }
        stage.addView(texture, FrameLayout.LayoutParams(-1, -1))
        view = texture
        val exo = ExoPlayer.Builder(activity).build()
        player = exo
        exo.volume = 0f
        exo.setVideoTextureView(texture)
        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (mine == token) texture.animate().alpha(1f).setDuration(1400).start()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && mine == token) finish(mine)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (mine == token) finish(mine)
            }
        })
        val item = MediaItem.Builder().setUri(source.video)
        if (source.hls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        val sources = DefaultMediaSourceFactory(YouTubeChunkedDataSource.Factory(source.userAgent))
        // The picture is all that is needed (no sound is played, so a separate sound track is not loaded).
        exo.setMediaSource(sources.createMediaSource(item.build()))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun finish(mine: Int) {
        if (mine != token) return
        stop()
        onFinished?.invoke()
    }

    /** The picture comes back (softly), the player is let go. */
    fun stop() {
        token++
        active = false
        player?.release()
        player = null
        view?.let { v ->
            v.animate().cancel()
            v.animate().alpha(0f).setDuration(600).withEndAction { stage.removeView(v) }.start()
        }
        view = null
    }

    fun release() {
        stop()
        finder.shutdownNow()
    }
}
