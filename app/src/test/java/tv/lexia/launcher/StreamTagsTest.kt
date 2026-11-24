package tv.lexia.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picture class that orders the source started without asking, before any site record. */
class StreamTagsTest {
    private val plainTv = TvAbility.Ability(height = 2160, hdr10 = true, hdr10Plus = false, hlg = false,
        dolbyVision = false, hevc = true, av1 = false)

    @Test
    fun pictureClassComesBeforeTheSiteRecord() {
        assertEquals(3, StreamTags.of("Film 2160p WEB-DL HEVC").tier(plainTv))
        assertEquals(3, StreamTags.of("Film 1080p").tier(plainTv))
        assertEquals(2, StreamTags.of("Film 720p").tier(plainTv))
        assertEquals(2, StreamTags.of("Film").tier(plainTv))
        assertEquals(1, StreamTags.of("Film 480p").tier(plainTv))
        // What this TV cannot show well, and cinema copies, are the lowest whatever their size.
        assertEquals(0, StreamTags.of("Film 2160p DV HEVC").tier(plainTv))
        assertEquals(0, StreamTags.of("Film 1080p AV1").tier(plainTv))
        assertEquals(0, StreamTags.of("Film HDCAM").tier(plainTv))
        // Dolby Vision with HDR10 under it plays as HDR10 here.
        assertEquals(3, StreamTags.of("Film 2160p DV HDR10 HEVC").tier(plainTv))
    }

    @Test
    fun theTvItCannotShowIsFlagged() {
        assertTrue(StreamTags.of("Film 2160p DV HEVC").troubleOn(plainTv).isNotEmpty())
        assertEquals("", StreamTags.of("Film 2160p HDR10 HEVC").troubleOn(plainTv))
        val dvTv = TvAbility.Ability(2160, true, false, false, true, true, true)
        assertEquals("", StreamTags.of("Film 2160p DV HEVC").troubleOn(dvTv))
        assertEquals("", StreamTags.of("Film 1080p AV1").troubleOn(dvTv))
    }
}
