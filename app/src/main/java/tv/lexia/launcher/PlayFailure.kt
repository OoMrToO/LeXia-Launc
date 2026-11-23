package tv.lexia.launcher

/**
 * Why a source would not play, in a few plain Turkish words. The player's message is technical
 * (HTTP codes, ExoPlayer error names, MPV lines); it is sorted into what a viewer can understand.
 * [sourceFault] says whether the site is to blame: a dropped connection or a picture this TV cannot
 * show says nothing about the site, so it does not count against it. [tryAnother]: another source
 * may well play (not so when the connection itself is down).
 */
internal class PlayFailure(val text: String, val sourceFault: Boolean, val tryAnother: Boolean = true) {

    companion object {
        private val HTTP = Regex("""(?i)\bHTTP\s*(\d{3})\b""")

        fun of(raw: String?): PlayFailure {
            val s = raw.orEmpty()
            val low = s.lowercase()
            if (s == com.nuvio.tv.LeXiaPlayerActivity.REASON_TIMEOUT) return PlayFailure("Kaynak zamanında açılmadı", true)
            val http = HTTP.find(s)?.groupValues?.get(1)?.toIntOrNull()
            return when {
                http == 401 || http == 403 || http == 410 -> PlayFailure("Kaynağın bağlantı süresi dolmuş", true)
                http == 404 -> PlayFailure("Kaynak bu dosyayı kaldırmış", true)
                http == 429 -> PlayFailure("Kaynak şu an çok yoğun", true)
                http != null && http >= 500 -> PlayFailure("Kaynağın sunucusu yanıt vermedi", true)
                "timeout" in low || "timed out" in low -> PlayFailure("Kaynak zamanında açılmadı", true)
                "unknownhost" in low || "network_connection_failed" in low || "no address associated" in low ||
                    "unable to resolve host" in low || "connection reset" in low || "econnreset" in low ->
                    PlayFailure("İnternet bağlantısı koptu", false, tryAnother = false)
                "decoder" in low || "decoding" in low || "exceeds_capabilities" in low || "format_unsupported" in low ->
                    PlayFailure("Bu TV bu görüntü biçimini açamadı", false)
                "unrecognized" in low || "parsing" in low || "unplayable" in low || "geçersiz" in low || "malformed" in low ->
                    PlayFailure("Kaynak video yerine başka bir şey gönderdi", true)
                else -> PlayFailure("Kaynak açılmadı", true)
            }
        }
    }
}
