// LeXia stand-in for a part of NuvioTV that the copied plugin files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv

/** The values Nuvio's copied files read from Nuvio's BuildConfig, taken from LeXia's. */
object BuildConfig {
    @JvmField val TMDB_API_KEY: String = tv.lexia.launcher.BuildConfig.TMDB_API_KEY
}
