package tv.lexia.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only what is the site's fault counts against it: no unfair marks from the viewer's own exits or the connection. */
class PlayFailureTest {
    @Test
    fun onlyTheSitesFaultCounts() {
        assertTrue(PlayFailure.of("HTTP 404 Not Found").sourceFault)
        assertTrue(PlayFailure.of("Response code: HTTP 503").sourceFault)
        assertTrue(PlayFailure.of("Source error: unrecognized input format").sourceFault)
        // The connection, the TV, or an old "Devam et" link that was given only a few seconds.
        assertFalse(PlayFailure.of("java.net.UnknownHostException: Unable to resolve host").sourceFault)
        assertFalse(PlayFailure.of("MediaCodecVideoRenderer error, decoder init failed").sourceFault)
        assertFalse(PlayFailure.of(com.nuvio.tv.LeXiaPlayerActivity.REASON_TIMEOUT).sourceFault)
        // No connection: trying another source would fail the same way.
        assertFalse(PlayFailure.of("Unable to resolve host").tryAnother)
        assertTrue(PlayFailure.of("HTTP 410").tryAnother)
    }
}
