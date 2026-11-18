package tv.lexia.launcher

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** İzlediklerim with a film half watched, a series on an episode and a finished film. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "tr-w960dp-h540dp-land-television-xhdpi", application = Application::class)
class WatchHistoryTest {
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            Thread.sleep(50)
        }
    }

    private fun pick(title: String, poster: String, kind: String = "movie") = Pick(
        title = title, meta = "", overview = "", backdrop = poster, reason = "", trailer = "", logo = "",
        imdb = 8.0, year = "2023", genres = listOf("Dram"), runtime = 120, poster = poster, kind = kind
    )

    @Test fun page() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File("build/shots").mkdirs()
        val oppenheimer = pick("Oppenheimer", "/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg")
        val dune = pick("Dune: Çöl Gezegeni Bölüm İki", "/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg")
        val bb = pick("Breaking Bad", "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg", kind = "tv")
        val store = MediaPlaybackStore(app)
        val history = WatchHistory(app)
        store.save("tt1", 78 * 60_000L, 180 * 60_000L); store.saveLast(oppenheimer.key, "tt1"); history.played(oppenheimer)
        store.save("tt0903747:2:5", 20 * 60_000L, 47 * 60_000L); store.saveLast(bb.key, "tt0903747:2:5"); history.played(bb)
        history.finished(bb, "tt0903747:2:4")
        store.markFinished("tt2"); store.saveLast(dune.key, "tt2"); history.finished(dune, "tt2")
        ActivityScenario.launch(WatchHistoryActivity::class.java).use { scenario ->
            settle(4000)
            scenario.onActivity { it.window.decorView.captureRoboImage("build/shots/izlediklerim.png") }
        }
    }
}
