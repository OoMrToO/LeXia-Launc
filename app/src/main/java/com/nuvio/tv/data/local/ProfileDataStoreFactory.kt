// LeXia stand-in for a part of NuvioTV that the copied plugin files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import java.util.concurrent.ConcurrentHashMap

/** One DataStore file per feature and profile, named as Nuvio names them ("plugin_settings"). */
class ProfileDataStoreFactory(private val context: Context) {
    private val cache = ConcurrentHashMap<String, DataStore<Preferences>>()

    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val fileName = if (profileId == 1) featureName else "${featureName}_p$profileId"
        return cache.getOrPut(fileName) {
            PreferenceDataStoreFactory.create(produceFile = { context.applicationContext.preferencesDataStoreFile(fileName) })
        }
    }
}
