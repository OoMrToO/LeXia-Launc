package tv.lexia.launcher

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.nuvio.tv.LeXiaNuvio
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws LeXia's media pages off-device (build/shots): Ayarlar › Medya kaynakları with a real Nuvio
 * repository, and the source page filled by Nuvio's search (fixed sample sources here).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "tr-w960dp-h540dp-land-television-xhdpi", application = Application::class)
class ScreensTest {

    private fun settle(ms: Long = 1500) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            // The main thread's clock moves with real time, so fades and delayed posts finish.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            Thread.sleep(50)
        }
    }

    private fun stream(provider: String, name: String, title: String) = Stream(
        name = name, title = title, description = null, url = "https://example.com/$provider-${name.hashCode()}.m3u8",
        ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
        addonName = provider, addonLogo = null
    )

    @Test fun screens() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File("build/shots").mkdirs()
        val repo = NuvioJsRepository(app)
        val address = System.getProperty("lexia.repo").orEmpty()
        if (address.isNotBlank()) repo.addRepository(address)

        ActivityScenario.launch(MediaAddonsActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { it.window.decorView.captureRoboImage("build/shots/medya-kaynaklari.png") }
        }

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
        Prefs(app).playAutoSource = false
        val film = Pick(
            title = "Kurtuluş Projesi", meta = "", overview = "", backdrop = "", reason = "", trailer = "", logo = "",
            imdb = 7.9, year = "2026", genres = listOf("Bilim Kurgu"), runtime = 156, id = 687163, imdbId = "tt12042730"
        )
        val intent = Intent(app, MediaSourcePickerActivity::class.java).putExtra("media.pick", film.toJson().toString())
        ActivityScenario.launch<MediaSourcePickerActivity>(intent).use { scenario ->
            settle(4000)
            scenario.onActivity { it.window.decorView.captureRoboImage("build/shots/kaynaklar.png") }
        }

        // A series: the episode list from Nuvio's details source (Cinemeta), with stills.
        val series = Pick(
            title = "Breaking Bad", meta = "", overview = "", backdrop = "", reason = "", trailer = "", logo = "",
            imdb = 9.5, year = "2008", genres = listOf("Dram"), runtime = 47, kind = "tv", seasons = 5,
            id = 1396, imdbId = "tt0903747"
        )
        val seriesIntent = Intent(app, MediaSourcePickerActivity::class.java).putExtra("media.pick", series.toJson().toString())
        ActivityScenario.launch<MediaSourcePickerActivity>(seriesIntent).use { scenario ->
            settle(15000)
            scenario.onActivity { it.window.decorView.captureRoboImage("build/shots/bolumler.png") }
        }
    }
}
