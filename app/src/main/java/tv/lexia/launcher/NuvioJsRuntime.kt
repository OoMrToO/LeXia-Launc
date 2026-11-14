package tv.lexia.launcher

import android.util.Base64
import android.util.Log
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

internal data class NuvioJsSubtitle(
    val url: String,
    val language: String = "Unknown",
    val headers: Map<String, String> = emptyMap()
)

internal data class NuvioJsStream(
    val title: String,
    val name: String,
    val url: String,
    val quality: String = "",
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<NuvioJsSubtitle> = emptyList()
)

/**
 * LeXia's own QuickJS adapter for the Nuvio scraper contract.
 *
 * JavaScript is executed in a fresh QuickJS context with a deliberately small native bridge:
 * HTTP fetch, URL parsing, base64 and a Jsoup-backed Cheerio subset. It has no filesystem,
 * reflection, Android Context, or arbitrary Java object access.
 */
internal class NuvioJsRuntime {

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(18, TimeUnit.SECONDS)
        .callTimeout(22, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun execute(
        code: String,
        mediaId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String
    ): List<NuvioJsStream> = withTimeout(55_000L) {
        executeInternal(code, mediaId, mediaType, season, episode, scraperId)
    }

    private suspend fun executeInternal(
        code: String,
        mediaId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String
    ): List<NuvioJsStream> {
        val documents = ConcurrentHashMap<String, Document>()
        val elements = ConcurrentHashMap<String, Element>()
        val activeCalls = ConcurrentHashMap.newKeySet<Call>()
        val resultJson = java.util.concurrent.atomic.AtomicReference("[]")
        val settingsJson = "{}"

        return try {
            quickJs(Dispatchers.IO) {
                define("console") {
                    function("log") { args -> Log.d("LeXiaPlugin:$scraperId", args.joinToString(" ") { it?.toString() ?: "null" }); null }
                    function("info") { args -> Log.i("LeXiaPlugin:$scraperId", args.joinToString(" ") { it?.toString() ?: "null" }); null }
                    function("warn") { args -> Log.w("LeXiaPlugin:$scraperId", args.joinToString(" ") { it?.toString() ?: "null" }); null }
                    function("error") { args -> Log.e("LeXiaPlugin:$scraperId", args.joinToString(" ") { it?.toString() ?: "null" }); null }
                    function("debug") { args -> Log.d("LeXiaPlugin:$scraperId", args.joinToString(" ") { it?.toString() ?: "null" }); null }
                }

                function("__native_fetch") { args ->
                    val url = args.getOrNull(0)?.toString().orEmpty()
                    val method = args.getOrNull(1)?.toString().orEmpty().ifBlank { "GET" }
                    val headers = args.getOrNull(2)?.toString().orEmpty().ifBlank { "{}" }
                    val body = args.getOrNull(3)?.toString().orEmpty()
                    val follow = args.getOrNull(4)?.toString()?.toBooleanStrictOrNull() ?: true
                    nativeFetch(url, method, headers, body, follow, activeCalls)
                }

                function("__native_parse_url") { args ->
                    val raw = args.getOrNull(0)?.toString().orEmpty()
                    val base = args.getOrNull(1)?.toString().orEmpty()
                    parseUrl(raw, base)
                }

                function("__native_base64") { args ->
                    val operation = args.getOrNull(0)?.toString().orEmpty()
                    val value = args.getOrNull(1)?.toString().orEmpty()
                    when (operation) {
                        "decode" -> try {
                            String(Base64.decode(value, Base64.DEFAULT), Charsets.ISO_8859_1)
                        } catch (_: Exception) { "" }
                        "encode" -> Base64.encodeToString(value.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP)
                        else -> ""
                    }
                }

                function("__get_scraper_id") { scraperId }
                function("__get_scraper_settings") { settingsJson }
                function("__get_tmdb_api_key") { "" }
                function("__capture_result") { args ->
                    resultJson.set(args.getOrNull(0)?.toString() ?: "[]")
                    null
                }

                function("__cheerio_load") { args ->
                    val html = args.getOrNull(0)?.toString().orEmpty()
                    val id = UUID.randomUUID().toString()
                    documents[id] = Jsoup.parse(html)
                    id
                }
                function("__cheerio_select") { args ->
                    val docId = args.getOrNull(0)?.toString().orEmpty()
                    val selector = normalizeSelector(args.getOrNull(1)?.toString().orEmpty())
                    val doc = documents[docId] ?: return@function "[]"
                    try {
                        val selected = doc.select(selector)
                        val ids = selected.mapIndexed { index, element ->
                            val id = "$docId:$index:${element.hashCode()}"
                            elements[id] = element
                            id
                        }
                        gson.toJson(ids)
                    } catch (_: Exception) { "[]" }
                }
                function("__cheerio_find") { args ->
                    val docId = args.getOrNull(0)?.toString().orEmpty()
                    val elementId = args.getOrNull(1)?.toString().orEmpty()
                    val selector = normalizeSelector(args.getOrNull(2)?.toString().orEmpty())
                    val parent = elements[elementId] ?: return@function "[]"
                    try {
                        val ids = parent.select(selector).mapIndexed { index, element ->
                            val id = "$docId:find:$index:${element.hashCode()}"
                            elements[id] = element
                            id
                        }
                        gson.toJson(ids)
                    } catch (_: Exception) { "[]" }
                }
                function("__cheerio_text") { args ->
                    val ids = args.getOrNull(0)?.toString().orEmpty().split(',').filter { it.isNotBlank() }
                    ids.mapNotNull { elements[it]?.text() }.joinToString(" ")
                }
                function("__cheerio_html") { args ->
                    val docId = args.getOrNull(0)?.toString().orEmpty()
                    val elementId = args.getOrNull(1)?.toString().orEmpty()
                    if (elementId.isBlank()) documents[docId]?.html().orEmpty()
                    else elements[elementId]?.html().orEmpty()
                }
                function("__cheerio_attr") { args ->
                    val id = args.getOrNull(0)?.toString().orEmpty()
                    val attr = args.getOrNull(1)?.toString().orEmpty()
                    elements[id]?.attr(attr)?.takeIf { it.isNotEmpty() } ?: "__UNDEFINED__"
                }
                function("__cheerio_next") { args ->
                    val id = args.getOrNull(0)?.toString().orEmpty()
                    val element = elements[id]?.nextElementSibling() ?: return@function "__NONE__"
                    val nextId = "$id:next:${element.hashCode()}"
                    elements[nextId] = element
                    nextId
                }
                function("__cheerio_prev") { args ->
                    val id = args.getOrNull(0)?.toString().orEmpty()
                    val element = elements[id]?.previousElementSibling() ?: return@function "__NONE__"
                    val prevId = "$id:prev:${element.hashCode()}"
                    elements[prevId] = element
                    prevId
                }
                function("__cheerio_parent") { args ->
                    val id = args.getOrNull(0)?.toString().orEmpty()
                    val element = elements[id]?.parent() ?: return@function "__NONE__"
                    val parentId = "$id:parent:${element.hashCode()}"
                    elements[parentId] = element
                    parentId
                }
                function("__cheerio_children") { args ->
                    val docId = args.getOrNull(0)?.toString().orEmpty()
                    val id = args.getOrNull(1)?.toString().orEmpty()
                    val selector = normalizeSelector(args.getOrNull(2)?.toString().orEmpty()).ifBlank { "*" }
                    val parent = elements[id] ?: return@function "[]"
                    try {
                        val ids = parent.children().select(selector).mapIndexed { index, element ->
                            val childId = "$docId:child:$index:${element.hashCode()}"
                            elements[childId] = element
                            childId
                        }
                        gson.toJson(ids)
                    } catch (_: Exception) { "[]" }
                }

                evaluate<Any?>(polyfillCode())
                evaluate<Any?>(
                    """
                    var module = { exports: {} };
                    var exports = module.exports;
                    (function() {
                        $code
                    })();
                    """.trimIndent()
                )
                function("__get_call_args") {
                    gson.toJson(
                        mapOf(
                            "tmdbId" to mediaId,
                            "mediaType" to mediaType,
                            "season" to season,
                            "episode" to episode
                        )
                    )
                }
                evaluate<Any?>(
                    """
                    (async function() {
                        try {
                            var getStreams = (module && module.exports && module.exports.getStreams) || globalThis.getStreams;
                            if (typeof getStreams !== "function") {
                                console.error("Nuvio plugin does not export getStreams");
                                __capture_result("[]");
                                return;
                            }
                            var args = JSON.parse(__get_call_args());
                            var result = await getStreams(args.tmdbId, args.mediaType, args.season, args.episode);
                            __capture_result(JSON.stringify(result || []));
                        } catch (error) {
                            console.error("Plugin execution failed:", error && (error.stack || error.message || error));
                            __capture_result("[]");
                        }
                    })();
                    """.trimIndent()
                )
            }
            parseResults(resultJson.get())
        } catch (error: Exception) {
            Log.e("LeXiaPlugin", "Scraper $scraperId failed: ${error.message}", error)
            emptyList()
        } finally {
            activeCalls.forEach { it.cancel() }
            activeCalls.clear()
            documents.clear()
            elements.clear()
        }
    }

    private fun nativeFetch(
        rawUrl: String,
        method: String,
        rawHeaders: String,
        body: String,
        followRedirects: Boolean,
        activeCalls: MutableSet<Call>
    ): String {
        return try {
            val headers = runCatching { JSONObject(rawHeaders) }.getOrDefault(JSONObject())
            val request = Request.Builder().url(rawUrl)
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = headers.optString(key)
                if (key.isNotBlank() && value.isNotBlank() && !key.equals("Accept-Encoding", true)) {
                    request.header(key, value)
                }
            }
            if (headers.optString("User-Agent").isBlank()) {
                request.header("User-Agent", "Mozilla/5.0 (Android TV; LeXia)")
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            when (method.uppercase()) {
                "POST" -> request.post(bytes.toRequestBody((headers.optString("Content-Type").ifBlank { "application/x-www-form-urlencoded" }).toMediaType()))
                "PUT" -> request.put(bytes.toRequestBody((headers.optString("Content-Type").ifBlank { "application/octet-stream" }).toMediaType()))
                "PATCH" -> request.patch(bytes.toRequestBody((headers.optString("Content-Type").ifBlank { "application/octet-stream" }).toMediaType()))
                "DELETE" -> if (body.isBlank()) request.delete() else request.delete(bytes.toRequestBody((headers.optString("Content-Type").ifBlank { "application/octet-stream" }).toMediaType()))
                "HEAD" -> request.head()
                else -> request.get()
            }
            val client = if (followRedirects) http else http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            val call = client.newCall(request.build())
            activeCalls.add(call)
            try {
                call.execute().use { response ->
                    val responseHeaders = JSONObject()
                    response.headers.names().forEach { name ->
                        responseHeaders.put(name, response.header(name).orEmpty())
                    }
                    val responseBody = response.body
                    val bytesRead = responseBody?.byteStream()?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (total < MAX_RESPONSE_BYTES) {
                            val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - total))
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                            total += count
                        }
                        output.toByteArray()
                    } ?: ByteArray(0)
                    val charset: Charset = responseBody?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    JSONObject()
                        .put("ok", response.isSuccessful)
                        .put("status", response.code)
                        .put("statusText", response.message)
                        .put("url", response.request.url.toString())
                        .put("body", String(bytesRead, charset))
                        .put("headers", responseHeaders)
                        .toString()
                }
            } finally {
                activeCalls.remove(call)
            }
        } catch (error: Exception) {
            JSONObject()
                .put("ok", false)
                .put("status", 0)
                .put("statusText", error.message ?: "Fetch failed")
                .put("url", rawUrl)
                .put("body", "")
                .put("headers", JSONObject())
                .toString()
        }
    }

    private fun parseUrl(raw: String, base: String): String {
        return try {
            val uri = if (base.isNotBlank()) URI(base).resolve(raw) else URI(raw)
            val scheme = uri.scheme.orEmpty()
            val host = uri.host.orEmpty()
            val port = if (uri.port >= 0) uri.port.toString() else ""
            JSONObject()
                .put("href", uri.toString())
                .put("protocol", if (scheme.isBlank()) "" else "$scheme:")
                .put("host", if (port.isBlank()) host else "$host:$port")
                .put("hostname", host)
                .put("port", port)
                .put("pathname", uri.rawPath?.ifBlank { "/" } ?: "/")
                .put("search", uri.rawQuery?.let { "?$it" } ?: "")
                .put("hash", uri.rawFragment?.let { "#$it" } ?: "")
                .put("origin", if (host.isBlank()) "" else "$scheme://$host${if (port.isBlank()) "" else ":$port"}")
                .toString()
        } catch (_: Exception) {
            JSONObject().put("href", raw).put("protocol", "").put("host", "").put("hostname", "")
                .put("port", "").put("pathname", "/").put("search", "").put("hash", "").put("origin", "").toString()
        }
    }

    private fun normalizeSelector(selector: String): String =
        selector.replace(Regex(""":contains\(["']([^"']+)["']\)"""), ":contains($1)")

    private fun parseResults(raw: String): List<NuvioJsStream> {
        return try {
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val items: List<Map<String, Any?>> = gson.fromJson(raw, type) ?: emptyList()
            items.mapNotNull { item ->
                val url = when (val value = item["url"]) {
                    is String -> value
                    is Map<*, *> -> value["url"]?.toString().orEmpty()
                    else -> ""
                }
                if (!isHttps(url)) return@mapNotNull null
                val headers = (item["headers"] as? Map<*, *>)?.mapNotNull { (key, value) ->
                    val k = key?.toString().orEmpty()
                    val v = value?.toString().orEmpty()
                    if (k.isBlank() || v.isBlank() || '\r' in v || '\n' in v) null else k to v
                }?.toMap().orEmpty()
                val subtitles = (item["subtitles"] as? List<*>)?.mapNotNull { rawSubtitle ->
                    val subtitle = rawSubtitle as? Map<*, *> ?: return@mapNotNull null
                    val subtitleUrl = subtitle["url"]?.toString().orEmpty()
                    if (!isHttps(subtitleUrl)) return@mapNotNull null
                    NuvioJsSubtitle(
                        url = subtitleUrl,
                        language = subtitle["language"]?.toString() ?: subtitle["lang"]?.toString() ?: "Unknown",
                        headers = (subtitle["headers"] as? Map<*, *>)?.mapNotNull { (k, v) ->
                            val key = k?.toString().orEmpty()
                            val value = v?.toString().orEmpty()
                            if (key.isBlank() || value.isBlank()) null else key to value
                        }?.toMap().orEmpty()
                    )
                }.orEmpty()
                NuvioJsStream(
                    title = item["title"]?.toString()?.takeIf { it.isNotBlank() } ?: item["name"]?.toString().orEmpty().ifBlank { "Kaynak" },
                    name = item["name"]?.toString().orEmpty(),
                    url = url,
                    quality = item["quality"]?.toString().orEmpty(),
                    headers = headers,
                    subtitles = subtitles
                )
            }.take(MAX_STREAMS)
        } catch (error: Exception) {
            Log.w("LeXiaPlugin", "Invalid scraper result: ${error.message}")
            emptyList()
        }
    }

    private fun isHttps(raw: String): Boolean =
        raw.toHttpUrlOrNull()?.scheme == "https"

    private fun polyfillCode(): String = """
        globalThis.global = globalThis;
        globalThis.window = globalThis;
        globalThis.location = globalThis.location || { href: "", origin: "", hostname: "", protocol: "https:" };
        globalThis.document = globalThis.document || { location: globalThis.location };
        globalThis.navigator = globalThis.navigator || { userAgent: "Mozilla/5.0 (Android TV; LeXia)" };
        globalThis.SCRAPER_ID = __get_scraper_id();
        globalThis.SCRAPER_SETTINGS = JSON.parse(__get_scraper_settings());
        globalThis.TMDB_API_KEY = globalThis.TMDB_API_KEY || __get_tmdb_api_key();

        globalThis.atob = function(value) { return __native_base64("decode", String(value)); };
        globalThis.btoa = function(value) { return __native_base64("encode", String(value)); };

        globalThis.Buffer = globalThis.Buffer || {
            from: function(value, encoding) {
                var source = String(value);
                var binary = encoding === "base64" ? __native_base64("decode", source) : source;
                return {
                    toString: function(outputEncoding) {
                        var enc = outputEncoding || "utf8";
                        if (enc === "base64") return __native_base64("encode", binary);
                        if (enc === "binary" || enc === "latin1") return binary;
                        if (enc === "hex") {
                            var out = "";
                            for (var i = 0; i < binary.length; i++) out += ("0" + binary.charCodeAt(i).toString(16)).slice(-2);
                            return out;
                        }
                        try { return decodeURIComponent(escape(binary)); } catch (e) { return binary; }
                    },
                    length: binary.length
                };
            }
        };

        globalThis.URLSearchParams = globalThis.URLSearchParams || function(initial) {
            this._items = [];
            var self = this;
            if (typeof initial === "string") {
                initial.replace(/^\?/, "").split("&").forEach(function(part) {
                    if (!part) return;
                    var pair = part.split("=");
                    self.append(decodeURIComponent((pair[0] || "").replace(/\+/g, " ")), decodeURIComponent((pair.slice(1).join("=") || "").replace(/\+/g, " ")));
                });
            } else if (initial && typeof initial === "object") {
                Object.keys(initial).forEach(function(key) { self.append(key, initial[key]); });
            }
        };
        URLSearchParams.prototype.append = function(k, v) { this._items.push([String(k), String(v)]); };
        URLSearchParams.prototype.set = function(k, v) {
            this.delete(k); this.append(k, v);
        };
        URLSearchParams.prototype.get = function(k) {
            var item = this._items.find(function(x) { return x[0] === String(k); });
            return item ? item[1] : null;
        };
        URLSearchParams.prototype.delete = function(k) {
            this._items = this._items.filter(function(x) { return x[0] !== String(k); });
        };
        URLSearchParams.prototype.toString = function() {
            return this._items.map(function(x) { return encodeURIComponent(x[0]) + "=" + encodeURIComponent(x[1]); }).join("&");
        };

        globalThis.Headers = globalThis.Headers || function(init) {
            this._values = {};
            var self = this;
            if (init && typeof init.forEach === "function") init.forEach(function(v, k) { self.set(k, v); });
            else if (init && typeof init === "object") Object.keys(init).forEach(function(k) { self.set(k, init[k]); });
        };
        Headers.prototype.set = function(k, v) { this._values[String(k).toLowerCase()] = String(v); };
        Headers.prototype.get = function(k) { return this._values[String(k).toLowerCase()] || null; };
        Headers.prototype.has = function(k) { return this.get(k) !== null; };
        Headers.prototype.forEach = function(fn) { Object.keys(this._values).forEach(function(k) { fn(this._values[k], k); }, this); };
        Headers.prototype.entries = function() { return Object.entries(this._values); };

        globalThis.URL = globalThis.URL || function(value, base) {
            var parsed = JSON.parse(__native_parse_url(String(value), base ? String(base) : ""));
            this.href = parsed.href;
            this.protocol = parsed.protocol;
            this.host = parsed.host;
            this.hostname = parsed.hostname;
            this.port = parsed.port;
            this.pathname = parsed.pathname;
            this.search = parsed.search;
            this.hash = parsed.hash;
            this.origin = parsed.origin;
            this.toString = function() { return this.href; };
            this.searchParams = new URLSearchParams(this.search);
        };

        globalThis.fetch = function(input, init) {
            init = init || {};
            var url = input && typeof input === "object" && input.url ? input.url : String(input);
            var method = init.method || "GET";
            var headers = new Headers(init.headers || {});
            var headerMap = {};
            headers.forEach(function(v, k) { headerMap[k] = v; });
            var body = init.body == null ? "" : String(init.body);
            var follow = init.redirect !== "manual";
            var response = JSON.parse(__native_fetch(url, method, JSON.stringify(headerMap), body, String(follow)));
            var responseHeaders = new Headers(response.headers || {});
            var textBody = response.body || "";
            var result = {
                ok: !!response.ok,
                status: response.status || 0,
                statusText: response.statusText || "",
                url: response.url || url,
                redirected: (response.url || url) !== url,
                headers: responseHeaders,
                text: function() { return Promise.resolve(textBody); },
                json: function() {
                    try { return Promise.resolve(JSON.parse(textBody)); }
                    catch (e) { return Promise.reject(e); }
                },
                arrayBuffer: function() { return Promise.resolve(textBody); },
                blob: function() { return Promise.resolve({ text: function() { return Promise.resolve(textBody); } }); },
                clone: function() { return result; }
            };
            return Promise.resolve(result);
        };

        globalThis.AbortController = globalThis.AbortController || function() {
            this.signal = { aborted: false, addEventListener: function() {}, removeEventListener: function() {} };
            this.abort = function() { this.signal.aborted = true; };
        };
        globalThis.setTimeout = globalThis.setTimeout || function(fn) { Promise.resolve().then(fn); return 0; };
        globalThis.clearTimeout = globalThis.clearTimeout || function() {};
        globalThis.setInterval = globalThis.setInterval || function(fn) { Promise.resolve().then(fn); return 0; };
        globalThis.clearInterval = globalThis.clearInterval || function() {};

        var cheerio = {
            load: function(html) {
                var docId = __cheerio_load(String(html || ""));
                function wrap(ids) {
                    var w = {
                        _ids: ids,
                        length: ids.length,
                        each: function(fn) {
                            ids.forEach(function(id, index) { var item = wrap([id]); fn.call(item, index, item); });
                            return w;
                        },
                        map: function(fn) {
                            var out = [];
                            ids.forEach(function(id, index) { var item = wrap([id]); var v = fn.call(item, index, item); if (v != null) out.push(v); });
                            return { get: function(i) { return typeof i === "number" ? out[i] : out; }, toArray: function() { return out; }, length: out.length };
                        },
                        find: function(selector) {
                            var out = [];
                            ids.forEach(function(id) { out = out.concat(JSON.parse(__cheerio_find(docId, id, selector))); });
                            return wrap(out);
                        },
                        text: function() { return ids.length ? __cheerio_text(ids.join(",")) : ""; },
                        html: function() { return ids.length ? __cheerio_html(docId, ids[0]) : ""; },
                        attr: function(name) { if (!ids.length) return undefined; var v = __cheerio_attr(ids[0], name); return v === "__UNDEFINED__" ? undefined : v; },
                        prop: function(name) { return this.attr(name); },
                        val: function() { return this.attr("value"); },
                        first: function() { return wrap(ids.length ? [ids[0]] : []); },
                        last: function() { return wrap(ids.length ? [ids[ids.length - 1]] : []); },
                        eq: function(i) { return wrap(i >= 0 && i < ids.length ? [ids[i]] : []); },
                        get: function(i) { return typeof i === "number" ? (ids[i] ? wrap([ids[i]]) : undefined) : ids.map(function(id) { return wrap([id]); }); },
                        toArray: function() { return ids.map(function(id) { return wrap([id]); }); },
                        next: function() { return wrap(ids.map(function(id) { return __cheerio_next(id); }).filter(function(id) { return id !== "__NONE__"; })); },
                        prev: function() { return wrap(ids.map(function(id) { return __cheerio_prev(id); }).filter(function(id) { return id !== "__NONE__"; })); },
                        parent: function() { return wrap(ids.map(function(id) { return __cheerio_parent(id); }).filter(function(id) { return id !== "__NONE__"; })); },
                        children: function(selector) {
                            var out = [];
                            ids.forEach(function(id) { out = out.concat(JSON.parse(__cheerio_children(docId, id, selector || "*"))); });
                            return wrap(out);
                        },
                        filter: function(fn) {
                            if (typeof fn !== "function") return w;
                            return wrap(ids.filter(function(id, index) { var item = wrap([id]); return !!fn.call(item, index, item); }));
                        },
                        remove: function() { return w; },
                        is: function() { return ids.length > 0; }
                    };
                    return w;
                }
                function $(selector) {
                    if (selector && typeof selector === "object" && selector._ids) return selector;
                    return wrap(JSON.parse(__cheerio_select(docId, String(selector || ""))));
                }
                $.html = function() { return __cheerio_html(docId, ""); };
                $.root = function() { return wrap(JSON.parse(__cheerio_select(docId, "html"))); };
                return $;
            }
        };
        globalThis.cheerio = cheerio;

        globalThis.require = function(name) {
            if (name === "cheerio" || name === "cheerio-without-node-native" || name === "react-native-cheerio") return cheerio;
            if (name === "fs") return { existsSync: function() { return false; }, readFileSync: function() { return null; } };
            if (name === "path") return { resolve: function() { return Array.prototype.join.call(arguments, "/"); }, join: function() { return Array.prototype.join.call(arguments, "/"); } };
            throw new Error("Module not available in LeXia plugin sandbox: " + name);
        };

        if (!Array.prototype.flat) Array.prototype.flat = function() { return [].concat.apply([], this); };
        if (!Array.prototype.flatMap) Array.prototype.flatMap = function(fn) { return this.map(fn).flat(); };
    """.trimIndent()

    private fun isHttps(raw: String): Boolean =
        runCatching { URI(raw).scheme.equals("https", true) }.getOrDefault(false)

    private companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_STREAMS = 40
    }
}
