package tv.lexia.launcher

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Looper
import android.os.Messenger
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Executes untrusted provider JavaScript in an isolated Android UID. */
class AddonSandboxService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger
    @Volatile private var networkBroker: IAddonNetworkBroker? = null
    private val brokerReady = CountDownLatch(1)
    private val brokerConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            networkBroker = IAddonNetworkBroker.asInterface(checkNotNull(service))
            brokerReady.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { networkBroker = null }
    }

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("lexia-addon-js").apply { start() }
        messenger = Messenger(Handler(worker.looper, ::handleMessage))
        bindService(Intent(this, AddonNetworkBrokerService::class.java), brokerConnection, BIND_AUTO_CREATE)
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handleMessage(message: Message): Boolean {
        if (message.what != MSG_EVALUATE) return false
        val reply = message.replyTo ?: return true
        val data = message.data
        val requestId = data.getInt(KEY_REQUEST_ID)
        val source = data.getString(KEY_SOURCE).orEmpty()
        val repository = data.getString(KEY_REPOSITORY).orEmpty()
        val providerId = data.getString(KEY_PROVIDER_ID).orEmpty()
        val callArgs = data.getString(KEY_CALL_ARGS).orEmpty()
        val settingsJson = data.getString(KEY_SETTINGS).orEmpty().ifBlank { "{}" }
        val sessionToken = data.getString(KEY_SESSION_TOKEN).orEmpty()
        if (source.isBlank() || source.length > MAX_SOURCE_CHARS) {
            sendReply(reply, requestId, false, "JavaScript source is empty or exceeds the size limit.")
            return true
        }
        val watchdogHandler = Handler(Looper.getMainLooper())
        val watchdog = Runnable { android.os.Process.killProcess(android.os.Process.myPid()) }
        watchdogHandler.postDelayed(watchdog, EXECUTION_TIMEOUT_MS + WATCHDOG_GRACE_MS)
        try {
            check(brokerReady.await(5, TimeUnit.SECONDS) && networkBroker != null) {
                "Addon network broker is unavailable."
            }
            val documentCache = mutableMapOf<String, Document>()
            val elementCache = mutableMapOf<String, Element>()
            val result = runBlocking(Dispatchers.IO) {
                withTimeout(EXECUTION_TIMEOUT_MS) {
                    quickJs(Dispatchers.IO) {
                        function("__native_fetch") { args ->
                            val request = JSONObject()
                                .put("url", args.getOrNull(0)?.toString().orEmpty())
                                .put("method", args.getOrNull(1)?.toString() ?: "GET")
                                .put("headers", runCatching { JSONObject(args.getOrNull(2)?.toString() ?: "{}") }.getOrDefault(JSONObject()))
                                .put("bodyKind", args.getOrNull(3)?.toString() ?: "none")
                                .put("body", args.getOrNull(4)?.toString().orEmpty())
                                .put("followRedirects", args.getOrNull(5)?.toString()?.toBooleanStrictOrNull() ?: true)
                                .put("sessionToken", sessionToken)
                            checkNotNull(networkBroker).fetch(repository, providerId, request.toString())
                        }
                        function("__parse_url") { args ->
                            val uri = URI(args.getOrNull(0)?.toString().orEmpty())
                            JSONObject()
                                .put("protocol", uri.scheme?.plus(":") ?: "")
                                .put("host", uri.rawAuthority ?: "")
                                .put("hostname", uri.host ?: "")
                                .put("port", if (uri.port < 0) "" else uri.port.toString())
                                .put("pathname", uri.rawPath ?: "/")
                                .put("search", uri.rawQuery?.let { "?$it" } ?: "")
                                .put("hash", uri.rawFragment?.let { "#$it" } ?: "")
                                .toString()
                        }
                        function("__cheerio_load") { args ->
                            val docId = java.util.UUID.randomUUID().toString()
                            documentCache[docId] = Jsoup.parse(args.getOrNull(0)?.toString().orEmpty())
                            docId
                        }
                        function("__cheerio_select") { args ->
                            val docId = args.getOrNull(0)?.toString().orEmpty()
                            val selector = args.getOrNull(1)?.toString().orEmpty()
                            val ids = runCatching {
                                documentCache[docId]?.select(selector)?.mapIndexed { index, element ->
                                    val id = "$docId:$index:${element.hashCode()}"
                                    elementCache[id] = element
                                    id
                                }.orEmpty()
                            }.getOrDefault(emptyList())
                            JSONArray(ids).toString()
                        }
                        function("__cheerio_find") { args ->
                            val docId = args.getOrNull(0)?.toString().orEmpty()
                            val parentId = args.getOrNull(1)?.toString().orEmpty()
                            val selector = args.getOrNull(2)?.toString().orEmpty()
                            val ids = runCatching {
                                elementCache[parentId]?.select(selector)?.mapIndexed { index, element ->
                                    val id = "$docId:find:$index:${element.hashCode()}"
                                    elementCache[id] = element
                                    id
                                }.orEmpty()
                            }.getOrDefault(emptyList())
                            JSONArray(ids).toString()
                        }
                        function("__cheerio_text") { args ->
                            args.getOrNull(1)?.toString().orEmpty().split(',').mapNotNull { elementCache[it]?.text() }.joinToString(" ")
                        }
                        function("__cheerio_html") { args ->
                            val docId = args.getOrNull(0)?.toString().orEmpty()
                            val elementId = args.getOrNull(1)?.toString().orEmpty()
                            if (elementId.isEmpty()) documentCache[docId]?.html().orEmpty()
                            else elementCache[elementId]?.html().orEmpty()
                        }
                        function("__cheerio_inner_html") { args ->
                            elementCache[args.getOrNull(1)?.toString().orEmpty()]?.html().orEmpty()
                        }
                        function("__cheerio_attr") { args ->
                            val elementId = args.getOrNull(1)?.toString().orEmpty()
                            val attr = args.getOrNull(2)?.toString().orEmpty()
                            elementCache[elementId]?.attr(attr)?.takeIf { it.isNotEmpty() } ?: "__UNDEFINED__"
                        }
                        function("__cheerio_next") { args ->
                            val docId = args.getOrNull(0)?.toString().orEmpty()
                            val element = elementCache[args.getOrNull(1)?.toString().orEmpty()]
                            val next = element?.nextElementSibling()
                            if (next == null) "__NONE__" else {
                                val id = "$docId:next:${next.hashCode()}"
                                elementCache[id] = next
                                id
                            }
                        }
                        function("__cheerio_prev") { args ->
                            val docId = args.getOrNull(0)?.toString().orEmpty()
                            val element = elementCache[args.getOrNull(1)?.toString().orEmpty()]
                            val prev = element?.previousElementSibling()
                            if (prev == null) "__NONE__" else {
                                val id = "$docId:prev:${prev.hashCode()}"
                                elementCache[id] = prev
                                id
                            }
                        }
                        var resultJson = "[]"
                        function("__capture_result") { args ->
                            resultJson = args.getOrNull(0)?.toString() ?: "[]"
                            null
                        }
                        function("__get_call_args") { callArgs }
                        function("__get_scraper_id") { providerId }
                        function("__get_scraper_settings") { settingsJson }
                        loadCryptoJsSource()?.let { cryptoSource -> evaluate<Any?>(cryptoSource) }
                        evaluate<Any?>(providerScript(source))
                        evaluate<Any?>(providerCallScript())
                        resultJson
                    }
                }
            }
            if (result.length > MAX_RESULT_CHARS) {
                sendReply(reply, requestId, false, "Provider result exceeds the size limit.")
            } else {
                sendReply(reply, requestId, true, result)
            }
        } catch (error: Throwable) {
            sendReply(reply, requestId, false, (error.message ?: error.javaClass.simpleName).take(1000))
        } finally {
            watchdogHandler.removeCallbacks(watchdog)
        }
        return true
    }

    private fun providerScript(source: String): String = """
        globalThis.global = globalThis; globalThis.window = globalThis; globalThis.self = globalThis;
        globalThis.SCRAPER_ID = __get_scraper_id();
        globalThis.SCRAPER_SETTINGS = JSON.parse(__get_scraper_settings());
        globalThis.console = globalThis.console || {log:function(){},info:function(){},warn:function(){},error:function(){},debug:function(){}};
        if (typeof atob === "undefined") globalThis.atob = function(s) {
          var c="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/",o="",b=0,n=0;
          String(s).replace(/=+$/,"").split("").forEach(function(ch){var v=c.indexOf(ch);if(v<0)return;b=(b<<6)|v;n+=6;if(n>=8){n-=8;o+=String.fromCharCode((b>>n)&255);}});
          return o;
        };
        if (typeof btoa === "undefined") globalThis.btoa = function(s) {
          var c="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/",o="";
          for(var i=0;i<s.length;i+=3){var a=s.charCodeAt(i),hb=i+1<s.length,hc=i+2<s.length,b=hb?s.charCodeAt(i+1):0,d=hc?s.charCodeAt(i+2):0;
            o+=c[a>>2]+c[((a&3)<<4)|(b>>4)]+(hb?c[((b&15)<<2)|(d>>6)]:"=")+(hc?c[d&63]:"=");}
          return o;
        };
        function URLSearchParams(s){this.p=[];var self=this;if(typeof s==="string")s.replace(/^\?/,"").split("&").forEach(function(x){if(x){var a=x.split("=");self.append(decodeURIComponent(a[0]),decodeURIComponent(a.slice(1).join("=")));}});}
        URLSearchParams.prototype.append=function(k,v){this.p.push([String(k),String(v)]);};
        URLSearchParams.prototype.get=function(k){var x=this.p.find(function(p){return p[0]===String(k);});return x?x[1]:null;};
        URLSearchParams.prototype.getAll=function(k){return this.p.filter(function(p){return p[0]===String(k);}).map(function(p){return p[1];});};
        URLSearchParams.prototype.set=function(k,v){this.delete(k);this.append(k,v);};
        URLSearchParams.prototype.has=function(k){return this.get(k)!==null;};
        URLSearchParams.prototype.delete=function(k){this.p=this.p.filter(function(p){return p[0]!==String(k);});};
        URLSearchParams.prototype.forEach=function(fn){this.p.forEach(function(p){fn(p[1],p[0]);});};
        URLSearchParams.prototype.toString=function(){return this.p.map(function(p){return encodeURIComponent(p[0])+"="+encodeURIComponent(p[1]);}).join("&");};
        function URL(s,b){var u=String(s);if(b&&!/^https?:\/\//i.test(u)){var x=String(b).match(/^https?:\/\/[^/]+/i);u=u.charAt(0)==="/"?((x?x[0]:"")+u):String(b).replace(/[^/]*$/,"")+u;}
          var d=JSON.parse(__parse_url(u));this.href=u;this.protocol=d.protocol;this.host=d.host;this.hostname=d.hostname;this.port=d.port;this.pathname=d.pathname;this.search=d.search;this.hash=d.hash;this.origin=d.protocol+"//"+d.host;this.searchParams=new URLSearchParams(d.search);}
        URL.prototype.toString=function(){return this.href;};
        globalThis.fetch = async function(url, options) {
          options=options||{}; var headers=options.headers||{};
          if(headers&&typeof headers.forEach==="function"){var h={};headers.forEach(function(v,k){h[k]=v;});headers=h;}
          var body=options.body==null?"":String(options.body);
          var data=JSON.parse(__native_fetch(String(url),options.method||"GET",JSON.stringify(headers),body?"text":"none",body,options.redirect!=="manual"));
          var hs=data.headers||{};
          function getHeader(n){var k=Object.keys(hs).find(function(x){return x.toLowerCase()===String(n).toLowerCase();});return k?hs[k]:null;}
          return {ok:!!data.ok,status:data.status||0,statusText:data.statusText||"",url:data.url||String(url),
            headers:{get:getHeader,has:function(n){return getHeader(n)!==null;}},
            text:async function(){return data.body||"";},json:async function(){return JSON.parse(data.body||"null");},
            arrayBuffer:async function(){var s=atob(data.bodyBase64||""),a=new Uint8Array(s.length);for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i);return a.buffer;}};
        };
    """.trimIndent() + "\n" + cheerioPolyfill() +
        "\nvar module = {exports:{}}; var exports = module.exports; (function(){\n" + source + "\n})();"

    private fun cheerioPolyfill(): String = """
        function createCheerioWrapper(docId, ids) {
            var wrapper = {_docId:docId, _elementIds:ids, length:ids.length};
            ids.forEach(function(id, index) { wrapper[index] = createCheerioWrapperFromIds(docId, [id]); });
            wrapper.each = function(fn) { ids.forEach(function(id, i) { var item=createCheerioWrapperFromIds(docId,[id]); fn.call(item,i,item); }); return wrapper; };
            wrapper.find = function(selector) { var found=[]; ids.forEach(function(id) { found=found.concat(JSON.parse(__cheerio_find(docId,id,selector))); }); return createCheerioWrapper(docId,found); };
            wrapper.text = function() { return ids.length ? __cheerio_text(docId,ids.join(",")) : ""; };
            wrapper.html = function() { return ids.length ? __cheerio_inner_html(docId,ids[0]) : ""; };
            wrapper.attr = function(name) { if(!ids.length)return undefined; var v=__cheerio_attr(docId,ids[0],name); return v==="__UNDEFINED__"?undefined:v; };
            wrapper.first = function() { return createCheerioWrapper(docId,ids.length?[ids[0]]:[]); };
            wrapper.last = function() { return createCheerioWrapper(docId,ids.length?[ids[ids.length-1]]:[]); };
            wrapper.eq = function(i) { return createCheerioWrapper(docId,i>=0&&i<ids.length?[ids[i]]:[]); };
            wrapper.get = function(i) { return typeof i==="number"?(i>=0&&i<ids.length?createCheerioWrapper(docId,[ids[i]]):undefined):ids.map(function(id){return createCheerioWrapper(docId,[id]);}); };
            wrapper.toArray = function() { return wrapper.get(); };
            wrapper.map = function(fn) { var out=[]; ids.forEach(function(id,i){var item=createCheerioWrapper(docId,[id]);var v=fn.call(item,i,item);if(v!==undefined&&v!==null)out.push(v);});return {length:out.length,get:function(i){return typeof i==="number"?out[i]:out;},toArray:function(){return out;}}; };
            wrapper.filter = function(test) { if(typeof test!=="function")return wrapper;var out=[];ids.forEach(function(id,i){var item=createCheerioWrapper(docId,[id]);if(test.call(item,i,item))out.push(id);});return createCheerioWrapper(docId,out); };
            wrapper.next = function() { var out=[];ids.forEach(function(id){var n=__cheerio_next(docId,id);if(n!=="__NONE__")out.push(n);});return createCheerioWrapper(docId,out); };
            wrapper.prev = function() { var out=[];ids.forEach(function(id){var n=__cheerio_prev(docId,id);if(n!=="__NONE__")out.push(n);});return createCheerioWrapper(docId,out); };
            wrapper.children = function(selector) { return wrapper.find(selector||"*"); };
            wrapper.parent = function() { return createCheerioWrapper(docId,[]); };
            return wrapper;
        }
        function createCheerioWrapperFromIds(docId, ids) { return createCheerioWrapper(docId,ids); }
        var cheerio = {
            load:function(html) {
                var docId=__cheerio_load(String(html||""));
                var $=function(selector,context) {
                    if(selector&&selector._elementIds)return selector;
                    if(context&&context._elementIds) {
                        var found=[];
                        context._elementIds.forEach(function(id){found=found.concat(JSON.parse(__cheerio_find(docId,id,selector)));});
                        return createCheerioWrapper(docId,found);
                    }
                    return createCheerioWrapper(docId,typeof selector==="string"?JSON.parse(__cheerio_select(docId,selector)):[]);
                };
                $.html=function(item){return __cheerio_html(docId,item&&item._elementIds&&item._elementIds.length?item._elementIds[0]:"");};
                return $;
            }
        };
        globalThis.cheerio=cheerio;
        globalThis.require=function(name) {
            if(name==="cheerio"||name==="cheerio-without-node-native"||name==="react-native-cheerio")return cheerio;
            if(name==="crypto-js"&&globalThis.CryptoJS)return globalThis.CryptoJS;
            throw new Error("Module '"+name+"' is not available in the isolated provider runtime");
        };
    """.trimIndent()

    private fun loadCryptoJsSource(): String? {
        val loader = javaClass.classLoader ?: return null
        val paths = listOf(
            "META-INF/resources/webjars/crypto-js/4.2.0/crypto-js.min.js",
            "META-INF/resources/webjars/crypto-js/4.2.0/crypto-js.js",
            "META-INF/resources/webjars/crypto-js/4.2.0/crypto-js/crypto-js.min.js",
            "META-INF/resources/webjars/crypto-js/4.2.0/crypto-js/crypto-js.js"
        )
        for (path in paths) {
            val source = runCatching {
                loader.getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()
            if (!source.isNullOrBlank()) return source
        }
        return null
    }

    private fun providerCallScript(): String = """
        (async function() {
            var args = JSON.parse(__get_call_args());
            var api = module.exports || {};
            var streams = [];
            var subtitles = [];
            var getStreams = api.getStreams || globalThis.getStreams;
            var getSubtitles = api.getSubtitles || globalThis.getSubtitles;
            try {
                if (typeof getStreams === "function") {
                    var found = await getStreams(args.tmdbId, args.mediaType, args.season, args.episode);
                    if (Array.isArray(found)) streams = found;
                }
            } catch (error) {
                console.error("Nuvio getStreams failed:", error && error.message ? error.message : error);
            }
            try {
                if (typeof getSubtitles === "function") {
                    var foundSubtitles = await getSubtitles(args.tmdbId, args.mediaType, args.season, args.episode);
                    if (Array.isArray(foundSubtitles)) subtitles = foundSubtitles;
                }
            } catch (error) {
                console.error("Nuvio getSubtitles failed:", error && error.message ? error.message : error);
            }
            __capture_result(JSON.stringify({streams:streams, subtitles:subtitles}));
        })();
    """.trimIndent()

    private fun sendReply(target: Messenger, requestId: Int, success: Boolean, value: String) {
        runCatching {
            val data = Bundle().apply { putInt(KEY_REQUEST_ID, requestId); putBoolean(KEY_SUCCESS, success); putString(KEY_VALUE, value) }
            target.send(Message.obtain(null, MSG_RESULT).apply { this.data = data })
        }
    }
    override fun onDestroy() {
        runCatching { unbindService(brokerConnection) }
        if (::worker.isInitialized) worker.quitSafely()
        super.onDestroy()
    }
    companion object {
        const val MSG_EVALUATE = 1
        const val MSG_RESULT = 2
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_SOURCE = "source"
        const val KEY_REPOSITORY = "repository"
        const val KEY_PROVIDER_ID = "provider_id"
        const val KEY_CALL_ARGS = "call_args"
        const val KEY_SETTINGS = "settings_json"
        const val KEY_SESSION_TOKEN = "session_token"
        const val KEY_SUCCESS = "success"
        const val KEY_VALUE = "value"
        private const val MAX_SOURCE_CHARS = 256 * 1024
        private const val MAX_RESULT_CHARS = 256 * 1024
        private const val EXECUTION_TIMEOUT_MS = 60_000L
        private const val WATCHDOG_GRACE_MS = 2_000L
    }
}
