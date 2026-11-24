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

    /** "Hızlı açılıyor": learnt from how long each site took to start, leaning on the latest tries. */
    @Test
    fun learnsWhichSitesStartFast() {
        val memory = SourceMemory(app)
        org.junit.Assert.assertFalse(memory.fast("Vidmody"))
        memory.started("Vidmody", 3_000)
        org.junit.Assert.assertTrue(memory.fast("vidmody"))
        memory.started("Yavaş", 14_000)
        org.junit.Assert.assertFalse(memory.fast("Yavaş"))
        // Got quicker: after a few fast starts it counts as fast.
        repeat(4) { memory.started("Yavaş", 2_000) }
        org.junit.Assert.assertTrue(memory.fast("Yavaş"))
        // A value that cannot be a start time is ignored.
        memory.started("Vidmody", 500_000)
        assertEquals(3_000L, memory.startMs("Vidmody"))
    }

    /** A site's record: failures pull it down, later plays bring it back; never tried says nothing. */
    @Test
    fun learnsWhichSitesPlay() {
        val memory = SourceMemory(app)
        // First seen: neither good nor bad, no label.
        assertEquals(0.5, memory.trust("Yeni"), 0.0)
        org.junit.Assert.assertFalse(memory.proven("Yeni"))
        org.junit.Assert.assertFalse(memory.unreliable("Yeni"))
        org.junit.Assert.assertFalse(memory.playedLately("Yeni"))
        // Played before: trusted, "Daha önce sorunsuz çalıştı" after two plays, played lately.
        memory.played("İyi")
        org.junit.Assert.assertFalse(memory.proven("İyi"))
        memory.played("iyi")
        memory.played("İyi")
        org.junit.Assert.assertTrue(memory.proven("İyi"))
        org.junit.Assert.assertTrue(memory.playedLately("İyi"))
        // Failing again and again: unreliable, never "proven".
        repeat(4) { memory.failed("Bozuk") }
        org.junit.Assert.assertTrue(memory.unreliable("Bozuk"))
        org.junit.Assert.assertFalse(memory.proven("Bozuk"))
        // Fixed later: its plays bring it back up.
        repeat(8) { memory.played("Bozuk") }
        org.junit.Assert.assertFalse(memory.unreliable("Bozuk"))
        org.junit.Assert.assertTrue(memory.trust("Bozuk") > 0.5)
    }

    /** Records kept before "when it last played" was added still read, and grow the new field. */
    @Test
    fun readsOldRecords() {
        app.getSharedPreferences("source_memory", android.content.Context.MODE_PRIVATE).edit()
            .putString("stats", "{\"eski\":[5,1]}").commit()
        val memory = SourceMemory(app)
        assertEquals(0.8, memory.trust("Eski"), 0.0)
        assertEquals(0L, memory.lastPlayedAt("Eski"))
        org.junit.Assert.assertTrue(memory.proven("Eski"))
        memory.played("Eski")
        org.junit.Assert.assertTrue(memory.lastPlayedAt("Eski") > 0)
        assertEquals(0.8, memory.trust("Eski"), 0.0)
    }
}
