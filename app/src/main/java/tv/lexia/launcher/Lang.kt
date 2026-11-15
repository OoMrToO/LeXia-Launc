package tv.lexia.launcher

import java.util.Locale

/**
 * Language names as add-ons and video files write them ("tur", "tr-TR", "Turkish", "Türkçe")
 * brought down to one two-letter code, and back to a Turkish name for the screen.
 */
object Lang {
    private val tr = Locale.forLanguageTag("tr-TR")

    /** Three-letter codes (both ISO 639-2 spellings) and common names, to the two-letter code. */
    private val known: Map<String, String> = HashMap<String, String>().apply {
        fun put(code: String, vararg names: String) {
            this[code] = code
            names.forEach { this[it] = code }
        }
        put("tr", "tur", "turkish", "türkçe", "turkce", "turkçe", "türkce")
        put("en", "eng", "english", "ingilizce")
        put("de", "ger", "deu", "german", "deutsch", "almanca")
        put("fr", "fre", "fra", "french", "français", "francais", "fransızca")
        put("es", "spa", "spanish", "español", "espanol", "ispanyolca")
        put("it", "ita", "italian", "italiano", "italyanca")
        put("pt", "por", "pob", "portuguese", "português", "portekizce")
        put("ru", "rus", "russian", "русский", "rusça")
        put("ar", "ara", "arabic", "arapça")
        put("fa", "per", "fas", "persian", "farsi", "farsça")
        put("az", "aze", "azerbaijani", "azerice")
        put("ku", "kur", "kurdish", "kürtçe")
        put("ja", "jpn", "japanese", "japonca")
        put("ko", "kor", "korean", "korece")
        put("zh", "chi", "zho", "chinese", "çince")
        put("hi", "hin", "hindi", "hintçe")
        put("nl", "dut", "nld", "dutch", "felemenkçe", "hollandaca")
        put("sv", "swe", "swedish", "isveççe")
        put("no", "nor", "nob", "norwegian", "norveççe")
        put("da", "dan", "danish", "danca")
        put("fi", "fin", "finnish", "fince")
        put("pl", "pol", "polish", "lehçe")
        put("cs", "cze", "ces", "czech", "çekçe")
        put("sk", "slo", "slk", "slovak", "slovakça")
        put("hu", "hun", "hungarian", "macarca")
        put("ro", "rum", "ron", "romanian", "rumence")
        put("bg", "bul", "bulgarian", "bulgarca")
        put("el", "gre", "ell", "greek", "yunanca")
        put("he", "heb", "iw", "hebrew", "ibranice")
        put("uk", "ukr", "ukrainian", "ukraynaca")
        put("sr", "srp", "scc", "serbian", "sırpça")
        put("hr", "hrv", "scr", "croatian", "hırvatça")
        put("bs", "bos", "bosnian", "boşnakça")
        put("sl", "slv", "slovenian", "slovence")
        put("sq", "alb", "sqi", "albanian", "arnavutça")
        put("mk", "mac", "mkd", "macedonian", "makedonca")
        put("id", "ind", "indonesian", "endonezce")
        put("ms", "may", "msa", "malay", "malayca")
        put("th", "tha", "thai", "tayca")
        put("vi", "vie", "vietnamese", "vietnamca")
        put("lt", "lit", "lithuanian", "litvanca")
        put("lv", "lav", "latvian", "letonca")
        put("et", "est", "estonian", "estonca")
        put("ur", "urd", "urdu", "urduca")
        put("kk", "kaz", "kazakh", "kazakça")
        put("be", "bel", "belarusian")
        put("ca", "cat", "catalan", "katalanca")
        put("is", "ice", "isl", "icelandic", "izlandaca")
    }

    /** "tr" for "tur", "TR", "tr-TR", "Turkish (forced)"...; empty when the text names no language. */
    fun code(raw: String?): String {
        val text = raw?.trim()?.replace('İ', 'i')?.lowercase(Locale.ROOT)?.replace("\u0307", "").orEmpty()
        if (text.isEmpty() || text == "und" || text == "unknown" || text == "mul" || text == "zxx") return ""
        known[text]?.let { return it }
        val head = text.split('-', '_', ' ', '(', '[', '.', '/', ',').firstOrNull { it.isNotEmpty() } ?: return ""
        return known[head].orEmpty()
    }

    /** The language's Turkish name, capitalised ("Türkçe", "İngilizce"); empty for an unknown code. */
    fun name(code: String): String {
        if (code.isBlank()) return ""
        val name = Locale.forLanguageTag(code).getDisplayLanguage(tr)
        if (name.isBlank() || name.equals(code, ignoreCase = true)) return ""
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(tr) else it.toString() }
    }

    /** The TV's own language as a two-letter code; Turkish when it cannot be read. */
    fun device(): String = code(Locale.getDefault().language).ifBlank { "tr" }
}
