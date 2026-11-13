package tv.lexia.launcher

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * Minimal synchronous string-only Binder contract for the isolated addon process.
 * Kept in Kotlin so it works even when the app's AIDL source set is disabled.
 */
interface IAddonNetworkBroker {
    fun fetch(repository: String, providerId: String, requestJson: String): String

    companion object {
        private const val DESCRIPTOR = "tv.lexia.launcher.IAddonNetworkBroker"
        private const val TRANSACTION_FETCH = IBinder.FIRST_CALL_TRANSACTION

        fun asInterface(binder: IBinder): IAddonNetworkBroker = object : IAddonNetworkBroker {
            override fun fetch(repository: String, providerId: String, requestJson: String): String {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(repository)
                    data.writeString(providerId)
                    data.writeString(requestJson)
                    binder.transact(TRANSACTION_FETCH, data, reply, 0)
                    reply.readException()
                    return reply.readString().orEmpty()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        }
    }
}

class AddonNetworkBrokerBinder(
    private val handler: (String, String, String) -> String
) : Binder() {
    init { attachInterface(null, "tv.lexia.launcher.IAddonNetworkBroker") }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            reply?.writeString("tv.lexia.launcher.IAddonNetworkBroker")
            return true
        }
        if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
        data.enforceInterface("tv.lexia.launcher.IAddonNetworkBroker")
        val repository = data.readString().orEmpty()
        val providerId = data.readString().orEmpty()
        val request = data.readString().orEmpty()
        val result = handler(repository, providerId, request)
        reply?.writeNoException()
        reply?.writeString(result)
        return true
    }
}
