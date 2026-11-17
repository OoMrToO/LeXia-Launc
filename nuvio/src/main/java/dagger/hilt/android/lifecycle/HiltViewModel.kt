// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package dagger.hilt.android.lifecycle

/** Marker only: LeXia builds Nuvio's view models by hand (com.nuvio.tv.LeXiaNuvio). */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class HiltViewModel
