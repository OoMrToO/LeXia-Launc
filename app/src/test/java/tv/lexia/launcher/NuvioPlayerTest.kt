package tv.lexia.launcher

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.nuvio.tv.LeXiaPlayerActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** NuvioTV's player screen opens inside LeXia (no video plays here: the controls and their layout). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "tr-w960dp-h540dp-land-television-xhdpi", application = Application::class)
class NuvioPlayerTest {
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            Thread.sleep(50)
        }
    }

    @Test fun opens() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File("build/shots").mkdirs()
        val intent = LeXiaPlayerActivity.intent(
            app,
            streamUrl = "https://example.com/film.m3u8",
            title = "Breaking Bad",
            contentId = "tt0903747",
            contentType = "series",
            videoId = "tt0903747:1:1",
            season = 1,
            episode = 1,
            episodeTitle = "Pilot",
            year = "2008",
            backdrop = "https://image.tmdb.org/t/p/w1280/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg",
            streamName = "Vidmody - 1080p",
            addonName = "Imu"
        )
        ActivityScenario.launch<LeXiaPlayerActivity>(intent).use { scenario ->
            settle(6000)
            scenario.onActivity { it.window.decorView.captureRoboImage("build/shots/nuvio-oynatici.png") }
        }
    }
}
