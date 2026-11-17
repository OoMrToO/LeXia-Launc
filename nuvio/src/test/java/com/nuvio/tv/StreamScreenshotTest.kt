package com.nuvio.tv

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws Nuvio's stream screen off-device with sample sources. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "tr-w960dp-h540dp-land-television-xhdpi")
class StreamScreenshotTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun stream(provider: String, name: String, title: String) = Stream(
        name = name, title = title, description = null, url = "https://example.com/$provider.m3u8",
        ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
        addonName = provider, addonLogo = null
    )

    @Test fun streams() {
        val groups = listOf(
            AddonStreams("Imu", null, listOf(
                stream("Imu", "Imu - 1080p", "Kurtuluş Projesi (2026) · Türkçe Dublaj"),
                stream("Imu", "Imu - 720p", "Kurtuluş Projesi (2026) · Türkçe Altyazı")
            )),
            AddonStreams("JoyBoy", null, listOf(stream("JoyBoy", "JoyBoy - 4K HDR", "Project Hail Mary 2026 2160p WEB-DL")))
        )
        LeXiaNuvio.streams = object : StreamRepository {
            override fun setLocalPluginSearchPaused(paused: Boolean) = Unit
            override fun getStreamsFromAllAddons(
                type: String, videoId: String, season: Int?, episode: Int?, forceRefresh: Boolean,
                priorityBingeGroup: String?, preferredPluginOnly: Boolean, continuePluginOnly: Boolean
            ): Flow<NetworkResult<List<AddonStreams>>> = flowOf(NetworkResult.Success(groups))
            override suspend fun getCachedStreamsFromAllAddons(type: String, videoId: String, season: Int?, episode: Int?): List<AddonStreams>? = null
            override suspend fun getStreamsFromAddon(addon: Addon, type: String, videoId: String): NetworkResult<List<Stream>> =
                NetworkResult.Success(emptyList())
        }
        val intent: Intent = LeXiaStreamActivity.intent(
            ApplicationProvider.getApplicationContext(),
            mapOf("videoId" to "tt12042730", "contentType" to "movie", "title" to "Kurtuluş Projesi", "year" to "2026",
                "contentId" to "tt12042730", "contentName" to "Kurtuluş Projesi", "manualSelection" to "true")
        )
        ActivityScenario.launch<LeXiaStreamActivity>(intent).use {
            rule.waitForIdle()
            Thread.sleep(1500)
            rule.waitForIdle()
            File("build/shots").mkdirs()
            rule.onRoot().captureRoboImage("build/shots/streams.png")
        }
    }
}
