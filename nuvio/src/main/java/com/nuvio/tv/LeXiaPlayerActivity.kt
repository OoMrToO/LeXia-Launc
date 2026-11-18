// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (Nuvio's navigation, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nuvio.tv.data.repository.LeXiaProgress
import com.nuvio.tv.ui.screens.player.PlayerScreen
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import com.nuvio.tv.ui.theme.NuvioTheme
import org.json.JSONObject
import java.net.URLEncoder

/**
 * NuvioTV's player screen (ui/screens/player/PlayerScreen.kt), unchanged, for one source that LeXia
 * picked: the extras are the route arguments of Nuvio's "player" route. Where Nuvio's navigation
 * would go on (back to its stream or detail screens, on to the next episode's sources), this
 * finishes and answers LeXia instead: [RESULT_FAILED], [RESULT_NEXT] (season and episode in the
 * data), RESULT_OK when watched to the end, RESULT_CANCELED otherwise.
 */
class LeXiaPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent.extras
        val arguments: Map<String, String?> = extras?.keySet()?.associateWith { extras.getString(it) }.orEmpty()
        LeXiaNuvio.applyPlayerDefaults(this)
        val keep = progress
        val viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LeXiaNuvio.playerViewModel(applicationContext, arguments, keep) as T
        })[PlayerViewModel::class.java]
        setContent {
            NuvioTheme {
                PlayerScreen(
                    viewModel = viewModel,
                    onBackPress = { videoId, season, episode, _, completed ->
                        answer(if (completed) Activity.RESULT_OK else Activity.RESULT_CANCELED, videoId, season, episode)
                    },
                    onPlaybackErrorBack = { answer(RESULT_FAILED, null, null, null) },
                    onPlaybackEnded = { nextVideoId, nextSeason, nextEpisode, _ ->
                        if (nextVideoId != null && nextSeason != null && nextEpisode != null) {
                            answer(RESULT_NEXT, nextVideoId, nextSeason, nextEpisode)
                        } else {
                            answer(Activity.RESULT_OK, null, null, null)
                        }
                    }
                )
            }
        }
    }

    private fun answer(result: Int, videoId: String?, season: Int?, episode: Int?) {
        if (isFinishing) return
        setResult(result, Intent().apply {
            videoId?.let { putExtra(RESULT_VIDEO_ID, it) }
            season?.let { putExtra(RESULT_SEASON, it) }
            episode?.let { putExtra(RESULT_EPISODE, it) }
        })
        finish()
    }

    companion object {
        const val RESULT_FAILED = Activity.RESULT_FIRST_USER
        const val RESULT_NEXT = Activity.RESULT_FIRST_USER + 1
        const val RESULT_VIDEO_ID = "lexia.player.videoId"
        const val RESULT_SEASON = "lexia.player.season"
        const val RESULT_EPISODE = "lexia.player.episode"

        /** LeXia's record of where films stop; set before [intent] is started. */
        @Volatile
        var progress: LeXiaProgress? = null

        /** As Nuvio's Screen.Player.createRoute encodes the text arguments. */
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /**
         * The player for [streamUrl]. [contentId] is the title's id ("tt…" or "tmdb:…"), [videoId] the
         * episode's ("tt…:2:5") or the film's; [headers] go with every request, as the source asked.
         */
        fun intent(
            context: Context,
            streamUrl: String,
            title: String,
            contentId: String,
            contentType: String,
            videoId: String,
            season: Int? = null,
            episode: Int? = null,
            episodeTitle: String? = null,
            year: String? = null,
            poster: String? = null,
            backdrop: String? = null,
            logo: String? = null,
            headers: Map<String, String> = emptyMap(),
            streamName: String? = null,
            addonName: String? = null,
            streamDescription: String? = null,
            filename: String? = null,
            bingeGroup: String? = null,
            startFromBeginning: Boolean = false,
            /** Subtitles that come with this source, offered beside the add-on ones (as Nuvio does). */
            subtitles: List<com.nuvio.tv.domain.model.Subtitle> = emptyList()
        ): Intent {
            com.nuvio.tv.ui.screens.player.StreamSidecarSubtitles.set(streamUrl, subtitles)
            val raw = mapOf(
                "streamUrl" to streamUrl,
                "contentId" to contentId,
                "contentType" to contentType,
                "videoId" to videoId,
                "season" to season?.toString(),
                "episode" to episode?.toString(),
                "bingeGroup" to bingeGroup,
                "startFromBeginning" to startFromBeginning.toString(),
                "launchStartedAtMs" to System.currentTimeMillis().toString()
            )
            val encoded = mapOf(
                "title" to title,
                "contentName" to title,
                "streamName" to streamName,
                "year" to year,
                "headers" to headers.takeIf { it.isNotEmpty() }?.let { JSONObject(it).toString() },
                "poster" to poster,
                "backdrop" to backdrop,
                "logo" to logo,
                "episodeTitle" to episodeTitle,
                "addonName" to addonName,
                "streamDescription" to streamDescription,
                "filename" to filename
            )
            return Intent(context, LeXiaPlayerActivity::class.java).apply {
                raw.forEach { (key, value) -> if (!value.isNullOrEmpty()) putExtra(key, value) }
                encoded.forEach { (key, value) -> if (!value.isNullOrEmpty()) putExtra(key, encode(value)) }
            }
        }
    }
}
