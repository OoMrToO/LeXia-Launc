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
import android.os.Process
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Executes provider JavaScript in an isolated Android UID. Network is available only
 * through the provider-scoped Binder broker and exact HTTPS host grants.
 */
class AddonSandboxService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger
    @Volatile private var networkBroker: IAddonNetworkBroker? = null
    private val brokerReady = CountDownLatch(1)
    private val brokerConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            networkBroker = IAddonNetworkBroker.asInterface(service!!)
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
                        define("console") {
                            function("log") { args -> android.util.Log.d("LeXiaAddon:$providerId", args.joinToString(" ")) }
                            function("error") { args -> android.util.Log.e("LeXiaAddon:$providerId", args.joinToString(" ")) }
                            function("warn") { args -> android.util.Log.w("LeXiaAddon:$providerId", args.joinToString(" ")) }
                        }
                        define("Native") {
                            asyncFunction("fetch") { args ->
                                val requestJson = args.firstOrNull()?.toString().orEmpty()
                                require(requestJson.length <= MAX_REQUEST_CHARS) { "Network request too large." }
                                checkNotNull(networkBroker).fetch(repository, providerId, requestJson)
                            }
                        }
                        evaluate<String>(
                            FETCH_POLYFILL + "\n" + COMMON_JS_WRAPPER
                                .replace("__PROVIDER_SOURCE__", source)
                                .replace("__CALL_ARGS__", JSONObject.quote(callArgs)),
                            filename = "provider-$providerId.js"
                        ) ?: "[]"
                    }
                }
            }
            if (result.length > MAX_RESULT_CHARS) {
                sendReply(reply, requestId, false, "JavaScript result exceeds the size limit.")
            } else {
                sendReply(reply, requestId, true, result)
            }
        } catch (error: Throwable) {
            sendReply(reply, requestId, false, (error.message ?: error.javaClass.simpleName).take(1000))
        }
        return true
    }

    private fun sendReply(target: Messenger, requestId: Int, success: Boolean, value: String) {
        runCatching {
            val data = Bundle().apply {
                putInt(KEY_REQUEST_ID, requestId)
                putBoolean(KEY_SUCCESS, success)
                putString(KEY_VALUE, value)
            }
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
        const val KEY_SUCCESS = "success"
        const val KEY_VALUE = "value"
        private const val MAX_SOURCE_CHARS = 256 * 1024
        private const val MAX_REQUEST_CHARS = 300 * 1024
        private const val MAX_RESULT_CHARS = 256 * 1024
        private const val EXECUTION_TIMEOUT_MS = 60_000L

        private const val FETCH_POLYFILL = """
            globalThis.global = globalThis;
            globalThis.window = globalThis;
            globalThis.self = globalThis;
            globalThis.fetch = async function(input, options) {
              options = options || {};
              const req = { url: String(input), method: options.method || "GET",
                headers: options.headers || {}, body: options.body == null ? "" : String(options.body) };
              const data = JSON.parse(await Native.fetch(JSON.stringify(req)));
              const headers = data.headers || {};
              return {
                ok: !!data.ok, status: data.status || 0, statusText: data.statusText || "",
                url: data.url || "",
                headers: { get: function(name) {
                  const key = Object.keys(headers).find(k => k.toLowerCase() === String(name).toLowerCase());
                  return key ? headers[key] : null;
                }},
                text: async function() { return data.body || ""; },
                json: async function() { return JSON.parse(data.body || "null"); }
              };
            };
            if (typeof atob === "undefined") globalThis.atob = function(v) {
              return globalThis.Buffer ? Buffer.from(v, "base64").toString("binary") : "";
            };
        """

        private const val COMMON_JS_WRAPPER = """
            (async function() {
              const module = { exports: {} };
              const exports = module.exports;
              __PROVIDER_SOURCE__
              const api = module.exports || {};
              const getStreams = api.getStreams || globalThis.getStreams;
              if (typeof getStreams !== "function") return "[]";
              const args = JSON.parse(__CALL_ARGS__);
              const streams = await getStreams(args.tmdbId, args.mediaType, args.season, args.episode);
              return JSON.stringify(Array.isArray(streams) ? streams : []);
            })()
        """
    }
}
