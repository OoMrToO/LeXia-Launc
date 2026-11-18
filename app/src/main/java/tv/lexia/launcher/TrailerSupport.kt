package tv.lexia.launcher

import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import androidx.media3.common.C as MediaC
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Fetches a YouTube stream in pieces. YouTube slows a stream that is asked for in one go down to
 * a trickle, but serves pieces named in the address ("&range=0-10485759") at full speed. Other
 * addresses (an HLS list, subtitles) pass through untouched.
 */
@UnstableApi
internal class YouTubeChunkedDataSource(private val http: DefaultHttpDataSource) : DataSource {

    class Factory(private val userAgent: String) : DataSource.Factory {
        override fun createDataSource(): DataSource = YouTubeChunkedDataSource(
            DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true)
                .createDataSource()
        )
    }

    private var chunked = false
    private var spec: DataSpec? = null
    private var position = 0L
    /** One past the last byte wanted. */
    private var end = 0L
    private var chunkLeft = 0L
    private var emptyChunks = 0

    override fun addTransferListener(transferListener: TransferListener) = http.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val total = uri.getQueryParameter("clen")?.toLongOrNull() ?: 0L
        chunked = uri.host.orEmpty().endsWith("googlevideo.com") && total > 0L && uri.getQueryParameter("range") == null
        if (!chunked) return http.open(dataSpec)
        spec = dataSpec
        position = dataSpec.position
        if (position > total) throw IOException("Position $position is past the end of the stream ($total)")
        end = if (dataSpec.length == MediaC.LENGTH_UNSET.toLong()) total else minOf(total, position + dataSpec.length)
        chunkLeft = 0L
        emptyChunks = 0
        if (position < end) openChunk()
        return end - dataSpec.position
    }

    private fun openChunk() {
        val whole = spec ?: throw IOException("Stream is not open")
        val last = minOf(position + CHUNK_BYTES, end) - 1
        val piece = whole.uri.buildUpon().appendQueryParameter("range", "$position-$last").build()
        http.open(DataSpec.Builder().setUri(piece).setHttpRequestHeaders(whole.httpRequestHeaders).build())
        chunkLeft = last - position + 1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return http.read(buffer, offset, length)
        if (length == 0) return 0
        if (position >= end) return MediaC.RESULT_END_OF_INPUT
        if (chunkLeft == 0L) {
            http.close()
            openChunk()
        }
        val count = http.read(buffer, offset, minOf(length.toLong(), chunkLeft).toInt())
        if (count == MediaC.RESULT_END_OF_INPUT) {
            // The piece came short. The next read asks again from here; a server that keeps
            // sending nothing is an error, not an endless loop.
            if (++emptyChunks > 3) throw IOException("YouTube sent an empty piece of the stream")
            chunkLeft = 0L
            return 0
        }
        if (count > 0) emptyChunks = 0
        position += count
        chunkLeft -= count
        return count
    }

    override fun getUri(): Uri? = if (chunked) spec?.uri else http.uri

    override fun getResponseHeaders(): Map<String, List<String>> = http.responseHeaders

    override fun close() {
        http.close()
        spec = null
        chunked = false
    }

    private companion object {
        const val CHUNK_BYTES = 10L * 1024 * 1024
    }
}

/** Which picture formats this TV plays well enough for a trailer. */
internal object TrailerDecoders {
    private val answers = HashMap<String, Boolean>()

    /** The TV shows HDR (HDR10, HLG or Dolby Vision): trailers come in HDR where YouTube has it. */
    fun screenHdr(activity: android.app.Activity): Boolean = try {
        @Suppress("DEPRECATION")
        val types = activity.windowManager.defaultDisplay.hdrCapabilities?.supportedHdrTypes
        types != null && types.isNotEmpty() && canDecode("vp9")
    } catch (_: Exception) {
        false
    }

    /** [codec] is "avc1", "vp9" or "av01". H.264 plays everywhere; the others only with the TV's own chip. */
    fun canDecode(codec: String): Boolean = synchronized(answers) {
        answers.getOrPut(codec) {
            when (codec) {
                "avc1" -> true
                "vp9" -> hasHardwareDecoder("video/x-vnd.on2.vp9")
                "av01" -> hasHardwareDecoder("video/av01")
                else -> false
            }
        }
    }

    private fun hasHardwareDecoder(mime: String): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } &&
                if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                else !info.name.startsWith("OMX.google.", true) && !info.name.startsWith("c2.android.", true)
        }
    } catch (_: Exception) {
        false
    }
}
