package tv.lexia.launcher

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Makes a downloaded subtitle file readable before the player parses it.
 *
 * The player reads subtitle files as UTF-8. Many Turkish files are saved as Windows-1254 (ş, ğ, ı
 * come out as boxes), some were converted twice ("ÅŸ" for ş), and add-on addresses often carry no
 * file extension, so a WebVTT file can arrive announced as SubRip or the other way round.
 */
object SubtitleText {
    const val SRT = "application/x-subrip"
    const val VTT = "text/vtt"
    const val SSA = "text/x-ssa"
    const val TTML = "application/ttml+xml"

    /** Bigger files are not subtitles. */
    const val MAX_BYTES = 6 * 1024 * 1024

    /**
     * The file as UTF-8 in the format the player was told to expect.
     * [language] is what the source says the subtitle's language is (may be empty);
     * [declaredMime] is the format announced to the player.
     */
    fun normalize(bytes: ByteArray, language: String, declaredMime: String): ByteArray {
        var text = repairDoubleEncoding(decode(bytes, language))
        if (text.startsWith('﻿')) text = text.substring(1)
        val actual = sniff(text)
        text = when {
            declaredMime == SRT && actual == VTT -> toSrt(cues(text))
            declaredMime == VTT && actual == SRT -> toVtt(cues(text))
            // Not what was announced and not convertible (an error page, say): the strict parsers
            // would stop the film over it, so the player gets an empty subtitle instead.
            declaredMime != SRT && actual != declaredMime -> return empty(declaredMime)
            else -> text
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    /** The format a subtitle address most likely holds, from its file extension. */
    fun mimeFromAddress(address: String): String {
        val path = address.substringBefore('#').substringBefore('?')
        return when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "vtt", "webvtt" -> VTT
            "ass", "ssa" -> SSA
            "ttml", "dfxp", "xml" -> TTML
            // No or unknown extension: add-on subtitles are SubRip far more often than anything else,
            // and normalize() converts a WebVTT file that turns up here.
            else -> SRT
        }
    }

    /** What an empty, valid file of this format looks like; served when a subtitle cannot be loaded. */
    fun empty(mime: String): ByteArray =
        (if (mime == VTT) "WEBVTT\n\n" else "").toByteArray(Charsets.UTF_8)

    // ---------- Character set ----------

    internal fun decode(bytes: ByteArray, language: String): String {
        val n = bytes.size
        if (n == 0) return ""
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        if (n >= 3 && b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) return String(bytes, 3, n - 3, Charsets.UTF_8)
        if (n >= 2 && b(0) == 0xFF && b(1) == 0xFE) return String(bytes, 2, n - 2, Charsets.UTF_16LE)
        if (n >= 2 && b(0) == 0xFE && b(1) == 0xFF) return String(bytes, 2, n - 2, Charsets.UTF_16BE)
        utf16WithoutMark(bytes)?.let { return String(bytes, it) }
        strictUtf8(bytes)?.let { return it }
        return String(bytes, legacyCharset(bytes, Lang.code(language)))
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** UTF-16 saved without its mark: every other byte of Latin text is zero. */
    private fun utf16WithoutMark(bytes: ByteArray): Charset? {
        val n = minOf(bytes.size, 4096) and 1.inv()
        if (n < 8) return null
        var even = 0
        var odd = 0
        for (i in 0 until n) {
            if (bytes[i].toInt() == 0) if (i % 2 == 0) even++ else odd++
        }
        val half = n / 2
        return when {
            odd > half * 0.4 && even < half * 0.05 -> Charsets.UTF_16LE
            even > half * 0.4 && odd < half * 0.05 -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun charset(name: String, fallback: Charset = Charsets.ISO_8859_1): Charset = try {
        Charset.forName(name)
    } catch (_: Exception) {
        fallback
    }

    /** The one-byte (or East Asian) character set files of this language were usually saved in. */
    private fun legacyCharset(bytes: ByteArray, lang: String): Charset = when (lang) {
        "tr", "az", "ku" -> charset("windows-1254")
        "ru", "uk", "bg", "sr", "mk", "be", "kk" -> charset("windows-1251")
        "el" -> charset("windows-1253")
        "ar", "fa", "ur" -> charset("windows-1256")
        "he" -> charset("windows-1255")
        "pl", "cs", "sk", "hu", "ro", "hr", "sl", "bs", "sq" -> charset("windows-1250")
        "lt", "lv", "et" -> charset("windows-1257")
        "th" -> charset("windows-874")
        "vi" -> charset("windows-1258")
        "ja" -> charset("Shift_JIS")
        "ko" -> charset("EUC-KR")
        "zh" -> charset("GB18030")
        "" -> if (looksCyrillic(bytes)) charset("windows-1251") else charset("windows-1254")
        // Western European languages. Windows-1254 is Windows-1252 with six Icelandic letters
        // swapped for Turkish ones, so a Turkish file mislabelled as English still reads right.
        "is" -> charset("windows-1252")
        else -> charset("windows-1254")
    }

    /** In Cyrillic text nearly every letter is a high byte; in Latin text only the accented ones. */
    private fun looksCyrillic(bytes: ByteArray): Boolean {
        var high = 0
        var ascii = 0
        for (i in 0 until minOf(bytes.size, 16384)) {
            val v = bytes[i].toInt() and 0xFF
            if (v >= 0xC0) high++
            else if ((v in 0x41..0x5A) || (v in 0x61..0x7A)) ascii++
        }
        return high > 40 && high > ascii * 2
    }

    // ---------- Text converted twice ----------

    private val cp1252Specials: Map<Char, Int> by lazy {
        val map = HashMap<Char, Int>()
        val cp = charset("windows-1252")
        for (v in 0x80..0x9F) {
            val ch = String(byteArrayOf(v.toByte()), cp)
            if (ch.length == 1 && ch[0] != '�' && ch[0].code > 0xFF) map[ch[0]] = v
        }
        map
    }

    /**
     * "ÅŸ", "Ä±", "Ã¼": UTF-8 bytes that were read as Western European and saved again. If the
     * whole text turns back into valid UTF-8, that is what it was.
     */
    internal fun repairDoubleEncoding(text: String): String {
        var hits = 0
        for (i in 0 until text.length - 1) {
            val c = text[i]
            if (c in 'Â'..'Å') {
                val next = text[i + 1]
                if (next.code in 0x80..0xBF || cp1252Specials.containsKey(next)) hits++
            }
        }
        if (hits < 3) return text
        val out = ByteArray(text.length)
        for (i in text.indices) {
            val c = text[i]
            out[i] = when {
                c.code <= 0xFF -> c.code.toByte()
                else -> (cp1252Specials[c] ?: return text).toByte()
            }
        }
        return strictUtf8(out) ?: text
    }

    // ---------- Format ----------

    private val timing = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val vttOnlyTags = Regex("""</?(?:c|v|lang|ruby|rt)(?:[.\s][^>]*)?>|<\d{1,2}:\d{2}(?::\d{2})?[.,]\d{1,3}>""")
    private val assTags = Regex("""\{\\[^}]*\}""")

    internal fun sniff(text: String): String {
        val head = text.take(4096).trimStart()
        return when {
            head.startsWith("WEBVTT") -> VTT
            head.contains("[Script Info]", ignoreCase = true) || head.contains("[V4+ Styles]", ignoreCase = true) -> SSA
            head.startsWith("<?xml") || head.startsWith("<tt") -> TTML
            timing.containsMatchIn(head) -> SRT
            else -> ""
        }
    }

    internal class Cue(val start: Long, val end: Long, val text: String)

    private fun millis(stamp: String): Long {
        val parts = stamp.replace(',', '.').split(':')
        val seconds = parts.last().split('.')
        val whole = seconds[0].toLongOrNull() ?: 0L
        val fraction = seconds.getOrElse(1) { "0" }.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        val minutes = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: 0L
        val hours = if (parts.size >= 3) parts[parts.size - 3].toLongOrNull() ?: 0L else 0L
        return ((hours * 60 + minutes) * 60 + whole) * 1000 + fraction
    }

    /** Cues of a SubRip or WebVTT file: a timing line and the text lines under it. */
    internal fun cues(text: String): List<Cue> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val result = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val match = timing.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                // A file without blank lines between cues: the next cue's number, then its timing.
                val nextIsTiming = i + 1 < lines.size && timing.containsMatchIn(lines[i + 1])
                if (timing.containsMatchIn(lines[i]) || (nextIsTiming && lines[i].trim().all { it.isDigit() })) break
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].trimEnd())
                i++
            }
            val start = millis(match.groupValues[1])
            val end = millis(match.groupValues[2])
            if (end > start && body.isNotBlank()) result.add(Cue(start, end, body.toString()))
        }
        return result
    }

    private fun stamp(ms: Long, separator: Char): String =
        String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, separator, ms % 1000)

    internal fun toSrt(cues: List<Cue>): String {
        val out = StringBuilder()
        cues.forEachIndexed { index, cue ->
            out.append(index + 1).append('\n')
            out.append(stamp(cue.start, ',')).append(" --> ").append(stamp(cue.end, ',')).append('\n')
            out.append(cue.text.replace(vttOnlyTags, "")).append("\n\n")
        }
        return out.toString()
    }

    internal fun toVtt(cues: List<Cue>): String {
        val out = StringBuilder("WEBVTT\n\n")
        for (cue in cues) {
            out.append(stamp(cue.start, '.')).append(" --> ").append(stamp(cue.end, '.')).append('\n')
            out.append(cue.text.replace(assTags, "")).append("\n\n")
        }
        return out.toString()
    }
}
