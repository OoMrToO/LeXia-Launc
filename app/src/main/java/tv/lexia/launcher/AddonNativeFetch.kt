package tv.lexia.launcher

/** Narrow string-only bridge; no Android objects are exposed to provider JavaScript. */
interface AddonNativeFetch {
    fun fetch(requestJson: String): String
}
