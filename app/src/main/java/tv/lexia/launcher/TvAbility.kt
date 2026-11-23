package tv.lexia.launcher

import android.app.Activity
import android.view.Display

/**
 * What this TV can really show, read once: its biggest picture, the HDR kinds its screen takes
 * and the video kinds its own chip decodes. The source list puts the sources the TV cannot show
 * well at the bottom (a Dolby Vision-only copy turns purple and green without Dolby Vision; AV1
 * stutters without its chip); Ayarlar › Hakkında shows it in one line.
 */
internal object TvAbility {
    class Ability(
        val height: Int,
        val hdr10: Boolean,
        val hdr10Plus: Boolean,
        val hlg: Boolean,
        val dolbyVision: Boolean,
        val hevc: Boolean,
        val av1: Boolean
    ) {
        val anyHdr: Boolean get() = hdr10 || hdr10Plus || hlg || dolbyVision

        /** "4K · HDR10 · HLG · Dolby Vision yok · AV1 yok". */
        val summary: String
            get() = listOfNotNull(
                when {
                    height >= 2000 -> "4K"
                    height >= 1000 -> "Full HD"
                    else -> "HD"
                },
                if (hdr10) "HDR10" else null,
                if (hdr10Plus) "HDR10+" else null,
                if (hlg) "HLG" else null,
                if (!anyHdr) "HDR yok" else null,
                if (dolbyVision) "Dolby Vision" else "Dolby Vision yok",
                if (hevc) null else "HEVC yok",
                if (av1) "AV1" else "AV1 yok"
            ).joinToString("  ·  ")
    }

    @Volatile private var known: Ability? = null

    fun of(activity: Activity): Ability = known ?: read(activity).also { known = it }

    private fun read(activity: Activity): Ability {
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay
        val height = try {
            display.supportedModes.maxOfOrNull { it.physicalHeight }
        } catch (_: Exception) {
            null
        } ?: activity.resources.displayMetrics.heightPixels
        val types = try {
            display.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
        } catch (_: Exception) {
            emptySet()
        }
        return Ability(
            height = height,
            hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in types,
            hdr10Plus = HDR_TYPE_HDR10_PLUS in types,
            hlg = Display.HdrCapabilities.HDR_TYPE_HLG in types,
            // The screen takes it and the chip decodes it; otherwise the picture is decoded as plain HEVC.
            dolbyVision = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types &&
                TrailerDecoders.hasHardwareDecoder("video/dolby-vision"),
            hevc = TrailerDecoders.hasHardwareDecoder("video/hevc"),
            av1 = TrailerDecoders.hasHardwareDecoder("video/av01")
        )
    }

    /** Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS (Android 10), by value so older TVs read it too. */
    private const val HDR_TYPE_HDR10_PLUS = 4
}
