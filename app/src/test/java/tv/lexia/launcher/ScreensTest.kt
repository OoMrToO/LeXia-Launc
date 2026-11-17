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
        name = name, title = title, description = null, url = "https://example.com/$provider-${(name + title).hashCode()}.m3u8",
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
                stream("Imu", "Vidmody - 1080p", "Kurtuluş Projesi Türkçe dublaj"),
                stream("Imu", "HDFilmDelisi - 1080p", "Kurtuluş Projesi dublaj")
            )),
            AddonStreams("JoyBoy", null, listOf(
                stream("JoyBoy", "Kurtuluş Projesi - 1080p", "dublaj"),
                stream("JoyBoy", "Kurtuluş Projesi - 1080p", "altyazılı")
            )),
            AddonStreams("Liderfilm", null, listOf(stream("Liderfilm", "DiziMom - 1080p", "altyazılı"))),
            AddonStreams("Sinemaizle", null, listOf(stream("Sinemaizle", "Sinemaizle - 4K", "2160p HDR altyazılı"))),
            AddonStreams("Filmekseni", null, listOf(stream("Filmekseni", "Filmekseni - 720p", "dublaj")))
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
            title = "Kurtuluş Projesi", meta = "", overview = "", backdrop = "/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg", reason = "", trailer = "", logo = "",
            imdb = 7.9, year = "2026", genres = listOf("Bilim Kurgu"), runtime = 156, id = 687163, imdbId = "tt12042730"
        )
        val intent = Intent(app, MediaSourcePickerActivity::class.java).putExtra("media.pick", film.toJson().toString())
        ActivityScenario.launch<MediaSourcePickerActivity>(intent).use { scenario ->
            settle(6000)
            // The remote on the second source, as on the TV.
            scenario.onActivity { a ->
                fun tiles(v: android.view.View): List<android.view.View> =
                    if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { tiles(v.getChildAt(it)) }
                    else emptyList<android.view.View>()
                val all = ArrayList<android.view.View>()
                fun walk(v: android.view.View) {
                    if (v.isFocusable && (v.tag as? String)?.startsWith("https://example.com/") == true) all.add(v)
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(a.window.decorView)
                all.getOrNull(1)?.let { it.isFocusableInTouchMode = true; it.requestFocus() }
            }
            settle(800)
            scenario.onActivity { it.window.decorView.captureRoboImage("build/shots/kaynaklar.png") }
        }

        // A favourite provider: the film starts on its best source, no list is waited for.
        Prefs(app).favSource = "liderfilm-id"
        Prefs(app).favSourceName = "Liderfilm"
        ActivityScenario.launch<MediaSourcePickerActivity>(intent).use { scenario ->
            // The favourite provider should start immediately, but Robolectric may deliver the
            // stream Flow on a worker after its dispatcher has been drained. Allow the normal
            // six-second best-source deadline as a deterministic fallback before asserting.
            settle(8000)
            scenario.onActivity { a ->
                val started = shadowOf(a).nextStartedActivityForResult
                val uri = started?.intent?.getStringExtra("media.uri").orEmpty()
                println("FAVORITE started=${started?.intent?.component?.className} uri=$uri")
                check(started != null && uri.contains("Liderfilm")) { "Favourite source did not start: $uri" }
            }
        }
        Prefs(app).favSource = ""
        Prefs(app).favSourceName = ""

        // A series: the episode list from Nuvio's details source (Cinemeta), with stills.
        val series = Pick(
            title = "Breaking Bad", meta = "", overview = "Kimya öğretmeni Walter White, ölümcül bir hastalık teşhisi koyulunca ailesinin geleceğini güvenceye almak için eski bir öğrencisiyle uyuşturucu üretmeye başlar.", backdrop = "/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg", reason = "", trailer = "", logo = "",
            imdb = 9.5, year = "2008", genres = listOf("Dram"), runtime = 47, kind = "tv", seasons = 5,
            id = 1396, imdbId = "tt0903747"
        )
        // The first episode half watched, the second watched to the end.
        MediaPlaybackStore(app).apply {
            save("tt0903747:1:1", 25 * 60_000L, 58 * 60_000L)
            markFinished("tt0903747:1:2")
        }
        val seriesIntent = Intent(app, MediaSourcePickerActivity::class.java).putExtra("media.pick", series.toJson().toString())
        ActivityScenario.launch<MediaSourcePickerActivity>(seriesIntent).use { scenario ->
            settle(15000)
            scenario.onActivity { it.window.decorView.captureRoboImage("build/shots/bolumler.png") }
        }
    
        // The series page: seasons and episodes under the buttons, the remote on an episode.
        MediaPlaybackStore(app).saveLast(series.key, "tt0903747:1:1")
        val detail = Intent(app, DetailActivity::class.java).putExtra("film", series.toJson().toString())
        ActivityScenario.launch<DetailActivity>(detail).use { scenario ->
            settle(12000)
            scenario.onActivity { a ->
                val card = a.window.decorView.findViewWithTag<android.view.View>("episode:tt0903747:1:3")
                println("EPISODE card=" + (card != null))
                card?.let { it.isFocusableInTouchMode = true; it.requestFocus() }
            }
            settle(3000)
            scenario.onActivity { it.window.decorView.captureRoboImage("build/shots/dizi-sayfasi.png") }
        }

        // A film in cinemas: the play pill shows its release, coloured.
        val inCinema = Pick(
            title = "Uzak Yıldız", meta = "", overview = "Bir okul öğretmeni, uzak bir yıldız sisteminde, Dünya'yı kurtarmak için tek başına uyanır.", backdrop = "/9SSEUrSqhljBMzRe4aBTh17rUaC.jpg", reason = "", trailer = "x", logo = "",
            imdb = 8.4, year = "2026", genres = listOf("Bilim Kurgu"), runtime = 156, kind = "movie", id = 999001,
            release = "2026-11-14"
        )
        java.io.File(app.filesDir, "pick.json").writeText(
            org.json.JSONObject().put("cinema", org.json.JSONArray().put(inCinema.toJson())).toString()
        )
        ActivityScenario.launch<DetailActivity>(
            Intent(app, DetailActivity::class.java).putExtra("film", inCinema.toJson().toString())
        ).use { scenario ->
            settle(4000)
            scenario.onActivity { it.window.decorView.captureRoboImage("build/shots/sinemada.png") }
        }
}
}
