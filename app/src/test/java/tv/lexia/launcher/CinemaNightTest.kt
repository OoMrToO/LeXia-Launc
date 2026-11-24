package tv.lexia.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Sinema Gecesi's choice: one film for tonight, not watched, Listem first, no repeats. */
class CinemaNightTest {
    private fun film(title: String, imdb: Double = 7.0, genres: List<String> = listOf("Dram"), kind: String = "movie") = Pick(
        title = title, meta = "", overview = "", backdrop = "/$title.jpg", reason = "", trailer = "", logo = "",
        imdb = imdb, year = "2020", genres = genres, runtime = 110, poster = "/$title-p.jpg", kind = kind
    )

    private val out: (Pick) -> Boolean = { true }

    @Test
    fun watchedTurnedDownFinishedAndSeriesStayOut() {
        val a = film("A"); val b = film("B"); val c = film("C"); val d = film("D"); val s = film("S", kind = "tv")
        val pool = CinemaNightActivity.candidates(
            saved = listOf(a), given = listOf(a, b, c, d, s),
            watched = setOf(b.key), down = setOf(c.key), finished = { it == d.key }, out = out
        )
        assertEquals(listOf(a), pool)
    }

    @Test
    fun listemComesUpMostOften() {
        val saved = film("Listem", imdb = 7.0)
        val others = (1..5).map { film("F$it", imdb = 7.0) }
        val pool = listOf(saved) + others
        val wins = (0 until 2000).count {
            CinemaNightActivity.choose(pool, emptySet(), emptySet(), setOf(saved.key), emptyList(), { false }, Random(it)) === saved
        }
        // Three times the weight of each of five others: about 3/8 of the time, far above 1/6.
        assertTrue("Listem won $wins of 2000", wins in 600..900)
    }

    @Test
    fun noRepeatsUntilAllWereShown() {
        val pool = (1..4).map { film("F$it") }
        val shown = HashSet<String>()
        repeat(4) {
            val p = CinemaNightActivity.choose(pool, shown, emptySet(), emptySet(), emptyList(), { false }, Random(it))!!
            assertTrue(shown.add(p.key))
        }
        // All shown on this page: nothing new is left.
        assertNull(CinemaNightActivity.choose(pool, shown, emptySet(), emptySet(), emptyList(), { false }))
    }

    @Test
    fun passedOverStaysAwayUnlessNothingElseIsLeft() {
        val a = film("A"); val b = film("B")
        repeat(50) {
            assertEquals(b, CinemaNightActivity.choose(listOf(a, b), emptySet(), setOf(a.key), emptySet(), emptyList(), { false }, Random(it)))
        }
        // Both kept away: one of them still comes, rather than nothing.
        assertTrue(CinemaNightActivity.choose(listOf(a, b), emptySet(), setOf(a.key, b.key), emptySet(), emptyList(), { false }) != null)
    }

    @Test
    fun emptyCatalogueGivesNoFilm() {
        assertNull(CinemaNightActivity.choose(emptyList(), emptySet(), emptySet(), emptySet(), emptyList(), { false }))
        assertTrue(CinemaNightActivity.candidates(emptyList(), emptyList(), emptySet(), emptySet(), { false }, out = out).isEmpty())
    }
}
