package tv.lexia.launcher

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The trailer add-on (Streailer) first, the film list's trailers when it has nothing that plays. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StreailerTrailersTest {
    private var asked = 0
    private var answer: String? = null
    private var clock = 1_000_000L

    private fun film(vararg trailers: Video, imdb: String = "tt1375666", main: String = trailers.firstOrNull()?.key ?: "") = Pick(
        title = "Başlangıç", meta = "", overview = "", backdrop = "", reason = "", trailer = main, logo = "",
        imdb = 8.8, year = "2010", genres = emptyList(), runtime = 148, id = 27205,
        trailers = trailers.toList(), imdbId = imdb
    )

    private fun stream(name: String, title: String, key: String) =
        """{"streams":[{"name":"$name","title":"$title","behaviorHints":{"notWebReady":true,"bingeGroup":"trailer"},"ytId":"$key"}]}"""

    @Before fun setUp() {
        StreailerTrailers.init(ApplicationProvider.getApplicationContext())
        StreailerTrailers.clearForTests()
        StreailerTrailers.now = { clock }
        StreailerTrailers.fetch = { asked++; answer }
    }

    @After fun tearDown() {
        StreailerTrailers.clearForTests()
        StreailerTrailers.now = System::currentTimeMillis
    }

    // 1. A Turkish trailer is found and opens first.
    @Test fun turkishDubFromTheAddonComesFirst() {
        answer = stream("🎬▶️ Trailer", "Başlangıç Türkçe Dublajlı Fragman", "AAAAAAAAAA1")
        val p = film(Video("BBBBBBBBBB2", "Türkçe altyazılı fragman"))
        val found = StreailerTrailers.lookup(p)
        assertNotNull(found)
        assertEquals("dub", found!!.language)
        assertEquals(StreailerTrailers.Origin.YOUTUBE_SEARCH, found.origin)
        assertEquals("AAAAAAAAAA1", StreailerTrailers.choose(p, found))
        assertEquals("AAAAAAAAAA1", TrailerActivity.bestTrailer(p))
        assertTrue(StreailerTrailers.address(p)!!.endsWith("/stream/movie/tt1375666.json"))
    }

    @Test fun theFilmListsDubBeatsTheAddonsSubtitledOne() {
        answer = stream("🎬▶️ Trailer", "Başlangıç Türkçe Altyazılı Fragman", "AAAAAAAAAA1")
        val p = film(Video("CCCCCCCCCC3", "Orijinal fragman"), Video("DDDDDDDDDD4", "Türkçe dublaj fragman"))
        val found = StreailerTrailers.lookup(p)
        assertEquals("sub", found!!.language)
        assertEquals("DDDDDDDDDD4", StreailerTrailers.choose(p, found))
    }

    @Test fun addonsTurkishBeatsTheListsOriginal() {
        answer = stream("🎬 Trailer", "Başlangıç", "AAAAAAAAAA1")
        val p = film(Video("CCCCCCCCCC3", "Orijinal fragman"))
        assertEquals("AAAAAAAAAA1", StreailerTrailers.choose(p, StreailerTrailers.lookup(p)))
    }

    @Test fun englishFromTheAddonOnlyWhenTheFilmHasNone() {
        answer = stream("🎬🇬🇧 Trailer", "Inception", "EEEEEEEEEE5")
        val withOwn = film(Video("CCCCCCCCCC3", "Fragman"))
        val found = StreailerTrailers.lookup(withOwn)
        assertEquals("orig", found!!.language)
        assertEquals("CCCCCCCCCC3", StreailerTrailers.choose(withOwn, found))
        assertEquals("EEEEEEEEEE5", StreailerTrailers.choose(film(main = ""), found))
    }

    // 2. No result: the film's own trailer.
    @Test fun emptyAnswerKeepsTheFilmsTrailer() {
        answer = """{"streams":[]}"""
        val p = film(Video("CCCCCCCCCC3", "Fragman"))
        assertNull(StreailerTrailers.lookup(p))
        assertEquals("CCCCCCCCCC3", TrailerActivity.bestTrailer(p))
        // Not asked again for a while.
        StreailerTrailers.lookup(p)
        assertEquals(1, asked)
    }

    // 3. Timeout / offline: null at once, the film's own trailer, not asked again straight away.
    @Test fun noAnswerFallsBackAndIsNotRetriedAtOnce() {
        answer = null
        val p = film(Video("CCCCCCCCCC3", "Fragman"))
        assertNull(StreailerTrailers.lookup(p))
        assertTrue(StreailerTrailers.known(p))
        StreailerTrailers.lookup(p)
        assertEquals(1, asked)
        clock += 11 * 60 * 1000L
        assertFalse(StreailerTrailers.known(p))
    }

    @Test fun aThrowingFetchIsAFailureNotACrash() {
        StreailerTrailers.fetch = { throw java.io.IOException("timeout") }
        assertNull(StreailerTrailers.lookup(film(Video("CCCCCCCCCC3", "Fragman"))))
    }

    // 4. Invalid answers are never used.
    @Test fun invalidAnswersAreIgnored() {
        assertNull(StreailerTrailers.parse("not json", 0))
        assertNull(StreailerTrailers.parse("""{"streams":"x"}""", 0))
        assertNull(StreailerTrailers.parse(stream("🎬 Trailer", "x", "bad id with spaces"), 0))
        assertNull(StreailerTrailers.parse(stream("🎬 Trailer", "x", "javascript:1"), 0))
        assertNull(StreailerTrailers.parse("""{"streams":[{"name":"🎬 Trailer","url":"http://evil/x.mp4"}]}""", 0))
        // A YouTube search hit that does not say it is a trailer.
        assertNull(StreailerTrailers.parse(stream("🎬▶️ Trailer", "Başlangıç inceleme", "AAAAAAAAAA1"), 0))
        assertNull(StreailerTrailers.parse(stream("Recap S1", "Özet fragman", "AAAAAAAAAA1"), 0))
    }

    @Test fun aFilmWithoutAnyIdIsNotAsked() {
        val p = film(Video("CCCCCCCCCC3", "Fragman"), imdb = "").let {
            Pick(it.title, "", "", "", "", it.trailer, "", 0.0, "", emptyList(), 0, trailers = it.trailers)
        }
        assertNull(StreailerTrailers.address(p))
        assertNull(StreailerTrailers.lookup(p))
        assertEquals(0, asked)
    }

    // 5 + 6. Found but would not play: the film's own trailer, and the bad key is forgotten.
    @Test fun unplayableAddonTrailerGivesWayToTheFilmsOwn() {
        answer = stream("🎬▶️ Trailer", "Başlangıç Türkçe Dublajlı Fragman", "AAAAAAAAAA1")
        val p = film(Video("CCCCCCCCCC3", "Türkçe altyazılı fragman"))
        assertEquals("AAAAAAAAAA1", StreailerTrailers.choose(p, StreailerTrailers.lookup(p)))
        StreailerTrailers.markBad(p, "AAAAAAAAAA1")
        assertEquals("CCCCCCCCCC3", TrailerActivity.bestTrailer(p))
        assertEquals("CCCCCCCCCC3", StreailerTrailers.choose(p, null, setOf("AAAAAAAAAA1")))
        // 9. The bad one is not used again, not even after a restart (disk).
        assertNull(StreailerTrailers.cached(p))
    }

    // 7. Nothing better anywhere: the film's own YouTube trailer stays (the page's YouTube fallback takes it).
    @Test fun withNothingFromTheAddonTheMainTrailerStays() {
        val p = film(Video("CCCCCCCCCC3", "Orijinal fragman"))
        assertEquals("CCCCCCCCCC3", StreailerTrailers.choose(p, null))
        assertEquals("CCCCCCCCCC3", StreailerTrailers.choose(p, null, setOf("ZZZZZZZZZZ9")))
    }

    // 8. A trailer found once is used again without asking.
    @Test fun foundTrailerIsCachedPerFilmAndLanguage() {
        answer = stream("🎬 Trailer", "Başlangıç", "AAAAAAAAAA1")
        val p = film(Video("CCCCCCCCCC3", "Fragman"))
        StreailerTrailers.lookup(p)
        StreailerTrailers.lookup(p)
        assertEquals(1, asked)
        assertEquals("AAAAAAAAAA1", StreailerTrailers.cached(p)?.key)
        // After a week it is asked again.
        clock += 8 * 24 * 60 * 60 * 1000L
        assertNull(StreailerTrailers.cached(p))
    }

    // 10. The home screen never waits: it reads only what is known, and asks nothing.
    @Test fun homeTrailerNeverAsks() {
        val p = film(Video("CCCCCCCCCC3", "Fragman"))
        assertEquals("CCCCCCCCCC3", p.homeTrailer)
        assertEquals(0, asked)
        answer = stream("🎬▶️ Trailer", "Başlangıç Türkçe Dublajlı Fragman", "AAAAAAAAAA1")
        StreailerTrailers.lookup(p)
        assertEquals("AAAAAAAAAA1", p.homeTrailer)
        // A subtitled one does not replace the home screen's trailer.
        StreailerTrailers.clearForTests()
        answer = stream("🎬▶️ Trailer", "Başlangıç Türkçe Altyazılı Fragman", "AAAAAAAAAA1")
        StreailerTrailers.lookup(p)
        assertEquals("CCCCCCCCCC3", p.homeTrailer)
    }

    // 11 + 12. Subtitles: a dubbed one gets none, a subtitled/original one keeps the YouTube Turkish ones.
    @Test fun languageDecidesSubtitles() {
        val at = 0L
        assertEquals("dub", StreailerTrailers.Found("AAAAAAAAAA1", StreailerTrailers.Origin.YOUTUBE_SEARCH, "X Türkçe Dublaj Fragman", at).language)
        assertEquals("sub", StreailerTrailers.Found("AAAAAAAAAA1", StreailerTrailers.Origin.YOUTUBE_SEARCH, "X Türkçe Altyazılı Fragman", at).language)
        assertEquals("tr", StreailerTrailers.Found("AAAAAAAAAA1", StreailerTrailers.Origin.TMDB_TURKISH, "X", at).language)
        assertEquals("orig", StreailerTrailers.Found("AAAAAAAAAA1", StreailerTrailers.Origin.TMDB_ENGLISH, "X", at).language)
        assertEquals("", StreailerTrailers.Found("AAAAAAAAAA1", StreailerTrailers.Origin.YOUTUBE_SEARCH, "X Official Trailer", at).language)
    }

    @Test fun seriesAreAskedAsSeries() {
        val p = Pick("Breaking Bad", "", "", "", "", "", "", 9.5, "", emptyList(), 0, kind = "tv", imdbId = "tt0903747")
        assertTrue(StreailerTrailers.address(p)!!.endsWith("/stream/series/tt0903747.json"))
    }
}
