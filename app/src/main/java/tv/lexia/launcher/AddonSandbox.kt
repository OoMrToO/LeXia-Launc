package tv.lexia.launcher

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

/**
 * Small IPC client for the isolated addon process.
 * No Android Context, filesystem, network client, or Java object is passed into QuickJS.
 */
object AddonSandbox {
    data class Result(val success: Boolean, val value: String)

    fun evaluate(context: Context, source: String, repository: String, providerId: String, callArgs: String = "{}", settingsJson: String = "{}", callback: (Result) -> Unit) {
        if (source.isBlank() || source.length > MAX_SOURCE_CHARS) {
            callback(Result(false, "JavaScript source is empty or too large."))
            return
        }
        val appContext = context.applicationContext
        val requestId = nextRequestId.incrementAndGet()
        val sessionToken = UUID.randomUUID().toString()
        AddonNetworkPermissions.registerSession(appContext, repository, providerId, sessionToken)
        var connection: ServiceConnection? = null
        val response = Messenger(Handler(Looper.getMainLooper()) { message ->
            val data = message.data
            if (message.what == AddonSandboxService.MSG_RESULT &&
                data.getInt(AddonSandboxService.KEY_REQUEST_ID) == requestId) {
                runCatching { connection?.let(appContext::unbindService) }
                AddonNetworkPermissions.clearSession(appContext, sessionToken)
                callback(
                    Result(
                        data.getBoolean(AddonSandboxService.KEY_SUCCESS),
                        data.getString(AddonSandboxService.KEY_VALUE).orEmpty()
                    )
                )
                true
            } else false
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
                runCatching {
                    val request = Message.obtain(null, AddonSandboxService.MSG_EVALUATE).apply {
                        replyTo = response
                        data = Bundle().apply {
                            putInt(AddonSandboxService.KEY_REQUEST_ID, requestId)
                            putString(AddonSandboxService.KEY_SOURCE, source)
                            putString(AddonSandboxService.KEY_REPOSITORY, repository)
                            putString(AddonSandboxService.KEY_PROVIDER_ID, providerId)
                            putString(AddonSandboxService.KEY_CALL_ARGS, callArgs)
                            putString(AddonSandboxService.KEY_SETTINGS, settingsJson)
                            putString(AddonSandboxService.KEY_SESSION_TOKEN, sessionToken)
                        }
                    }
                    Messenger(binder!!).send(request)
                }.onFailure {
                    runCatching { appContext.unbindService(this) }
                    AddonNetworkPermissions.clearSession(appContext, sessionToken)
                    callback(Result(false, it.message ?: "Sandbox request failed."))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                AddonNetworkPermissions.clearSession(appContext, sessionToken)
                callback(Result(false, "JavaScript sandbox stopped (possible execution timeout)."))
            }

            override fun onBindingDied(name: ComponentName?) {
                runCatching { appContext.unbindService(this) }
                AddonNetworkPermissions.clearSession(appContext, sessionToken)
                callback(Result(false, "JavaScript sandbox process died."))
            }

            override fun onNullBinding(name: ComponentName?) {
                runCatching { appContext.unbindService(this) }
                AddonNetworkPermissions.clearSession(appContext, sessionToken)
                callback(Result(false, "JavaScript sandbox could not be started."))
            }
        }
        val intent = Intent(appContext, AddonSandboxService::class.java)
        if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            AddonNetworkPermissions.clearSession(appContext, sessionToken)
            callback(Result(false, "Could not bind to JavaScript sandbox."))
        }
    }

    private val nextRequestId = AtomicInteger(0)
    private const val MAX_SOURCE_CHARS = 256 * 1024
}
