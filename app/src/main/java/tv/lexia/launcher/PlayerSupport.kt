package tv.lexia.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.media3.common.C as MediaC
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** What the source said about one separate subtitle file. */
internal class SubtitleHint(val language: String, val mime: String)

/**
 * Hands the player its data as usual, except separate subtitle files: those are downloaded whole
 * and repaired first (see [SubtitleText]). A subtitle that cannot be loaded becomes an empty one,
 * so a dead subtitle link never stops the film.
 */
@UnstableApi
internal class SubtitleFixDataSource(
    private val media: DataSource,
    private val subtitles: Map<String, SubtitleHint>
) : DataSource {

    class Factory(
        private val media: DataSource.Factory,
        private val subtitles: Map<String, SubtitleHint>
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SubtitleFixDataSource(media.createDataSource(), subtitles)
    }

    private var passThrough = true
    private var uri: Uri? = null
    private var data: ByteArray? = null
    private var position = 0
    private var remaining = 0

    override fun addTransferListener(transferListener: TransferListener) = media.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val hint = subtitles[dataSpec.uri.toString()]
        if (hint == null) {
            passThrough = true
            return media.open(dataSpec)
        }
        passThrough = false
        uri = dataSpec.uri
        val fixed = try {
            SubtitleText.normalize(download(dataSpec.uri), hint.language, hint.mime)
        } catch (e: Exception) {
            Log.w("LeXiaPlayer", "Subtitle could not be loaded: ${e.message}")
            SubtitleText.empty(hint.mime)
        }
        data = fixed
        position = dataSpec.position.coerceIn(0L, fixed.size.toLong()).toInt()
        val left = fixed.size - position
        remaining = if (dataSpec.length == MediaC.LENGTH_UNSET.toLong()) left else minOf(dataSpec.length, left.toLong()).toInt()
        return remaining.toLong()
    }

    /** The whole file, without the film's own request headers: subtitles live on other servers. */
    private fun download(address: Uri): ByteArray {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .createDataSource()
        try {
            http.open(DataSpec(address))
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = http.read(buffer, 0, buffer.size)
                if (read == MediaC.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, read)
                if (out.size() > SubtitleText.MAX_BYTES) throw IOException("Subtitle file is too large")
            }
            return out.toByteArray()
        } finally {
            try {
                http.close()
            } catch (_: Exception) {
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passThrough) return media.read(buffer, offset, length)
        if (length == 0) return 0
        if (remaining <= 0) return MediaC.RESULT_END_OF_INPUT
        val count = minOf(length, remaining)
        System.arraycopy(data!!, position, buffer, offset, count)
        position += count
        remaining -= count
        return count
    }

    override fun getUri(): Uri? = if (passThrough) media.uri else uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (passThrough) media.responseHeaders else emptyMap()

    override fun close() {
        if (passThrough) {
            media.close()
        } else {
            data = null
            uri = null
            remaining = 0
            passThrough = true
        }
    }
}

/**
 * The player's usual renderers, with subtitles shown [delayUs] later (or earlier, when negative)
 * than the file says. The delay can change while the film plays.
 */
@UnstableApi
internal class DelayedSubtitleRenderers(context: Context, private val delayUs: AtomicLong) : DefaultRenderersFactory(context) {
    init {
        // A second decoder is tried when the TV's first choice fails to start.
        setEnableDecoderFallback(true)
        // As NuvioTV's player by default (decoder priority "on"): sound the TV cannot decode
        // itself (AC3, E-AC3, DTS, TrueHD...) goes to Nuvio's FFmpeg decoder instead of failing.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val first = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (i in first until out.size) {
            val renderer = out[i]
            out[i] = object : ForwardingRenderer(renderer) {
                override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
                    super.render((positionUs - delayUs.get()).coerceAtLeast(0L), elapsedRealtimeUs)
                }
            }
        }
    }
}

/** Names for the audio and subtitle lists: "Türkçe · 5.1 · Dolby Digital+". */
@UnstableApi
internal object TrackNames {
    fun audio(format: Format, number: Int): String {
        val parts = ArrayList<String>()
        parts.add(language(format).ifBlank { format.label?.trim().orEmpty() }.ifBlank { "Ses $number" })
        channels(format.channelCount)?.let(parts::add)
        codec(format)?.let(parts::add)
        return parts.joinToString("  ·  ")
    }

    fun text(format: Format, number: Int): String {
        val name = language(format)
        val label = format.label?.trim().orEmpty()
        val parts = ArrayList<String>()
        when {
            name.isNotBlank() -> {
                parts.add(name)
                // A label that only repeats the language ("TR", "Turkish") adds nothing.
                if (label.isNotBlank() && Lang.code(label) != Lang.code(format.language)) parts.add(label)
            }
            label.isNotBlank() -> parts.add(label)
            else -> parts.add("Altyazı $number")
        }
        if (format.selectionFlags and MediaC.SELECTION_FLAG_FORCED != 0) parts.add("Zorunlu")
        return parts.joinToString("  ·  ")
    }

    private fun language(format: Format): String = Lang.name(Lang.code(format.language))

    private fun channels(count: Int): String? = when (count) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }

    private fun codec(format: Format): String? = when (format.sampleMimeType) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS-HD"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        else -> null
    }
}

/** The player's progress line: played part, loaded part, and a knob while the remote is on it. */
internal class PlayerSeekBar(context: Context, private val ui: Ui) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val loaded = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73FFFFFF }
    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TEXT }
    private val rect = RectF()

    /** Both between 0 and 1. */
    var progress = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                invalidate()
            }
        }
    var buffered = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                invalidate()
            }
        }

    init {
        isFocusable = true
        setOnFocusChangeListener { _, _ -> invalidate() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ui.u(40))
    }

    override fun onDraw(canvas: Canvas) {
        val focused = isFocused
        val thickness = ui.uf(if (focused) 10 else 6)
        val knob = ui.uf(13)
        val left = knob
        val right = width - knob
        val middle = height / 2f
        val radius = thickness / 2f
        fun line(to: Float, paint: Paint) {
            rect.set(left, middle - radius, left + (right - left) * to, middle + radius)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        line(1f, track)
        if (buffered > progress) line(buffered, loaded)
        if (progress > 0f) line(progress, played)
        if (focused) canvas.drawCircle(left + (right - left) * progress, middle, knob, played)
    }
}
