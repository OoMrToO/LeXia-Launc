package tv.lexia.launcher

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Devam et" starts the film or episode again from the source it last played on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SourceMemoryTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun remembersTheLastSourcePerTitle() {
        val memory = SourceMemory(app)
        val stream = MediaStream(
            addonName = "Vidmody",
            name = "Vidmody - 1080p",
            description = "Türkçe dublaj",
            url = "https://example.com/film.m3u8",
            headers = mapOf("Referer" to "https://example.com/"),
            subtitles = listOf(MediaSubtitle("https://example.com/tr.srt", "tr", "Türkçe")),
            fileName = "",
            sizeBytes = 0
        )
        assertNull(memory.lastPlayed("tt1234567"))
        memory.playedFrom("tt1234567", stream)
        val back = memory.lastPlayed("tt1234567")!!
        assertEquals(stream.url, back.url)
        assertEquals("https://example.com/", back.headers["Referer"])
        assertEquals("tr", back.subtitles.single().language)
        // Another episode keeps its own.
        assertNull(memory.lastPlayed("tt1234567:1:2"))
        memory.forgetPlayed("tt1234567")
        assertNull(memory.lastPlayed("tt1234567"))
    }
}
