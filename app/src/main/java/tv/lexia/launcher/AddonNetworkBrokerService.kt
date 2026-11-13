package tv.lexia.launcher

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale

/**
 * Network broker in the normal app UID. The isolated JS process can only make
 * requests through this narrow Binder interface and the user's exact-host grants.
 */
class AddonNetworkBrokerService : Service() {
    private val binder = AddonNetworkBrokerBinder { repository, providerId, requestJson ->
        val callerUid = android.os.Binder.getCallingUid()
        // Android assigns isolated service processes UIDs in this reserved range.
        // Process.isIsolated() only checks the current (broker) process, not the Binder caller.
        val request = JSONObject(requestJson)
        check(callerUid in 99_000..99_999) {
            "Only the isolated addon runtime may use this broker."
        }
        check(AddonNetworkPermissions.isSessionAllowed(this, repository, providerId, request.optString("sessionToken"))) {
            "Addon runtime session is invalid or expired."
        }
        runCatching { performFetch(repository, providerId, request) }
            .getOrElse { JSONObject().put("ok", false).put("status", 0)
                .put("statusText", (it.message ?: "Request failed").take(500))
                .put("body", "").put("url", "").toString() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun performFetch(repository: String, providerId: String, request: JSONObject): String {
        val rawUrl = request.optString("url")
        val method = request.optString("method", "GET").uppercase(Locale.ROOT)
        require(method in setOf("GET", "HEAD", "POST")) { "HTTP method not permitted." }
        var current = URL(rawUrl)
        val headers = request.optJSONObject("headers") ?: JSONObject()
        val body = request.optString("body")
        val bodyBytes = if (request.optString("bodyKind") == "base64") java.util.Base64.getDecoder().decode(body) else body.toByteArray(Charsets.UTF_8)
        require(bodyBytes.size <= MAX_BODY) { "Request body too large." }
        var redirects = 0
        while (true) {
            require(current.protocol.equals("https", true) && current.userInfo == null) {
                "Only HTTPS URLs without user information are allowed."
            }
            require(AddonNetworkPermissions.isAllowed(this, repository, providerId, current.toString())) {
                "Host not approved for this provider: ${current.host}"
            }
            val addresses = InetAddress.getAllByName(current.host)
            require(addresses.isNotEmpty() && addresses.all { address ->
                !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                    !address.isMulticastAddress
            }) { "Private or local network destinations are blocked." }

            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.requestMethod = method
                connection.setRequestProperty("Accept", "*/*")
                val allowedHeaders = setOf("accept", "content-type", "user-agent", "referer", "origin", "accept-language", "authorization", "cookie", "x-requested-with", "x-csrf-token", "x-api-key")
                val headerNames = headers.names() ?: org.json.JSONArray()
                for (i in 0 until headerNames.length()) {
                    val name = headerNames.optString(i)
                    require(name.lowercase(Locale.ROOT) in allowedHeaders) { "Header not permitted: $name" }
                    val value = headers.optString(name)
                    require(value.length <= 2048 && !value.contains("\r") && !value.contains("\n")) { "Invalid header." }
                    connection.setRequestProperty(name, value)
                }
                if (method == "POST" && body.isNotEmpty()) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(bodyBytes) }
                }
                val status = connection.responseCode
                if (status in 300..399) {
                    require(redirects++ < 5) { "Too many redirects." }
                    val location = connection.getHeaderField("Location") ?: error("Redirect without location.")
                    current = URL(current, location)
                    continue
                }
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val output = ByteArrayOutputStream()
                stream?.use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_RESPONSE) { "Response too large." }
                        output.write(buffer, 0, count)
                    }
                }
                val responseBytes = output.toByteArray()
                val text = output.toString(Charsets.UTF_8.name())
                val responseHeaders = JSONObject()
                connection.headerFields.filterKeys { it != null }.forEach { (key, values) ->
                    responseHeaders.put(key, values.joinToString(", "))
                }
                return JSONObject().put("ok", status in 200..299).put("status", status)
                    .put("statusText", connection.responseMessage ?: "")
                    .put("url", current.toString()).put("body", text)
                    .put("bodyBase64", java.util.Base64.getEncoder().encodeToString(responseBytes))
                    .put("headers", responseHeaders).toString()
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        private const val MAX_BODY = 256 * 1024
        private const val MAX_RESPONSE = 1024 * 1024
    }
}
