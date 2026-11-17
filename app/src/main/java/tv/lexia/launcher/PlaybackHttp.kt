package tv.lexia.launcher

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.network.IPv4FirstDns
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * How the player asks video hosts for a film, as NuvioTV's player does
 * (PlayerPlaybackNetworking.createHttpDataSourceFactory and PlayerMediaSourceFactory):
 * a browser User-Agent unless the source names its own, the source's headers on every request
 * (redirects and HLS segments included), IPv4 first, HTTP/1.1. Hosts that scrapers find answer
 * an unknown player ("ExoPlayerLib") with 403 or 429.
 */
@UnstableApi
internal object PlaybackHttp {

    /** NuvioTV's PlayerMediaSourceFactory.DEFAULT_USER_AGENT. */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 32
            })
            .dns(IPv4FirstDns())
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Blank names and values and any Range header are dropped (Nuvio's sanitizeHeaders). */
    fun sanitize(headers: Map<String, String>): Map<String, String> =
        headers.mapNotNull { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isEmpty() || value.isEmpty() || key.equals("Range", ignoreCase = true)) null else key to value
        }.toMap()

    fun factory(headers: Map<String, String>): OkHttpDataSource.Factory {
        val clean = sanitize(headers)
        val withHeaders = if (clean.isEmpty()) client else client.newBuilder()
            .addNetworkInterceptor { chain ->
                val next = chain.request().newBuilder()
                clean.forEach { (key, value) -> next.header(key, value) }
                chain.proceed(next.build())
            }
            .build()
        return OkHttpDataSource.Factory(withHeaders).apply {
            setDefaultRequestProperties(clean)
            if (clean.keys.none { it.equals("User-Agent", ignoreCase = true) }) setUserAgent(USER_AGENT)
        }
    }
}
