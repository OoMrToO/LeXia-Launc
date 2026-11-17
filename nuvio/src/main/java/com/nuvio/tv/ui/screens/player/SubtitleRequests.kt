// Copied unchanged from NuvioTV's ui/screens/player/PlayerRuntimeControllerSubtitleTiming.kt: only its
// subtitle-request part (the rest belongs to Nuvio's player controller, which LeXia does not have).
package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.Dispatchers
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.player.SubtitleCharsetDetector
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

private fun OkHttpClient.Builder.subtitleDownloadDefaults(): OkHttpClient.Builder = this
    // Sidecar and auto-sync downloads use these clients; keep timeouts generous for flaky hosts.
    .connectTimeout(12_000, TimeUnit.MILLISECONDS)
    .readTimeout(15_000, TimeUnit.MILLISECONDS)
    .callTimeout(25_000, TimeUnit.MILLISECONDS)
    .retryOnConnectionFailure(true)
    .followRedirects(true)
    .followSslRedirects(true)
    .addNetworkInterceptor(ForwardedStreamHeaderGuard)

// Validating client. Interceptors are cleared so playbackHttpClient's trust-all SSL fallback can't
// resend stream credentials; executeSubtitleRequest handles the fallback instead.
internal val subtitleHttpClient: OkHttpClient by lazy {
    PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
        .apply { interceptors().clear() }
        .subtitleDownloadDefaults()
        .build()
}

internal val subtitleUnvalidatedTlsHttpClient: OkHttpClient by lazy {
    PlayerPlaybackNetworking.trustAllPlaybackHttpClient.newBuilder()
        .subtitleDownloadDefaults()
        .build()
}


private val SUBTITLE_REQUEST_EXCLUDED_HEADERS = setOf("range", "host", "connection", "transfer-encoding")

// Stream headers allowed on other hosts (#3328).
private val SUBTITLE_CROSS_HOST_HEADERS = setOf("referer", "origin", "user-agent", "accept-language")

// Not forwarded on an HTTPS to HTTP downgrade.
private val SUBTITLE_DOWNGRADE_EXCLUDED_HEADERS = setOf("referer", "origin")

private fun isDowngrade(requestUrl: HttpUrl, scopeUrl: HttpUrl?): Boolean =
    scopeUrl != null && scopeUrl.isHttps && !requestUrl.isHttps

/** Same host as [scopeUrl] and no HTTPS to HTTP downgrade. Sibling subdomains are out of scope. */
internal fun isInHeaderScope(requestUrl: HttpUrl, scopeUrl: HttpUrl?): Boolean {
    if (scopeUrl == null) return false
    if (isDowngrade(requestUrl, scopeUrl)) return false
    return requestUrl.host == scopeUrl.host
}

/**
 * All stream headers in scope, only [SUBTITLE_CROSS_HOST_HEADERS] outside it. Credentials can use any
 * header name, so this is an allowlist.
 */
internal fun subtitleStreamHeaders(
    streamHeaders: Map<String, String>,
    subtitleUrl: HttpUrl,
    streamUrl: HttpUrl?
): Map<String, String> {
    val inScope = isInHeaderScope(subtitleUrl, streamUrl)
    val downgrade = isDowngrade(subtitleUrl, streamUrl)
    return streamHeaders.filterKeys { name ->
        val lower = name.lowercase()
        when {
            lower in SUBTITLE_REQUEST_EXCLUDED_HEADERS -> false
            inScope -> true
            downgrade && lower in SUBTITLE_DOWNGRADE_EXCLUDED_HEADERS -> false
            else -> lower in SUBTITLE_CROSS_HOST_HEADERS
        }
    }
}

/**
 * Stream headers scoped to the stream URL: [names] are removed on a hop outside it, [downgradeNames] on an
 * HTTPS to HTTP hop. Subtitle-owned headers are tracked by [SubtitleOwnHeaders].
 */
internal class ForwardedStreamHeaders(
    val streamUrl: HttpUrl,
    val names: Set<String>,
    val downgradeNames: Set<String> = emptySet()
)

/**
 * The subtitle's own headers, scoped to the subtitle URL's host the same way: [names] are removed on a hop
 * to another host, [downgradeNames] on an HTTPS to HTTP hop.
 */
internal class SubtitleOwnHeaders(
    val subtitleUrl: HttpUrl,
    val names: Set<String>,
    val downgradeNames: Set<String>
)

/**
 * Applies [ForwardedStreamHeaders] and [SubtitleOwnHeaders] per redirect hop; OkHttp itself only strips
 * Authorization. Relies on OkHttp carrying request tags into redirects. It never adds headers back, so an
 * HTTP URL redirecting to HTTPS doesn't regain them.
 */
internal object ForwardedStreamHeaderGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val url = request.url
        val removed = mutableSetOf<String>()
        request.tag(ForwardedStreamHeaders::class.java)?.let { stream ->
            if (!isInHeaderScope(url, stream.streamUrl)) removed += stream.names
            if (isDowngrade(url, stream.streamUrl)) removed += stream.downgradeNames
        }
        request.tag(SubtitleOwnHeaders::class.java)?.let { own ->
            if (!isInHeaderScope(url, own.subtitleUrl)) removed += own.names
            if (isDowngrade(url, own.subtitleUrl)) removed += own.downgradeNames
        }
        return chain.proceed(request.withoutHeaders(removed))
    }
}

/** Removes the host-scoped stream and subtitle headers, for the permissive TLS retry. */
internal fun Request.withoutCredentialHeaders(): Request =
    withoutHeaders(
        tag(ForwardedStreamHeaders::class.java)?.names.orEmpty() +
            tag(SubtitleOwnHeaders::class.java)?.names.orEmpty()
    )

private fun Request.withoutHeaders(names: Set<String>): Request =
    if (names.isEmpty()) this else newBuilder().apply { names.forEach { removeHeader(it) } }.build()

/** Scoped stream headers, then the subtitle's own headers, then defaults, tagged for the guard. */
internal fun buildSubtitleRequest(
    subtitleUrl: HttpUrl,
    streamUrl: HttpUrl?,
    streamHeaders: Map<String, String>,
    explicitHeaders: Map<String, String>?
): Request {
    val requestBuilder = Request.Builder().url(subtitleUrl)

    val scopedStreamHeaders = subtitleStreamHeaders(streamHeaders, subtitleUrl, streamUrl)
    scopedStreamHeaders.forEach { (key, value) -> requestBuilder.header(key, value) }

    // Explicit subtitle headers override stream headers.
    explicitHeaders?.forEach { (key, value) ->
        if (key.lowercase() !in SUBTITLE_REQUEST_EXCLUDED_HEADERS) {
            requestBuilder.header(key, value)
        }
    }

    // A stream User-Agent is always allowed through, so this only fills the gap.
    if (requestBuilder.build().header("User-Agent") == null) {
        requestBuilder.header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
    }

    if (requestBuilder.build().header("Accept") == null) {
        requestBuilder.header("Accept", "text/plain, text/vtt, application/x-subrip, */*")
    }

    val explicitNames = explicitHeaders?.keys.orEmpty().map { it.lowercase() }.toSet()
    val forwardedNames = scopedStreamHeaders.keys
        .filter { it.lowercase() !in SUBTITLE_CROSS_HOST_HEADERS && it.lowercase() !in explicitNames }
        .toSet()
    val downgradeNames = scopedStreamHeaders.keys
        .filter { it.lowercase() in SUBTITLE_DOWNGRADE_EXCLUDED_HEADERS && it.lowercase() !in explicitNames }
        .toSet()
    if (streamUrl != null && (forwardedNames.isNotEmpty() || downgradeNames.isNotEmpty())) {
        requestBuilder.tag(
            ForwardedStreamHeaders::class.java,
            ForwardedStreamHeaders(streamUrl, forwardedNames, downgradeNames)
        )
    }
    val sentOwnNames = explicitHeaders?.keys.orEmpty()
        .filter { it.lowercase() !in SUBTITLE_REQUEST_EXCLUDED_HEADERS }
    val ownNames = sentOwnNames.filter { it.lowercase() !in SUBTITLE_CROSS_HOST_HEADERS }.toSet()
    val ownDowngradeNames = sentOwnNames.filter { it.lowercase() in SUBTITLE_DOWNGRADE_EXCLUDED_HEADERS }.toSet()
    if (ownNames.isNotEmpty() || ownDowngradeNames.isNotEmpty()) {
        requestBuilder.tag(
            SubtitleOwnHeaders::class.java,
            SubtitleOwnHeaders(subtitleUrl, ownNames, ownDowngradeNames)
        )
    }
    return requestBuilder.build()
}

/**
 * Tries [validated] first. On any SSLException, reruns the whole redirect chain on [permissive] without
 * host-scoped stream or subtitle headers, so self-signed hosts still work.
 */
internal fun executeSubtitleRequest(
    request: Request,
    validated: OkHttpClient = subtitleHttpClient,
    permissive: OkHttpClient = subtitleUnvalidatedTlsHttpClient
): okhttp3.Response =
    try {
        validated.newCall(request).execute()
    } catch (e: SSLException) {
        permissive.newCall(request.withoutCredentialHeaders()).execute()
    }

