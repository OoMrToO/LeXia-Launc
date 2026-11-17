// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.StreamSidecarSubtitles
import com.nuvio.tv.ui.screens.stream.StreamPlaybackInfo
import com.nuvio.tv.ui.screens.stream.StreamScreen
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Opens Nuvio's own stream screen (ui/screens/stream/StreamScreen.kt) for one film or episode, the
 * way Nuvio's navigation does: the text extras are the route arguments of Nuvio's "stream" route.
 * A picked source goes to [play], which LeXia overrides to start its own player; Back closes the screen.
 */
open class LeXiaStreamActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startFromBeginning = intent.getStringExtra("startFromBeginning") == "true"
        setContent {
            NuvioTheme {
                StreamScreen(
                    startFromBeginning = startFromBeginning,
                    onBackPress = { finish() },
                    onStreamSelected = { info -> play(info) },
                    onAutoPlayResolved = { info -> play(info) }
                )
            }
        }
    }

    /** The viewer picked [info] (or Nuvio's auto-play did). */
    protected open fun play(info: StreamPlaybackInfo) = Unit

    /** The subtitles the picked source brought with it (Nuvio keeps them beside the stream address). */
    protected fun subtitlesOf(info: StreamPlaybackInfo): List<Subtitle> =
        info.url?.let { StreamSidecarSubtitles.forUrl(it) }.orEmpty()

    companion object {
        /** [arguments] are named as in Nuvio's Screen.Stream route (videoId, contentType, title, season, ...). */
        fun intent(context: Context, arguments: Map<String, String?>, activity: Class<out LeXiaStreamActivity> = LeXiaStreamActivity::class.java): Intent =
            Intent(context, activity).apply {
                arguments.forEach { (key, value) -> if (value != null) putExtra(key, value) }
            }
    }
}
