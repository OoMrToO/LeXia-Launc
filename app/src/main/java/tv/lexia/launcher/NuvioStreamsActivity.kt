package tv.lexia.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.nuvio.tv.LeXiaStreamActivity
import com.nuvio.tv.ui.screens.stream.StreamPlaybackInfo
import java.util.concurrent.Executors

/**
 * Nuvio's own stream screen (the nuvio module's LeXiaStreamActivity shows NuvioTV's StreamScreen
 * unchanged) for one film or episode of LeXia's. The source the viewer picks plays in LeXia's
 * player; what the player answers goes back to the screen that opened this one.
 */
internal class NuvioStreamsActivity : LeXiaStreamActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var addonSubtitles: List<MediaSubtitle> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Subtitle add-ons (OpenSubtitles and the like) are asked while the sources are searched.
        val type = intent.getStringExtra("contentType").orEmpty()
        val videoId = intent.getStringExtra("videoId").orEmpty()
        val app = applicationContext
        worker.execute {
            try {
                addonSubtitles = MediaAddonRepository(app).fetchSubtitles(type, videoId)
            } catch (e: Exception) {
                Log.w("LeXiaSources", "Subtitle search failed: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun play(info: StreamPlaybackInfo) {
        val url = info.url
        if (url.isNullOrBlank() || info.isTorrent || info.isExternal) {
            Toast.makeText(this, "Bu kaynak LeXia'da oynatılamıyor. Başka bir kaynak seçin.", Toast.LENGTH_LONG).show()
            return
        }
        val prefs = Prefs(this)
        val wanted = prefs.playSubLang.takeIf { it != "off" }.orEmpty().ifBlank { Lang.device() }
        fun rank(s: MediaSubtitle) = if (Lang.code(s.language).ifBlank { Lang.code(s.label) } == wanted) 0 else 1
        val own = subtitlesOf(info).map { MediaSubtitle(it.url, it.lang, it.getDisplayLanguage()) }
        val subtitles = (own + addonSubtitles.sortedBy { rank(it) })
            .distinctBy { it.url }
            .take(24)
            .map { SubtitleTrack(it.url, language = it.language, label = it.label) }
        val intent = try {
            PlayerActivity.intent(
                this,
                url,
                intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                intent.getStringExtra(EXTRA_KEY).orEmpty(),
                info.headers.orEmpty(),
                subtitles,
                watchKey = intent.getStringExtra(EXTRA_WATCH_KEY).orEmpty(),
                markWatchedOnCompletion = intent.getStringExtra(EXTRA_MARK_WATCHED) != "false",
                nextTitle = intent.getStringExtra(EXTRA_NEXT_TITLE).orEmpty(),
                fromSourceList = true
            )
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Oynatıcı açılamadı.", Toast.LENGTH_LONG).show()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PLAY)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PLAY) return
        // Watched to the end, or the next episode was asked for: the opening screen takes over.
        // Anything else (Back, a source that would not play) returns to Nuvio's list.
        if (resultCode == PlayerActivity.RESULT_NEXT || resultCode == Activity.RESULT_OK) {
            setResult(resultCode)
            finish()
        }
    }

    companion object {
        private const val REQUEST_PLAY = 41
        private const val EXTRA_TITLE = "lexia.title"
        private const val EXTRA_KEY = "lexia.key"
        private const val EXTRA_WATCH_KEY = "lexia.watch"
        private const val EXTRA_MARK_WATCHED = "lexia.markWatched"
        private const val EXTRA_NEXT_TITLE = "lexia.next"

        /**
         * [videoId] is the Stremio id of the film or episode ("tt…", "tt…:1:2" or "tmdb:…").
         * [playbackTitle], [mediaKey], [watchKey] and [nextTitle] are what LeXia's player is given.
         */
        fun intent(
            context: Context,
            film: Pick,
            videoId: String,
            contentId: String,
            season: Int?,
            episode: Int?,
            episodeName: String?,
            playbackTitle: String,
            mediaKey: String,
            nextTitle: String
        ): Intent {
            fun image(path: String, size: String) = when {
                path.startsWith("/") -> "https://image.tmdb.org/t/p/$size$path"
                path.startsWith("http") -> path
                else -> null
            }
            return intent(
                context,
                mapOf(
                    // Named as in NuvioTV's "stream" route.
                    "videoId" to videoId,
                    "contentType" to if (film.isSeries) "series" else "movie",
                    "title" to film.title,
                    "poster" to image(film.poster, "w500"),
                    "backdrop" to image(film.backdrop, "w1280"),
                    "season" to season?.toString(),
                    "episode" to episode?.toString(),
                    "episodeName" to episodeName,
                    "year" to film.year.takeIf { it.isNotBlank() },
                    "contentId" to contentId,
                    "contentName" to film.title,
                    EXTRA_TITLE to playbackTitle,
                    EXTRA_KEY to mediaKey,
                    EXTRA_WATCH_KEY to film.key,
                    EXTRA_MARK_WATCHED to (!film.isSeries).toString(),
                    EXTRA_NEXT_TITLE to nextTitle
                ),
                NuvioStreamsActivity::class.java
            )
        }
    }
}
