package tv.lexia.launcher

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import app.cash.quickjs.QuickJs
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Executes bounded JavaScript in an isolated Android UID.
 *
 * This process has no INTERNET permission (isolatedProcess); host I/O is deliberately
 * not exposed to JavaScript. A watchdog terminates the whole sandbox process if native
 * evaluation does not return before the deadline.
 */
class AddonSandboxService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("lexia-addon-js").apply { start() }
        messenger = Messenger(Handler(worker.looper, ::handleMessage))
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handleMessage(message: Message): Boolean {
        if (message.what != MSG_EVALUATE) return false
        val reply = message.replyTo ?: return true
        val requestId = message.data.getInt(KEY_REQUEST_ID)
        val source = message.data.getString(KEY_SOURCE).orEmpty()
        if (source.isBlank() || source.length > MAX_SOURCE_CHARS) {
            sendReply(reply, requestId, false, "JavaScript source is empty or exceeds the size limit.")
            return true
        }

        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val timeout = watchdog.schedule({
            // QuickJS's synchronous evaluate API has no safe cross-thread interrupt.
            // Killing this isolated process is the hard execution-time boundary.
            Process.killProcess(Process.myPid())
        }, EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        try {
            val result = QuickJs.create().use { engine ->
                engine.evaluate(source, "addon-provider.js")?.toString().orEmpty()
            }
            if (result.length > MAX_RESULT_CHARS) {
                sendReply(reply, requestId, false, "JavaScript result exceeds the size limit.")
            } else {
                sendReply(reply, requestId, true, result)
            }
        } catch (error: Throwable) {
            sendReply(reply, requestId, false, (error.message ?: error.javaClass.simpleName).take(1000))
        } finally {
            timeout.cancel(false)
            watchdog.shutdownNow()
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
        if (::worker.isInitialized) worker.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val MSG_EVALUATE = 1
        const val MSG_RESULT = 2
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_SOURCE = "source"
        const val KEY_SUCCESS = "success"
        const val KEY_VALUE = "value"
        private const val MAX_SOURCE_CHARS = 256 * 1024
        private const val MAX_RESULT_CHARS = 256 * 1024
        private const val EXECUTION_TIMEOUT_MS = 8_000L
    }
}
