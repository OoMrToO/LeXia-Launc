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
import android.os.Messenger
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
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
        val sessionToken = data.getString(KEY_SESSION_TOKEN).orEmpty()
        if (source.isBlank() || source.length > MAX_SOURCE_CHARS) {
            sendReply(reply, requestId, false, "JavaScript source is empty or exceeds the size limit.")
            return true
        }
        try {
            check(brokerReady.await(5, TimeUnit.SECONDS) && networkBroker != null) {
                "Addon network broker is unavailable."
            }
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
                        evaluate<String>(providerScript(source, callArgs))
                    }
                }
            }
            sendReply(reply, requestId, true, result.take(MAX_RESULT_CHARS))
        } catch (error: Throwable) {
            sendReply(reply, requestId, false, (error.message ?: error.javaClass.simpleName).take(1000))
        }
        return true
    }

    private fun providerScript(source: String, callArgs: String): String = """
        globalThis.global = globalThis; globalThis.window = globalThis; globalThis.self = globalThis;
        globalThis.SCRAPER_SETTINGS = {};
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
        URLSearchParams.prototype.set=function(k,v){this.delete(k);this.append(k,v);};
        URLSearchParams.prototype.has=function(k){return this.get(k)!==null;};
        URLSearchParams.prototype.delete=function(k){this.p=this.p.filter(function(p){return p[0]!==String(k);});};
        URLSearchParams.prototype.toString=function(){return this.p.map(function(p){return encodeURIComponent(p[0])+"="+encodeURIComponent(p[1]);}).join("&");};
        function URL(s,b){var u=String(s);if(b&&!/^https?:\/\//i.test(u)){var x=String(b).match(/^https?:\/\/[^/]+/i);u=u.charAt(0)==="/"?((x?x[0]:"")+u):String(b).replace(/[^/]*$/,"")+u;}
          var d=JSON.parse(__parse_url(u));this.href=u;this.protocol=d.protocol;this.host=d.host;this.hostname=d.hostname;this.port=d.port;this.pathname=d.pathname;this.search=d.search;this.hash=d.hash;this.origin=d.protocol+"//"+d.host;this.searchParams=new URLSearchParams(d.search);}
        URL.prototype.toString=function(){return this.href;};
        globalThis.fetch = async function(url, options) {
          options=options||{}; var headers=options.headers||{};
          if(headers&&typeof headers.forEach==="function"){var h={};headers.forEach(function(v,k){h[k]=v;});headers=h;}
          var body=options.body==null?"":String(options.body);
          var data=JSON.parse(__native_fetch(String(url),options.method||"GET",JSON.stringify(headers),"text",body,options.redirect!=="manual"));
          var hs=data.headers||{};
          function getHeader(n){var k=Object.keys(hs).find(function(x){return x.toLowerCase()===String(n).toLowerCase();});return k?hs[k]:null;}
          return {ok:!!data.ok,status:data.status||0,statusText:data.statusText||"",url:data.url||String(url),
            headers:{get:getHeader,has:function(n){return getHeader(n)!==null;}},
            text:async function(){return data.body||"";},json:async function(){return JSON.parse(data.body||"null");},
            arrayBuffer:async function(){var s=atob(data.bodyBase64||""),a=new Uint8Array(s.length);for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i);return a.buffer;}};
        };
        const module={exports:{}}; const exports=module.exports;
        ${source}
        const args=JSON.parse(${JSONObject.quote(callArgs)});
        const api=module.exports||{}; const getStreams=api.getStreams||globalThis.getStreams;
        if(typeof getStreams!=="function") JSON.stringify([]);
        else { const streams=await getStreams(args.tmdbId,args.mediaType,args.season,args.episode); JSON.stringify(Array.isArray(streams)?streams:[]); }
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
        const val KEY_SESSION_TOKEN = "session_token"
        const val KEY_SUCCESS = "success"
        const val KEY_VALUE = "value"
        private const val MAX_SOURCE_CHARS = 256 * 1024
        private const val MAX_RESULT_CHARS = 256 * 1024
        private const val EXECUTION_TIMEOUT_MS = 60_000L
    }
}
