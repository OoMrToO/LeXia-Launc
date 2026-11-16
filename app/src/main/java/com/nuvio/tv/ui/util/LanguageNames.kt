// LeXia stand-in for Nuvio's ui/util/LanguageUtils.kt (needs Nuvio's string resources). Not Nuvio code.
package com.nuvio.tv.ui.util

import java.util.Locale

fun languageCodeToName(code: String): String {
    val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
    return name.ifBlank { code }
}
