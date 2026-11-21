package tv.lexia.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class TrailerReleasePhaseTest {
    @Test fun pastReleaseIsNotReportedAsCurrentlyInCinemas() {
        assertEquals(TrailerReleasePhase.PAST_OR_UNKNOWN, trailerReleasePhase(-1))
        assertEquals(TrailerReleasePhase.PAST_OR_UNKNOWN, trailerReleasePhase(-365))
    }

    @Test fun releaseTodayHasItsOwnStatus() {
        assertEquals(TrailerReleasePhase.RELEASES_TODAY, trailerReleasePhase(0))
    }

    @Test fun futureReleaseIsUpcoming() {
        assertEquals(TrailerReleasePhase.UPCOMING, trailerReleasePhase(1))
        assertEquals(TrailerReleasePhase.UPCOMING, trailerReleasePhase(30))
    }

    @Test fun missingOrInvalidReleaseDateHasNoStatus() {
        assertEquals(TrailerReleasePhase.PAST_OR_UNKNOWN, trailerReleasePhase(null))
    }
}
