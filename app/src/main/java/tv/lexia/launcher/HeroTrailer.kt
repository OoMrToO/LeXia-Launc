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
import androidx.media3.exoplayer.source.MergingMediaSource
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

    /** Called with true when the trailer's first frame shows, false when the picture comes back. */
    var onShowing: ((Boolean) -> Unit)? = null

    /** The sound comes up softly a few seconds after the picture (and goes softly when it stops). */
    var sound = false
    private var volumeRise: android.animation.ValueAnimator? = null
    private val soundUp = Runnable {
        val p = player ?: return@Runnable
        volumeRise?.cancel()
        volumeRise = android.animation.ValueAnimator.ofFloat(p.volume, SOUND_LEVEL).apply {
            duration = 2_500
            addUpdateListener { player?.volume = it.animatedValue as Float }
            start()
        }
    }

    /** The trailer playing now (its YouTube key) and how far in, in seconds; null when none is on screen. */
    fun where(): Pair<String, Int>? {
        val p = player ?: return null
        if (view == null || current.isBlank()) return null
        return current to (p.currentPosition / 1000L).toInt()
    }

    private var current = ""

    fun start(youtubeId: String) {
        stop()
        if (youtubeId.isBlank()) return
        current = youtubeId
        val mine = ++token
        active = true
        finder.execute {
            val source = try {
                // 720p: sharp enough under the dimmed home screen, light for weaker TVs (1080p made them stutter).
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
                if (mine == token) {
                    texture.animate().alpha(1f).setDuration(1400).start()
                    onShowing?.invoke(true)
                    if (sound && withSound) stage.postDelayed(soundUp, SOUND_AFTER_MS)
                }
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
        // Without sound only the picture is loaded; with it, YouTube's separate sound track too.
        val picture = sources.createMediaSource(item.build())
        withSound = sound && (source.audio != null || source.hls)
        exo.setMediaSource(
            if (withSound && source.audio != null) MergingMediaSource(picture, sources.createMediaSource(MediaItem.fromUri(source.audio)))
            else picture
        )
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
        stage.removeCallbacks(soundUp)
        volumeRise?.cancel()
        volumeRise = null
        val old = player
        player = null
        // Sound playing: it fades out first, then the player goes.
        if (old != null && old.volume > 0.02f) {
            android.animation.ValueAnimator.ofFloat(old.volume, 0f).apply {
                duration = 450
                addUpdateListener { old.volume = it.animatedValue as Float }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = old.release()
                })
                start()
            }
        } else {
            old?.release()
        }
        if (view != null) onShowing?.invoke(false)
        view?.let { v ->
            v.animate().cancel()
            v.animate().alpha(0f).setDuration(600).withEndAction { stage.removeView(v) }.start()
        }
        view = null
    }

    fun release() {
        // Leaving for good: no fade, the player goes at once.
        player?.volume = 0f
        stop()
        finder.shutdownNow()
    }

    private var withSound = false

    private companion object {
        const val SOUND_AFTER_MS = 4_000L
        const val SOUND_LEVEL = 0.7f
    }
}
