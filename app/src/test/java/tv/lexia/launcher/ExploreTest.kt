package tv.lexia.launcher

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Keşfet's tabs and rows, with one film half watched (its bar) and one series left on an episode. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "tr-w960dp-h540dp-land-television-xhdpi", application = Application::class)
class ExploreTest {
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            Thread.sleep(50)
        }
    }

    private fun film(title: String, poster: String, imdb: Double, genres: List<String>, year: String, kind: String = "movie", id: Int = 0) = Pick(
        title = title, meta = "", overview = "", backdrop = poster, reason = "", trailer = "", logo = "",
        imdb = imdb, year = year, genres = genres, runtime = 120, poster = poster, kind = kind, id = id
    )

    @Test fun rows() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File("build/shots").mkdirs()
        val films = listOf(
            film("Dune: Çöl Gezegeni Bölüm İki", "/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg", 8.5, listOf("Bilim-Kurgu", "Macera"), "2024", id = 693134),
            film("Oppenheimer", "/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg", 8.3, listOf("Dram", "Tarih"), "2023", id = 872585),
            film("John Wick: Bölüm 4", "/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg", 7.7, listOf("Aksiyon", "Gerilim"), "2023", id = 603692),
            film("Örümcek-Adam: Örümcek Evrenine Geçiş", "/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg", 8.6, listOf("Animasyon", "Aksiyon"), "2023", id = 569094),
            film("Kurtuluş Projesi", "/9SSEUrSqhljBMzRe4aBTh17rUaC.jpg", 7.9, listOf("Bilim-Kurgu", "Dram"), "2026", id = 687163),
            film("Barbie", "/iuFNMS8U5cb6xfzi51Dbkovj7vM.jpg", 6.8, listOf("Komedi", "Macera"), "2023", id = 346698),
            film("Breaking Bad", "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg", 9.5, listOf("Dram", "Suç"), "2008", kind = "tv", id = 1396)
        )
        File(app.filesDir, "pick.json").writeText(JSONObject()
            .put("picks", JSONArray().apply { films.forEach { put(it.toJson()) } })
            .put("top10", JSONArray().apply { films.take(4).forEach { put(it.toJson()) } })
            .toString())
        MediaPlaybackStore(app).apply {
            save("tt1", 40 * 60_000L, 120 * 60_000L)
            saveLast(films[1].key, "tt1")
            save("tt0903747:2:5", 20 * 60_000L, 47 * 60_000L)
            saveLast(films[6].key, "tt0903747:2:5")
        }
        ActivityScenario.launch(ExploreActivity::class.java).use { scenario ->
            settle(5000)
            scenario.onActivity { it.window.decorView.captureRoboImage("build/shots/kesfet.png") }
            // The series tab: Breaking Bad left on season 2, episode 5.
            scenario.onActivity { a ->
                val tabs = a.window.decorView.findViewById<android.view.View>(android.R.id.content)
                fun find(v: android.view.View): android.view.View? =
                    if (v is android.widget.TextView && v.text.toString() == "Diziler") v
                    else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
                find(tabs)?.performClick()
            }
            settle(3000)
            scenario.onActivity { it.window.decorView.captureRoboImage("build/shots/kesfet-diziler.png") }
        }
    }
}
