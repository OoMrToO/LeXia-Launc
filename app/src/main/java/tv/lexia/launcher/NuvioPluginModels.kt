// What NuvioPluginRuntime.kt needs from the rest of NuvioTV (GPL-3.0), copied from
// domain/model/Plugin.kt, domain/model/Subtitle.kt, domain/model/Stream.kt and core/network/IPv4FirstDns.kt.
package tv.lexia.launcher

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.math.roundToInt

/** Result from a local scraper execution. */
internal data class LocalScraperResult(
    val title: String,
    val name: String? = null,
    val url: String,
    val quality: String? = null,
    val size: String? = null,
    val language: String? = null,
    val provider: String? = null,
    val type: String? = null,
    val seeders: Int? = null,
    val peers: Int? = null,
    val infoHash: String? = null,
    val headers: Map<String, String>? = null,
    val subtitles: List<Subtitle> = emptyList(),
    val audioDelayMs: Int? = null,
    val audioDelayLanguage: String? = null
)

internal data class Subtitle(
    val id: String,
    val url: String,
    val lang: String,
    val addonName: String,
    val addonLogo: String?,
    val isStreamProvided: Boolean = false,
    val headers: Map<String, String>? = null
)

private const val AUDIO_DELAY_MIN_MS = -60000
private const val AUDIO_DELAY_MAX_MS = 60000

/** Normalizes a `behaviorHints.audioDelayMs` value: whole ms, NaN/Infinity dropped, clamped to ±60 s. */
internal fun Number?.toStreamAudioDelayMs(): Int? =
    this?.toDouble()
        ?.takeIf { it.isFinite() }
        ?.roundToInt()
        ?.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)

/** Normalizes a `behaviorHints.audioDelayLanguage` value: trimmed language code, blank or oversized values dropped. */
internal fun String?.toStreamAudioDelayLanguage(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && it.length <= 35 }

/** IPv4 addresses first: avoids 60 s waits on networks with broken IPv6 routing (Nuvio issue #651). */
internal class NuvioIPv4FirstDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        return addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
    }
}

/** Nuvio's own rule: an episode goes to a scraper as "series" when the scraper says it takes series. */
internal fun jsPluginMediaType(
    mediaType: String,
    supportedTypes: List<String>,
    season: Int?,
    episode: Int?
): String = if (
    mediaType.equals("tv", ignoreCase = true) && season != null && episode != null &&
    supportedTypes.any { it.equals("series", ignoreCase = true) }
) "series" else mediaType
