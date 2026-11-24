package tv.lexia.launcher

import java.util.Locale

/**
 * What a source says about itself, read out of its name, description and file name
 * ("2160p WEB-DL DV HDR10+ DDP5.1 Atmos HEVC 18.4 GB"): the facts behind the badges in the source
 * list, and behind the choice of the source that is played without asking.
 */
internal class StreamTags(
    /** Picture height: 2160, 1080, 720...; 0 when the source does not say. */
    val resolution: Int,
    /** "Dolby Vision", "HDR10+", "HDR", "HLG" or empty. */
    val hdr: String,
    /** "HEVC", "AV1", "H.264" or empty. */
    val codec: String,
    /** "Atmos", "DTS-HD", "Dolby Digital+"... or empty. */
    val audio: String,
    /** "7.1", "5.1", "2.0" or empty. */
    val channels: String,
    /** "REMUX", "BluRay", "WEB-DL", "WEBRip", "HDTV", "DVD", "CAM" or empty. */
    val source: String,
    /** "Dublaj", "Altyazılı" or empty. */
    val turkish: String,
    val threeD: Boolean,
    /** File size in bytes; 0 when unknown. */
    val sizeBytes: Long,
    /** Dolby Vision that also carries HDR10 ("DV HDR10"): a TV without Dolby Vision shows it as HDR10. */
    val hdrFallback: Boolean = false
) {
    /** Filmed off a cinema screen. */
    val cinemaCopy: Boolean get() = source == "CAM"

    val resolutionName: String
        get() = when {
            resolution >= 4320 -> "8K"
            resolution >= 2160 -> "4K"
            resolution >= 1440 -> "1440p"
            resolution > 0 -> "${resolution}p"
            else -> ""
        }

    val sizeName: String
        get() = when {
            sizeBytes <= 0L -> ""
            sizeBytes >= GB -> String.format(TR, "%.1f GB", sizeBytes.toDouble() / GB)
            else -> String.format(TR, "%d MB", sizeBytes / MB)
        }

    /** The badges, most telling first. */
    val badges: List<String>
        get() = listOf(
            resolutionName, hdr, turkish, source, codec,
            listOf(audio, channels).filter { it.isNotEmpty() }.joinToString(" "),
            if (threeD) "3D" else "",
            sizeName
        ).filter { it.isNotEmpty() }

    /**
     * How good a first choice this source is on a screen [screenHeight] pixels tall. A picture
     * larger than the screen is worth a little less than one that fits it exactly: it looks the
     * same and loads slower. [wantDubbed] is true when the viewer's sound language is Turkish.
     */
    fun score(tv: TvAbility.Ability, wantDubbed: Boolean): Int {
        val screenHeight = tv.height
        val screenHdr = tv.anyHdr
        val screen = if (screenHeight >= 2000) 2160 else if (screenHeight >= 1300) 1440 else 1080
        var score = when {
            resolution == 0 -> 600
            resolution <= screen -> resolution
            else -> screen - 80
        }
        if (cinemaCopy) score -= 3000
        if (threeD) score -= 2000
        if (hdr.isNotEmpty()) score += if (screenHdr) 40 else -40
        // What this TV cannot show well goes below every source it can.
        if (troubleOn(tv).isNotEmpty()) score -= 500
        when (turkish) {
            "Dublaj" -> score += if (wantDubbed) 60 else -20
            "Altyazılı" -> score += if (wantDubbed) 0 else 60
        }
        score += when (source) {
            "REMUX" -> 12
            "BluRay" -> 10
            "WEB-DL" -> 8
            "WEBRip" -> 4
            else -> 0
        }
        return score
    }

    /**
     * The picture class, for the source started without asking: what this TV cannot show well or a
     * cinema copy 0, below 720p 1, 720p (or not told) 2, Full HD and up 3. A site's record only orders
     * sources within one class, so a poor picture never wins just because its site played before.
     */
    fun tier(tv: TvAbility.Ability): Int = when {
        troubleOn(tv).isNotEmpty() || cinemaCopy || threeD -> 0
        resolution in 1..699 -> 1
        resolution == 0 || resolution < 1000 -> 2
        else -> 3
    }

    /**
     * Below the lowest picture wanted ([min] lines; 0 = any): a told resolution under it, or a cinema
     * copy. A source that does not tell its resolution is kept (many good ones do not).
     */
    fun belowMin(min: Int): Boolean = min > 0 && (resolution in 1 until min || cinemaCopy)

    /** Why this source may play badly on this TV ("" when it should play well). */
    fun troubleOn(tv: TvAbility.Ability): String = when {
        hdr == "Dolby Vision" && !hdrFallback && !tv.dolbyVision -> "Bu TV'de renkleri bozuk görünebilir"
        codec == "AV1" && !tv.av1 -> "Bu TV'de takılabilir"
        codec == "HEVC" && !tv.hevc -> "Bu TV'de takılabilir"
        else -> ""
    }

    companion object {
        private const val MB = 1024L * 1024L
        private const val GB = MB * 1024L
        private val TR = Locale.forLanguageTag("tr-TR")

        private fun word(pattern: String) = Regex("""(?<![a-z0-9])(?:$pattern)(?![a-z0-9])""")

        private val resolutions = listOf(
            4320 to word("4320p|8k"),
            2160 to word("2160p|4k|uhd"),
            1440 to word("1440p|2k"),
            1080 to word("1080[pi]|fhd|full ?hd"),
            720 to word("720p"),
            576 to word("576p"),
            480 to word("480p|sd"),
            360 to word("360p"),
            240 to word("240p"),
            // On its own "HD" is the weakest hint, so it is asked last.
            720 to Regex("""(?<![a-z0-9-])hd(?![a-z0-9-])""")
        )
        private val dolbyVision = word("dolby ?vision|dovi|dv")
        private val hdr10Plus = Regex("""(?<![a-z0-9])hdr ?10 ?(?:\+|plus)""")
        private val hdr = word("hdr(?:10)?")
        private val hlg = word("hlg")
        private val hevc = word("hevc|[hx] ?\\.?265")
        private val av1 = word("av1")
        private val avc = word("avc|[hx] ?\\.?264")
        private val atmos = word("atmos")
        private val trueHd = word("true ?hd")
        private val dtsHd = Regex("""(?<![a-z0-9])dts[- ]?(?:hd|x|ma)(?![a-z0-9])""")
        private val dts = word("dts")
        private val ddPlus = Regex("""(?<![a-z0-9])(?:ddp|dd\+|e-?ac-?3|dolby digital ?(?:plus|\+))""")
        private val dd = Regex("""(?<![a-z0-9])(?:ac-?3|dd|dolby digital)(?![a-z+])""")
        private val aac = word("aac")
        // "5.1", but not the "5.1" of "5.1 GB".
        private val channels = Regex("""(?<![0-9])(?<![0-9]\.)([257])\.([01])(?![0-9])(?!\s*(?:gb|gib|mb|mib))""")
        private val remux = word("remux")
        private val bluRay = word("blu-?ray|bd-?rip|br-?rip|bdmv")
        private val webRip = word("web-?rip")
        private val webDl = word("web-?dl|web")
        private val hdtv = word("hdtv|pdtv")
        private val dvd = word("dvd-?rip|dvd")
        // "TS" and "TC" only inside a release name ("1080p.TS.x264"), not as the ".ts" of a file name.
        private val cam = Regex("""(?<![a-z0-9])(?:cam|hd-?cam|cam-?rip|hd-?ts|telesync|hd-?tc|telecine|screener|t[sc](?=[. _-][a-z0-9]))(?![a-z0-9])""")
        private val dubbed = Regex("""dublaj|(?<![a-z])dub(?:bed)?(?![a-z])""")
        private val subtitled = Regex("""alt ?yaz[ıi]|(?<![a-z])sub(?:bed|s)?(?![a-z])""")
        private val threeD = word("3d|hsbs|h-sbs|half-sbs")
        private val size = Regex("""(\d+(?:[.,]\d+)?)\s*(gb|gib|mb|mib)(?![a-z])""")

        /** Reads the tags out of everything a source says about itself. */
        fun of(vararg texts: String, sizeBytes: Long = 0L): StreamTags {
            val text = texts.joinToString(" \n ")
                .replace('İ', 'i').replace('I', 'i')
                .lowercase(Locale.ROOT)
                .replace("̇", "")
            val resolution = resolutions.firstOrNull { it.second.containsMatchIn(text) }?.first ?: 0
            val audioChannels = channels.findAll(text).map { it.value }
                .maxByOrNull { it.substringBefore('.').toInt() }.orEmpty()
            val bytes = if (sizeBytes > 0L) sizeBytes else size.find(text)?.let { m ->
                val amount = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let 0L
                (amount * if (m.groupValues[2].startsWith("g")) GB else MB).toLong()
            } ?: 0L
            return StreamTags(
                resolution = resolution,
                hdr = when {
                    dolbyVision.containsMatchIn(text) -> "Dolby Vision"
                    hdr10Plus.containsMatchIn(text) -> "HDR10+"
                    hdr.containsMatchIn(text) -> "HDR"
                    hlg.containsMatchIn(text) -> "HLG"
                    else -> ""
                },
                hdrFallback = dolbyVision.containsMatchIn(text) &&
                    (hdr10Plus.containsMatchIn(text) || hdr.containsMatchIn(text)),
                codec = when {
                    hevc.containsMatchIn(text) -> "HEVC"
                    av1.containsMatchIn(text) -> "AV1"
                    avc.containsMatchIn(text) -> "H.264"
                    else -> ""
                },
                audio = when {
                    atmos.containsMatchIn(text) -> "Atmos"
                    trueHd.containsMatchIn(text) -> "TrueHD"
                    dtsHd.containsMatchIn(text) -> "DTS-HD"
                    dts.containsMatchIn(text) -> "DTS"
                    ddPlus.containsMatchIn(text) -> "Dolby Digital+"
                    dd.containsMatchIn(text) -> "Dolby Digital"
                    aac.containsMatchIn(text) -> "AAC"
                    else -> ""
                },
                channels = audioChannels,
                source = when {
                    cam.containsMatchIn(text) -> "CAM"
                    remux.containsMatchIn(text) -> "REMUX"
                    bluRay.containsMatchIn(text) -> "BluRay"
                    webRip.containsMatchIn(text) -> "WEBRip"
                    webDl.containsMatchIn(text) -> "WEB-DL"
                    hdtv.containsMatchIn(text) -> "HDTV"
                    dvd.containsMatchIn(text) -> "DVD"
                    else -> ""
                },
                turkish = when {
                    dubbed.containsMatchIn(text) -> "Dublaj"
                    subtitled.containsMatchIn(text) -> "Altyazılı"
                    else -> ""
                },
                threeD = threeD.containsMatchIn(text),
                sizeBytes = bytes
            )
        }
    }
}
