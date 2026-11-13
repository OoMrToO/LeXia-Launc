package tv.lexia.launcher

import android.content.Context
import java.net.IDN
import java.util.Locale

/**
 * Explicit, per-repository and per-provider HTTPS host grants.
 * Grants are exact hostnames: wildcards, URL paths, ports and IP literals are rejected.
 * The isolated runtime accesses the network through a provider-scoped Binder broker.
 */
object AddonNetworkPermissions {
    private const val PREFS = "lexia_addon_network_permissions"
    private const val SESSION_TOKEN = "active_provider_session_token"
    private const val SESSION_REPOSITORY = "active_provider_session_repository"
    private const val SESSION_PROVIDER = "active_provider_session_id"
    private const val SESSION_EXPIRY = "active_provider_session_expiry"

    fun registerSession(context: Context, repository: String, providerId: String, token: String) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SESSION_TOKEN, token)
            .putString(SESSION_REPOSITORY, repository)
            .putString(SESSION_PROVIDER, providerId)
            .putLong(SESSION_EXPIRY, System.currentTimeMillis() + 3 * 60 * 1000L)
            .commit()) { "Sağlayıcı oturumu başlatılamadı." }
    }

    fun isSessionAllowed(context: Context, repository: String, providerId: String, token: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return token.isNotBlank() &&
            prefs.getString(SESSION_TOKEN, null) == token &&
            prefs.getString(SESSION_REPOSITORY, null) == repository &&
            prefs.getString(SESSION_PROVIDER, null) == providerId &&
            prefs.getLong(SESSION_EXPIRY, 0L) > System.currentTimeMillis()
    }

    fun clearSession(context: Context, token: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(SESSION_TOKEN, null) == token) {
            prefs.edit().remove(SESSION_TOKEN).remove(SESSION_REPOSITORY)
                .remove(SESSION_PROVIDER).remove(SESSION_EXPIRY).apply()
        }
    }

    fun grantedHosts(context: Context, repository: String, providerId: String): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(key(repository, providerId), emptySet())
            .orEmpty().toSet()

    fun grant(context: Context, repository: String, providerId: String, rawHost: String) {
        val host = normalizeHost(rawHost)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val grants = prefs.getStringSet(key(repository, providerId), emptySet()).orEmpty().toMutableSet()
        grants.add(host)
        check(prefs.edit().putStringSet(key(repository, providerId), grants).commit()) {
            "Ağ izni kaydedilemedi."
        }
    }

    fun revoke(context: Context, repository: String, providerId: String, host: String) {
        val normalized = normalizeHost(host)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val grants = prefs.getStringSet(key(repository, providerId), emptySet()).orEmpty().toMutableSet()
        grants.remove(normalized)
        prefs.edit().putStringSet(key(repository, providerId), grants).apply()
    }

    fun isAllowed(context: Context, repository: String, providerId: String, rawUrl: String): Boolean {
        val uri = runCatching { android.net.Uri.parse(rawUrl) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", true) || uri.userInfo != null) return false
        val host = runCatching { normalizeHost(uri.host.orEmpty()) }.getOrNull() ?: return false
        return host in grantedHosts(context, repository, providerId)
    }

    fun normalizeHost(raw: String): String {
        val value = raw.trim().trimEnd('.')
        require(value.isNotBlank() && value.length <= 253) { "Alan adı geçersiz." }
        require(!value.contains("://") && !value.contains('/') && !value.contains(':') &&
            !value.contains('*') && !value.contains('@') && !value.contains('?') && !value.contains('#')) {
            "Yalnızca tam alan adı girilebilir; joker karakter ve URL kabul edilmez."
        }
        val ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        require(ascii.contains('.') && ascii.split('.').all { label ->
            label.isNotBlank() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-')
        }) { "Tam alan adı gerekli." }
        require(ascii != "localhost" && !ascii.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
            "Yerel adresler ve IP adresleri izin listesine eklenemez."
        }
        return ascii
    }

    private fun key(repository: String, providerId: String): String =
        "provider:" + repository.trimEnd('/') + ":" + providerId
}
