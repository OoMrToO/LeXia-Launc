// LeXia stand-ins for the parts of NuvioTV that its player refers to but LeXia does not have
// (Trakt, Simkl, MDBList, trailers, cloud library, Android TV "Watch Next", home layout settings,
// the app's language picker). Not Nuvio code: each keeps only the members the player uses and
// answers as Nuvio does when the feature is off or signed out.
package com.nuvio.tv

/** NuvioTV keeps this in MainActivity: the app language chosen in its settings. LeXia follows the TV. */
object LocaleCache {
    const val UNSET = "__UNSET__"

    @Volatile
    var localeTag: String = ""
}
